package dev.ledgerx.checkpoint;

import dev.ledgerx.domain.InMemoryLedger;
import dev.ledgerx.journal.DurableLedger;
import dev.ledgerx.journal.Replay;
import dev.ledgerx.testing.RandomSource;
import dev.ledgerx.wal.Corruption;
import dev.ledgerx.wal.FsyncPolicy;
import dev.ledgerx.wal.UnrecoverableLogException;
import dev.ledgerx.wal.WalFormat;
import dev.ledgerx.wal.WalRecovery;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The crash-at-every-LSN-boundary harness ADR 0004 §7 cites: for every prefix of a random
 * history — crash, recover, hash — 100% byte-identical.
 *
 * <p><strong>What "every prefix" means here, mechanically.</strong> A history of {@code n}
 * accepted steps produces a log whose records end at known byte offsets; a crash at any instant
 * leaves some prefix of those bytes durable — not a whole number of records, a whole number of
 * <em>bytes</em>. So the harness sweeps every byte offset {@code o} of the finished log and
 * reconstructs, for each, the exact disk state the instant the log was {@code o} bytes long:
 * the log truncated to {@code o}, plus the checkpoint that was live then (the newest one whose
 * watermark does not exceed {@code o} — snapshots are byte-identical files, so replaying the
 * recorded bytes is replaying history). It then opens a ledger on that state and demands the
 * recovered state equal, byte for byte, the canonical state the clean run had after the last
 * record that fits.
 *
 * <p>Four verdicts per offset, all of which must hold for the run to pass:
 *
 * <ul>
 *   <li><strong>checkpoint + tail</strong> — the recovered ledger's canonical bytes equal the
 *       clean run's bytes at that prefix;</li>
 *   <li><strong>full replay from scratch</strong> — the same log folded with the checkpoint
 *       deleted folds to the same bytes, which is the identity the ticket names: the
 *       checkpoint is an optimization, never a source of truth;</li>
 *   <li><strong>crash, recover, continue</strong> — at every record boundary (and a sample of
 *       mid-frame cuts), posting the rest of the history through the recovered ledger and
 *       reopening lands on the clean run's final bytes: recovery is not merely correct at the
 *       moment it happens, it is invisible in the state of everything that comes after;</li>
 *   <li><strong>torn heads refuse</strong> — offsets 1–15 cut the segment header, which no
 *       crash can damage; the open must refuse with the log untouched.</li>
 * </ul>
 *
 * <p>The crashes are byte-level truncations rather than forked {@code SIGKILL}s — that is the
 * point, not a shortcut: a kill lands at one instant per JVM launch, and the ticket's claim is
 * about <em>every</em> prefix. {@code dev.ledgerx.wal.crash.CrashHarness} already proves the
 * forked-kill end (real process death, real page-cache semantics); this harness proves the
 * exhaustive one, and what it cannot model is stated in the ADR: a controller that reorders or
 * lies about a force, which byte truncation cannot produce.
 *
 * <p>Usage: {@code java dev.ledgerx.checkpoint.ReplayHarness [trials] [ops]}; sized by
 * {@code -Dledgerx.replay.trials} / {@code .ops} / {@code .seed} for CI and campaigns. Exits
 * non-zero on any mismatch, with the trial, seed and byte offset of the first divergence.
 */
public final class ReplayHarness {

  /** The campaign CI runs: 12 histories × 40 steps sweeps ~40,000 crash offsets. */
  private static final int DEFAULT_TRIALS = 12;

  private static final int DEFAULT_OPS = 40;

  /** A checkpoint every this many steps, so several checkpoints live through one history. */
  private static final int CHECKPOINT_EVERY = 7;

  /** Byte offsets that are multiples of this also get the continue-the-history treatment. */
  private static final int CONTINUE_SAMPLE = 23;

  private static final Path ROOT = Path.of("build", "replay-harness");

  private static final FsyncPolicy[] POLICIES = {
    FsyncPolicy.GROUP_COMMIT, FsyncPolicy.PER_COMMIT, FsyncPolicy.NO_FSYNC,
  };

  private final List<String> failures = new ArrayList<>();

  public static void main(String[] args) throws IOException {
    int trials = integer(args, 0, "ledgerx.replay.trials", DEFAULT_TRIALS);
    int ops = integer(args, 1, "ledgerx.replay.ops", DEFAULT_OPS);
    long seed = Long.getLong("ledgerx.replay.seed", 20260917L);
    boolean keep = args.length > 2 || System.getProperty("ledgerx.replay.keep") != null;
    ReplayHarness harness = new ReplayHarness();
    long started = System.nanoTime();
    harness.run(Math.max(1, trials), Math.max(8, ops), seed, keep);
    harness.report((System.nanoTime() - started) / 1_000_000L, trials);
    if (!harness.failures.isEmpty()) {
      System.out.println(
          "FAIL " + harness.failures.size() + " mismatches — build/replay-harness/trial-N keeps"
              + " the failing history");
      System.exit(1);
    }
  }

  private static int integer(String[] args, int at, String property, int fallback) {
    if (args.length > at) {
      return Integer.parseInt(args[at]);
    }
    return Integer.getInteger(property, fallback);
  }

  private void run(int trials, int ops, long seed, boolean keep) throws IOException {
    deleteTree(ROOT);
    Files.createDirectories(ROOT);
    for (int trial = 1; trial <= trials; trial++) {
      long trialSeed = seed + trial;
      FsyncPolicy policy = POLICIES[(trial - 1) % POLICIES.length];
      Path dir = ROOT.resolve("trial-" + trial);
      Files.createDirectories(dir);
      try {
        sweep(trial, trialSeed, policy, ops, dir);
      } catch (IOException | RuntimeException broke) {
        failures.add("trial " + trial + " blew up: " + broke);
        broke.printStackTrace();
      }
      if (!failures.isEmpty() || keep) {
        System.out.println("  trial " + trial + " state kept in " + dir);
      } else {
        deleteTree(dir);
      }
    }
    if (failures.isEmpty()) {
      deleteTree(ROOT);
    }
  }

  private void sweep(int number, long seed, FsyncPolicy policy, int ops, Path dir)
      throws IOException {
    RandomSource rnd = new RandomSource(seed);
    List<Histories.Step> steps = Histories.random(rnd, ops);

    // The clean run, recorded: canonical bytes after every step, the byte offset every record
    // ends at, and every checkpoint as the bytes that were on disk when it was live.
    int n = steps.size();
    byte[][] canonical = new byte[n + 1][];
    long[] endAt = new long[n + 1];
    List<byte[]> checkpointBytes = new ArrayList<>();
    List<Long> checkpointOffsets = new ArrayList<>();
    canonical[0] = StateHash.encode(new InMemoryLedger());
    endAt[0] = WalFormat.SEGMENT_HEADER_BYTES;
    InMemoryLedger folded = new InMemoryLedger();
    long tears = 0L;
    long continues = 0L;
    try (DurableLedger ledger = DurableLedger.open(dir, policy)) {
      for (int i = 0; i < n; i++) {
        Histories.apply(ledger, steps, i, i + 1);
        Histories.apply(folded, steps, i, i + 1);
        canonical[i + 1] = StateHash.encode(ledger.ledger());
        if (!Arrays.equals(canonical[i + 1], StateHash.encode(folded))) {
          failures.add("trial " + number + " step " + (i + 1)
              + ": the live ledger and a fold of the same steps disagree");
          return;
        }
        endAt[i + 1] = ledger.lastAck().end();
        if ((i + 1) % CHECKPOINT_EVERY == 0) {
          Checkpoint.Loaded mark = ledger.writeCheckpoint();
          if (mark.watermarkLsn() != i + 1L || mark.watermarkOffset() != endAt[i + 1]) {
            failures.add("trial " + number + ": checkpoint watermark (lsn "
                + mark.watermarkLsn() + " @ " + mark.watermarkOffset() + ") is not the log ("
                + (i + 1) + " @ " + endAt[i + 1] + ")");
            return;
          }
          checkpointBytes.add(Files.readAllBytes(dir.resolve(Checkpoint.FILE_NAME)));
          checkpointOffsets.add(endAt[i + 1]);
        }
      }
    }
    byte[] pristine = Files.readAllBytes(dir.resolve(dev.ledgerx.wal.Wal.FILE_NAME));
    long size = endAt[n];

    // The sweep. scratch holds exactly one crash's disk state at a time.
    Path scratch = dir.resolve("scratch");
    Files.createDirectories(scratch);
    Path scratchWal = scratch.resolve(dev.ledgerx.wal.Wal.FILE_NAME);
    Path scratchCp = scratch.resolve(Checkpoint.FILE_NAME);
    for (long o = 0; o <= size; o++) {
      Files.write(scratchWal, Arrays.copyOf(pristine, (int) o));
      Files.deleteIfExists(scratch.resolve(Checkpoint.REJECTED_NAME));
      Files.deleteIfExists(scratch.resolve(Checkpoint.TEMP_NAME));
      int live = -1;
      for (int m = checkpointOffsets.size() - 1; m >= 0; m--) {
        if (checkpointOffsets.get(m) <= o) {
          live = m;
          break;
        }
      }
      if (live >= 0) {
        Files.write(scratchCp, checkpointBytes.get(live));
      } else {
        Files.deleteIfExists(scratchCp);
      }
      int k = 0;
      while (k + 1 <= n && endAt[k + 1] <= o) {
        k++;
      }

      if (o > 0 && o < WalFormat.SEGMENT_HEADER_BYTES) {
        // The head of a log is never truncatable (ADR 0003 §4): expect refusal, file intact.
        byte[] torn = Files.readAllBytes(scratchWal);
        try {
          DurableLedger.open(scratch, policy).close();
          failures.add("trial " + number + " offset " + o + ": a damaged head opened");
        } catch (UnrecoverableLogException refused) {
          if (refused.corruption() != Corruption.BAD_SEGMENT_HEADER) {
            failures.add("trial " + number + " offset " + o + ": refused as "
                + refused.corruption() + ", not a damaged head");
          }
        }
        if (!Arrays.equals(torn, Files.readAllBytes(scratchWal))) {
          failures.add("trial " + number + " offset " + o + ": the refusal edited the log");
        }
        continue;
      }

      try (DurableLedger opened = DurableLedger.open(scratch, policy)) {
        byte[] recovered = StateHash.encode(opened.ledger());
        if (!Arrays.equals(recovered, canonical[k])) {
          failures.add("trial " + number + " [" + policy + "] seed " + seed + ": crash at byte "
              + o + " (prefix " + k + " of " + n + ") recovered a different state:\n  got  "
              + StateHash.hex(recovered) + "\n  want " + StateHash.hex(canonical[k]));
          return;
        }
        if (opened.recovery().torn()) {
          tears++;
        }
        // Full replay from scratch, on the same truncated log with the checkpoint gone: the
        // fold the whole log produces must be the state the checkpoint path produced.
        WalRecovery.Scan scan = WalRecovery.scan(scratchWal);
        byte[] scratchReplay = StateHash.encode(Replay.foldEvents(scan.records()));
        if (!Arrays.equals(scratchReplay, canonical[k])) {
          failures.add("trial " + number + " [" + policy + "] seed " + seed + ": crash at byte "
              + o + " — full replay from scratch differs from checkpoint + tail:\n  got  "
              + StateHash.hex(scratchReplay) + "\n  want " + StateHash.hex(canonical[k]));
          return;
        }

        boolean boundary = endAt[k] == o;
        if (boundary || o % CONTINUE_SAMPLE == 0) {
          Histories.apply(opened, steps, k, n);
          byte[] continued = StateHash.encode(opened.ledger());
          if (!Arrays.equals(continued, canonical[n])) {
            failures.add("trial " + number + " [" + policy + "] seed " + seed + ": crash at "
                + "byte " + o + ", recover, continue does not reach the clean run's end:\n  got "
                + " " + StateHash.hex(continued) + "\n  want " + StateHash.hex(canonical[n]));
            return;
          }
          continues++;
        }
      }
      if (endAt[Math.min(k, n)] == o) {
        // At a record boundary the recovery left nothing to repair; reopen what the
        // continuation wrote and confirm the state is stable across a second recovery.
        try (DurableLedger reopened = DurableLedger.open(scratch, policy)) {
          byte[] after = StateHash.encode(reopened.ledger());
          if (!Arrays.equals(after, canonical[n])) {
            failures.add("trial " + number + " [" + policy + "] seed " + seed + ": crash at "
                + "byte " + o + " — the reopened continuation differs:\n  got  "
                + StateHash.hex(after) + "\n  want " + StateHash.hex(canonical[n]));
            return;
          }
        }
      }
    }
    System.out.println(
        "  trial " + number + " [" + policy.mode().toString().toLowerCase() + " / seed " + seed
            + "]: steps=" + n + " log=" + size + "B checkpoints=" + checkpointBytes.size()
            + " tears=" + tears + " continues=" + continues
            + " mismatches=" + failures.size());
    System.out.flush();
  }

  private void report(long millis, int trials) {
    System.out.println();
    if (failures.isEmpty()) {
      System.out.println(
          "PASS " + trials + "/" + trials + " replay trials — every prefix of every history"
              + " recovered byte-identically: checkpoint + tail, full replay from scratch, and"
              + " crash-recover-continue all agree with the clean run");
    }
    System.out.println("  in " + millis + " ms");
  }

  private static void deleteTree(Path root) throws IOException {
    if (!Files.exists(root)) {
      return;
    }
    List<Path> paths = new ArrayList<>();
    try (var walked = Files.walk(root)) {
      walked.sorted(java.util.Comparator.reverseOrder()).forEach(paths::add);
    }
    for (Path path : paths) {
      Files.deleteIfExists(path);
    }
  }
}
