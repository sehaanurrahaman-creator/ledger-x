package dev.ledgerx.idempotency;

import dev.ledgerx.domain.AccountId;
import dev.ledgerx.domain.AccountKind;
import dev.ledgerx.domain.IdempotencyKey;
import dev.ledgerx.domain.MerchantId;
import dev.ledgerx.domain.Money;
import dev.ledgerx.domain.RejectedTransactionException;
import dev.ledgerx.domain.Transaction;
import dev.ledgerx.journal.DurableLedger;
import dev.ledgerx.journal.EventCodec;
import dev.ledgerx.testing.PropertyRunner;
import dev.ledgerx.testing.RandomSource;
import dev.ledgerx.testing.Shrink;
import dev.ledgerx.wal.FsyncPolicy;
import dev.ledgerx.wal.RecordType;
import dev.ledgerx.wal.Wal;
import dev.ledgerx.wal.WalRecovery;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The idempotency properties, proved against the durable ledger: the ticket's one-line theorem
 * and the three traps, as random campaigns rather than hand-built cases.
 *
 * <p>The headline property is the one the issue asked for in exactly these words — <em>no
 * sequence of retries, crashes and recoveries ever produces two postings for one key</em> — and
 * it is not checked by counting records after the fact. Every operation is predicted first by a
 * model of the ADR (bind, replay, conflict, expire — in that precedence), the ledger must agree
 * with the prediction, and the receipt must carry the original's LSN and capture instant, so a
 * second posting cannot hide behind a correct-looking receipt. The model is small enough to hold
 * in your head, which is the point: the property says the ledger <em>is</em> that model, under
 * any interleaving of time, retries and reopens the generator can produce.
 *
 * <p>The three traps are campaign verbs, not separate checks: a retry re-sends a stored body, a
 * conflict re-sends a mutated one, and a {@code Tick} moves the clock — forwards across the
 * retention window, and backwards too, because skew that shrinks a replay window into refusing
 * it would be a silent exactly-once violation in the other direction. Reopens fold the log (or
 * load a checkpoint) mid-history, which is what makes this a property about recovery rather than
 * about one long-lived object.
 *
 * <p>Run by {@code ./build.sh}; exits non-zero if any check fails. Reproduce a case with
 * {@code -Dledgerx.property.seed=<the seed in the failure>}.
 */
public final class IdempotencyProperties {

  private static final int DEFAULT_TRIALS = 40;
  private static final int DEFAULT_OPERATIONS = 120;

  /** Fixed, so a green build is the same build tomorrow. */
  private static final long DEFAULT_BASE_SEED = 20260914L;

  private static final Duration RETENTION = Duration.ofHours(24);

  /** Fsync is the crash harness's department; this suite is about the decisions, not the disk. */
  private static final FsyncPolicy POLICY = FsyncPolicy.NO_FSYNC;

  /** Cumulative across the campaign; asserted complete by the coverage check. */
  private static final Coverage campaignCoverage = new Coverage();

  private IdempotencyProperties() {}

  // --- the operations ------------------------------------------------------------------

  /** One decision a client or a clock can make; self-contained, so any sublist is a case. */
  sealed interface Op permits Open, Keyed, Tick, Reopen, Checkpoint {}

  /** Open another account — posted transactions must name known accounts or be refused. */
  record Open(int account) implements Op {}

  /**
   * A keyed posting attempt. {@code amount}, {@code from} and {@code to} are the body as sent,
   * so the generator produces retries and conflicts by re-sending or mutating them.
   */
  record Keyed(int merchant, int key, long amount, int from, int to) implements Op {}

  /** Move the clock — either direction, by deltas that straddle the retention window. */
  record Tick(long deltaMillis) implements Op {}

  /** Close the ledger and open it again: the crash-recovery path, without the crash. */
  record Reopen() implements Op {}

  /** Write a checkpoint, which the next reopen should prefer over folding the log. */
  record Checkpoint() implements Op {}

  /** A body, derived from the op the way a client derives it from its intent. */
  private static Transaction bodyOf(Keyed op) {
    return Transaction.transfer(
        AccountId.of("acct-" + op.from()), AccountId.of("acct-" + op.to()),
        Money.ofMinor(op.amount()));
  }

  private static String scopeOf(int merchant, int key) {
    return "merchant-" + merchant + "/key-" + key;
  }

  // --- the campaign --------------------------------------------------------------------

  /** What the ADR says happens: the oracle the ledger is compared against after every op. */
  private static final class Model {

    /** scope → the binding as the ADR defines it: fingerprint, instant, the op, the LSN. */
    final Map<String, Binding> bindings = new HashMap<>();

    /** Accounts the campaign has opened, so unknown-account refusals are modelled, not buggy. */
    final List<Integer> accounts = new ArrayList<>();

    /** The next record's LSN, predicted from how many records the history has appended. */
    long nextLsn = 1L;

    /**
     * The op that bound the scope is carried, not searched for: the sweep after the campaign
     * re-sends the body the binding remembers, and what it remembers is bytes plus provenance.
     */
    record Binding(String fingerprint, long capturedAtMillis, Keyed op) {}
  }

  /** Every outcome the campaign can produce, so the coverage check can demand them all. */
  private enum Outcome {
    POSTED,
    REPLAYED,
    CONFLICT,
    EXPIRED,
    REJECTED,
    REOPENED,
    CHECKPOINTED
  }

  /** Counts per outcome, across the whole campaign: a property suite that proves nothing. */
  private static final class Coverage {
    final Map<Outcome, Integer> seen = new HashMap<>();

    void add(Outcome outcome) {
      seen.merge(outcome, 1, Integer::sum);
    }

    String summary() {
      StringBuilder text = new StringBuilder();
      for (Outcome outcome : Outcome.values()) {
        if (text.length() > 0) {
          text.append(' ');
        }
        text.append(outcome.name().toLowerCase()).append('=').append(seen.getOrDefault(outcome, 0));
      }
      return text.toString();
    }

    void assertEveryOutcomeWalked() {
      for (Outcome outcome : Outcome.values()) {
        if (seen.getOrDefault(outcome, 0) == 0) {
          throw new AssertionError(
              "the campaign never produced a " + outcome.name().toLowerCase()
                  + " case, so the property said nothing about it");
        }
      }
    }
  }

  /**
   * The headline property. Each case is a random op list applied to a real durable ledger; each
   * op is predicted by the model, and any disagreement — outcome, LSN, capture instant, binding
   * count, or the invariant that Σ balances is zero — fails the case with the seed in the
   * report.
   */
  private static String applyAll(List<Op> ops, Path dir, Coverage foldInto)
      throws IOException {
    IdempotencyContract.MutableClock clock = new IdempotencyContract.MutableClock(1_000_000L);
    Model model = new Model();
    Coverage coverage = new Coverage();
    DurableLedger ledger =
        DurableLedger.open(
            dir, POLICY, true, dev.ledgerx.checkpoint.CheckpointPolicy.MANUAL,
            IdempotencyPolicy.of(clock, RETENTION));
    try {
      for (int at = 0; at < ops.size(); at++) {
        Op op = ops.get(at);
        String where = "op " + at + " (" + op + "): ";
        if (op instanceof Open open) {
          if (model.accounts.contains(open.account())) {
            // A duplicate open is the ledger refusing, not the campaign failing: the account
            // exists, nothing appends, and the next op proceeds from the state it found.
            try {
              ledger.openAccount(AccountId.of("acct-" + open.account()), AccountKind.ASSET);
              require(false, where + "a duplicate open was accepted");
            } catch (IllegalArgumentException already) {
              // the model's prediction, delivered
            }
            continue;
          }
          ledger.openAccount(AccountId.of("acct-" + open.account()), AccountKind.ASSET);
          model.accounts.add(open.account());
          model.nextLsn++;
          continue;
        }
        if (op instanceof Tick tick) {
          clock.advance(tick.deltaMillis());
          continue;
        }
        if (op instanceof Reopen) {
          String hashBefore = ledger.stateHash();
          int bindingsBefore = ledger.ledger().bindingCount();
          ledger.close();
          ledger =
              DurableLedger.open(
                  dir, POLICY, true, dev.ledgerx.checkpoint.CheckpointPolicy.MANUAL,
                  IdempotencyPolicy.of(clock, RETENTION));
          require(
              ledger.stateHash().equals(hashBefore),
              where + "a reopen changed the state hash: " + hashBefore + " -> "
                  + ledger.stateHash());
          require(
              ledger.ledger().bindingCount() == bindingsBefore,
              where + "a reopen changed the binding count: " + bindingsBefore + " -> "
                  + ledger.ledger().bindingCount());
          coverage.add(Outcome.REOPENED);
          continue;
        }
        if (op instanceof Checkpoint) {
          long lsnBefore = model.nextLsn;
          ledger.checkpoint();
          require(model.nextLsn == lsnBefore, where + "a checkpoint appended a record");
          coverage.add(Outcome.CHECKPOINTED);
          continue;
        }
        Keyed keyed = (Keyed) op;
        Transaction body = bodyOf(keyed);
        String scope = scopeOf(keyed.merchant(), keyed.key());
        Model.Binding binding = model.bindings.get(scope);
        // The model speaks first, and its order is the contract's: a bound key answers from
        // the table — conflict, replay, expiry — before the candidate's own merits are ever
        // looked at, because "this key means a different intent now" is the more useful
        // refusal and the one that cannot be fixed by retrying harder. Only an unbound key's
        // candidate is validated, and only a valid one binds.
        if (binding == null && (!model.accounts.contains(keyed.from())
            || !model.accounts.contains(keyed.to()))) {
          expectRejected(ledger, keyed, body, where, coverage);
        } else if (binding == null) {
          IdempotentReceipt receipt =
              ledger.postIdempotent(
                  MerchantId.of("merchant-" + keyed.merchant()),
                  IdempotencyKey.of("key-" + keyed.key()),
                  body);
          require(!receipt.replayed(), where + "a fresh key replayed something");
          require(
              receipt.originalLsn() == model.nextLsn,
              where + "the receipt names lsn " + receipt.originalLsn()
                  + ", the model predicted " + model.nextLsn);
          require(
              receipt.capturedAtMillis() == clock.millis(),
              where + "the receipt captured " + receipt.capturedAtMillis()
                  + ", the clock said " + clock.millis());
          model.bindings.put(
              scope,
              new Model.Binding(EventCodec.fingerprintOf(body).hex(), clock.millis(), keyed));
          model.nextLsn++;
          coverage.add(Outcome.POSTED);
        } else {
          String presented = EventCodec.fingerprintOf(body).hex();
          if (!presented.equals(binding.fingerprint())) {
            try {
              ledger.postIdempotent(
                  MerchantId.of("merchant-" + keyed.merchant()),
                  IdempotencyKey.of("key-" + keyed.key()),
                  body);
              require(false, where + "a different body under a bound key was accepted");
            } catch (IdempotencyConflictException conflict) {
              // the model's prediction, delivered
            }
            coverage.add(Outcome.CONFLICT);
          } else if (clock.millis() - binding.capturedAtMillis() < RETENTION.toMillis()) {
            IdempotentReceipt receipt =
                ledger.postIdempotent(
                    MerchantId.of("merchant-" + keyed.merchant()),
                    IdempotencyKey.of("key-" + keyed.key()),
                    body);
            require(
                receipt.replayed(),
                where + "a stored, live body posted instead of replaying");
            require(
                receipt.capturedAtMillis() == binding.capturedAtMillis(),
                where + "the replay's capture instant moved: " + receipt.capturedAtMillis()
                    + " not " + binding.capturedAtMillis());
            coverage.add(Outcome.REPLAYED);
          } else {
            try {
              ledger.postIdempotent(
                  MerchantId.of("merchant-" + keyed.merchant()),
                  IdempotencyKey.of("key-" + keyed.key()),
                  body);
              require(false, where + "an expired key replayed or re-executed");
            } catch (IdempotencyExpiredException expired) {
              // the model's prediction, delivered
            }
            coverage.add(Outcome.EXPIRED);
          }
        }
        // After every op: the ledger is what the model says, and the books balance.
        require(
            ledger.ledger().bindingCount() == model.bindings.size(),
            where + "the ledger holds " + ledger.ledger().bindingCount()
                + " bindings, the model holds " + model.bindings.size());
        long sum = 0L;
        for (Money balance : ledger.ledger().balances().values()) {
          sum += balance.minorUnits();
        }
        require(sum == 0L, where + "the balances sum to " + sum + ", not zero");
        ledger.ledger().audit();
      }

      // The closing sweep: every key the history ever bound answers per the model, one more
      // time, after everything. This is the response table reading cold, end to end.
      for (Map.Entry<String, Model.Binding> entry : model.bindings.entrySet()) {
        Model.Binding binding = entry.getValue();
        Keyed original = binding.op();
        Transaction body = bodyOf(original);
        boolean live =
            clock.millis() - binding.capturedAtMillis() < RETENTION.toMillis();
        try {
          IdempotentReceipt receipt =
              ledger.postIdempotent(
                  MerchantId.of("merchant-" + original.merchant()),
                  IdempotencyKey.of("key-" + original.key()),
                  body);
          require(
              live && receipt.replayed(),
              "the closing sweep got an unexpected outcome for " + entry.getKey());
        } catch (IdempotencyExpiredException expired) {
          require(
              !live,
              "the closing sweep got an unexpected expiry for " + entry.getKey());
        }
      }

      // The one-record-per-key theorem, read from the file rather than trusted from the API.
      int counted = 0;
      for (var record : WalRecovery.scan(dir.resolve(Wal.FILE_NAME)).records()) {
        if (record.type() == RecordType.IDEMPOTENT_POSTING) {
          counted++;
        }
      }
      require(
          counted == model.bindings.size(),
          "the log holds " + counted + " keyed records for " + model.bindings.size()
              + " bindings");
      for (Map.Entry<Outcome, Integer> entry : coverage.seen.entrySet()) {
        foldInto.seen.merge(entry.getKey(), entry.getValue(), Integer::sum);
      }
      return model.bindings.size() + " bindings, all one record each; " + coverage.summary();
    } finally {
      ledger.close();
      deleteTree(dir);
    }
  }

  /** A refusal the model predicted: the domain says no, nothing binds, nothing appends. */
  private static void expectRejected(
      DurableLedger ledger, Keyed keyed, Transaction body, String where, Coverage coverage)
      throws IOException {
    long before = ledger.ledger().bindingCount();
    try {
      ledger.postIdempotent(
          MerchantId.of("merchant-" + keyed.merchant()),
          IdempotencyKey.of("key-" + keyed.key()),
          body);
      require(false, where + "a posting naming an unknown account was accepted");
    } catch (RejectedTransactionException refused) {
      // the model's prediction, delivered
    }
    require(
        ledger.ledger().bindingCount() == before,
        where + "a refused candidate changed the binding count");
    coverage.add(Outcome.REJECTED);
  }

// --- the generator -------------------------------------------------------------------

  private static List<Op> sequence(RandomSource rnd, int length) {
    List<Op> ops = new ArrayList<>(length);
    // The prefix every case can rely on: four accounts, so the first keyed op is never a
    // refusal by construction and the campaign starts from the interesting verbs.
    for (int account = 0; account < 4; account++) {
      ops.add(new Open(account));
    }
    Map<String, Keyed> firstUsed = new HashMap<>();
    java.util.Set<Integer> opened = new java.util.HashSet<>();
    for (int account = 0; account < 4; account++) {
      opened.add(account);
    }
    while (ops.size() < length) {
      int pick = rnd.nextInt(100);
      if (pick < 10 && opened.size() < 10) {
        int account = 4 + rnd.nextInt(6);
        while (opened.contains(account)) {
          account = 4 + (account - 4 + 1) % 6;
        }
        opened.add(account);
        ops.add(new Open(account));
        continue;
      }
      if (pick < 22) {
        long delta = tickDelta(rnd);
        ops.add(new Tick(delta));
        continue;
      }
      if (pick < 30) {
        ops.add(new Reopen());
        continue;
      }
      if (pick < 35) {
        ops.add(new Checkpoint());
        continue;
      }
      int merchant = rnd.nextInt(3);
      int key = rnd.nextInt(12);
      String scope = scopeOf(merchant, key);
      Keyed firstUse = firstUsed.get(scope);
      if (pick < 75 || firstUse == null) {
        // A fresh scope: the first use, whose body later retries re-send exactly.
        Keyed fresh = newKeyed(rnd, merchant, key);
        firstUsed.put(scope, fresh);
        ops.add(fresh);
        continue;
      }
      // A scope the history has used: the same body re-sent (a retry — the common case a
      // well-behaved client produces under timeout-driven retries), or a mutated one (the
      // client bug the 409 exists for).
      if (rnd.chance(70)) {
        ops.add(firstUse);
      } else {
        ops.add(
            new Keyed(
                firstUse.merchant(),
                firstUse.key(),
                firstUse.amount() + 1L + rnd.nextInt(1_000),
                firstUse.to(),
                firstUse.from()));
      }
    }
    return ops;
  }

  private static Keyed newKeyed(RandomSource rnd, int merchant, int key) {
    int from = rnd.nextInt(10);
    int to = rnd.nextInt(10);
    if (to == from) {
      to = (from + 1) % 10;
    }
    return new Keyed(merchant, key, 1L + rnd.nextInt(1_000_000), from, to);
  }

  /** Deltas that straddle the window and the skew: both directions, both sides of 24 hours. */
  private static long tickDelta(RandomSource rnd) {
    long[] menu = {
      -7_200_000L, -1L, 1L, 3_599_999L, 86_399_999L, 86_400_000L, 86_400_001L, 172_800_000L
    };
    if (rnd.chance(70)) {
      return menu[rnd.nextInt(menu.length)];
    }
    return rnd.nextLong(-108_000_000L, 108_000_000L);
  }

  private static List<List<Op>> smaller(List<Op> failing) {
    List<List<Op>> candidates = new ArrayList<>(Shrink.halves(failing));
    candidates.addAll(Shrink.withoutOne(failing));
    return candidates;
  }

  // --- the checks ----------------------------------------------------------------------

  public static void main(String[] args) {
    int trials = integerProperty("ledgerx.property.trials", DEFAULT_TRIALS);
    int operations = integerProperty("ledgerx.property.operations", DEFAULT_OPERATIONS);
    long baseSeed = longProperty("ledgerx.property.seed", DEFAULT_BASE_SEED);

    System.out.println("ledger-x idempotency properties on " + Runtime.version());
    System.out.println(
        "  campaign: "
            + trials
            + " cases x "
            + operations
            + " operations, seed base "
            + baseSeed);

    PropertyRunner runner = new PropertyRunner(trials, baseSeed, campaignCoverage::summary);

    runner.check(
        "no sequence of retries, conflicts, expiries, crashes and recoveries posts a key twice",
        rnd -> sequence(rnd, operations),
        IdempotencyProperties::smaller,
        ops -> applyAll(ops, Files.createTempDirectory("ledger-x-idempotency-props"),
            campaignCoverage));

    runner.checkOnce("the campaign walks every outcome path", () -> {
      campaignCoverage.assertEveryOutcomeWalked();
      return campaignCoverage.summary();
    });

    runner.checkOnce(
        "the harness reproduces a case from its seed",
        () -> {
          List<Op> first = sequence(new RandomSource(baseSeed), operations);
          String one = applyAll(first, Files.createTempDirectory("ledger-x-repro-one"),
              new Coverage());
          String two = applyAll(first, Files.createTempDirectory("ledger-x-repro-two"),
              new Coverage());
          require(
              one.equals(two),
              "one seed produced two different campaigns:\n  " + one + "\n  " + two);
          return "seed " + baseSeed + " replays identically";
        });

    System.out.println();
    System.out.println(runner.verdict());
    if (!runner.passed()) {
      System.exit(1);
    }
  }

  // --- small things --------------------------------------------------------------------

  private static void require(boolean condition, String what) {
    if (!condition) {
      throw new AssertionError(what);
    }
  }

  private static int integerProperty(String name, int fallback) {
    String value = System.getProperty(name);
    return value == null ? fallback : Integer.parseInt(value);
  }

  private static long longProperty(String name, long fallback) {
    String value = System.getProperty(name);
    return value == null ? fallback : Long.parseLong(value);
  }

  private static void deleteTree(Path root) throws IOException {
    if (root == null || !Files.exists(root)) {
      return;
    }
    try (var walk = Files.walk(root)) {
      walk.sorted(Comparator.reverseOrder()).forEach(p -> {
        try {
          Files.deleteIfExists(p);
        } catch (IOException ignored) {
          // a temp directory that cannot be fully cleaned is not a failed check
        }
      });
    }
  }
}
