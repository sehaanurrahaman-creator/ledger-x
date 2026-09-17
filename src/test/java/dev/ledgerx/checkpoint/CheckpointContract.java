package dev.ledgerx.checkpoint;

import dev.ledgerx.domain.AccountId;
import dev.ledgerx.domain.AccountKind;
import dev.ledgerx.domain.InMemoryLedger;
import dev.ledgerx.domain.Money;
import dev.ledgerx.journal.DurableLedger;
import dev.ledgerx.journal.Replay;
import dev.ledgerx.wal.FsyncPolicy;
import dev.ledgerx.wal.Wal;
import dev.ledgerx.wal.WalFormat;
import dev.ledgerx.wal.WalRecovery;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * The checkpoint contract: every claim ADR 0004 makes about the file, the swap and the refusals,
 * asserted on every build.
 *
 * <p>{@link dev.ledgerx.checkpoint.crash.LsnBoundaryHarness} asks whether a random kill can find a
 * case nobody thought of; this file asks the questions the ADR answers, at exact bytes, so that
 * "the state section is hashed and the log prefix is hashed and they are different questions" is a
 * line in a test report rather than a paragraph nobody re-reads. Twelve checks, all deterministic,
 * and the hostile inputs are edits of a real checkpoint's bytes rather than files assembled through
 * the writer — otherwise the writer would be testing itself.
 *
 * <p>The ticket's central equality gets its own check here in miniature and is swept at every
 * prefix of a random history by the boundary harness: <em>checkpoint + tail</em> must equal
 * <em>a replay from scratch</em>, which must equal the run's own hash. This file checks it three
 * ways at one watermark (the store's load, a fold by hand, and a recovery with every checkpoint
 * deleted) and the harness checks it at every watermark of a history that a crash can reach.
 *
 * <p>Run by {@code ./build.sh}; exits non-zero if any check fails. A failing check keeps its
 * directory and prints the path, so the bytes can be hexdumped rather than re-created.
 */
public final class CheckpointContract {

  /** The fsync policy here: every ack forced, so a crash cannot be blamed for a tear. */
  private static final FsyncPolicy POLICY = FsyncPolicy.PER_COMMIT;

  /** Kinds are metadata (ADR 0002 §4); cycling them keeps the state sections unequal in shape. */
  private static final AccountKind[] KINDS = AccountKind.values();

  private static final List<String> failures = new ArrayList<>();
  private static int checksRun;

  private CheckpointContract() {}

  /** A check returns a short detail string for the log, or throws to fail. */
  private interface Check {
    String run(Path dir) throws Exception;
  }

  public static void main(String[] args) {
    System.out.println("ledger-x checkpoint contract on " + Runtime.version());
    check("a checkpoint file is exactly these bytes", CheckpointContract::fileIsTheseBytes);
    check("a checkpoint round-trips byte for byte", CheckpointContract::roundTrips);
    check("every integrity layer names its own refusal", CheckpointContract::refusalsAreNamed);
    check(
        "the name, the header and the state agree on the watermark",
        CheckpointContract::nameIsChecked);
    check(
        "a checkpoint whose coverage the log has lost is refused",
        CheckpointContract::coverageGoneIsRefused);
    check(
        "a checkpoint from a different history is refused",
        CheckpointContract::walDigestBindsTheHistory);
    check(
        "a corrupt newest falls back to the older one",
        CheckpointContract::corruptNewestFallsBack);
    check(
        "retention keeps the newest and deletes the stale temp",
        CheckpointContract::retentionAndTemps);
    check("the swap's stages happen in the fixed order", CheckpointContract::stagesAreOrdered);
    check(
        "the state hash pins the canonical bytes, opening order included",
        CheckpointContract::hashPinsTheBytes);
    check("a restored ledger audits and can go on", CheckpointContract::restoredLedgerAudits);
    check(
        "checkpoint plus tail equals a replay from scratch",
        CheckpointContract::equalsFullReplay);

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
      dir = Files.createTempDirectory("ledger-x-checkpoint");
      String detail = body.run(dir);
      System.out.println("  ok   " + name + " [" + detail + "]");
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

  // --- the file, byte for byte -----------------------------------------------------------

  private static String fileIsTheseBytes(Path dir) throws Exception {
    Checkpoint checkpoint;
    Path file;
    long ceiling;
    try (DurableLedger ledger = open(dir, CheckpointPolicy.MANUAL)) {
      openTwoAccounts(ledger);
      ledger.transfer(AccountId.of("acct-0"), AccountId.of("acct-1"), Money.ofMinor(5_000L));
      checkpoint = ledger.checkpoint();
      file = checkpointFile(dir);
      ceiling = ledger.lastJournalEnd();
    }
    byte[] bytes = Files.readAllBytes(file);
    byte[] section = checkpoint.state().canonicalBytes();
    require(
        bytes.length == CheckpointFormat.FILE_OVERHEAD_BYTES + section.length,
        "the file is " + bytes.length + " bytes and the layout says "
            + (CheckpointFormat.FILE_OVERHEAD_BYTES + section.length));
    require(
        WalFormat.intAt(bytes, 0) == CheckpointFormat.MAGIC,
        "the file does not start with LCKP");
    require(
        (bytes[4] & 0xFF) == CheckpointFormat.FORMAT_VERSION && (bytes[5] & 0xFF) == 0,
        "version or flags are not what version 1 promises");
    require(
        WalFormat.shortAt(bytes, 6) == CheckpointFormat.HEADER_BYTES,
        "the header does not declare its own size");
    require(
        WalFormat.intAt(bytes, 8) == 0 && WalFormat.intAt(bytes, 12) == 0,
        "a reserved header word is not zero");
    require(
        WalFormat.longAt(bytes, 16) == checkpoint.lastLsn(),
        "the header's watermark is not the state's");
    require(
        WalFormat.longAt(bytes, 24) == checkpoint.coveredBytes(),
        "the header's coverage is not the checkpoint's");
    require(
        WalFormat.intAt(bytes, 32) == section.length,
        "the header's state length is not the section's");
    require(
        WalFormat.intAt(bytes, 36) == 0,
        "the state section's reserved word is not zero");
    require(
        Arrays.equals(Arrays.copyOfRange(bytes, 40, 72), checkpoint.walDigestUnsafe()),
        "the header's log digest is not the checkpoint's");
    require(
        Arrays.equals(
            Arrays.copyOfRange(bytes, CheckpointFormat.HEADER_BYTES, bytes.length - 36), section),
        "the state section is not the canonical bytes");
    require(
        checkpoint.coveredBytes() == ceiling,
        "coverage " + checkpoint.coveredBytes() + " is not the end of frame "
            + checkpoint.lastLsn(),
        ceiling);
    require(
        Sha256.same(
            Arrays.copyOfRange(bytes, bytes.length - 36, bytes.length - 4),
            checkpoint.state().stateHashBytes()),
        "the trailer's state hash is not the state's");
    require(
        WalFormat.crc32c(bytes, bytes.length - 4) == WalFormat.intAt(bytes, bytes.length - 4),
        "the trailer's crc does not cover everything before it");
    require(
        file.getFileName().toString().equals(CheckpointFormat.nameFor(checkpoint.lastLsn())),
        "the file is not named for its watermark");
    return "72 + " + section.length + " + 36 bytes, named " + file.getFileName();
  }

  private static String roundTrips(Path dir) throws Exception {
    Checkpoint checkpoint;
    byte[] file;
    try (DurableLedger ledger = open(dir, CheckpointPolicy.MANUAL)) {
      openTwoAccounts(ledger);
      ledger.transfer(AccountId.of("acct-0"), AccountId.of("acct-1"), Money.ofMinor(5_000L));
      checkpoint = ledger.checkpoint();
      file = Files.readAllBytes(checkpointFile(dir));
    }
    Checkpoint decoded = Checkpoint.decode(file);
    require(decoded.sameAs(checkpoint), "decode(encode(c)) is not c");
    require(Arrays.equals(decoded.encode(), file), "re-encoding the file changed its bytes");
    require(
        decoded.stateHash().equals(checkpoint.stateHash()),
        "the decoded state hashes differently");
    return file.length + " bytes, " + decoded.state().accountCount() + " accounts, watermark "
        + decoded.lastLsn();
  }

  private static String refusalsAreNamed(Path dir) throws Exception {
    byte[] base;
    try (DurableLedger ledger = open(dir, CheckpointPolicy.MANUAL)) {
      openTwoAccounts(ledger);
      ledger.transfer(AccountId.of("acct-0"), AccountId.of("acct-1"), Money.ofMinor(5_000L));
      ledger.checkpoint();
    }
    base = Files.readAllBytes(checkpointFile(dir));
    List<String> named = new ArrayList<>();
    named.add(expectRefusal(flip(base, 0), CheckpointRefusal.BAD_MAGIC));
    named.add(expectRefusal(withByte(base, 4, (byte) 2), CheckpointRefusal.BAD_HEADER));
    named.add(
        expectRefusal(
            Arrays.copyOf(base, base.length - 1), CheckpointRefusal.LENGTH_MISMATCH));
    named.add(
        expectRefusal(
            flip(base, CheckpointFormat.HEADER_BYTES + 2), CheckpointRefusal.CRC_MISMATCH));
    byte[] digestEdited = flip(base, base.length - WalFormat.CRC_BYTES - 1);
    recomputeCrc(digestEdited);
    named.add(expectRefusal(digestEdited, CheckpointRefusal.STATE_DIGEST_MISMATCH));
    byte[] versionEdited = withByte(base, CheckpointFormat.HEADER_BYTES, (byte) 2);
    rehash(versionEdited);
    named.add(expectRefusal(versionEdited, CheckpointRefusal.STATE_MALFORMED));
    byte[] countEdited = withInt(base, CheckpointFormat.HEADER_BYTES + 9, -1);
    rehash(countEdited);
    named.add(expectRefusal(countEdited, CheckpointRefusal.STATE_MALFORMED));
    return named.size() + " edits, each refused by name: " + String.join(", ", named);
  }

  // --- the bindings ---------------------------------------------------------------------

  private static String nameIsChecked(Path dir) throws Exception {
    String expected;
    long lsn;
    try (DurableLedger ledger = open(dir, CheckpointPolicy.MANUAL)) {
      openTwoAccounts(ledger);
      ledger.transfer(AccountId.of("acct-0"), AccountId.of("acct-1"), Money.ofMinor(5_000L));
      expected = ledger.stateHash();
      ledger.checkpoint();
      lsn = ledger.lastJournalLsn();
    }
    Path file = checkpointFile(dir);
    Files.move(file, file.resolveSibling(CheckpointFormat.nameFor(lsn + 1)));
    try (DurableLedger ledger = open(dir, CheckpointPolicy.MANUAL)) {
      require(ledger.checkpointLoad().refusals() == 1, "the renamed file was not refused");
      require(
          ledger.checkpointLoad().refused().get(0).why() == CheckpointRefusal.NAME_MISMATCH,
          "the renamed file was refused as " + ledger.checkpointLoad().refused().get(0).why());
      require(!ledger.checkpointLoad().used(), "a renamed file was used anyway");
      require(
          ledger.stateHash().equals(expected),
          "refusing the checkpoint lost state instead of folding the log");
    }
    return "a file named for lsn " + (lsn + 1) + " whose header says " + lsn + " was refused";
  }

  private static String coverageGoneIsRefused(Path dir) throws Exception {
    try (DurableLedger ledger = open(dir, CheckpointPolicy.MANUAL)) {
      openTwoAccounts(ledger);
      ledger.transfer(AccountId.of("acct-0"), AccountId.of("acct-1"), Money.ofMinor(5_000L));
      ledger.checkpoint();
    }
    Path walFile = dir.resolve(Wal.FILE_NAME);
    WalRecovery.truncateTo(walFile, WalFormat.SEGMENT_HEADER_BYTES);
    WalRecovery.Scan scan = WalRecovery.scan(walFile);
    CheckpointStore.Load load = new CheckpointStore(dir).load(walFile, scan);
    require(!load.used(), "a checkpoint whose coverage is gone was used");
    require(load.refusals() == 1, "expected one refusal, saw " + load.refusals());
    require(
        load.refused().get(0).why() == CheckpointRefusal.COVERAGE_GONE,
        "the refusal was " + load.refused().get(0).why());
    return "the log cut back to its " + WalFormat.SEGMENT_HEADER_BYTES
        + "-byte header, the checkpoint refused as " + load.refused().get(0).why();
  }

  private static String walDigestBindsTheHistory(Path dir) throws Exception {
    Path left = dir.resolve("left");
    Path right = dir.resolve("right");
    Checkpoint taken;
    Checkpoint other;
    try (DurableLedger ledger = open(left, CheckpointPolicy.MANUAL)) {
      openTwoAccounts(ledger);
      ledger.transfer(AccountId.of("acct-0"), AccountId.of("acct-1"), Money.ofMinor(5_000L));
      taken = ledger.checkpoint();
    }
    try (DurableLedger ledger = open(right, CheckpointPolicy.MANUAL)) {
      openTwoAccounts(ledger);
      ledger.transfer(AccountId.of("acct-0"), AccountId.of("acct-1"), Money.ofMinor(6_000L));
      other = ledger.checkpoint();
    }
    require(
        taken.lastLsn() == other.lastLsn(),
        "the two histories must end at the same lsn to make this point");
    require(
        taken.coveredBytes() == other.coveredBytes(),
        "the two histories must cover the same bytes to make this point");
    require(
        !Sha256.same(taken.walDigestUnsafe(), other.walDigestUnsafe()),
        "two different histories produced the same log digest");
    Files.copy(
        checkpointFile(left),
        checkpointFile(right),
        StandardCopyOption.REPLACE_EXISTING);
    Path walFile = right.resolve(Wal.FILE_NAME);
    WalRecovery.Scan scan = WalRecovery.scan(walFile);
    CheckpointStore.Load load = new CheckpointStore(right).load(walFile, scan);
    require(!load.used(), "the other history's checkpoint was used");
    require(load.refusals() == 1, "expected one refusal, saw " + load.refusals());
    require(
        load.refused().get(0).why() == CheckpointRefusal.WAL_DIGEST_MISMATCH,
        "the refusal was " + load.refused().get(0).why());
    return "two histories at lsn " + taken.lastLsn() + " covering " + taken.coveredBytes()
        + " bytes, told apart only by the log digest";
  }

  private static String corruptNewestFallsBack(Path dir) throws Exception {
    String expected;
    try (DurableLedger ledger = open(dir, CheckpointPolicy.MANUAL)) {
      openTwoAccounts(ledger);
      ledger.checkpoint();
      ledger.transfer(AccountId.of("acct-0"), AccountId.of("acct-1"), Money.ofMinor(5_000L));
      ledger.checkpoint();
      expected = ledger.stateHash();
    }
    CheckpointStore store = new CheckpointStore(dir);
    List<Path> files = store.newestFirst();
    require(files.size() == 2, "expected two checkpoints, saw " + files.size());
    long newest = CheckpointFormat.watermarkOf(files.get(0).getFileName().toString());
    byte[] bytes = Files.readAllBytes(files.get(0));
    Files.write(files.get(0), flip(bytes, CheckpointFormat.HEADER_BYTES + 1));
    try (DurableLedger ledger = open(dir, CheckpointPolicy.MANUAL)) {
      require(ledger.checkpointLoad().used(), "the older checkpoint was not used");
      require(ledger.checkpointLoad().refusals() == 1, "expected one refusal, saw "
          + ledger.checkpointLoad().refusals());
      require(
          ledger.checkpointLoad().refused().get(0).why() == CheckpointRefusal.CRC_MISMATCH,
          "the refusal was " + ledger.checkpointLoad().refused().get(0).why());
      require(
          CheckpointFormat.watermarkOf(ledger.checkpointLoad().file().getFileName().toString())
              == newest - 1,
          "the file used is not the one below the corrupt checkpoint");
      require(
          ledger.replayedRecords() == 1,
          "the tail after the older checkpoint was " + ledger.replayedRecords() + " records");
      require(
          ledger.stateHash().equals(expected),
          "checkpoint + one tail record did not equal the run");
    }
    return "the lsn " + newest + " file corrupted, the lsn " + (newest - 1)
        + " file used, " + "1 record folded";
  }

  // --- the swap and retention -----------------------------------------------------------

  private static String retentionAndTemps(Path dir) throws Exception {
    CheckpointStore store = new CheckpointStore(dir);
    try (DurableLedger ledger = open(dir, new CheckpointPolicy(1, 2))) {
      openTwoAccounts(ledger);
      ledger.transfer(AccountId.of("acct-0"), AccountId.of("acct-1"), Money.ofMinor(5_000L));
      ledger.transfer(AccountId.of("acct-1"), AccountId.of("acct-0"), Money.ofMinor(1_000L));
      Path stale =
          store.directory().resolve(CheckpointFormat.nameFor(99L) + CheckpointFormat.TEMP_SUFFIX);
      Files.writeString(stale, "half a swap, abandoned");
      ledger.transfer(AccountId.of("acct-0"), AccountId.of("acct-1"), Money.ofMinor(2_000L));
      List<Path> files = store.newestFirst();
      require(files.size() == 2, "retention kept " + files.size() + " files, not two");
      require(
          CheckpointFormat.watermarkOf(files.get(0).getFileName().toString())
              == ledger.lastJournalLsn(),
          "the newest file is not the newest checkpoint");
      require(
          CheckpointFormat.watermarkOf(files.get(1).getFileName().toString())
              == ledger.lastJournalLsn() - 1,
          "the file below it is not the one before");
      require(store.staleTemps().isEmpty(), "the stale temp survived the next swap");
      require(
          ledger.checkpointsWritten() == 5,
          "expected five automatic checkpoints, wrote " + ledger.checkpointsWritten());
      return "five checkpoints in, two files out, the stale temp gone";
    }
  }

  private static String stagesAreOrdered(Path dir) throws Exception {
    List<CheckpointStage> seen = new ArrayList<>();
    try (DurableLedger ledger = open(dir, CheckpointPolicy.MANUAL)) {
      openTwoAccounts(ledger);
      ledger.onCheckpointStage(seen::add);
      ledger.checkpoint();
    }
    List<CheckpointStage> expected = List.of(CheckpointStage.values());
    require(seen.equals(expected), "the swap reported " + seen + ", not " + expected);
    return seen.size() + " stages, in the order the enum declares";
  }

  // --- the state hash -------------------------------------------------------------------

  private static String hashPinsTheBytes(Path dir) throws Exception {
    String first = twoAccountHistory(dir.resolve("first"), false);
    String second = twoAccountHistory(dir.resolve("second"), false);
    String reversed = twoAccountHistory(dir.resolve("reversed"), true);
    require(first.equals(second), "two identical histories hashed differently");
    require(
        !first.equals(reversed),
        "the same balances opened in the other order hashed the same");
    return "identical histories agree on " + heads(first) + ", the reversed opening order differs";
  }

  private static String twoAccountHistory(Path dir, boolean reversedOrder) throws IOException {
    try (DurableLedger ledger = open(dir, CheckpointPolicy.MANUAL)) {
      if (reversedOrder) {
        ledger.openAccount(AccountId.of("acct-1"), AccountKind.LIABILITY);
        ledger.openAccount(AccountId.of("acct-0"), AccountKind.ASSET);
      } else {
        openTwoAccounts(ledger);
      }
      ledger.transfer(AccountId.of("acct-0"), AccountId.of("acct-1"), Money.ofMinor(5_000L));
      return ledger.stateHash();
    }
  }

  private static String restoredLedgerAudits(Path dir) throws Exception {
    InMemoryLedger live = new InMemoryLedger();
    live.openAccount(AccountId.of("acct-0"), AccountKind.ASSET);
    live.openAccount(AccountId.of("acct-1"), AccountKind.LIABILITY);
    live.transfer(AccountId.of("acct-0"), AccountId.of("acct-1"), Money.ofMinor(5_000L));
    LedgerState state = LedgerState.of(live, 3L);
    InMemoryLedger restored = state.restore();
    restored.audit();
    require(restored.accounts().equals(live.accounts()), "the accounts or their order changed");
    require(restored.restoredAccounts() == 2, "restored " + restored.restoredAccounts()
        + " accounts, not two");
    for (AccountId id : live.balances().keySet()) {
      require(
          restored.balanceOf(id).equals(live.balanceOf(id)),
          "the balance of " + id + " changed on the way through a checkpoint");
    }
    restored.transfer(AccountId.of("acct-1"), AccountId.of("acct-0"), Money.ofMinor(1_000L));
    restored.audit();
    return "two accounts restored at lsn 3, audited, and posted into";
  }

  private static String equalsFullReplay(Path dir) throws Exception {
    String expected;
    try (DurableLedger ledger = open(dir, new CheckpointPolicy(3, 2))) {
      buildHistory(ledger);
      expected = ledger.stateHash();
    }
    require(
        new CheckpointStore(dir).newestFirst().size() == 2,
        "retention did not keep two files");
    try (DurableLedger ledger = open(dir, new CheckpointPolicy(3, 2))) {
      require(ledger.checkpointLoad().used(), "the reopened ledger ignored its checkpoint");
      require(
          ledger.stateHash().equals(expected),
          "checkpoint + tail gave " + heads(ledger.stateHash()) + ", not " + heads(expected));
    }
    WalRecovery.Scan scan = WalRecovery.scan(dir.resolve(Wal.FILE_NAME));
    InMemoryLedger folded = Replay.fold(scan);
    String fromScratch = LedgerState.of(folded, Replay.lastJournalLsn(scan)).stateHash();
    require(
        fromScratch.equals(expected),
        "a fold from byte 0 gave " + heads(fromScratch) + ", not " + heads(expected));
    CheckpointStore store = new CheckpointStore(dir);
    for (Path file : store.newestFirst()) {
      Files.deleteIfExists(file);
    }
    try (DurableLedger ledger = open(dir, CheckpointPolicy.MANUAL)) {
      require(!ledger.checkpointLoad().used(), "a deleted checkpoint was used");
      require(
          ledger.stateHash().equals(expected),
          "recovery with no checkpoints gave " + heads(ledger.stateHash()));
    }
    return "checkpoint + tail, a fold by hand and a recovery with none all give " + heads(expected);
  }

  private static void buildHistory(DurableLedger ledger) throws IOException {
    for (int i = 0; i < 20; i++) {
      ledger.openAccount(AccountId.of("acct-" + i), KINDS[i % KINDS.length]);
    }
    for (int i = 0; i < 12; i++) {
      ledger.transfer(
          AccountId.of("acct-" + (i % 19)),
          AccountId.of("acct-" + (i + 1)),
          Money.ofMinor(100L + i));
    }
  }

  // --- plumbing -------------------------------------------------------------------------

  private static DurableLedger open(Path dir, CheckpointPolicy checkpoints) throws IOException {
    return DurableLedger.open(dir, POLICY, true, checkpoints);
  }

  private static void openTwoAccounts(DurableLedger ledger) throws IOException {
    ledger.openAccount(AccountId.of("acct-0"), AccountKind.ASSET);
    ledger.openAccount(AccountId.of("acct-1"), AccountKind.LIABILITY);
  }

  private static Path checkpointFile(Path dir) throws IOException {
    return new CheckpointStore(dir).newestFirst().get(0);
  }

  private static String heads(String hash) {
    return hash.substring(0, 12) + "...";
  }

  private static void require(boolean claim, String complaint) {
    if (!claim) {
      throw new AssertionError(complaint);
    }
  }

  private static void require(boolean claim, String complaint, long actual) {
    require(claim, complaint + " (is " + actual + ")");
  }

  /** The name of the refusal a decode of this file produces, or a failure if it is accepted. */
  private static String expectRefusal(byte[] file, CheckpointRefusal expected) throws IOException {
    try {
      Checkpoint.decode(file);
    } catch (CorruptCheckpointException refused) {
      if (refused.refusal() != expected) {
        throw new AssertionError(
            "refused as " + refused.refusal() + ", expected " + expected + " — " + refused);
      }
      return refused.refusal().name();
    }
    throw new AssertionError("a file that should have been " + expected + " was accepted");
  }

  private static byte[] flip(byte[] file, int at) {
    byte[] copy = file.clone();
    copy[at] ^= 0x01;
    return copy;
  }

  private static byte[] withByte(byte[] file, int at, byte value) {
    byte[] copy = file.clone();
    copy[at] = value;
    return copy;
  }

  private static byte[] withInt(byte[] file, int at, int value) {
    byte[] copy = file.clone();
    WalFormat.putInt(copy, at, value);
    return copy;
  }

  /** Recomputes the trailer's CRC, so a later integrity layer is the one that has to refuse. */
  private static void recomputeCrc(byte[] file) {
    WalFormat.putInt(
        file, file.length - WalFormat.CRC_BYTES, WalFormat.crc32c(file, file.length - 4));
  }

  /** Recomputes the state hash and then the CRC, leaving the bytes self-consistent but wrong. */
  private static void rehash(byte[] file) {
    int stateBytes = WalFormat.intAt(file, 32);
    int at = CheckpointFormat.HEADER_BYTES;
    byte[] digest = Sha256.of(Arrays.copyOfRange(file, at, at + stateBytes));
    System.arraycopy(digest, 0, file, at + stateBytes, CheckpointFormat.DIGEST_BYTES);
    recomputeCrc(file);
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
