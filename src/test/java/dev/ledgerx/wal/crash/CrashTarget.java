package dev.ledgerx.wal.crash;

import dev.ledgerx.domain.AccountId;
import dev.ledgerx.domain.AccountKind;
import dev.ledgerx.domain.Entry;
import dev.ledgerx.domain.Money;
import dev.ledgerx.domain.RejectedTransactionException;
import dev.ledgerx.domain.Side;
import dev.ledgerx.domain.Transaction;
import dev.ledgerx.journal.DurableLedger;
import dev.ledgerx.testing.RandomSource;
import dev.ledgerx.wal.FsyncPolicy;
import dev.ledgerx.wal.Wal;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * The process that gets {@code kill -9}'d: a durable ledger, a random workload, one line per ack.
 *
 * <p>The protocol is short and strict, and the order inside it is the test — every line is one
 * {@code println} on an auto-flushing stream, and a line exists only after the thing it claims has
 * already happened:
 *
 * <ul>
 *   <li>{@code A <lsn> <end> <durable> <forced> <op>} — this record was <em>acked</em>, meaning
 *       durable under the policy in force. The parent reads "the line arrived" as "the client was
 *       told", which is the relation the ack rule is about, and it keeps {@code end} and
 *       {@code durable} so it knows how far into the file that promise reaches.
 *   <li>{@code N} — a candidate was refused, so nothing was appended: ADR 0002's atomic
 *       rejection,
 *       watched from outside the process.
 *   <li>{@code OK} — the workload finished, which only happens in a cycle nobody killed.
 * </ul>
 *
 * <p>{@code <op>} is the ledger op in plain text — {@code O <account> <kind>} or
 * {@code P <count> <account>:<D|C>:<minor> …} — deliberately <em>not</em> the record's bytes.
 * The
 * parent parses it into domain objects and folds them itself, so the two sides of the comparison
 * share no codec: were this ticket's encoder and decoder both wrong in the same way, the harness
 * would still notice, because the parent's side of the comparison never calls them.
 *
 * <p>Usage: {@code java dev.ledgerx.wal.crash.CrashTarget <dir> <policy> <seed> <ops>}.
 */
public final class CrashTarget {

  /** Deliberately unbalanced candidates, so the rejection path is walked in every cycle. */
  private static final int REJECT_PERCENT = 12;

  private CrashTarget() {}

  public static void main(String[] args) {
    if (args.length < 4) {
      System.err.println(
          "usage: CrashTarget <dir> <per-commit|group|no-fsync|group:<n>:<ms>> <seed> <ops>");
      System.exit(2);
    }
    PrintStream out = new PrintStream(System.out, true, StandardCharsets.UTF_8);
    try {
      run(
          Path.of(args[0]),
          parsePolicy(args[1]),
          Long.parseLong(args[2]),
          Integer.parseInt(args[3]),
          out);
      out.println("OK");
    } catch (IOException | RuntimeException broke) {
      // Reachable only when nobody killed us: a storage failure or a broken invariant is a bug, and
      // a non-zero exit is how the parent tells that apart from a torn tail.
      out.println("X " + broke);
      broke.printStackTrace(System.err);
      System.exit(1);
    }
  }

  private static void run(Path dir, FsyncPolicy policy, long seed, int ops, PrintStream out)
      throws IOException {
    Files.createDirectories(dir);
    RandomSource rnd = new RandomSource(seed);
    try (DurableLedger ledger = DurableLedger.open(dir, policy)) {
      List<AccountId> accounts = new ArrayList<>();
      AccountKind[] kinds = AccountKind.values();
      for (int i = 0; i < 3; i++) {
        accounts.add(open(out, ledger, "seed-" + i, kinds[i % kinds.length]));
      }
      for (int op = 0; op < ops; op++) {
        if (rnd.chance(15) && accounts.size() < 24) {
          accounts.add(open(out, ledger, "acct-" + op, kinds[rnd.nextInt(kinds.length)]));
          continue;
        }
        try {
          Transaction posted = ledger.post(transaction(rnd, accounts));
          ack(out, ledger, postedText(posted));
        } catch (RejectedTransactionException refused) {
          out.println("N");
        }
      }
    }
  }

  private static AccountId open(
      PrintStream out, DurableLedger ledger, String id, AccountKind kind) throws IOException {
    ledger.openAccount(new AccountId(id), kind);
    ack(out, ledger, "O " + id + " " + kind);
    return new AccountId(id);
  }

  /** One line per ack, with the two offsets the parent needs in order to state the obligation. */
  private static void ack(PrintStream out, DurableLedger ledger, String op) {
    Wal.Ack committed = ledger.lastAck();
    out.println(
        "A "
            + committed.lsn().value()
            + " "
            + committed.end()
            + " "
            + committed.durableThrough()
            + " "
            + (committed.forced() ? 1 : 0)
            + " "
            + op);
  }

  private static String postedText(Transaction posted) {
    StringBuilder text = new StringBuilder("P ").append(posted.size());
    for (Entry entry : posted.entries()) {
      text
          .append(' ')
          .append(entry.account().value())
          .append(':')
          .append(entry.side() == Side.DEBIT ? 'D' : 'C')
          .append(':')
          .append(entry.amount().minorUnits());
    }
    return text.toString();
  }

  /** Two to six entries on accounts that exist; balanced unless this op wants a refusal. */
  private static Transaction transaction(RandomSource rnd, List<AccountId> accounts) {
    AccountId[] pool = accounts.toArray(new AccountId[0]);
    int entries = 2 + rnd.nextInt(5);
    List<Entry> body = new ArrayList<>(entries);
    long net = 0L;
    for (int i = 0; i + 1 < entries; i++) {
      long amount = amount(rnd);
      Side side = rnd.nextBoolean() ? Side.DEBIT : Side.CREDIT;
      net += side.sign() * amount;
      body.add(new Entry(rnd.pick(pool), side, Money.ofMinor(amount)));
    }
    long closing = Math.abs(net);
    if (closing == 0L) {
      closing = 1L;
      net = -1L;
    }
    if (rnd.chance(REJECT_PERCENT)) {
      closing = Math.addExact(closing, 1L); // unbalanced on purpose: refused, and never logged
    }
    body.add(
        new Entry(rnd.pick(pool), net > 0L ? Side.CREDIT : Side.DEBIT, Money.ofMinor(closing)));
    return new Transaction(body);
  }

  /** Small amounts, huge amounts, and amounts at a quarter of the {@code long} range. */
  private static long amount(RandomSource rnd) {
    return switch (rnd.nextInt(4)) {
      case 0 -> rnd.nextLong(1L, 100L);
      case 1 -> rnd.nextLong(1L, 1_000_000L);
      case 2 -> rnd.nextLong(Long.MAX_VALUE / 4L, Long.MAX_VALUE / 4L + 1_000L);
      default -> 1L;
    };
  }

  /** The policy names the harness sweeps; the third form is the axis the benchmark ticket wants. */
  public static FsyncPolicy parsePolicy(String name) {
    switch (name) {
      case "per-commit":
        return FsyncPolicy.PER_COMMIT;
      case "group":
        return FsyncPolicy.GROUP_COMMIT;
      case "no-fsync":
        return FsyncPolicy.NO_FSYNC;
      default:
        String[] parts = name.split(":");
        if (parts.length == 3 && parts[0].equals("group")) {
          return FsyncPolicy.groupCommit(
              Integer.parseInt(parts[1]), Duration.ofMillis(Long.parseLong(parts[2])));
        }
        throw new IllegalArgumentException(
            "unknown policy '"
                + name
                + "'; the menu is per-commit, group, no-fsync, group:<records>:<ms>");
    }
  }

}
