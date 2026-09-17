package dev.ledgerx.checkpoint.crash;

import dev.ledgerx.checkpoint.CheckpointPolicy;
import dev.ledgerx.checkpoint.CheckpointStage;
import dev.ledgerx.checkpoint.CheckpointStore;
import dev.ledgerx.checkpoint.LedgerState;
import dev.ledgerx.domain.AccountId;
import dev.ledgerx.domain.AccountKind;
import dev.ledgerx.domain.Entry;
import dev.ledgerx.domain.IdempotencyKey;
import dev.ledgerx.domain.InMemoryLedger;
import dev.ledgerx.domain.MerchantId;
import dev.ledgerx.domain.Money;
import dev.ledgerx.domain.Side;
import dev.ledgerx.domain.Transaction;
import dev.ledgerx.idempotency.IdempotencyConflictException;
import dev.ledgerx.idempotency.IdempotentReceipt;
import dev.ledgerx.idempotency.IdempotencyPolicy;
import dev.ledgerx.journal.DurableLedger;
import dev.ledgerx.journal.Replay;
import dev.ledgerx.wal.FsyncPolicy;
import dev.ledgerx.wal.Wal;
import dev.ledgerx.wal.WalRecovery;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * The crash-at-every-LSN-boundary harness: for every prefix of a random history, kill the writer,
 * recover, and compare state hashes.
 *
 * <p><strong>What it compares, and why five comparisons rather than one.</strong> The ticket's
 * property is "a run's final hash must equal the post-recovery hash for any crash + replay of the
 * same history", and a single comparison between two paths that share most of their code would be a
 * weak test of it. So each boundary is checked five ways, and all five must agree on one 64-digit
 * number:
 *
 * <ol>
 *   <li>the <em>crashed run's own</em> hash, printed by the child just before it kills itself —
 *       the "run's final hash" the ticket names;
 *   <li>the <em>golden run's</em> hash at the same prefix — a separate, uninterrupted process
 *       that played the same history to the end and recorded a hash after every operation;
 *   <li>the <em>recovered</em> ledger's hash, with the checkpoint store in play;
 *   <li>a <em>full replay from scratch</em>, folded by the harness out of the log with
 *       {@link Replay#fold} — independent of the checkpoint machinery entirely, which is what
 *       makes "checkpoint + tail" and "replay from zero" comparable rather than circular;
 *   <li>the hash after <em>deleting every checkpoint</em> and reopening — the deletion theorem
 *       that makes garbage collection safe (ADR 0004 §6).
 * </ol>
 *
 * <p>Then it keeps writing: the recovered ledger is resumed with the rest of the history and its
 * hash must track the golden run's hash operation for operation to the end. Recovery that lands
 * on a state which is not a legitimate continuation — a re-used LSN, a lost record, a checkpoint
 * applied to the wrong prefix — shows up as a divergence at the first resumed operation rather
 * than as a plausible-looking number.
 *
 * <p><strong>What is checked besides hashes.</strong> A crash at a boundary must not tear the log
 * (there is no partial record at that instant, so recovery must cut nothing); the recovered run's
 * log must be a byte prefix of the golden run's; every checkpoint that survived the crash must
 * load, with zero refusals, and must be <em>used</em> whenever one exists; and the state at every
 * instant must be a prefix boundary of the history, which the golden run's per-operation hash table
 * turns into a lookup rather than an opinion.
 *
 * <p><strong>The second experiment: a crash inside the checkpoint swap.</strong> The boundary crash
 * tests that the log reconstructs state; it cannot test the swap, because at a boundary no swap is
 * in flight. So for every {@link CheckpointStage} the harness kills the writer <em>inside</em> the
 * swap — after the temp file was written, after its force, after the rename, after the directory
 * sync, after collection — and requires that recovery find the state at the instant of the kill,
 * from the previous checkpoint and the tail if necessary. This is the one place the harness uses a
 * hook in the product rather than a signal alone, and the stage list is instrumentation an operator
 * wants anyway (ADR 0004 §5).
 *
 * <p>Usage: {@code LsnBoundaryHarness [ops] [seed] [keep]}. System properties
 * {@code ledgerx.boundary.ops}, {@code ledgerx.boundary.seed} and {@code ledgerx.boundary.keep} are
 * the way {@code ./build.sh boundary} reaches it. A failing cycle keeps its directory and prints
 * the path, so the log can be read rather than re-created.
 */
public final class LsnBoundaryHarness {

  /** Short enough that a full sweep is a CI step, long enough for rejections and re-openings. */
  private static final int DEFAULT_OPS = 24;

  /** Fixed, from the 18th: a green run is the same run tomorrow. */
  private static final long DEFAULT_SEED = 20260918L;

  private static final Path ROOT = Path.of("build", "boundary-harness");

  private static final int MAX_PRINTED_FAILURES = 30;

  /** The configurations swept: the two ends of the fsync menu, and a cadence in between. */
  private static final Config[] CONFIGS = {
    new Config("per-commit fsync, no checkpoints", "per-commit", "off"),
    new Config("group commit, a checkpoint every commit", "group", "every:1"),
    new Config("no fsync, a checkpoint every third commit", "no-fsync", "every:3:retain:3"),
  };

  /**
   * How many of the golden run's own checkpoints the stage sweep kills inside, per stage: the
   * first, the middle and the last. The arms are read from the golden run's {@code CKPT} lines
   * rather than taken as fractions of the history, because a fraction can name an instant after
   * the history's last checkpoint — an armed child that has nothing left to die inside.
   */
  private static final int STAGE_ARMS_PER_STAGE = 3;

  private final List<String> failures = new ArrayList<>();
  private long cycles;
  private long killed;
  private long stageKills;
  private long recoveries;
  private long tailRecords;
  private long checkpointsUsed;

  public static void main(String[] args) throws IOException, InterruptedException {
    int ops = integer(args, 0, "ledgerx.boundary.ops", DEFAULT_OPS);
    long seed = longValue(args, 1, "ledgerx.boundary.seed", DEFAULT_SEED);
    boolean keep = args.length > 2 || System.getProperty("ledgerx.boundary.keep") != null;
    LsnBoundaryHarness harness = new LsnBoundaryHarness();
    long started = System.nanoTime();
    harness.run(Math.max(4, ops), seed, keep);
    harness.report((System.nanoTime() - started) / 1_000_000L, ops);
    if (!harness.failures.isEmpty()) {
      System.out.println();
      System.out.println(
          "FAIL "
              + harness.failures.size()
              + " of "
              + harness.cycles
              + " boundary cycles — each line above names the configuration, the boundary and the"
              + " hash that disagreed; the last failing cycle's directory is kept under "
              + ROOT);
      System.exit(1);
    }
  }

  private static int integer(String[] args, int at, String property, int fallback) {
    if (args.length > at) {
      return Integer.parseInt(args[at]);
    }
    return Integer.valueOf(System.getProperty(property, Integer.toString(fallback)));
  }

  private static long longValue(String[] args, int at, String property, long fallback) {
    if (args.length > at) {
      return Long.parseLong(args[at]);
    }
    return Long.valueOf(System.getProperty(property, Long.toString(fallback)));
  }

  private void run(int ops, long seed, boolean keep) throws IOException, InterruptedException {
    deleteTree(ROOT);
    Files.createDirectories(ROOT);
    try {
      for (Config config : CONFIGS) {
        System.out.println();
        System.out.println("== " + config.describe() + " ==");
        Path goldenDir = ROOT.resolve(slug(config) + "-golden");
        Protocol golden = execute(goldenDir, config, seed, ops, "none");
        if (!golden.ok()) {
          fail(
              config.describe() + " golden",
              "the uninterrupted run did not finish cleanly: " + golden.summary());
          continue;
        }
        List<String> history = golden.outcomes();
        System.out.println(
            "  golden run: "
                + history.size()
                + " operations, "
                + golden.acks()
                + " acks, final hash "
                + shortHash(golden.finalHash()));
        if (history.size() != ops) {
          fail(config.describe() + " golden", "asked for " + ops + " operations, got "
              + history.size());
          continue;
        }
        for (int boundary = 0; boundary <= ops; boundary++) {
          cycles++;
          List<String> problems = boundary(config, golden, boundary, ops, seed);
          tally(problems, config, "after " + boundary + " operation(s)");
        }
        if (config.checkpointPolicy().automatic()) {
          for (CheckpointStage stage : CheckpointStage.values()) {
            for (int arm : golden.stageArms(STAGE_ARMS_PER_STAGE)) {
              cycles++;
              stageKills++;
              List<String> problems = stage(config, golden, stage, arm, ops, seed);
              tally(problems, config, "killed at " + stage + " after " + arm + " operation(s)");
            }
          }
        }
        System.out.println("  " + cycles + " cycles so far, " + failures.size() + " bad");
        if (!keep && failures.isEmpty()) {
          deleteTree(goldenDir);
        }
      }
    } finally {
      if (!keep && failures.isEmpty()) {
        deleteTree(ROOT);
      }
    }
  }

  private void tally(List<String> problems, Config config, String what) {
    for (String problem : problems) {
      if (failures.size() < MAX_PRINTED_FAILURES) {
        System.out.println("  FAIL [" + config.describe() + ", " + what + "]: " + problem);
        System.out.flush();
      }
      failures.add(config.describe() + ", " + what + ": " + problem);
    }
  }

  private void fail(String what, String problem) {
    if (failures.size() < MAX_PRINTED_FAILURES) {
      System.out.println("  FAIL [" + what + "]: " + problem);
      System.out.flush();
    }
    failures.add(what + ": " + problem);
  }

  // --- the boundary cycle ---------------------------------------------------------------

  private List<String> boundary(Config config, Protocol golden, int boundary, int ops, long seed)
      throws IOException, InterruptedException {
    List<String> problems = new ArrayList<>();
    Path dir = ROOT.resolve(slug(config) + "-after-" + boundary);
    Protocol child = execute(dir, config, seed, ops, "after:" + boundary);
    if (!child.killed()) {
      problems.add("the child was not killed: " + child.summary());
      return problems;
    }
    killed++;
    String expected = golden.hashAt(boundary);
    if (!expected.equals(child.liveHash())) {
      problems.add(
          "the crashed run's own hash at this boundary is "
              + shortHash(child.liveHash())
              + " and the golden run's is "
              + shortHash(expected));
      return problems;
    }
    if (!child.opPrefix(ops).equals(golden.outcomes().subList(0, boundary))) {
      problems.add("the crashed run's operation log is not the golden run's prefix");
      return problems;
    }
    compareLogPrefix(
        ROOT.resolve(slug(config) + "-golden"), dir, child.liveEnd(), problems);
    problems.addAll(recoverAndCompare(config, dir, golden, boundary, ops, true));
    return problems;
  }

  // --- the stage cycle ------------------------------------------------------------------

  private List<String> stage(
      Config config, Protocol golden, CheckpointStage stage, int armAfter, int ops, long seed)
      throws IOException, InterruptedException {
    List<String> problems = new ArrayList<>();
    Path dir = ROOT.resolve(slug(config) + "-stage-" + stage + "-" + armAfter);
    Protocol child = execute(dir, config, seed, ops, "stage:" + stage + ":" + armAfter);
    if (!child.killed()) {
      problems.add("the child was not killed: " + child.summary());
      return problems;
    }
    killed++;
    if (child.stage() == null) {
      problems.add("the child died without reporting a stage: " + child.summary());
      return problems;
    }
    int at = golden.indexOfHash(child.stageHash());
    if (at < 0) {
      problems.add(
          "the state at the crash, "
              + shortHash(child.stageHash())
              + ", is not any prefix of the history — recovery would have nothing to land on");
      return problems;
    }
    if (at <= armAfter) {
      problems.add("the crash was inside a checkpoint taken before the arming point");
      return problems;
    }
    return recoverAndCompare(config, dir, golden, at, ops, true);
  }

  // --- recovery, five ways, and a resume ------------------------------------------------

  /**
   * The comparisons every cycle makes: the recovered hash, a from-scratch fold, a
   * deleted-checkpoint reopen, and a resume that replays the rest of the history against the
   * golden run's hashes.
   */
  private List<String> recoverAndCompare(
      Config config, Path dir, Protocol golden, int boundary, int ops, boolean expectCheckpointUse)
      throws IOException {
    List<String> problems = new ArrayList<>();
    String expected = golden.hashAt(boundary);
    Path walFile = dir.resolve(Wal.FILE_NAME);
    CheckpointStore store = new CheckpointStore(dir);
    boolean hadCheckpoint = !store.newestFirst().isEmpty();
    String recoveredHash;
    String recoveredDetail;
    try (DurableLedger recovered =
        DurableLedger.open(dir, config.fsyncPolicy(), true, config.checkpointPolicy())) {
      recoveries++;
      tailRecords += recovered.replayedRecords();
      recoveredHash = recovered.stateHash();
      recoveredDetail =
          "recovery: "
              + recovered.checkpointLoad()
              + ", replayed "
              + recovered.replayedRecords()
              + " record(s), lsn "
              + recovered.lastJournalLsn();
      if (recovered.checkpointLoad().used()) {
        checkpointsUsed++;
      }
      if (recovered.recovery().torn()) {
        problems.add("a crash at a boundary tore the log: " + recovered.recovery());
      }
      if (recovered.checkpointLoad().refusals() != 0) {
        problems.add(
            "recovery refused a checkpoint that survived the crash: "
                + recovered.checkpointLoad().refused());
      }
      if (expectCheckpointUse && recovered.checkpointLoad().used() != hadCheckpoint) {
        problems.add(
            "the directory "
                + (hadCheckpoint ? "held a checkpoint and recovery did not use one" : "held no")
                + " — "
                + recoveredDetail);
      }
      if (recovered.lastJournalLsn() != golden.lsnAt(boundary)) {
        problems.add(
            "recovery landed at lsn "
                + recovered.lastJournalLsn()
                + " and the history's boundary is at lsn "
                + golden.lsnAt(boundary));
      }
    }
    if (!expected.equals(recoveredHash)) {
      problems.add(
          "recovered hash "
              + shortHash(recoveredHash)
              + " != the run's own hash "
              + shortHash(expected)
              + " ("
              + recoveredDetail
              + ")");
      return problems;
    }
    // Independent of every recovery code path in the product: scan the log, fold it, hash it.
    WalRecovery.Scan scan = WalRecovery.scan(walFile);
    InMemoryLedger folded = Replay.fold(scan);
    String scratch = LedgerState.of(folded, Replay.lastJournalLsn(scan)).stateHash();
    if (!expected.equals(scratch)) {
      problems.add("a full replay from scratch gives " + shortHash(scratch) + ", not "
          + shortHash(expected));
      return problems;
    }
    // The deletion theorem: a checkpoint is a cache, so removing every one of them changes nothing.
    for (Path file : store.newestFirst()) {
      Files.deleteIfExists(file);
    }
    for (Path temp : store.staleTemps()) {
      Files.deleteIfExists(temp);
    }
    try (DurableLedger fromScratch =
        DurableLedger.open(dir, config.fsyncPolicy(), true, CheckpointPolicy.MANUAL)) {
      if (!expected.equals(fromScratch.stateHash())) {
        problems.add(
            "with every checkpoint deleted, recovery gives "
                + shortHash(fromScratch.stateHash())
                + ", not "
                + shortHash(expected));
        return problems;
      }
    }
    // And the state is a legitimate continuation: replay the rest of the history into it, under
    // the configuration's own cadence, so that a checkpoint written *after* a recovery is exercised
    // too — a checkpoint whose coverage starts at a log that already holds a recovery marker.
    try (DurableLedger resumed =
        DurableLedger.open(
            dir, config.fsyncPolicy(), true, config.checkpointPolicy(), policy())) {
      for (int op = boundary + 1; op <= ops; op++) {
        apply(resumed, golden.outcomes().get(op - 1));
        if (!golden.hashAt(op).equals(resumed.stateHash())) {
          problems.add(
              "after resuming operation "
                  + op
                  + " the hash is "
                  + shortHash(resumed.stateHash())
                  + " and the golden run's is "
                  + shortHash(golden.hashAt(op)));
          return problems;
        }
      }
    }
    if (WalRecovery.scan(walFile).report().torn()) {
      problems.add("the log tore while the recovered ledger was being written to");
    }
    return problems;
  }

  /**
   * The same frozen-clock policy the target runs under — the fold must answer a retry the way
   * the child did, on any day the harness runs.
   */
  private static IdempotencyPolicy policy() {
    return IdempotencyPolicy.of(
        java.time.Clock.fixed(java.time.Instant.EPOCH, java.time.ZoneOffset.UTC),
        java.time.Duration.ofHours(24));
  }

  /** Applies one operation from the golden run's own record of it to a live ledger. */
  private static void apply(DurableLedger ledger, String outcome) throws IOException {
    if (outcome.startsWith("R ")) {
      // A replay: same scope, same body, and the ledger must agree it already answered.
      String[] parts = outcome.split(" ", 5);
      IdempotentReceipt receipt =
          ledger.postIdempotent(
              MerchantId.of(parts[1]), IdempotencyKey.of(parts[2]), transactionIn(parts[4]));
      if (!receipt.replayed()) {
        throw new IOException(
            "a replayed outcome posted instead: " + outcome);
      }
      return;
    }
    if (outcome.startsWith("C ")) {
      // A conflict: same scope, a different body, and the ledger must refuse it again.
      String[] parts = outcome.split(" ", 4);
      try {
        ledger.postIdempotent(
            MerchantId.of(parts[1]), IdempotencyKey.of(parts[2]), transactionIn(parts[3]));
      } catch (IdempotencyConflictException conflict) {
        return;
      }
      throw new IOException("a conflicted outcome was accepted instead: " + outcome);
    }
    if (!outcome.startsWith("A ")) {
      // An "N" outcome: the golden run appended nothing, and so must this one.
      return;
    }
    // A <lsn> <end> <durable> <forced> <op...> — five numbers and then the operation itself.
    String[] ack = outcome.split(" ", 6);
    if (ack.length < 6) {
      throw new IOException("an ack line with no operation in it: " + outcome);
    }
    String op = ack[5];
    if (op.startsWith("O ")) {
      String[] parts = op.split(" ");
      ledger.openAccount(AccountId.of(parts[1]), AccountKind.valueOf(parts[2]));
      return;
    }
    if (op.startsWith("I ")) {
      String[] parts = op.split(" ", 4);
      ledger.postIdempotent(
          MerchantId.of(parts[1]), IdempotencyKey.of(parts[2]), transactionIn(parts[3]));
      return;
    }
    if (!op.startsWith("P ")) {
      throw new IOException("an operation this harness does not know: " + outcome);
    }
    ledger.post(transactionIn(op.substring(2)));
  }

  /** {@code <n> acct:D:minor …} — the body every keyed verb carries, parsed into a
   * transaction.  */
  private static Transaction transactionIn(String body) {
    String[] parts = body.split(" ");
    int count = Integer.parseInt(parts[0]);
    List<Entry> entries = new ArrayList<>(count);
    for (int i = 0; i < count; i++) {
      String[] row = parts[1 + i].split(":");
      entries.add(
          new Entry(
              AccountId.of(row[0]),
              row[1].equals("D") ? Side.DEBIT : Side.CREDIT,
              Money.ofMinor(Long.parseLong(row[2]))));
    }
    return new Transaction(entries);
  }

  /** The crashed run's log must be a byte prefix of the uninterrupted run's log. */
  private void compareLogPrefix(
      Path goldenDir, Path crashedDir, long end, List<String> problems)
      throws IOException {
    byte[] golden = Files.readAllBytes(goldenDir.resolve(Wal.FILE_NAME));
    byte[] crashed = Files.readAllBytes(crashedDir.resolve(Wal.FILE_NAME));
    if (crashed.length != end) {
      problems.add(
          "the crashed log is "
              + crashed.length
              + " bytes and the child's last ack ended at "
              + end);
      return;
    }
    if (golden.length < crashed.length
        || !Arrays.equals(golden, 0, crashed.length, crashed, 0, crashed.length)) {
      problems.add("the crashed run's log is not a byte prefix of the golden run's log");
    }
  }

  // --- running a child ------------------------------------------------------------------

  private Protocol execute(Path dir, Config config, long seed, int ops, String crash)
      throws IOException, InterruptedException {
    deleteTree(dir);
    Files.createDirectories(dir);
    String java = ProcessHandle.current().info().command().orElse("java");
    List<String> command =
        List.of(
            java,
            "-XX:TieredStopAtLevel=1",
            "-Xmx128m",
            "-Djdk.tracePinnedThreads=full",
            "-Dstdout.encoding=UTF-8",
            "-cp",
            System.getProperty("java.class.path"),
            LsnBoundaryTarget.class.getName(),
            dir.toString(),
            Long.toString(seed),
            Integer.toString(ops),
            config.fsync(),
            config.checkpoints(),
            crash);
    Process child = new ProcessBuilder(command).redirectErrorStream(true).start();
    List<String> lines = new ArrayList<>();
    try (BufferedReader reader =
        new BufferedReader(new InputStreamReader(child.getInputStream(), StandardCharsets.UTF_8))) {
      String line;
      while ((line = reader.readLine()) != null) {
        if (line.contains("pinned")) {
          throw new IOException("the write path pinned a virtual thread: " + line);
        }
        lines.add(line);
      }
    }
    if (!child.waitFor(120, TimeUnit.SECONDS)) {
      child.destroyForcibly();
      throw new IOException("the child did not finish: " + dir);
    }
    return new Protocol(lines, child.exitValue());
  }

  // --- the child's protocol, parsed -----------------------------------------------------

  /** One child's stdout, understood: its acks, its hash table, and how it stopped. */
  private static final class Protocol {

    private final List<String> lines;
    private final int exit;

    Protocol(List<String> lines, int exit) {
      this.lines = lines;
      this.exit = exit;
    }

    /**
     * SIGKILL, as the exit code of a process ended by a signal — and as the absence of the
     * child's own admission that it could not signal itself, which is the one way a status of 137
     * could be a clean {@code halt()} wearing a signal's number.
     */
    boolean killed() {
      for (String line : lines) {
        if (line.startsWith("HALTING ")) {
          return false;
        }
      }
      return exit == 137 || exit == 9 || exit < 0;
    }

    boolean ok() {
      return find("OK ") != null;
    }

    int acks() {
      int acks = 0;
      for (String line : lines) {
        if (line.startsWith("A ")) {
          acks++;
        }
      }
      return acks;
    }

    /**
     * Every operation's outcome, in order: the {@code A}/{@code N}/{@code R}/{@code C} part of
     * its line. A retry and a conflict are outcomes the fold must apply too — they change
     * nothing in the golden ledger, but proving that is the fold's job, not an assumption of it.
     */
    List<String> outcomes() {
      List<String> outcomes = new ArrayList<>();
      for (String line : lines) {
        if (line.startsWith("A ") || line.startsWith("N ") || line.startsWith("R ")
            || line.startsWith("C ")) {
          outcomes.add(line);
        }
      }
      return outcomes;
    }

    /** The first {@code count} operations' outcomes, in order. */
    List<String> opPrefix(int count) {
      List<String> outcomes = outcomes();
      return outcomes.subList(0, Math.min(count, outcomes.size()));
    }

    /**
     * Arm points for the stage sweep: the operation before each {@code CKPT} line, capped at
     * {@code wanted} and spread first/middle/last. Arming after op {@code k - 1} makes the
     * checkpoint that began during op {@code k} the one the child dies inside — the checkpoint
     * is real, because the golden run took it, and it is after the arm, by construction.
     */
    List<Integer> stageArms(int wanted) {
      List<Integer> checkpoints = new ArrayList<>();
      for (String line : lines) {
        if (line.startsWith("CKPT ")) {
          checkpoints.add(Integer.parseInt(field(line, 1)));
        }
      }
      if (checkpoints.isEmpty()) {
        return List.of();
      }
      List<Integer> arms = new ArrayList<>(wanted);
      for (int i = 0; i < wanted && i < checkpoints.size(); i++) {
        int index = checkpoints.size() == 1
            ? 0
            : Math.round((checkpoints.size() - 1) * i / (float) (wanted - 1));
        int arm = checkpoints.get(index) - 1;
        if (arm >= 0 && !arms.contains(arm)) {
          arms.add(arm);
        }
      }
      return arms;
    }

    /** An {@code H} line reads {@code H <op> <hash> <lsn> <end>}, so the hash is field 2. */
    String hashAt(int op) {
      return field(find("H " + op + " "), 2);
    }

    /** The boundary's journal lsn: field 3, after the hash. */
    long lsnAt(int op) {
      return Long.parseLong(field(find("H " + op + " "), 3));
    }

    String liveHash() {
      return field(find("LIVE "), 1);
    }

    long liveEnd() {
      return Long.parseLong(field(find("LIVE "), 3));
    }

    CheckpointStage stage() {
      String line = find("STAGE ");
      return line == null ? null : CheckpointStage.valueOf(field(line, 1));
    }

    String stageHash() {
      return field(find("STAGE "), 2);
    }

    String finalHash() {
      String line = find("OK ");
      return line == null ? liveHash() : field(line, 1);
    }

    int indexOfHash(String hash) {
      if (hash == null) {
        return -1;
      }
      for (int op = 0; ; op++) {
        String at = hashAt(op);
        if (at == null) {
          return -1;
        }
        if (at.equals(hash)) {
          return op;
        }
      }
    }

    String summary() {
      String last = lines.isEmpty() ? "(no output)" : lines.get(lines.size() - 1);
      return "exit " + exit + ", " + lines.size() + " line(s), last: " + last;
    }

    private String find(String prefix) {
      for (String line : lines) {
        if (line.startsWith(prefix)) {
          return line;
        }
      }
      return null;
    }

    /** Field {@code index} of a line, where field 0 is the keyword. */
    private static String field(String line, int index) {
      return line == null ? null : line.split(" ")[index];
    }
  }

  // --- reporting and plumbing -----------------------------------------------------------

  private void report(long millis, int ops) {
    System.out.println();
    System.out.println("boundary harness: " + cycles + " cycles, " + killed + " SIGKILLs, "
        + recoveries + " recoveries");
    System.out.println(
        "  " + (ops + 1) + " prefixes per configuration, plus " + stageKills
            + " stage kills, armed on the golden run's own checkpoints");
    System.out.println("  recoveries folded " + tailRecords + " tail record(s) in total");
    System.out.println("  " + checkpointsUsed + " recoveries used a checkpoint");
    System.out.println("  " + millis + " ms");
    if (failures.isEmpty()) {
      System.out.println();
      System.out.println("PASS " + cycles + "/" + cycles + " boundary cycles byte-identical");
    }
  }

  private static String shortHash(String hash) {
    return hash == null ? "(none)" : hash.substring(0, 12) + "...";
  }

  private static String slug(Config config) {
    return config.fsync() + "-" + config.checkpoints().replace(':', '-');
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

  /** One swept configuration: how durable a commit must be, and how often state is snapshotted. */
  private record Config(String describe, String fsync, String checkpoints) {

    FsyncPolicy fsyncPolicy() {
      return LsnBoundaryTarget.parseFsync(fsync);
    }

    CheckpointPolicy checkpointPolicy() {
      return LsnBoundaryTarget.parseCheckpoints(checkpoints);
    }
  }
}
