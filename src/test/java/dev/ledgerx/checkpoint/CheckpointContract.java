package dev.ledgerx.checkpoint;

import dev.ledgerx.domain.AccountId;
import dev.ledgerx.domain.AccountKind;
import dev.ledgerx.domain.InMemoryLedger;
import dev.ledgerx.domain.Money;
import dev.ledgerx.domain.Transaction;
import dev.ledgerx.journal.DurableLedger;
import dev.ledgerx.journal.Replay;
import dev.ledgerx.testing.RandomSource;
import dev.ledgerx.wal.Corruption;
import dev.ledgerx.wal.FsyncPolicy;
import dev.ledgerx.wal.RecordType;
import dev.ledgerx.wal.Tail;
import dev.ledgerx.wal.UnrecoverableLogException;
import dev.ledgerx.wal.WalFormat;
import dev.ledgerx.wal.WalRecovery;
import dev.ledgerx.wal.WalRecord;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The checkpoint contract: every claim ADR 0004 makes about the state hash, the file format,
 * the swap protocol and the recovery rules, asserted on every build.
 *
 * <p>Not the replay harness — {@link ReplayHarness} crashes a history at every byte offset it
 * has; this file asks the ADR's questions at exact bytes, so that "a damaged checkpoint is
 * discarded but a checkpoint ahead of its log refuses to open" is a line in a test report
 * rather than a paragraph nobody re-reads. The byte-level checks are written down rather than
 * computed by the code under test, on the same rule as {@code WalContract}'s.
 */
public final class CheckpointContract {

  private static final List<String> failures = new ArrayList<>();
  private static int checksRun;

  private CheckpointContract() {}

  /** A check returns a short detail string for the log, or throws to fail. */
  private interface Check {
    String run(Path dir) throws Exception;
  }

  public static void main(String[] args) {
    System.out.println("ledger-x checkpoint contract on " + Runtime.version());
    check("a canonical state is exactly these bytes", CheckpointContract::canonicalIsTheseBytes);
    check("a checkpoint file is exactly these bytes", CheckpointContract::fileIsTheseBytes);
    check("the same state encodes identically; a different state does not",
        CheckpointContract::encodingIsDeterministic);
    check("restore is the inverse of encode, over random histories",
        CheckpointContract::restoreIsTheInverse);
    check("a checkpoint is byte-identical however it was produced",
        CheckpointContract::checkpointIsByteIdentical);
    check("the watermark names bytes the log has forced, under every policy",
        CheckpointContract::watermarkIsForced);
    check("a checkpoint ahead of its log refuses to open", CheckpointContract::aheadRefuses);
    check("a damaged checkpoint is discarded, never obeyed", CheckpointContract::damagedDiscarded);
    check("a temp file is garbage, never state", CheckpointContract::tempIsGarbage);
    check("deleting the checkpoint cannot change recovery", CheckpointContract::gcIsSafe);
    check("recovery folds the tail, not the history", CheckpointContract::tailNotHistory);
    check("a tear above the checkpoint cuts, marks, and replays identically",
        CheckpointContract::tearAboveCheckpoint);
    check("an empty ledger checkpoints, restores, and stays a legal stale checkpoint",
        CheckpointContract::emptyLedgerCheckpoints);

    System.out.println();
    if (failures.isEmpty()) {
      System.out.println("PASS " + checksRun + "/" + checksRun + " checkpoint checks");
      return;
    }
    System.out.println("FAIL " + failures.size() + " of " + checksRun + " checkpoint checks");
    for (String failure : failures) {
      System.out.println("  - " + failure);
    }
    System.exit(1);
  }

  private static void check(String name, Check body) {
    checksRun++;
    Path dir = null;
    try {
      dir = Files.createTempDirectory("ledger-x-ckp");
      String detail = body.run(dir);
      System.out.println("  ok   " + name + " [" + detail + "]");
      // Buffered stdout would hide which check is slow, and a hung check is the interesting
      // case — the same rule the other contract suites run under.
      System.out.flush();
      deleteTree(dir);
    } catch (Throwable failed) {
      failures.add(name + " — " + failed);
      System.out.println("  FAIL " + name + " — " + failed);
      System.out.flush();
      if (dir != null) {
        System.out.println("       evidence kept in " + dir);
      }
    }
  }

  // --- the hash, byte for byte ---------------------------------------------------------

  /**
   * Two accounts, one transfer: alpha (ASSET) −410, beta (LIABILITY) +410, three events. The
   * encoding below is written down byte by byte, not produced by {@link StateHash}.
   */
  private static byte[] expectedCanonical() {
    byte[] expected = new byte[46];
    expected[0] = 1;                                       // version
    WalFormat.putLong(expected, 1, 3L);                    // eventCount: open, open, post
    WalFormat.putInt(expected, 9, 2);                      // accountCount
    byte[] alpha = "alpha".getBytes(StandardCharsets.US_ASCII);
    byte[] beta = "beta".getBytes(StandardCharsets.US_ASCII);
    expected[13] = (byte) alpha.length;                    // u8 idLength
    System.arraycopy(alpha, 0, expected, 14, alpha.length);
    expected[19] = 0;                                      // kind ordinal, ASSET
    expected[20] = (byte) beta.length;
    System.arraycopy(beta, 0, expected, 21, beta.length);
    expected[25] = 1;                                      // kind ordinal, LIABILITY
    WalFormat.putInt(expected, 26, 2);                     // balanceCount
    WalFormat.putLong(expected, 30, -410L);                // alpha, debit-positive
    WalFormat.putLong(expected, 38, 410L);                 // beta
    return expected;
  }

  private static String canonicalIsTheseBytes(Path dir) {
    InMemoryLedger ledger = new InMemoryLedger();
    ledger.openAccount(new AccountId("alpha"), AccountKind.ASSET);
    ledger.openAccount(new AccountId("beta"), AccountKind.LIABILITY);
    ledger.transfer(new AccountId("alpha"), new AccountId("beta"), Money.ofMinor(410L));
    byte[] canonical = StateHash.encode(ledger);
    byte[] expected = expectedCanonical();
    if (!Arrays.equals(expected, canonical)) {
      throw new AssertionError(
          "the canonical state is not the layout ADR 0004 §2 documents:\n  was "
              + StateHash.hex(canonical) + "\n  want " + StateHash.hex(expected));
    }
    if (!Arrays.equals(canonical, StateHash.encode(ledger))) {
      throw new AssertionError("one ledger encoded two ways");
    }
    return "46 bytes: version, event count 3, two accounts, two balances";
  }

  private static String fileIsTheseBytes(Path dir) throws IOException {
    byte[] state = expectedCanonical();
    Checkpoint.write(dir, 3L, 160L, state);
    byte[] onDisk = Files.readAllBytes(dir.resolve(Checkpoint.FILE_NAME));
    byte[] expected =
        new byte[Checkpoint.HEADER_BYTES + state.length + Checkpoint.TRAILER_BYTES];
    WalFormat.putInt(expected, 0, Checkpoint.SEGMENT_MAGIC);
    expected[4] = Checkpoint.FORMAT_VERSION;
    expected[5] = 0;
    WalFormat.putShort(expected, 6, Checkpoint.HEADER_BYTES);
    WalFormat.putInt(expected, 8, 0);
    WalFormat.putLong(expected, 12, 3L);
    WalFormat.putLong(expected, 20, 160L);
    WalFormat.putInt(expected, 28, state.length);
    WalFormat.putInt(expected, 32, WalFormat.crc32c(expected, 32));
    System.arraycopy(state, 0, expected, Checkpoint.HEADER_BYTES, state.length);
    byte[] digest = sha256(state);
    System.arraycopy(digest, 0, expected,
        Checkpoint.HEADER_BYTES + state.length, Checkpoint.DIGEST_BYTES);
    WalFormat.putInt(expected, expected.length - 4,
        WalFormat.crc32c(expected, expected.length - 4));
    if (!Arrays.equals(expected, onDisk)) {
      throw new AssertionError(
          "the checkpoint file is not the layout ADR 0004 §3 documents:\n  was "
              + StateHash.hex(onDisk) + "\n  want " + StateHash.hex(expected));
    }
    if (Files.exists(dir.resolve(Checkpoint.TEMP_NAME))) {
      throw new AssertionError("the swap left its temp file behind");
    }
    return onDisk.length + " bytes: 36 header, 46 state, 32 digest, 4 crc";
  }

  private static String encodingIsDeterministic(Path dir) {
    List<Histories.Step> steps = Histories.random(new RandomSource(20260917L), 20);
    InMemoryLedger first = new InMemoryLedger();
    Histories.apply(first, steps, 0, steps.size());
    InMemoryLedger second = new InMemoryLedger();
    Histories.apply(second, steps, 0, steps.size());
    if (!Arrays.equals(StateHash.encode(first), StateHash.encode(second))) {
      throw new AssertionError("two ledgers built by the same steps encoded differently");
    }

    // A cancelling pair of postings leaves every balance exactly where it was and grows the
    // journal by two — the near-miss that justifies the event count's place in the hash.
    InMemoryLedger padded = new InMemoryLedger();
    Histories.apply(padded, steps, 0, steps.size());
    padded.post(Transaction.transfer(
        new AccountId("alpha"), new AccountId("beta"), Money.ofMinor(11L)));
    padded.post(Transaction.transfer(
        new AccountId("beta"), new AccountId("alpha"), Money.ofMinor(11L)));
    if (!padded.balances().equals(first.balances())) {
      throw new AssertionError("the near-miss moved money; the fixture is wrong");
    }
    if (Arrays.equals(StateHash.encode(first), StateHash.encode(padded))) {
      throw new AssertionError("same balances at a different journal depth hashed identically");
    }

    // Opening order is canonical order: the same table with the accounts swapped encodes
    // differently, because "which account was opened first" is a fact about the history.
    InMemoryLedger swapped = new InMemoryLedger();
    swapped.openAccount(new AccountId("beta"), AccountKind.LIABILITY);
    swapped.openAccount(new AccountId("alpha"), AccountKind.ASSET);
    swapped.transfer(new AccountId("alpha"), new AccountId("beta"), Money.ofMinor(410L));
    InMemoryLedger plain = new InMemoryLedger();
    plain.openAccount(new AccountId("alpha"), AccountKind.ASSET);
    plain.openAccount(new AccountId("beta"), AccountKind.LIABILITY);
    plain.transfer(new AccountId("alpha"), new AccountId("beta"), Money.ofMinor(410L));
    if (Arrays.equals(StateHash.encode(swapped), StateHash.encode(plain))) {
      throw new AssertionError("opening order is not canonical order");
    }

    // A kind is state: the same balances under different metadata is a different state.
    byte[] modified = StateHash.encode(plain).clone();
    modified[19] = 2; // alpha's kind byte, ASSET -> EQUITY
    if (Arrays.equals(StateHash.encode(plain), modified)) {
      throw new AssertionError("the kind byte is not part of the hash");
    }
    return "identical for identical histories; depth, order and kind each move it";
  }

  private static String restoreIsTheInverse(Path dir) throws IOException {
    RandomSource rnd = new RandomSource(40401L);
    for (int trial = 0; trial < 8; trial++) {
      List<Histories.Step> steps = Histories.random(rnd.fork(), 24);
      InMemoryLedger ledger = new InMemoryLedger();
      Histories.apply(ledger, steps, 0, steps.size());
      byte[] canonical = StateHash.encode(ledger);
      InMemoryLedger restored = StateHash.decodeLedger(canonical);
      restored.audit();
      if (!restored.balances().equals(ledger.balances())) {
        throw new AssertionError("trial " + trial + ": restored balances differ");
      }
      if (restored.size() != ledger.size()) {
        throw new AssertionError("trial " + trial + ": restored journal position differs");
      }
      if (!Arrays.equals(StateHash.encode(restored), canonical)) {
        throw new AssertionError("trial " + trial + ": encode(decode(b)) != b");
      }
      // A restored ledger is a real ledger: it posts, and the posting lands on top of the
      // restored balances with the audit still true afterwards.
      restored.post(Transaction.transfer(
          new AccountId("alpha"), new AccountId("beta"), Money.ofMinor(123L)));
      restored.audit();
    }
    return "8 histories: balances, position, audit and re-encoding all survive the round trip";
  }

  // --- the swap, end to end ---------------------------------------------------------------

  /** Runs a seeded history through a durable ledger, checkpointing every {@code every} steps. */
  private static DurableLedger run(
      Path dir, List<Histories.Step> steps, FsyncPolicy policy, int every) throws IOException {
    DurableLedger ledger = DurableLedger.open(dir, policy);
    for (int i = 0; i < steps.size(); i++) {
      Histories.apply(ledger, steps, i, i + 1);
      if (every > 0 && (i + 1) % every == 0) {
        ledger.writeCheckpoint();
      }
    }
    return ledger;
  }

  private static String checkpointIsByteIdentical(Path dir) throws IOException {
    List<Histories.Step> steps = Histories.random(new RandomSource(70408L), 18);
    Path one = dir.resolve("one");
    Path two = dir.resolve("two");
    try (DurableLedger a = run(one, steps, FsyncPolicy.GROUP_COMMIT, 9)) {
      try (DurableLedger b = run(two, steps, FsyncPolicy.GROUP_COMMIT, 9)) {
        if (!Arrays.equals(StateHash.encode(a.ledger()), StateHash.encode(b.ledger()))) {
          throw new AssertionError("two clean runs of one history disagree");
        }
      }
    }
    byte[] first = Files.readAllBytes(one.resolve(Checkpoint.FILE_NAME));
    byte[] second = Files.readAllBytes(two.resolve(Checkpoint.FILE_NAME));
    if (!Arrays.equals(first, second)) {
      throw new AssertionError(
          "the same state checkpointed twice is not byte-identical — a clock or a counter is"
              + " in the file");
    }
    return first.length + " bytes, identical across two runs and two directories";
  }

  private static String watermarkIsForced(Path dir) throws IOException {
    for (FsyncPolicy policy : FsyncPolicy.MENU) {
      Path sub = dir.resolve(policy.mode().name().toLowerCase());
      Files.createDirectories(sub);
      List<Histories.Step> steps = Histories.random(new RandomSource(80301L), 5);
      DurableLedger ledger = DurableLedger.open(sub, policy);
      Histories.apply(ledger, steps, 0, steps.size());
      if (policy == FsyncPolicy.NO_FSYNC
          && ledger.wal().durableThrough() >= ledger.wal().writtenThrough()) {
        throw new AssertionError("no-fsync claimed a force it never issued");
      }
      Checkpoint.Loaded checkpoint = ledger.writeCheckpoint();
      long size = Files.size(sub.resolve(dev.ledgerx.wal.Wal.FILE_NAME));
      if (checkpoint.watermarkLsn() != steps.size()) {
        throw new AssertionError(policy + ": watermark lsn " + checkpoint.watermarkLsn()
            + " is not the log's last record " + steps.size());
      }
      if (checkpoint.watermarkOffset() != size
          || ledger.wal().durableThrough() != size
          || ledger.wal().writtenThrough() != size) {
        throw new AssertionError(policy + ": the watermark is not the forced end of the log ("
            + checkpoint.watermarkOffset() + " vs durable " + ledger.wal().durableThrough()
            + ", written " + ledger.wal().writtenThrough() + ", file " + size + ")");
      }
      if (Files.exists(sub.resolve(Checkpoint.TEMP_NAME))) {
        throw new AssertionError(policy + ": the swap left its temp file behind");
      }
      // The force barrier is a real force: after one more lazy append, force() publishes the
      // durability of exactly the bytes that append wrote.
      ledger.post(Transaction.transfer(
          new AccountId("alpha"), new AccountId("beta"), Money.ofMinor(9L)));
      if (policy == FsyncPolicy.NO_FSYNC
          && ledger.wal().durableThrough() != checkpoint.watermarkOffset()) {
        throw new AssertionError("no-fsync advanced its watermark without a force");
      }
      long forced = ledger.wal().force();
      long after = Files.size(sub.resolve(dev.ledgerx.wal.Wal.FILE_NAME));
      if (forced != after || ledger.wal().durableThrough() != forced) {
        throw new AssertionError(
            policy + ": force() returned " + forced + ", not the end of the log " + after);
      }
      ledger.close();
    }
    return "per-commit, group commit and no-fsync all checkpoint through a durable watermark";
  }

  // --- the recovery rules -----------------------------------------------------------------

  /** Runs {@code steps} accepted steps and checkpoints at the end; returns the directory. */
  private static Path seedValid(Path dir, int steps) throws IOException {
    List<Histories.Step> history = Histories.random(new RandomSource(90210L + steps), steps);
    DurableLedger ledger = DurableLedger.open(dir, FsyncPolicy.GROUP_COMMIT);
    Histories.apply(ledger, history, 0, steps);
    ledger.writeCheckpoint();
    ledger.close();
    return dir;
  }

  private static String aheadRefuses(Path dir) throws IOException {
    Path sub = dir.resolve("ahead");
    Files.createDirectories(sub);
    seedValid(sub, 6);
    Path wal = sub.resolve(dev.ledgerx.wal.Wal.FILE_NAME);
    Path checkpoint = sub.resolve(Checkpoint.FILE_NAME);
    byte[] intactLog = Files.readAllBytes(wal);
    long watermark = WalFormat.longAt(Files.readAllBytes(checkpoint), 20);
    for (long cut : new long[] {WalFormat.SEGMENT_HEADER_BYTES, watermark / 2, watermark - 1}) {
      WalRecovery.truncateTo(wal, cut);
      byte[] torn = Files.readAllBytes(wal);
      try (DurableLedger ignored = DurableLedger.open(sub, FsyncPolicy.GROUP_COMMIT)) {
        throw new AssertionError("cut at " + cut + ": a checkpoint ahead of its log opened");
      } catch (UnrecoverableLogException refused) {
        if (refused.corruption() != Corruption.CHECKPOINT_MISMATCH) {
          throw new AssertionError("cut at " + cut + " refused as " + refused.corruption());
        }
      }
      if (!Arrays.equals(torn, Files.readAllBytes(wal))) {
        throw new AssertionError("cut at " + cut + ": the refusal edited the log");
      }
      Files.write(wal, intactLog);
    }
    // The frame-end cross-check, patched: a checkpoint that claims its LSN's frame ends at an
    // offset the log disagrees with is refused however intact its own bytes are.
    byte[] file = Files.readAllBytes(checkpoint);
    WalFormat.putLong(file, 20, WalFormat.longAt(file, 20) + 3L);
    rewriteChecksums(file);
    Files.write(checkpoint, file);
    try (DurableLedger ignored = DurableLedger.open(sub, FsyncPolicy.GROUP_COMMIT)) {
      throw new AssertionError("a moved watermark offset opened");
    } catch (UnrecoverableLogException refused) {
      if (refused.corruption() != Corruption.CHECKPOINT_MISMATCH) {
        throw new AssertionError("a moved offset refused as " + refused.corruption());
      }
    }
    // And an LSN the log never reached: same verdict, different lie.
    file = Files.readAllBytes(checkpoint);
    WalFormat.putLong(file, 12, 4_000L);
    WalFormat.putLong(file, 20, intactLog.length);
    rewriteChecksums(file);
    Files.write(checkpoint, file);
    try (DurableLedger ignored = DurableLedger.open(sub, FsyncPolicy.GROUP_COMMIT)) {
      throw new AssertionError("a watermark lsn past the log's end opened");
    } catch (UnrecoverableLogException refused) {
      if (refused.corruption() != Corruption.CHECKPOINT_MISMATCH) {
        throw new AssertionError("an over-lsn checkpoint refused as " + refused.corruption());
      }
    }
    return "3 truncations below the watermark plus 2 patched watermarks, all refused, log intact";
  }

  private static void rewriteChecksums(byte[] file) {
    WalFormat.putInt(file, 32, WalFormat.crc32c(file, 32));
    WalFormat.putInt(file, file.length - 4, WalFormat.crc32c(file, file.length - 4));
  }

  private static String damagedDiscarded(Path dir) throws IOException {
    Path sub = dir.resolve("damaged");
    Files.createDirectories(sub);
    seedValid(sub, 8);
    Path wal = sub.resolve(dev.ledgerx.wal.Wal.FILE_NAME);
    Path checkpoint = sub.resolve(Checkpoint.FILE_NAME);
    byte[] pristine = Files.readAllBytes(checkpoint);
    byte[] expected = StateHash.encode(fullReplay(wal));
    Map<String, byte[]> damages = new LinkedHashMap<>();
    for (int at : new int[] {
        Checkpoint.HEADER_BYTES + 2,                                  // inside the state
        pristine.length - Checkpoint.DIGEST_BYTES - 2,                // inside the digest
        15,                                                           // inside the header crc
        pristine.length - 2}) {                                       // inside the file crc
      byte[] flipped = pristine.clone();
      flipped[at] ^= 0x01;
      damages.put("flip bit at " + at, flipped);
    }
    for (int keep : new int[] {
        10,
        40,
        pristine.length - 1,
        Checkpoint.HEADER_BYTES + Checkpoint.TRAILER_BYTES - 1}) {
      damages.put("truncate to " + keep, Arrays.copyOf(pristine, keep));
    }
    for (Map.Entry<String, byte[]> damage : damages.entrySet()) {
      Files.write(checkpoint, damage.getValue());
      try (DurableLedger opened = DurableLedger.open(sub, FsyncPolicy.GROUP_COMMIT)) {
        if (!Arrays.equals(StateHash.encode(opened.ledger()), expected)) {
          throw new AssertionError(damage.getKey() + ": recovery without the checkpoint differs");
        }
      }
      if (Files.exists(checkpoint)) {
        throw new AssertionError(damage.getKey() + ": the damaged checkpoint was not set aside");
      }
      if (!Files.exists(sub.resolve(Checkpoint.REJECTED_NAME))) {
        throw new AssertionError(damage.getKey() + ": the evidence was not kept");
      }
      Files.delete(sub.resolve(Checkpoint.REJECTED_NAME));
    }
    return damages.size() + " damages: every one discarded, replay from scratch, evidence kept";
  }

  /** The ledger a from-scratch fold of {@code wal} produces — the reference for comparisons. */
  private static InMemoryLedger fullReplay(Path wal) throws IOException {
    return Replay.foldEvents(WalRecovery.scan(wal).records());
  }

  private static String tempIsGarbage(Path dir) throws IOException {
    Path sub = dir.resolve("temp");
    Files.createDirectories(sub);
    seedValid(sub, 6);
    Path checkpoint = sub.resolve(Checkpoint.FILE_NAME);
    byte[] expected = StateHash.encode(fullReplay(sub.resolve(dev.ledgerx.wal.Wal.FILE_NAME)));
    Files.write(sub.resolve(Checkpoint.TEMP_NAME), new byte[] {9, 9, 9, 9});
    try (DurableLedger opened = DurableLedger.open(sub, FsyncPolicy.GROUP_COMMIT)) {
      if (!Arrays.equals(StateHash.encode(opened.ledger()), expected)) {
        throw new AssertionError("a temp file changed recovery");
      }
    }
    if (Files.exists(sub.resolve(Checkpoint.TEMP_NAME))) {
      throw new AssertionError("a planted temp file survived open");
    }
    // A temp holding a perfectly valid checkpoint of a different state is still garbage: the
    // swap's rule is atomic rename, never "whatever tmp happens to hold".
    Path other = dir.resolve("other");
    Files.createDirectories(other);
    seedValid(other, 11);
    byte[] foreign = Files.readAllBytes(other.resolve(Checkpoint.FILE_NAME));
    Files.write(sub.resolve(Checkpoint.TEMP_NAME), foreign);
    try (DurableLedger opened = DurableLedger.open(sub, FsyncPolicy.GROUP_COMMIT)) {
      if (!Arrays.equals(StateHash.encode(opened.ledger()), expected)) {
        throw new AssertionError("a valid foreign temp file was obeyed");
      }
    }
    if (Files.exists(sub.resolve(Checkpoint.TEMP_NAME))) {
      throw new AssertionError("a foreign temp file survived open");
    }
    // No live checkpoint and a temp: from scratch, and the temp is still garbage.
    Files.delete(checkpoint);
    Files.write(sub.resolve(Checkpoint.TEMP_NAME), new byte[] {1});
    try (DurableLedger opened = DurableLedger.open(sub, FsyncPolicy.GROUP_COMMIT)) {
      if (!Arrays.equals(StateHash.encode(opened.ledger()), expected)) {
        throw new AssertionError("recovery without any checkpoint differs");
      }
    }
    if (Files.exists(sub.resolve(Checkpoint.TEMP_NAME))) {
      throw new AssertionError("a temp file survived open with no checkpoint beside it");
    }
    return "garbage and foreign temps: never state, always gone after open";
  }

  private static String gcIsSafe(Path dir) throws IOException {
    Path sub = dir.resolve("gc");
    Files.createDirectories(sub);
    List<Histories.Step> steps = Histories.random(new RandomSource(120451L), 12);
    byte[] expected;
    try (DurableLedger ledger = DurableLedger.open(sub, FsyncPolicy.GROUP_COMMIT)) {
      Histories.apply(ledger, steps, 0, 6);
      ledger.writeCheckpoint();
      Histories.apply(ledger, steps, 6, steps.size());
      expected = StateHash.encode(ledger.ledger());
    }
    Files.deleteIfExists(sub.resolve(Checkpoint.FILE_NAME));
    Files.deleteIfExists(sub.resolve(Checkpoint.REJECTED_NAME));
    try (DurableLedger reopened = DurableLedger.open(sub, FsyncPolicy.GROUP_COMMIT)) {
      if (reopened.checkpointAtOpen() != null) {
        throw new AssertionError("a deleted checkpoint was reported as loaded");
      }
      if (!Arrays.equals(StateHash.encode(reopened.ledger()), expected)) {
        throw new AssertionError("deleting the checkpoint changed recovery");
      }
    }
    return "checkpoint deleted after 6 of 12 steps: recovery is byte-identical without it";
  }

  private static String tailNotHistory(Path dir) throws IOException {
    Path sub = dir.resolve("tail");
    Files.createDirectories(sub);
    List<Histories.Step> steps = Histories.random(new RandomSource(130457L), 12);
    byte[] expected;
    Checkpoint.Loaded written;
    try (DurableLedger ledger = DurableLedger.open(sub, FsyncPolicy.GROUP_COMMIT)) {
      Histories.apply(ledger, steps, 0, 6);
      written = ledger.writeCheckpoint();
      Histories.apply(ledger, steps, 6, steps.size());
      expected = StateHash.encode(ledger.ledger());
    }
    DurableLedger reopened = DurableLedger.open(sub, FsyncPolicy.GROUP_COMMIT);
    Checkpoint.Loaded used = reopened.checkpointAtOpen();
    if (used == null
        || used.watermarkLsn() != written.watermarkLsn()
        || used.watermarkOffset() != written.watermarkOffset()) {
      throw new AssertionError("open did not use the checkpoint it was given");
    }
    if (reopened.ledger().size() != steps.size()) {
      throw new AssertionError(
          "the restored ledger lost the journal position: " + reopened.ledger().size() + " of "
              + steps.size());
    }
    if (reopened.ledger().events().size() != steps.size() - used.watermarkLsn()) {
      throw new AssertionError(
          "the restored ledger holds " + reopened.ledger().events().size() + " events; the tail"
              + " is " + (steps.size() - used.watermarkLsn())
              + " — the checkpoint was not actually used");
    }
    if (!Arrays.equals(StateHash.encode(reopened.ledger()), expected)) {
      throw new AssertionError("checkpoint + tail is not the state the clean run ended on");
    }
    reopened.close();
    return "watermark " + used.watermarkLsn() + ": 6 events folded, 6 restored, one state";
  }

  private static String tearAboveCheckpoint(Path dir) throws IOException {
    Path sub = dir.resolve("tear");
    Files.createDirectories(sub);
    List<Histories.Step> steps = Histories.random(new RandomSource(140459L), 10);
    try (DurableLedger ledger = DurableLedger.open(sub, FsyncPolicy.GROUP_COMMIT)) {
      Histories.apply(ledger, steps, 0, 4);
      ledger.writeCheckpoint();
      Histories.apply(ledger, steps, 4, steps.size());
    }
    Path wal = sub.resolve(dev.ledgerx.wal.Wal.FILE_NAME);
    InMemoryLedger expectedLedger = new InMemoryLedger();
    Histories.apply(expectedLedger, steps, 0, steps.size() - 1);
    byte[] expected = StateHash.encode(expectedLedger);
    WalRecovery.truncateTo(wal, Files.size(wal) - 3);
    DurableLedger opened = DurableLedger.open(sub, FsyncPolicy.GROUP_COMMIT);
    if (!opened.recovery().torn()) {
      throw new AssertionError("a torn tail was not reported torn");
    }
    if (!Arrays.equals(StateHash.encode(opened.ledger()), expected)) {
      throw new AssertionError("recovery after the tear is not the last whole record's state");
    }
    opened.close();
    List<WalRecord> records = WalRecovery.scan(wal).records();
    if (records.get(records.size() - 1).type() != RecordType.RECOVERY_MARKER) {
      throw new AssertionError("the cut left no marker saying so");
    }
    DurableLedger again = DurableLedger.open(sub, FsyncPolicy.GROUP_COMMIT);
    if (again.recovery().torn() || again.recovery().tail() != Tail.CLEAN_EOF) {
      throw new AssertionError("recovery is not idempotent: " + again.recovery());
    }
    if (!Arrays.equals(StateHash.encode(again.ledger()), expected)) {
      throw new AssertionError("the second recovery landed elsewhere");
    }
    Checkpoint.Loaded rewritten = again.writeCheckpoint();
    if (rewritten.watermarkLsn() != records.size()) {
      throw new AssertionError(
          "the new watermark " + rewritten.watermarkLsn() + " does not cover the marker the cut"
              + " appended (last lsn " + records.size() + ")");
    }
    again.close();
    return "cut at the last frame, marker appended, recovered twice, re-checkpointed at lsn "
        + records.size();
  }

  private static String emptyLedgerCheckpoints(Path dir) throws IOException {
    Path sub = dir.resolve("empty");
    Files.createDirectories(sub);
    byte[] emptyState;
    try (DurableLedger ledger = DurableLedger.open(sub, FsyncPolicy.GROUP_COMMIT)) {
      Checkpoint.Loaded empty = ledger.writeCheckpoint();
      if (empty.watermarkLsn() != 0L
          || empty.watermarkOffset() != WalFormat.SEGMENT_HEADER_BYTES) {
        throw new AssertionError(
            "an empty ledger's watermark is lsn " + empty.watermarkLsn() + " at byte "
                + empty.watermarkOffset());
      }
      emptyState = StateHash.encode(ledger.ledger());
    }
    DurableLedger reopened = DurableLedger.open(sub, FsyncPolicy.GROUP_COMMIT);
    if (reopened.checkpointAtOpen() == null
        || reopened.checkpointAtOpen().watermarkLsn() != 0L
        || !Arrays.equals(StateHash.encode(reopened.ledger()), emptyState)) {
      reopened.close();
      throw new AssertionError("an empty checkpoint did not restore to an empty ledger");
    }
    // The stale-but-legal case: the checkpoint predates every record, and the tail is the
    // whole log. The restored base is empty; the fold applies everything after it.
    List<Histories.Step> steps = Histories.random(new RandomSource(150463L), 5);
    Histories.apply(reopened, steps, 0, steps.size());
    byte[] expected = StateHash.encode(reopened.ledger());
    reopened.close();
    DurableLedger stale = DurableLedger.open(sub, FsyncPolicy.GROUP_COMMIT);
    try {
      if (stale.checkpointAtOpen() == null || stale.checkpointAtOpen().watermarkLsn() != 0L) {
        throw new AssertionError("the stale empty checkpoint was not used");
      }
      if (!Arrays.equals(StateHash.encode(stale.ledger()), expected)) {
        throw new AssertionError("an empty checkpoint plus the whole log is not the full state");
      }
    } finally {
      stale.close();
    }
    return "watermark (0, " + WalFormat.SEGMENT_HEADER_BYTES + "); the whole log folds over it";
  }

  // --- plumbing -----------------------------------------------------------------------------

  private static byte[] sha256(byte[] bytes) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(bytes);
    } catch (NoSuchAlgorithmException missing) {
      throw new IllegalStateException("SHA-256 is required by the JCA specification", missing);
    }
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
