package dev.ledgerx.wal.crash;

import dev.ledgerx.domain.AccountId;
import dev.ledgerx.domain.AccountKind;
import dev.ledgerx.domain.Entry;
import dev.ledgerx.domain.InMemoryLedger;
import dev.ledgerx.domain.JournalDigest;
import dev.ledgerx.domain.JournalEvent;
import dev.ledgerx.domain.Money;
import dev.ledgerx.domain.RejectedTransactionException;
import dev.ledgerx.domain.Side;
import dev.ledgerx.domain.Transaction;
import dev.ledgerx.journal.DurableLedger;
import dev.ledgerx.journal.EventCodec;
import dev.ledgerx.journal.Replay;
import dev.ledgerx.testing.RandomSource;
import dev.ledgerx.wal.FsyncPolicy;
import dev.ledgerx.wal.RecordType;
import dev.ledgerx.wal.Wal;
import dev.ledgerx.wal.WalFormat;
import dev.ledgerx.wal.WalRecovery;
import dev.ledgerx.wal.WalRecord;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * The kill -9 micro-harness ADR 0003 §8 cites: fork a writer, {@code SIGKILL} it at a random point
 * in its life, then check what the log says about that moment.
 *
 * <p><strong>What is compared.</strong> The child prints one line per ack ({@link CrashTarget}) and
 * the parent keeps those lines as the record of what was promised to a client. After the kill the
 * parent folds the acked lines into a ledger of its own and folds the <em>log</em> into
 * another, and
 * the two must agree event for event. So "zero invariant violations" and "zero acked-but-lost
 * transactions" are data rather than assertions: a missing ack, a torn frame accepted, a
 * hole in the
 * sequence, a fold that refuses, a Σ balances that is not 0, or a log that will not open each
 * fails
 * the cycle with the cycle number, the policy, the seed and the offsets.
 *
 * <p><strong>Two crash models, because {@code kill -9} alone cannot test fsync.</strong> A process
 * crash leaves the page cache behind, so every ack survives it under all three policies — and a
 * harness that stopped there would let {@code NO_FSYNC} pass while it promises nothing across a
 * machine crash, which is the exact difference this ticket exists to make measurable. The second
 * model therefore does what the kernel will not: truncate the file to a random offset
 * <em>at or past
 * the child's last force watermark</em>, discarding exactly the tail no promise covers. That
 * is power
 * loss without a power supply, and its limits are stated in the ADR rather than left to the reader:
 * it loses a suffix and nothing else, so it cannot produce a reordering, and it cannot produce a
 * controller that lied about a force it already accepted.
 *
 * <p><strong>Random where it matters, seeded where it must be reproducible.</strong> The kill
 * instant
 * is nondeterministic by construction — a parent cannot aim at a child's syscall — and
 * everything
 * else (how many acks to read before killing, where to truncate, the workload) comes from a seed
 * printed with the failure, so a red run is replayable up to the instant of the kill.
 *
 * <p>Usage:
 * {@code java dev.ledgerx.wal.crash.CrashHarness [cycles] [opsPerCycle] [keep]}. The child
 * inherits this JVM's classpath and {@code -D} flags, which is how {@code ./build.sh crash} reaches
 * it; {@code keep} leaves every cycle's directory under {@code build/crash-harness/}.
 */
public final class CrashHarness {

  /** The ticket's floor, and what CI runs: 1,000 kill/recover cycles. */
  private static final int DEFAULT_CYCLES = 1_000;

  /** Ops per child: long enough that a kill lands mid-work, short enough for CI to afford. */
  private static final int DEFAULT_OPS = 120;

  private static final Path ROOT = Path.of("build", "crash-harness");

  /** The menu, each cycle in turn — including the two group budgets that bound the axis. */
  private static final String[] POLICIES = {
    "per-commit", "group", "no-fsync", "group:1:0", "group:64:5000"
  };

  /** The two crash models. */
  private static final String[] MODES = {"process", "powerloss"};

  private static final int MAX_PRINTED_FAILURES = 30;

  /** What one policy/model pair did, as ADR 0003 quotes it. */
  private static final class Tally {
    long cycles;
    long killed;
    long acks;
    long refused;
    long lostForcedAcks;
    long lostUnforcedAcks;
    long tornCycles;
    long truncatedBytes;
    long records;
    long logBytes;
    long markers;
    long millis;

  }

  private final Map<String, Tally> tallies = new LinkedHashMap<>();
  private final List<String> failures = new ArrayList<>();

  public static void main(String[] args) throws IOException, InterruptedException {
    int cycles = integer(args, 0, "ledgerx.crash.cycles", DEFAULT_CYCLES);
    int ops = integer(args, 1, "ledgerx.crash.ops", DEFAULT_OPS);
    boolean keep = args.length > 2 || System.getProperty("ledgerx.crash.keep") != null;
    CrashHarness harness = new CrashHarness();
    long started = System.nanoTime();
    int failed = harness.run(Math.max(1, cycles), Math.max(4, ops), keep);
    harness.report((System.nanoTime() - started) / 1_000_000L);
    if (failed != 0) {
      System.out.println(
          "FAIL "
              + failed
              + " of "
              + cycles
              + " kill -9 cycles — the cycle lines above name the seed and the offsets, and"
              + " build/crash-harness/cycle-N keeps the log");
      System.exit(1);
    }
  }

  private static int integer(String[] args, int at, String property, int fallback) {
    if (args.length > at) {
      return Integer.parseInt(args[at]);
    }
    return Integer.valueOf(System.getProperty(property, Integer.toString(fallback)));
  }

  private int run(int cycles, int ops, boolean keep)
      throws IOException, InterruptedException {
    deleteTree(ROOT);
    Files.createDirectories(ROOT);
    try {
      for (int index = 1; index <= cycles; index++) {
        String policyName = POLICIES[(index - 1) % POLICIES.length];
        String mode = MODES[((index - 1) / POLICIES.length) % MODES.length];
        String key = policyName + " / " + mode;
        Tally tally = tallies.computeIfAbsent(key, unused -> new Tally());
        tally.cycles++;
        Path dir = ROOT.resolve("cycle-" + index);
        long started = System.nanoTime();
        List<String> problems;
        try {
          problems = cycle(dir, policyName, mode, ops, tally);
          if (problems.size() == 1 && problems.get(0).startsWith("the child acked nothing")) {
            // One retry, and only for the no-acks case: a sandbox under memory pressure fails to
            // start a JVM, which is not what this harness exists to detect. A second failure is
            // reported as a real one.
            System.out.println("  retry cycle " + index + " — " + problems.get(0));
            deleteTree(dir);
            problems = cycle(dir, policyName, mode, ops, tally);
          }
        } catch (IOException | RuntimeException broke) {
          problems = List.of("the cycle itself blew up: " + broke);
          broke.printStackTrace();
        }
        tally.millis += (System.nanoTime() - started) / 1_000_000L;
        if (problems.isEmpty()) {
          if (!keep) {
            deleteTree(dir);
          }
        } else {
          for (String problem : problems) {
            if (failures.size() < MAX_PRINTED_FAILURES) {
              System.out.println(
                  "  FAIL cycle "
                      + index
                      + " ["
                      + key
                      + "] seed "
                      + seedFor(index)
                      + ": "
                      + problem);
              System.out.flush();
            }
            failures.add("cycle " + index + " [" + key + "]: " + problem);
          }
        }
        if (index % 100 == 0 || index == cycles) {
          System.out.println(
              "  cycle " + index + "/" + cycles + " — " + failures.size() + " bad, "
                  + totalAcks() + " acks observed");
          System.out.flush();
        }
      }
    } finally {
      if (!keep && failures.isEmpty()) {
        deleteTree(ROOT);
      }
    }
    return failures.size();
  }

  /**
 * One cycle: work, kill, read, compare, repair, append past the repair.
   *
 * @return the violations found, empty when the log told the truth
   */
  private List<String> cycle(Path dir, String policyName, String mode, int ops, Tally tally)
      throws IOException, InterruptedException {
    List<String> problems = new ArrayList<>();
    FsyncPolicy policy = CrashTarget.parsePolicy(policyName);
    long seed = seedFor(Long.parseLong(dir.getFileName().toString().substring("cycle-".length())));
    RandomSource rnd = new RandomSource(seed);
    int target = 1 + rnd.nextInt(Math.max(1, ops / 2));
    List<AckLine> acked = new ArrayList<>();
    List<String> chatter = new ArrayList<>();
    Process child = fork(dir, policyName, seed, ops);
    try {
      child.getOutputStream().close();
      boolean killed = readAcks(child, target, acked, chatter, tally);
      if (killed) {
        child.destroyForcibly();
        tally.killed++;
        if (!child.waitFor(30, TimeUnit.SECONDS)) {
          problems.add("the SIGKILLed child would not die");
        }
      } else {
        child.waitFor(60, TimeUnit.SECONDS);
        if (child.exitValue() != 0) {
          problems.add("the child exited " + child.exitValue() + " without being killed");
        }
      }
    } finally {
      if (child.isAlive()) {
        child.destroyForcibly();
      }
    }
    if (acked.isEmpty()) {
      // A child that never acked anything is an environment failure far more often than a
      // durability bug — it could not fork, it was reaped, it could not open the log — so the
      // message has to say which. Nothing here forgives it: the exit code and the child's own
      // lines go into the failure, and the run retries the cycle once before believing it.
      int exit = child.isAlive() ? -1 : child.exitValue();
      return List.of(
          "the child acked nothing before it stopped (exit "
              + exit + "; its output: " + (chatter.isEmpty() ? "none" : chatter) + ")");
    }
    tally.acks += acked.size();
    Path file = dir.resolve(Wal.FILE_NAME);

    // The power-loss model: discard everything the child never forced. The floor is the highest
    // watermark a forced ack reported (and never less than a header, because the model loses a
    // suffix rather than a prefix), so the cut can only ever eat bytes that were never promised.
    long floor = Math.max(WalFormat.SEGMENT_HEADER_BYTES, forcedFloor(acked));
    boolean powerloss = mode.equals("powerloss");
    long size = Files.size(file);
    if (powerloss && size > floor) {
      WalRecovery.truncateTo(file, floor + rnd.nextLong(size - floor + 1L));
    }

    WalRecovery.Scan scan = WalRecovery.scan(file);
    if (scan.report().torn()) {
      tally.tornCycles++;
      tally.truncatedBytes += scan.report().truncatedBytes();
    }
    tally.records += scan.report().journalRecords();
    tally.logBytes += Files.size(file);
    problems.addAll(sequenceIsDense(scan));
    problems.addAll(acksAreHonoured(scan, acked, policy, powerloss, floor, tally));
    problems.addAll(prefixFoldsToAckedState(scan, acked));

    // The repair: opening for real must cut the tail, say so in the log, and take new work whose
    // numbers continue the sequence. That last part is the resurrection check — a repair that
    // left
    // the discarded bytes in place would bury them under the new records, where the next scan would
    // reach them and a fold would apply them a second time.
    long framesBefore = scan.report().frames();
    boolean cut = scan.report().torn();
    try (DurableLedger reopened = DurableLedger.open(dir, policy)) {
      reopened.ledger().audit();
      if (reopened.recovery().lastLsn() < 0) {
        problems.add("recovery reported a negative last lsn");
      }
      if (cut) {
        tally.markers++;
        if (!lastFrameIsMarker(file)) {
          problems.add(
              "recovery cut " + scan.report().truncatedBytes()
                  + " bytes and left no marker saying so");
        }
      }
      reopened.openAccount(new AccountId("probe-a"), AccountKind.ASSET);
      reopened.openAccount(new AccountId("probe-b"), AccountKind.LIABILITY);
      reopened.transfer(new AccountId("probe-a"), new AccountId("probe-b"), Money.ofMinor(7L));
      // Re-read behind the new appends: a record that was there before the probe and is not there
      // after it means the probe landed on top of it, i.e. the numbering was re-issued.
      WalRecovery.Scan during = WalRecovery.scan(file);
      for (AckLine ack : acked) {
        if (ack.forced && find(during, ack.lsn) == null) {
          problems.add(
              "lsn " + ack.lsn
                  + " survived recovery and then vanished behind a new append");
        }
      }
    } catch (IOException | RuntimeException cannotReopen) {
      problems.add("the log would not open after the crash: " + cannotReopen);
      return problems;
    }
    WalRecovery.Scan after = WalRecovery.scan(file);
    if (after.report().tail().isTear()) {
      problems.add(
          "repair is not idempotent: after cutting, the log still reads as "
              + after.report().tail() + " at byte " + after.report().cleanBytes());
    }
    long owed = framesBefore + (cut ? 1L : 0L) + 3L;
    if (after.report().frames() != owed) {
      problems.add(
          "the repaired log holds " + after.report().frames() + " frames and " + owed
              + " were owed: " + framesBefore + " already there"
              + (cut ? ", a marker, and 3 probe records" : " and 3 probe records"));
    }
    WalRecord last = after.records().get(after.records().size() - 1);
    if (last.lsn().value() != owed) {
      problems.add(
          "after repair the last record is " + last.lsn() + " but the log holds " + owed
              + " frames, so numbering and records disagree");
    }
    problems.addAll(sequenceIsDense(after));
    return problems;
  }

  private static long seedFor(long index) {
    return 20260915000L + index * 7919L;
  }

  private static Process fork(Path dir, String policy, long seed, int ops) throws IOException {
    String java = ProcessHandle.current().info().command().orElse("java");
    List<String> command =
        List.of(
            java,
            "-XX:TieredStopAtLevel=1",
            "-Xmx128m",
            // ADR 0001's pinning rule, on the only threads this workload has. A synchronized block
            // on the write path makes the JVM print a stack trace to stdout, which the parent reads
            // as a failure — so the rule is enforced here rather than trusted.
            "-Djdk.tracePinnedThreads=full",
            "-Dstdout.encoding=UTF-8",
            "-cp",
            System.getProperty("java.class.path"),
            CrashTarget.class.getName(),
            dir.toString(),
            policy,
            Long.toString(seed),
            Integer.toString(ops));
    return new ProcessBuilder(command).redirectErrorStream(true).start();
  }

  /**
 * Reads the child's stdout until {@code target} acks have arrived or the child finishes.
   *
 * @return {@code true} when the child is still working and must be killed
   */
  private static boolean readAcks(
      Process child, int target, List<AckLine> acked, List<String> chatter, Tally tally)
      throws IOException {
    try (BufferedReader lines =
        new BufferedReader(
            new InputStreamReader(child.getInputStream(), StandardCharsets.UTF_8))) {
      String line;
      while (acked.size() < target && (line = lines.readLine()) != null) {
        if (line.startsWith("A ")) {
          acked.add(AckLine.parse(line));
        } else if (line.startsWith("N")) {
          tally.refused++;
        } else if (chatter.size() < 8) {
          chatter.add(line);
        } else if (line.startsWith("OK")) {
          return false;
        } else if (line.startsWith("X ")) {
          throw new IOException("the child reported a failure: " + line);
        } else if (line.contains("pinned")) {
          throw new IOException("the write path pinned a virtual thread: " + line);
        }
      }
    }
    return acked.size() >= target;
  }

  /**
 * The offset a cut may not pass: the highest watermark any <em>forced</em> ack reported. Under
 * {@code NO_FSYNC} there is no such ack, so the floor is the header and everything is on the
 * table — which is precisely what that policy promises.
   */
  private static long forcedFloor(List<AckLine> acked) {
    long floor = 0L;
    for (AckLine ack : acked) {
      if (ack.forced) {
        floor = Math.max(floor, ack.durableThrough);
      }
    }
    return floor;
  }

  /**
 * The dense-sequence rule, checked here as well as in recovery: no hole, no repeat, no
 * rewind.
   */
  private static List<String> sequenceIsDense(WalRecovery.Scan scan) {
    List<String> problems = new ArrayList<>();
    long expected = 1L;
    for (WalRecord record : scan.records()) {
      if (record.lsn().value() != expected) {
        problems.add(
            "the clean prefix has a hole: " + record.lsn() + " where lsn " + expected + " was due");
        return problems;
      }
      expected++;
    }
    return problems;
  }

  /**
 * The ack rule, from outside. A record whose ack was forced <em>must</em> be in the log, under
 * every policy and in both crash models. A record never forced is on the log's word only, so its
 * absence after a power cut is the policy's honest cost — counted rather than forgiven, because
 * the count is the number the benchmark ticket is not allowed to hide.
   */
  private static List<String> acksAreHonoured(
      WalRecovery.Scan scan,
      List<AckLine> acked,
      FsyncPolicy policy,
      boolean powerloss,
      long floor,
      Tally tally) {
    Map<Long, WalRecord> byLsn = new LinkedHashMap<>();
    for (WalRecord record : scan.records()) {
      if (byLsn.put(record.lsn().value(), record) != null) {
        tally.lostForcedAcks++;
        return List.of("lsn " + record.lsn() + " appears twice in one clean prefix");
      }
    }
    List<String> problems = new ArrayList<>();
    for (AckLine ack : acked) {
      WalRecord found = byLsn.get(ack.lsn);
      if (found == null) {
        if (!ack.forced) {
          if (powerloss) {
            tally.lostUnforcedAcks++;
          }
          continue;
        }
        tally.lostForcedAcks++;
        problems.add(
            "an acked transaction is gone: lsn " + ack.lsn + ", end " + ack.end
                + ", forced through " + ack.durableThrough + ", cut at " + floor + " under "
                + policy.mode() + " (" + policy.mode().promise() + ")");
        continue;
      }
      if (!found.type().isJournalEvent()) {
        problems.add("lsn " + ack.lsn + " holds a " + found.type() + ", not the acked event");
      } else if (!bytesMatch(found, ack)) {
        problems.add("lsn " + ack.lsn + " is in the log but its payload is not what was acked");
      }
    }
    return problems;
  }

  private static WalRecord find(WalRecovery.Scan scan, long lsn) {
    for (WalRecord record : scan.records()) {
      if (record.lsn().value() == lsn) {
        return record;
      }
    }
    return null;
  }

  /** What the child acked and what the log holds must describe the same op. */
  private static boolean bytesMatch(WalRecord record, AckLine ack) {
    try {
      return ack.matches(EventCodec.decode(record.type(), record.payloadUnsafe(), ack.lsn));
    } catch (IOException willNotDecode) {
      return false;
    }
  }

  /**
 * A clean prefix must fold into a ledger that passes its own audit, leave Σ balances at 0, and
 * begin with the same events the acked lines describe, in order. This is the check that makes "no
 * half-committed transaction" more than a restatement of the frame format: a fold that accepted a
 * truncated posting, or that applied a transaction the domain refuses, fails here.
   */
  private static List<String> prefixFoldsToAckedState(WalRecovery.Scan scan, List<AckLine> acked) {
    List<String> problems = new ArrayList<>();
    InMemoryLedger recovered;
    try {
      recovered = Replay.foldEvents(scan.records());
    } catch (IOException | RuntimeException refused) {
      return List.of("the clean prefix does not fold: " + refused);
    }
    try {
      recovered.audit();
    } catch (RuntimeException auditFailed) {
      problems.add("the recovered ledger fails its own audit: " + auditFailed);
    }
    if (!recovered.totalBalance().isZero()) {
      problems.add(
          "Σ balances is " + recovered.totalBalance().toMajorString() + " after recovery");
    }
    InMemoryLedger reference = new InMemoryLedger();
    for (AckLine ack : acked) {
      try {
        ack.applyTo(reference);
      } catch (RejectedTransactionException refused) {
        problems.add("an acked transaction was refused when replayed: " + refused);
        return problems;
      }
    }
    int shared = Math.min(reference.size(), recovered.size());
    List<JournalEvent> referenceEvents = reference.events();
    List<JournalEvent> recoveredEvents = recovered.events();
    for (int i = 0; i < shared; i++) {
      String expected = JournalDigest.canonical(referenceEvents.get(i));
      String actual = JournalDigest.canonical(recoveredEvents.get(i));
      if (!expected.equals(actual)) {
        problems.add(
            "event " + i + " differs: the log replays " + actual + " where the acked lines say "
                + expected);
        break;
      }
    }
    return problems;
  }

  /** The marker is a record in the file, not one in {@code Wal.recovered()}: read the file. */
  private static boolean lastFrameIsMarker(Path file) throws IOException {
    List<WalRecord> records = WalRecovery.scan(file).records();
    return !records.isEmpty()
        && records.get(records.size() - 1).type() == RecordType.RECOVERY_MARKER;
  }

  private void report(long millis) {
    System.out.println();
    long acks = 0L;
    long lostForced = 0L;
    long lostUnforced = 0L;
    long records = 0L;
    long bytes = 0L;
    long torn = 0L;
    long cycles = 0L;
    for (Map.Entry<String, Tally> row : tallies.entrySet()) {
      Tally tally = row.getValue();
      acks += tally.acks;
      lostForced += tally.lostForcedAcks;
      lostUnforced += tally.lostUnforcedAcks;
      records += tally.records;
      bytes += tally.logBytes;
      torn += tally.tornCycles;
      cycles += tally.cycles;
      System.out.println(
          "  "
              + row.getKey()
              + ": cycles="
              + tally.cycles
              + " killed="
              + tally.killed
              + " acks="
              + tally.acks
              + " refused="
              + tally.refused
              + " acked-but-lost[forced]="
              + tally.lostForcedAcks
              + "[unforced]="
              + tally.lostUnforcedAcks
              + " torn="
              + tally.tornCycles
              + " bytes-cut="
              + tally.truncatedBytes
              + " records="
              + tally.records
              + " bytes="
              + tally.logBytes
              + " markers="
              + tally.markers
              + " "
              + tally.millis
              + "ms");
    }
    System.out.println(
        "  total: cycles="
            + cycles
            + " acks="
            + acks
            + " acked-but-lost[forced]="
            + lostForced
            + " [unforced]="
            + lostUnforced
            + " torn="
            + torn
            + " bytes-per-record="
            + (records == 0L ? "n/a" : Long.toString(bytes / Math.max(1L, records)))
            + " in "
            + millis
            + " ms");
    if (failures.isEmpty()) {
      System.out.println(
          "PASS "
              + cycles
              + "/"
              + cycles
              + " kill -9 cycles — zero invariant violations, zero acked-but-lost forced"
              + " transactions; unforced acks lost across a power cut: "
              + lostUnforced
              + " (which is what NO_FSYNC promises)");
    }
  }

  private long totalAcks() {
    long acks = 0L;
    for (Tally tally : tallies.values()) {
      acks += tally.acks;
    }
    return acks;
  }

  /** One acked record as the child described it: the offsets, and the op in plain text. */
  private static final class AckLine {
    private long lsn;
    private long end;
    private long durableThrough;
    private boolean forced;
    private boolean opening;
    private String account = "";
    private String kind = "";
    private List<String[]> entries = List.of();

    static AckLine parse(String line) {
      String[] parts = line.substring(2).split(" ");
      AckLine ack = new AckLine();
      ack.lsn = Long.parseLong(parts[0]);
      ack.end = Long.parseLong(parts[1]);
      ack.durableThrough = Long.parseLong(parts[2]);
      ack.forced = parts[3].equals("1");
      ack.opening = parts[4].equals("O");
      if (ack.opening) {
        ack.account = parts[5];
        ack.kind = parts[6];
      } else {
        int count = Integer.parseInt(parts[5]);
        List<String[]> rows = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
          rows.add(parts[6 + i].split(":"));
        }
        ack.entries = List.copyOf(rows);
      }
      return ack;
    }

    /** Whether the decoded log record describes this same op. */
    boolean matches(JournalEvent event) {
      if (event instanceof JournalEvent.AccountOpened opened) {
        return opening
            && opened.account().id().value().equals(account)
            && opened.account().kind().name().equals(kind);
      }
      if (event instanceof JournalEvent.Posted posted && !opening) {
        List<Entry> body = posted.transaction().entries();
        if (body.size() != entries.size()) {
          return false;
        }
        for (int i = 0; i < body.size(); i++) {
          Entry entry = body.get(i);
          String[] expected = entries.get(i);
          if (!entry.account().value().equals(expected[0])
              || !(entry.side() == Side.DEBIT ? "D" : "C").equals(expected[1])
              || entry.amount().minorUnits() != Long.parseLong(expected[2])) {
            return false;
          }
        }
        return true;
      }
      return false;
    }

    /** Applies this ack to a ledger the harness owns — the reference side of the comparison. */
    void applyTo(InMemoryLedger ledger) {
      if (opening) {
        AccountId id = new AccountId(account);
        if (!ledger.isKnown(id)) {
          ledger.openAccount(id, AccountKind.valueOf(kind));
        }
        return;
      }
      List<Entry> body = new ArrayList<>(entries.size());
      for (String[] row : entries) {
        body.add(
            new Entry(
                new AccountId(row[0]),
                row[1].equals("D") ? Side.DEBIT : Side.CREDIT,
                Money.ofMinor(Long.parseLong(row[2]))));
      }
      ledger.post(new Transaction(body));
    }
  }

  private static void deleteTree(Path root) throws IOException {
    if (!Files.exists(root)) {
      return;
    }
    List<Path> paths = new ArrayList<>();
    try (var walked = Files.walk(root)) {
      walked.sorted(Comparator.reverseOrder()).forEach(paths::add);
    }
    for (Path path : paths) {
      Files.deleteIfExists(path);
    }
  }
}
