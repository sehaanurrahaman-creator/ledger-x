package dev.ledgerx.checkpoint.crash;

import dev.ledgerx.checkpoint.CheckpointListener;
import dev.ledgerx.checkpoint.CheckpointPolicy;
import dev.ledgerx.checkpoint.CheckpointStage;
import dev.ledgerx.domain.AccountId;
import dev.ledgerx.domain.Entry;
import dev.ledgerx.domain.IdempotencyKey;
import dev.ledgerx.domain.LedgerOp;
import dev.ledgerx.domain.ModelLedger;
import dev.ledgerx.domain.MerchantId;
import dev.ledgerx.domain.Money;
import dev.ledgerx.domain.OpEntry;
import dev.ledgerx.domain.OpGenerator;
import dev.ledgerx.domain.RejectedTransactionException;
import dev.ledgerx.domain.Side;
import dev.ledgerx.domain.Transaction;
import dev.ledgerx.idempotency.IdempotencyPolicy;
import dev.ledgerx.journal.DurableLedger;
import dev.ledgerx.testing.RandomSource;
import dev.ledgerx.wal.FsyncPolicy;
import dev.ledgerx.wal.Wal;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The process that kills itself at a chosen instant: a durable ledger, a deterministic history, one
 * flushed line per ack, and a {@code SIGKILL} at the boundary the parent asked for.
 *
 * <p>It is the child of {@link LsnBoundaryHarness} and a descendant of ADR 0003's
 * {@code CrashTarget}, with one difference that is the whole point of this ticket: the instant of
 * the crash is <em>not</em> random. A parent cannot aim at a child's syscall, but a child can aim
 * at its own — so the crash lands exactly after the k-th commit returns, which is exactly the
 * moment a state hash is defined at, and every boundary of a random history is reachable. Sending
 * the signal to itself keeps the crash real: no shutdown hook runs, no buffer is flushed, no
 * {@code close()} tidies up, and the page cache behaves as it does after any process death.
 *
 * <p>The protocol, one flushed line each, and a line exists only after the thing it claims has
 * happened:
 *
 * <pre>{@code
 * A <lsn> <end> <durableThrough> <forced> O acct-<n> <KIND>    an account was opened and acked
 * A <lsn> <end> <durableThrough> <forced> P <n> acct:D:minor …  a posting was acked
 * A <lsn> <end> <durableThrough> <forced> I <m> <key> <n> …    a keyed posting was acked
 * R <m> <key> <origLsn> <n> acct:D:minor …                     a retry replayed; nothing appended
 * C <m> <key> <n> acct:D:minor …                          a reused key conflicted; nothing
 *                                                              appended
 * N <why>                                                      nothing was appended
 * CKPT <op>                                                    a checkpoint began during op <op>
 * H <op> <stateHash> <lastJournalLsn> <lastJournalEnd>         the live state after op <op>
 * LIVE <stateHash> <lastJournalLsn> <lastJournalEnd> <op>      printed, then the boundary crash
 * ARMED <stage>                                                a stage crash is armed
 * STAGE <stage> <stateHash> <lastJournalLsn> <lastJournalEnd>  printed, then the stage crash
 * OK <stateHash> <lastJournalLsn> <lastJournalEnd>             ran to the end, uncrashed
 * }</pre>
 *
 * <p>The op text is plain text and not a WAL payload: the parent parses it into domain objects and
 * folds it itself, so the two sides of the comparison share no codec. What the child and the parent
 * necessarily share is the <em>history</em> — the same generator, the same seed — because "the
 * same history" is not a property either of them can check alone. What they do not share is the
 * machinery under test.
 *
 * <p>Usage: {@code LsnBoundaryTarget <dir> <seed> <ops> <fsync> <checkpoints> <crash>} where
 * {@code fsync} is {@code per-commit|group|no-fsync}, {@code checkpoints} is
 * {@code off|every:<n>|every:<n>:retain:<r>}, and {@code crash} is
 * {@code none|after:<k>|stage:<STAGE>:<afterOps>}.
 */
public final class LsnBoundaryTarget {

  private LsnBoundaryTarget() {}

  public static void main(String[] args) throws IOException {
    if (args.length < 6) {
      System.err.println(
          "usage: LsnBoundaryTarget <dir> <seed> <ops> <fsync> <checkpoints> <crash>");
      System.exit(2);
    }
    Path dir = Path.of(args[0]);
    long seed = Long.parseLong(args[1]);
    int ops = Integer.parseInt(args[2]);
    FsyncPolicy fsync = parseFsync(args[3]);
    CheckpointPolicy checkpoints = parseCheckpoints(args[4]);
    Crash crash = parseCrash(args[5]);
    PrintStream out = new PrintStream(System.out, true, StandardCharsets.UTF_8);
    Files.createDirectories(dir);
    run(dir, seed, ops, fsync, checkpoints, crash, out);
  }

  /** The crash the parent asked for, and the instant it names. */
  record Crash(Mode mode, long at, CheckpointStage stage) {

    enum Mode {
      /** Run the whole history and exit 0: the golden run every boundary is compared against. */
      NONE,
      /** Die immediately after the {@code at}-th operation completes. */
      AFTER,
      /** Die inside the swap's {@code stage}, of the first checkpoint after {@code at} ops. */
      STAGE
    }
  }

  /** How the ledger reports itself at an instant, for a line that has to outlive the process. */
  @FunctionalInterface
  interface StateTail {
    String report();
  }

  private static void run(
      Path dir,
      long seed,
      int ops,
      FsyncPolicy fsync,
      CheckpointPolicy checkpoints,
      Crash crash,
      PrintStream out)
      throws IOException {
    List<LedgerOp> history = OpGenerator.sequence(new RandomSource(seed), ops);
    String tail;
    int[] currentOp = {0};
    try (DurableLedger ledger = DurableLedger.open(dir, fsync, true, checkpoints, policy())) {
      StageKiller killer =
          new StageKiller(
              crash,
              out,
              () -> ledger.stateHash() + " " + ledger.lastJournalLsn() + " "
                  + ledger.lastJournalEnd(),
              () -> currentOp[0]);
      ledger.onCheckpointStage(killer);
      History applier = new History(ledger, out);
      report(out, ledger, 0);
      if (crash.mode() == Crash.Mode.AFTER && crash.at() == 0L) {
        boundary(ledger, out, 0);
      }
      if (crash.mode() == Crash.Mode.STAGE && crash.at() == 0L) {
        // "after zero operations" arms before the first commit, so the first checkpoint any commit
        // takes is the one killed; its covered state is the state after that commit.
        out.println("ARMED " + crash.stage());
        killer.arm();
      }
      for (int i = 0; i < history.size(); i++) {
        applier.apply(history.get(i), i);
        int op = i + 1;
        currentOp[0] = op;
        report(out, ledger, op);
        if (crash.mode() == Crash.Mode.AFTER && crash.at() == op) {
          boundary(ledger, out, op);
        }
        if (crash.mode() == Crash.Mode.STAGE && crash.at() == op) {
          // Armed after an operation, so the checkpoint that gets killed is the next one — the
          // one a commit of the following operation triggers. The state it is a checkpoint *of*
          // is therefore the state after that following commit, and the child reports it on the way
          // out rather than leaving the parent to guess.
          out.println("ARMED " + crash.stage());
          killer.arm();
        }
      }
      tail = ledger.stateHash() + " " + ledger.lastJournalLsn() + " " + ledger.lastJournalEnd();
    }
    // A clean exit is not a crash, and reopening is still the check: under NO_FSYNC nothing forced
    // the tail on the way out except this reopen's own force (ADR 0003 §4), so a golden run whose
    // "OK" line matched a state the log does not hold would be exactly the bug this harness hunts.
    try (DurableLedger reopened =
        DurableLedger.open(
            dir, FsyncPolicy.PER_COMMIT, true, CheckpointPolicy.MANUAL, policy())) {
      String after = reopened.stateHash() + " " + reopened.lastJournalLsn() + " "
          + reopened.lastJournalEnd();
      if (!after.equals(tail)) {
        throw new IllegalStateException(
            "this run's live state is " + tail + " and reopening the directory gives " + after);
      }
    }
    out.println("OK " + tail);
  }

  /**
   * The idempotency policy every open in this target uses: a clock frozen at the epoch, so a
   * binding's age is a property of the history — zero forever — and neither the child's runs
   * nor the parent's folds can disagree about which replays replay.
   */
  private static IdempotencyPolicy policy() {
    return IdempotencyPolicy.of(Clock.fixed(Instant.EPOCH, ZoneOffset.UTC),
        Duration.ofHours(24));
  }

  private static void report(PrintStream out, DurableLedger ledger, int op) {
    out.println("H " + op + " " + ledger.stateHash() + " " + ledger.lastJournalLsn() + " "
        + ledger.lastJournalEnd());
  }

  /** Prints the state as the run leaves it and dies; there is no process to ask afterwards. */
  private static void boundary(DurableLedger ledger, PrintStream out, int op) {
    out.println("LIVE " + ledger.stateHash() + " " + ledger.lastJournalLsn() + " "
        + ledger.lastJournalEnd() + " " + op);
    out.flush();
    die(out);
  }

  /**
   * The kill: a real {@code SIGKILL}, delivered by a {@code kill(1)} this process spawns.
   *
   * <p>It is aimed by the child rather than arranged by the parent because only the process knows
   * the instant it just reached. It is delivered from outside because the JDK will not let a
   * process destroy itself — {@code ProcessHandleImpl} answers "destroy of current process not
   * allowed" — and because a signal from outside is the honest model anyway: nothing runs,
   * nothing is flushed, no {@code finally} runs, and the page cache is left as a crash leaves it.
   *
   * <p>One line is printed if the signal does <em>not</em> land, so a harness running somewhere
   * without {@code kill(1)} says so rather than quietly testing a clean exit that happened to carry
   * status 137.
   */
  private static void die(PrintStream out) {
    long pid = ProcessHandle.current().pid();
    try {
      new ProcessBuilder("kill", "-9", Long.toString(pid)).start();
      Thread.sleep(2_000L);
    } catch (IOException | InterruptedException couldNotSignal) {
      Thread.currentThread().interrupt();
      out.println("HALTING " + couldNotSignal);
      out.flush();
    }
    Runtime.getRuntime().halt(137);
  }

  /** Watches the swap's stages, reports each checkpoint as it starts, and dies when armed. */
  private static final class StageKiller implements CheckpointListener {

    private final Crash crash;
    private final PrintStream out;
    private final StateTail state;
    private final java.util.function.IntSupplier currentOp;
    private boolean armed;

    StageKiller(
        Crash crash, PrintStream out, StateTail state, java.util.function.IntSupplier currentOp) {
      this.crash = crash;
      this.out = out;
      this.state = state;
      this.currentOp = currentOp;
    }

    void arm() {
      this.armed = crash.mode() == Crash.Mode.STAGE;
    }

    @Override
    public void reached(CheckpointStage stage) {
      if (stage == CheckpointStage.TEMP_WRITTEN) {
        // The first stage of a swap, and therefore the line that tells the parent when this
        // history takes checkpoints — the parent arms its stage crashes from these, so a
        // checkpoint the history never reaches is never armed, and an armed crash always has
        // a swap to die inside.
        out.println("CKPT " + currentOp.getAsInt());
      }
      if (!armed || stage != crash.stage()) {
        return;
      }
      out.println("STAGE " + stage + " " + state.report());
      out.flush();
      die(out);
    }
  }

  /**
   * Applies the generated history to the durable ledger: the same generator the domain suite uses,
   * the same shape of refusal, one line per outcome. Where an operation cannot be built at all (a
   * non-positive amount — {@code Entry} refuses it), the history records {@code N} exactly as a
   * rejection does, because from the ledger's point of view both are "nothing happened".
   */
  private static final class History {

    private final DurableLedger ledger;
    private final ModelLedger model = new ModelLedger();
    private final PrintStream out;
    private int opened;
    private KeyedBody lastKeyed;
    private final List<String> schedule = new ArrayList<>();

    History(DurableLedger ledger, PrintStream out) {
      this.ledger = ledger;
      this.out = out;
    }

    void apply(LedgerOp op, int index) throws IOException {
      if (op instanceof LedgerOp.OpenAccount open) {
        AccountId id = AccountId.of("acct-" + opened);
        ledger.openAccount(id, open.kind());
        model.open(id, open.kind());
        opened++;
        ackLine("O " + id.value() + " " + open.kind());
        return;
      }
      LedgerOp.Post post = (LedgerOp.Post) op;
      if (needsAnOpenAccount(post) && model.accountCount() == 0) {
        out.println("N nothing to post against");
        return;
      }
      List<ModelLedger.ResolvedEntry> resolved = model.resolve(post);
      ModelLedger.Prediction expected = model.predict(resolved);
      if (expected.verdict() == ModelLedger.Verdict.UNCONSTRUCTIBLE) {
        out.println("N " + expected.why());
        return;
      }
      if (!schedule.isEmpty() && lastKeyed != null) {
        // A keyed posting schedules its own aftermath: the next two posting ops re-send the
        // body — once as the client meant it (a retry), once as a client bug (a mutation) —
        // so every binding the history creates is also read back, on both sides of every
        // boundary the parent crashes at, whatever the generator made of the slots between.
        switch (schedule.remove(0)) {
          case "R" -> retry();
          case "C" -> mutatedReuse();
          default -> throw new IllegalStateException("a verb this target does not know");
        }
        return;
      }
      Transaction candidate = build(resolved);
      if (index % 2 == 0) {
        keyedPosting(index, candidate);
        return;
      }
      try {
        Transaction posted = ledger.post(candidate);
        model.commit(posted);
        ackLine(postedText(posted));
      } catch (RejectedTransactionException refused) {
        out.println("N " + refused.reason());
      }
    }

    /**
     * The keyed act, interleaved with the plain history: every even op posts under a fresh key,
     * and every keyed posting schedules its own aftermath — the next posting op re-sends the
     * body as a retry, the one after re-sends it mutated. The boundary the harness hunts is any
     * instant where a binding's commit and its transaction's commit could come apart, and a
     * schedule tied to the keyed postings themselves puts a binding's whole life — bound,
     * replayed, conflicted — inside every window the history walks, whatever the generator
     * made of the slots in between.
     *
     * <p>The clock is frozen at the epoch for the whole run, so a binding's age is a property of
     * the history rather than of the wall clock: replays replay and conflicts conflict in both
     * the child and the parent's fold, on any day either of them runs.
     */
    private void keyedPosting(int index, Transaction candidate) throws IOException {
      String merchant = "merchant-" + index % 3;
      String key = "key-" + index;
      try {
        dev.ledgerx.idempotency.IdempotentReceipt receipt =
            ledger.postIdempotent(MerchantId.of(merchant), IdempotencyKey.of(key), candidate);
        model.commit(receipt.transaction());
        lastKeyed = new KeyedBody(merchant, key, receipt.transaction());
        schedule.add("R");
        schedule.add("C");
        ackLine(
            "I " + merchant + " " + key + " " + postedText(receipt.transaction()).substring(2));
      } catch (RejectedTransactionException refused) {
        out.println("N " + refused.reason());
      }
    }

    /** A retry of the last keyed body: the response replays, nothing appends, nothing acks. */
    private void retry() throws IOException {
      dev.ledgerx.idempotency.IdempotentReceipt receipt =
          ledger.postIdempotent(
              MerchantId.of(lastKeyed.merchant()), IdempotencyKey.of(lastKeyed.key()),
              lastKeyed.body());
      if (!receipt.replayed()) {
        throw new IllegalStateException(
            "a retry of " + lastKeyed.key() + " posted instead of replaying");
      }
      out.println(
          "R " + lastKeyed.merchant() + " " + lastKeyed.key() + " " + receipt.originalLsn()
              + " " + postedText(lastKeyed.body()).substring(2));
    }

    /**
     * The client bug the 409 exists for: the last key re-sent with every amount nudged by one.
     * Still balanced, still naming known accounts — everything about it is plausible except the
     * key, which is exactly the shape of divergence a 200-replay would have hidden.
     */
    private void mutatedReuse() throws IOException {
      List<Entry> nudged = new ArrayList<>(lastKeyed.body().size());
      for (Entry entry : lastKeyed.body().entries()) {
        nudged.add(
            new Entry(
                entry.account(), entry.side(),
                Money.ofMinor(entry.amount().minorUnits() + 1L)));
      }
      Transaction mutated = new Transaction(nudged);
      try {
        ledger.postIdempotent(
            MerchantId.of(lastKeyed.merchant()), IdempotencyKey.of(lastKeyed.key()), mutated);
        throw new IllegalStateException(
            "a mutated body under " + lastKeyed.key() + " was accepted");
      } catch (dev.ledgerx.idempotency.IdempotencyConflictException conflict) {
        out.println(
            "C " + lastKeyed.merchant() + " " + lastKeyed.key() + " "
                + postedText(mutated).substring(2));
      }
    }

    /** The last keyed body, kept for the retry and conflict verbs to re-send. */
    private record KeyedBody(String merchant, String key, Transaction body) {}

    /** One ack line, carrying the two offsets the parent needs to state the obligation. */
    private void ackLine(String op) {
      Wal.Ack committed = ledger.lastAck();
      out.println("A " + committed.lsn().value() + " " + committed.end() + " "
          + committed.durableThrough() + " " + (committed.forced() ? 1 : 0) + " " + op);
    }

    private static boolean needsAnOpenAccount(LedgerOp.Post post) {
      for (OpEntry entry : post.entries()) {
        if (!entry.isUnknownAccount()) {
          return true;
        }
      }
      return false;
    }

    private static Transaction build(List<ModelLedger.ResolvedEntry> resolved) {
      List<Entry> entries = new ArrayList<>(resolved.size());
      for (ModelLedger.ResolvedEntry row : resolved) {
        entries.add(new Entry(row.account(), row.side(), Money.ofMinor(row.minorUnits())));
      }
      return new Transaction(entries);
    }

    private static String postedText(Transaction posted) {
      StringBuilder text = new StringBuilder("P ").append(posted.size());
      for (Entry entry : posted.entries()) {
        text.append(' ')
            .append(entry.account().value())
            .append(':')
            .append(entry.side() == Side.DEBIT ? 'D' : 'C')
            .append(':')
            .append(entry.amount().minorUnits());
      }
      return text.toString();
    }
  }

  // --- the command line, one parser per knob --------------------------------------------

  static FsyncPolicy parseFsync(String name) {
    switch (name) {
      case "per-commit":
        return FsyncPolicy.PER_COMMIT;
      case "group":
        return FsyncPolicy.GROUP_COMMIT;
      case "no-fsync":
        return FsyncPolicy.NO_FSYNC;
      default:
        throw new IllegalArgumentException(
            "unknown fsync policy '" + name + "'; the menu is per-commit, group, no-fsync");
    }
  }

  static CheckpointPolicy parseCheckpoints(String name) {
    if (name.equals("off")) {
      return CheckpointPolicy.MANUAL;
    }
    String[] parts = name.split(":");
    if (parts.length < 2 || !parts[0].equals("every")) {
      throw new IllegalArgumentException(
          "unknown checkpoint policy '"
              + name
              + "'; the menu is off, every:<n>, every:<n>:retain:<r>");
    }
    CheckpointPolicy policy = CheckpointPolicy.everyCommits(Integer.parseInt(parts[1]));
    if (parts.length == 4 && parts[2].equals("retain")) {
      return policy.retaining(Integer.parseInt(parts[3]));
    }
    if (parts.length == 2) {
      return policy;
    }
    throw new IllegalArgumentException(
        "unknown checkpoint policy '"
            + name
            + "'; the menu is off, every:<n>, every:<n>:retain:<r>");
  }

  static Crash parseCrash(String name) {
    if (name.equals("none")) {
      return new Crash(Crash.Mode.NONE, -1L, null);
    }
    String[] parts = name.split(":");
    if (parts.length == 2 && parts[0].equals("after")) {
      return new Crash(Crash.Mode.AFTER, Long.parseLong(parts[1]), null);
    }
    if (parts.length == 3 && parts[0].equals("stage")) {
      return new Crash(
          Crash.Mode.STAGE, Long.parseLong(parts[2]), CheckpointStage.valueOf(parts[1]));
    }
    throw new IllegalArgumentException(
        "unknown crash '" + name + "'; the menu is none, after:<k>, stage:<STAGE>:<afterOps>");
  }
}
