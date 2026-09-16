package dev.ledgerx.wal;

import dev.ledgerx.domain.Account;
import dev.ledgerx.domain.AccountId;
import dev.ledgerx.domain.AccountKind;
import dev.ledgerx.domain.Entry;
import dev.ledgerx.domain.JournalDigest;
import dev.ledgerx.domain.JournalEvent;
import dev.ledgerx.domain.Money;
import dev.ledgerx.domain.RejectedTransactionException;
import dev.ledgerx.domain.Side;
import dev.ledgerx.domain.Transaction;
import dev.ledgerx.journal.DurableLedger;
import dev.ledgerx.journal.EventCodec;
import dev.ledgerx.testing.RandomSource;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The durable-write contract: every claim ADR 0003 makes about the record format, the fsync policy
 * menu and the torn-tail rules, asserted on every build.
 *
 * <p>This is not the crash harness. {@link dev.ledgerx.wal.crash.CrashHarness} asks whether a
 * random
 * kill can find a case nobody thought of; this file asks the questions the ADR answers, at
 * exact byte
 * offsets, so that "a scribble inside a payload truncates and a scribble inside the header refuses
 * to open" is a line in a test report rather than a paragraph nobody re-reads. Nineteen checks, all
 * deterministic, and the hostile inputs are built from raw frames rather than through
 * {@link Wal} —
 * otherwise a format bug would be tested by the code that has it.
 *
 * <p>Run by {@code ./build.sh}; exits non-zero if any check fails. A failing check keeps its
 * directory and prints the path, so the log can be hexdumped rather than re-created.
 */
public final class WalContract {

  /** Fixture payloads are 4 bytes, so every fixture frame is exactly this wide. */
  private static final int FIXTURE_FRAME = WalFormat.FRAME_OVERHEAD_BYTES + 4;

  private static final List<String> failures = new ArrayList<>();
  private static int checksRun;

  private WalContract() {}

  /** A check returns a short detail string for the log, or throws to fail. */
  private interface Check {
    String run(Path dir) throws Exception;
  }

  public static void main(String[] args) {
    System.out.println("ledger-x durable-write contract on " + Runtime.version());
    check("a record frame is exactly these bytes", WalContract::frameIsTheseBytes);
    check("a segment header is exactly these bytes", WalContract::headerIsTheseBytes);
    check("the crc covers length, lsn, type and payload", WalContract::crcCoversEverything);
    check(
        "recovery understands every byte offset of a truncated log",
        WalContract::everyByteOffsetIsUnderstood);
    check("a torn tail is cut and recorded in the log", WalContract::tearIsCutAndRecorded);
    check("a damaged head is refused, never cut", WalContract::headDamageRefusesToOpen);
    check("an unknown record type refuses to open", WalContract::unknownTypeRefusesToOpen);
    check("an lsn hole is a tear, not a gap to step over", WalContract::lsnHoleTruncates);
    check("cutting, not skipping, is what stops resurrection", WalContract::noResurrection);
    check("per-commit fsync forces once per record", WalContract::perCommitForcesOnceEach);
    check("group commit forces once per group", WalContract::groupCommitCoalesces);
    check("a lone writer gets no batching at all", WalContract::noBatchingWithoutContention);
    check("no fsync never forces, and says so on the ack", WalContract::noFsyncNeverForces);
    check(
        "the ack rule holds: forced acks sit under the watermark",
        WalContract::acksUnderWatermark);
    check("replay is deterministic and byte-identical", WalContract::replayIsDeterministic);
    check("the codec round-trips and never collides", WalContract::codecIsInjective);
    check("a rejected transaction never reaches the log", WalContract::rejectionLeavesNoTrace);
    check("an intact posting the ledger refuses is fatal", WalContract::invalidPostingIsFatal);
    check("the payload ceiling is enforced at the call", WalContract::payloadCeiling);

    System.out.println();
    if (failures.isEmpty()) {
      System.out.println("PASS " + checksRun + "/" + checksRun + " wal checks");
      return;
    }
    System.out.println("FAIL " + failures.size() + " of " + checksRun + " wal checks");
    for (String failure : failures) {
      System.out.println("  - " + failure);
    }
    System.exit(1);
  }

  private static void check(String name, Check body) {
    checksRun++;
    Path dir = null;
    try {
      dir = Files.createTempDirectory("ledger-x-wal");
      String detail = body.run(dir);
      System.out.println("  ok   " + name + " [" + detail + "]");
      // Buffered stdout would hide which check is slow, and a hung check is the interesting case.
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

  // --- the format, byte for byte ---------------------------------------------------------

  private static String frameIsTheseBytes(Path dir) {
    byte[] payload = {0x00, 0x01, 0x02};
    WalRecord record = new WalRecord(Lsn.of(1L), RecordType.POSTED, payload);
    byte[] frame = record.encode();
    if (frame.length != WalFormat.FRAME_OVERHEAD_BYTES + 3) {
      throw new AssertionError("frame is " + frame.length + " bytes, not 20 plus the payload");
    }
    byte[] expected = new byte[23];
    WalFormat.putInt(expected, 0, 23);
    WalFormat.putLong(expected, 4, 1L);
    expected[12] = (byte) RecordType.POSTED.code();
    WalFormat.putShort(expected, 14, 3);
    System.arraycopy(payload, 0, expected, 16, 3);
    WalFormat.putInt(expected, 19, WalFormat.crc32c(expected, 19));
    if (!Arrays.equals(expected, frame)) {
      throw new AssertionError(
          "the frame is not the layout ADR 0003 §2 documents: " + hex(frame) + " vs "
              + hex(expected));
    }
    WalRecord.FrameCheck back = WalRecord.inspect(frame, frame.length, Lsn.of(1L));
    if (!(back instanceof WalRecord.ValidFrame valid) || !valid.record().equals(record)) {
      throw new AssertionError("a frame this file just wrote does not read back: " + back);
    }
    return "23 bytes framing a 3-byte payload: " + hex(frame);
  }

  private static String headerIsTheseBytes(Path dir) throws IOException {
    byte[] header = WalFormat.encodeSegmentHeader();
    if (header.length != WalFormat.SEGMENT_HEADER_BYTES) {
      throw new AssertionError("the header is " + header.length + " bytes");
    }
    if (WalFormat.intAt(header, 0) != WalFormat.SEGMENT_MAGIC) {
      throw new AssertionError("the magic is not \"LWAL\"");
    }
    byte[] oneRecord = new WalRecord(Lsn.of(1L), RecordType.ACCOUNT_OPENED, opened("a", "ASSET"))
        .encode();
    if (WalFormat.intAt(oneRecord, 0) != 23) {
      throw new AssertionError("an account opening is not 23 bytes: " + hex(oneRecord));
    }
    Path file = dir.resolve(Wal.FILE_NAME);
    try (Wal wal = Wal.open(dir, FsyncPolicy.PER_COMMIT)) {
      wal.appendSync(
          RecordType.ACCOUNT_OPENED, EventCodec.encode(open("a", AccountKind.ASSET)));
    }
    byte[] onDisk = Files.readAllBytes(file);
    if (onDisk.length != WalFormat.SEGMENT_HEADER_BYTES + 23) {
      throw new AssertionError(
          "a fresh log holding one record is " + onDisk.length + " bytes, expected "
              + (WalFormat.SEGMENT_HEADER_BYTES + 23));
    }
    for (int i = 0; i < WalFormat.SEGMENT_HEADER_BYTES; i++) {
      if (onDisk[i] != header[i]) {
        throw new AssertionError("byte " + i + " on disk is not what the encoder wrote");
      }
    }
    return "16-byte header, crc32c 0x" + Integer.toHexString(WalFormat.intAt(header, 12))
        + ", then a 23-byte frame";
  }

  private static String crcCoversEverything(Path dir) throws IOException {
    Path file = dir.resolve(Wal.FILE_NAME);
    int records = 4;
    try (Wal wal = Wal.open(dir, FsyncPolicy.PER_COMMIT)) {
      for (int i = 0; i < records; i++) {
        wal.appendSync(RecordType.POSTED, new byte[] {(byte) i, 1, 2, 3});
      }
    }
    byte[] intact = Files.readAllBytes(file);
    int flips = 0;
    for (int frame = 0; frame < records; frame++) {
      int start = WalFormat.SEGMENT_HEADER_BYTES + frame * FIXTURE_FRAME;
      for (int[] field : new int[][] {{0, 4}, {4, 8}, {12, 1}, {16, 4}}) {
        for (int at = start + field[0]; at < start + field[0] + field[1]; at++) {
          byte[] damaged = intact.clone();
          damaged[at] ^= 0x01;
          Files.write(file, damaged);
          int kept = WalRecovery.scan(file).report().frames();
          if (kept != frame) {
            throw new AssertionError(
                "one bit flipped at byte " + at + " left " + kept + " records intact; only the "
                    + frame + " before it should have survived");
          }
          flips++;
        }
      }
    }
    Files.write(file, intact);
    return flips
        + " single-bit flips across length, lsn, type and payload: each truncated at exactly"
        + " its own"
        + " record";
  }

  // --- the torn-tail rules ---------------------------------------------------------------

  private static String everyByteOffsetIsUnderstood(Path dir) throws IOException {
    Path file = dir.resolve(Wal.FILE_NAME);
    int records = 4;
    try (Wal wal = Wal.open(dir, FsyncPolicy.PER_COMMIT)) {
      for (int i = 0; i < records; i++) {
        wal.appendSync(RecordType.POSTED, new byte[] {(byte) i, 0, 0, 1});
      }
    }
    byte[] intact = Files.readAllBytes(file);
    int offsets = 0;
    for (long cut = 0; cut <= intact.length; cut++) {
      Files.write(file, Arrays.copyOf(intact, (int) cut));
      if (cut > 0 && cut < WalFormat.SEGMENT_HEADER_BYTES) {
        if (scanRefuses(file).corruption() != Corruption.BAD_SEGMENT_HEADER) {
          throw new AssertionError("a " + cut + "-byte file was not refused as head damage");
        }
        offsets++;
        continue;
      }
      WalRecovery.Scan scan = WalRecovery.scan(file);
      int complete =
          (int)
              Math.max(
                  0L,
                  (cut - WalFormat.SEGMENT_HEADER_BYTES) / FIXTURE_FRAME);
      if (scan.report().frames() != complete) {
        throw new AssertionError(
            "truncated to " + cut + " bytes: recovery kept " + scan.report().frames()
                + " records and " + complete + " were whole");
      }
      long cleanEnd = WalFormat.SEGMENT_HEADER_BYTES + (long) complete * FIXTURE_FRAME;
      if (scan.report().cleanBytes() != Math.min(cut, cleanEnd)) {
        throw new AssertionError(
            "at " + cut + " bytes the clean prefix ends at " + scan.report().cleanBytes()
                + ", expected " + Math.min(cut, cleanEnd));
      }
      offsets++;
    }
    Files.write(file, intact);
    return offsets
        + " truncate points from 0 to EOF, and the clean prefix always ends on a whole frame";
  }

  private static String tearIsCutAndRecorded(Path dir) throws IOException {
    Path file = dir.resolve(Wal.FILE_NAME);
    try (Wal wal = Wal.open(dir, FsyncPolicy.PER_COMMIT)) {
      for (int i = 0; i < 5; i++) {
        wal.appendSync(RecordType.POSTED, new byte[] {(byte) i, 0, 0, 1});
      }
    }
    byte[] intact = Files.readAllBytes(file);
    long surviving = WalFormat.SEGMENT_HEADER_BYTES + 3L * FIXTURE_FRAME;
    long cutAt = surviving + 11;
    Files.write(file, Arrays.copyOf(intact, (int) cutAt));
    WalRecovery.Scan before = WalRecovery.scan(file);
    if (!before.report().torn() || before.report().frames() != 3) {
      throw new AssertionError("expected 3 records and a tear, got " + before.report());
    }
    // What the marker reports is the cut recovery made: the bytes still in the file past the last
    // whole frame. The rest of the crash's damage (37 of these 48 bytes) was gone before recovery
    // looked, and a log cannot report bytes it never saw.
    long dropped = cutAt - surviving;
    try (Wal reopened = Wal.open(dir, FsyncPolicy.PER_COMMIT)) {
      if (!reopened.recovery().torn()) {
        throw new AssertionError("the WAL lost the fact that it cut something");
      }
      List<WalRecord> records = WalRecovery.scan(file).records();
      WalRecord marker = records.get(records.size() - 1);
      if (marker.type() != RecordType.RECOVERY_MARKER) {
        throw new AssertionError("the last frame is " + marker.type() + ", not a recovery marker");
      }
      byte[] payload = marker.payloadUnsafe();
      if (WalRecord.markerBytesDropped(payload) != dropped) {
        throw new AssertionError(
            "the marker says " + WalRecord.markerBytesDropped(payload) + " bytes were cut and "
                + dropped + " were past the last whole frame");
      }
      if (WalRecord.markerFramesKept(payload) != 3) {
        throw new AssertionError("the marker miscounts the records it kept");
      }
      if (WalRecord.markerLastLsn(payload).value() != 3L) {
        throw new AssertionError("the marker names the wrong last lsn");
      }
      if (WalRecord.markerReason(payload) != before.report().tail()) {
        throw new AssertionError(
            "the marker blames " + WalRecord.markerReason(payload) + " and the scan saw "
                + before.report().tail());
      }
      reopened.appendSync(RecordType.POSTED, new byte[] {9, 0, 0, 1});
    }
    WalRecovery.Scan after = WalRecovery.scan(file);
    if (after.report().tail().isTear()) {
      throw new AssertionError("after repair the log still reads as " + after.report().tail());
    }
    if (Files.size(file) <= surviving) {
      throw new AssertionError("the repaired file did not grow past the cut");
    }
    return "torn at " + cutAt + ", cut to " + surviving + "; the marker names the reason ("
        + before.report().tail() + "), the " + dropped + " torn bytes it cut and the 3 kept";
  }

  private static String headDamageRefusesToOpen(Path dir) throws IOException {
    Path file = dir.resolve(Wal.FILE_NAME);
    try (Wal wal = Wal.open(dir, FsyncPolicy.PER_COMMIT)) {
      wal.appendSync(RecordType.POSTED, new byte[] {1, 2, 3, 4});
    }
    long size = Files.size(file);
    byte[] bytes = Files.readAllBytes(file);
    bytes[4] ^= 0x40;
    Files.write(file, bytes);
    if (scanRefuses(file).corruption() != Corruption.BAD_SEGMENT_HEADER) {
      throw new AssertionError("header damage was not read as a bad segment header");
    }
    if (Files.size(file) != size) {
      throw new AssertionError("a refused open touched the file");
    }
    // A file shorter than its header is the same finding. There is nothing to truncate down to:
    // without a header there is no record boundary to trust, so head damage is never a torn tail.
    Files.write(file, Arrays.copyOf(bytes, 6));
    if (scanRefuses(file).corruption() != Corruption.BAD_SEGMENT_HEADER) {
      throw new AssertionError("a 6-byte file was not refused as head damage");
    }
    return "a scribbled version byte and a 6-byte file both refuse to open, file untouched";
  }

  private static String unknownTypeRefusesToOpen(Path dir) throws IOException {
    Path file = dir.resolve(Wal.FILE_NAME);
    byte[] frame = rawFrame(1L, 0x7F, new byte[] {1, 2, 3, 4});
    Files.write(
        file,
        concat(WalFormat.encodeSegmentHeader(), frame),
        StandardOpenOption.CREATE,
        StandardOpenOption.TRUNCATE_EXISTING);
    if (scanRefuses(file).corruption() != Corruption.UNKNOWN_RECORD_TYPE) {
      throw new AssertionError("a complete frame with an unmapped type was not refused");
    }
    if (Files.size(file) != WalFormat.SEGMENT_HEADER_BYTES + frame.length) {
      throw new AssertionError("a refused open touched the file");
    }
    // Type 0 — what a zeroed byte reads as — is refused too, rather than skipped. That is the
    // property that makes adding a record type a format-version bump instead of a silent skew.
    byte[] zeroType = rawFrame(1L, RecordType.UNKNOWN_CODE, new byte[] {1, 2, 3, 4});
    Files.write(
        file,
        concat(WalFormat.encodeSegmentHeader(), zeroType),
        StandardOpenOption.TRUNCATE_EXISTING);
    if (scanRefuses(file).corruption() != Corruption.UNKNOWN_RECORD_TYPE) {
      throw new AssertionError("type 0 was not refused as an unknown type");
    }
    return "0x7F and 0x00 both stop the scan, and neither is truncated away";
  }

  private static String lsnHoleTruncates(Path dir) throws IOException {
    Path file = dir.resolve(Wal.FILE_NAME);
    int frameSize = WalFormat.FRAME_OVERHEAD_BYTES + 1;
    byte[] frames =
        concat(
            WalFormat.encodeSegmentHeader(),
            rawFrame(1L, RecordType.POSTED.code(), new byte[] {1}),
            rawFrame(2L, RecordType.POSTED.code(), new byte[] {2}),
            rawFrame(4L, RecordType.POSTED.code(), new byte[] {3}));
    Files.write(file, frames, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    WalRecovery.Scan scan = WalRecovery.scan(file);
    if (scan.report().frames() != 2 || scan.report().tail() != Tail.LSN_MISMATCH) {
      throw new AssertionError(
          "a gap kept " + scan.report().frames() + " records and read as " + scan.report().tail());
    }
    long owed = WalFormat.SEGMENT_HEADER_BYTES + 2L * frameSize;
    if (scan.report().cleanBytes() != owed) {
      throw new AssertionError(
          "the prefix ends at " + scan.report().cleanBytes() + ", not " + owed);
    }
    try (Wal wal = Wal.open(file, FsyncPolicy.PER_COMMIT, true)) {
      wal.appendSync(RecordType.POSTED, new byte[] {4});
    }
    WalRecovery.Scan repaired = WalRecovery.scan(file);
    if (repaired.report().frames() != 4 || repaired.report().tail() != Tail.CLEAN_EOF) {
      throw new AssertionError(
          "after repair and one append the log holds " + repaired.report().frames() + " frames and"
              + " reads as " + repaired.report().tail());
    }
    if (repaired.records().get(2).type() != RecordType.RECOVERY_MARKER) {
      throw new AssertionError("the repair left no marker between the cut and the new append");
    }
    if (repaired.records().get(3).lsn().value() != 4L) {
      throw new AssertionError(
          "the appended record got " + repaired.records().get(3).lsn() + "; a dense log continues"
              + " from the cut, and the stale lsn 4 must be gone");
    }
    return "1,2,4 keeps 1,2 and cuts at the hole; the next append is lsn 4, not lsn 5";
  }

  private static String noResurrection(Path dir) throws IOException {
    Path file = dir.resolve(Wal.FILE_NAME);
    try (Wal wal = Wal.open(dir, FsyncPolicy.PER_COMMIT)) {
      for (int i = 0; i < 6; i++) {
        wal.appendSync(RecordType.POSTED, new byte[] {(byte) (i + 1), 0, 0, 1});
      }
    }
    byte[] whole = Files.readAllBytes(file);
    // The shape a crash leaves: three records intact, the fourth half-written, and the fifth and
    // sixth still in the file. A repair that stepped over the tear instead of cutting it would
    // replay 5 and 6 after the next appends, and the ledger would post them twice.
    long tornAt = WalFormat.SEGMENT_HEADER_BYTES + 3L * FIXTURE_FRAME + 12L;
    Files.write(file, Arrays.copyOf(whole, (int) tornAt));
    try (Wal wal = Wal.open(dir, FsyncPolicy.PER_COMMIT)) {
      wal.appendSync(RecordType.POSTED, new byte[] {100, 0, 0, 1});
    }
    WalRecovery.Scan after = WalRecovery.scan(file);
    int postings = 0;
    for (WalRecord record : after.records()) {
      if (record.type() != RecordType.POSTED) {
        continue;
      }
      postings++;
      byte first = record.payloadUnsafe()[0];
      if (first <= 6 && first != postings) {
        throw new AssertionError(
            "the log replays posting " + first + " in position " + postings
                + " — a resurrected record, or a lost one");
      }
    }
    if (postings != 4) {
      throw new AssertionError(
          "the repaired log holds " + postings + " postings: 3 acked survivors and 1 new append"
              + " were due, and the records past the tear must stay dropped");
    }
    if (after.report().frames() != 5) {
      throw new AssertionError(
          "the log holds " + after.report().frames() + " frames; 3 postings, a marker and a new"
              + " posting were due");
    }
    return "the 3 records past the tear stayed gone; the log grew by a marker and one append";
  }

  // --- the fsync menu --------------------------------------------------------------------

  private static String perCommitForcesOnceEach(Path dir) throws IOException {
    try (Wal wal = Wal.open(dir, FsyncPolicy.PER_COMMIT)) {
      for (int i = 0; i < 24; i++) {
        wal.appendSync(RecordType.POSTED, new byte[] {(byte) i});
      }
      Wal.Stats stats = wal.stats();
      if (stats.forces() != 24 || stats.groups() != 24) {
        throw new AssertionError("per-commit wrote " + stats);
      }
      if (stats.frames() != 24 || stats.bytes() != 24L * (WalFormat.FRAME_OVERHEAD_BYTES + 1)) {
        throw new AssertionError("per-commit framing is wrong: " + stats);
      }
      if (!wal.policy().mode().forces()) {
        throw new AssertionError("PER_COMMIT does not report itself as forcing");
      }
      return stats + ", one fdatasync per record";
    }
  }

  private static String groupCommitCoalesces(Path dir) throws Exception {
    FsyncPolicy policy = FsyncPolicy.groupCommit(64, Duration.ofMillis(250));
    int writers = 32;
    int each = 8;
    AtomicInteger ackedCount = new AtomicInteger();
    try (Wal wal = Wal.open(dir, policy)) {
      List<Thread> threads = new ArrayList<>(writers);
      for (int w = 0; w < writers; w++) {
        int id = w;
        threads.add(
            Thread.ofVirtual()
                .start(
                    () -> {
                      List<CompletableFuture<Wal.Ack>> mine = new ArrayList<>(each);
                      for (int i = 0; i < each; i++) {
                        mine.add(
                            wal.append(
                                RecordType.POSTED, new byte[] {(byte) id, (byte) i, 0, 0, 1}));
                      }
                      for (CompletableFuture<Wal.Ack> future : mine) {
                        future.join();
                        ackedCount.incrementAndGet();
                      }
                    }));
      }
      for (Thread thread : threads) {
        thread.join();
      }
      Wal.Stats stats = wal.stats();
      if (ackedCount.get() != writers * each) {
        throw new AssertionError(
            "only " + ackedCount.get() + " of " + writers * each + " appends were acked");
      }
      if (stats.frames() != writers * each) {
        throw new AssertionError("expected " + writers * each + " frames, got " + stats);
      }
      if (stats.forces() >= stats.frames()) {
        throw new AssertionError(
            "group commit forced " + stats.forces() + " times for " + stats.frames()
                + " records, which is per-commit behaviour");
      }
      if (stats.forces() == 0) {
        throw new AssertionError("nothing was forced, so nothing was promised");
      }
      long dense = 0;
      for (WalRecord record : WalRecovery.scan(wal.file()).records()) {
        dense = record.lsn().value();
      }
      if (dense != writers * each) {
        throw new AssertionError("the last lsn is " + dense + ", not " + writers * each);
      }
      return writers + " writers x " + each + " records -> " + stats.forces() + " forces for "
          + stats.frames() + " frames across " + stats.groups() + " groups";
    }
  }

  private static String noBatchingWithoutContention(Path dir) throws IOException {
    try (Wal wal = Wal.open(dir, FsyncPolicy.GROUP_COMMIT)) {
      for (int i = 0; i < 20; i++) {
        wal.appendSync(RecordType.POSTED, new byte[] {(byte) i});
      }
      Wal.Stats stats = wal.stats();
      if (stats.forces() != 20) {
        throw new AssertionError(
            "a lone writer under group commit should force per record — batching is a property of"
                + " load, not of the mode — but got " + stats);
      }
      return "20 sequential appends, 20 forces: the 1-thread column of the benchmark table";
    }
  }

  private static String noFsyncNeverForces(Path dir) throws IOException {
    try (Wal wal = Wal.open(dir, FsyncPolicy.NO_FSYNC)) {
      Wal.Ack ack = wal.appendSync(RecordType.POSTED, new byte[] {1, 2, 3, 4});
      if (ack.forced()) {
        throw new AssertionError("a NO_FSYNC ack claimed to be forced");
      }
      if (wal.stats().forces() != 0) {
        throw new AssertionError("the no-fsync policy issued a force: " + wal.stats());
      }
      for (int i = 0; i < 50; i++) {
        wal.appendSync(RecordType.POSTED, new byte[] {(byte) i, 0, 0, 1});
      }
      if (wal.stats().forces() != 0) {
        throw new AssertionError("a busy no-fsync log started forcing: " + wal.stats());
      }
      long size = Files.size(wal.file());
      if (size != WalFormat.SEGMENT_HEADER_BYTES + 51L * FIXTURE_FRAME) {
        throw new AssertionError("the log is " + size + " bytes, which is not 51 records of "
            + FIXTURE_FRAME + " plus a header");
      }
      return "51 records, 0 forces, " + size + " bytes, and the ack says unforced";
    }
  }

  private static String acksUnderWatermark(Path dir) throws IOException {
    StringBuilder detail = new StringBuilder();
    for (FsyncPolicy policy : List.of(FsyncPolicy.PER_COMMIT, FsyncPolicy.GROUP_COMMIT)) {
      try (Wal wal = Wal.open(dir.resolve(policy.mode().name()), policy)) {
        for (int i = 0; i < 16; i++) {
          Wal.Ack ack = wal.appendSync(RecordType.POSTED, new byte[] {(byte) i});
          if (!ack.forced()) {
            throw new AssertionError(policy.mode() + " acked a record that was not forced");
          }
          if (ack.durableThrough() < ack.end()) {
            throw new AssertionError(
                policy.mode() + " acked " + ack + " while forcing only through "
                    + ack.durableThrough());
          }
          if (wal.durableThrough() < ack.end()) {
            throw new AssertionError(policy.mode() + "'s watermark moved backwards at " + ack);
          }
        }
        detail.append(policy.mode()).append(" ok; ");
      }
    }
    // The per-commit override, which is why the policy is a value and not a global: an unforced
  // append in a forcing log rides without waiting, and a forced append in a lazy log waits
  // for its
    // own force.
    try (Wal wal = Wal.open(dir.resolve("override"), FsyncPolicy.NO_FSYNC)) {
      Wal.Ack lazy = wal.appendSync(RecordType.POSTED, new byte[] {1, 2, 3, 4});
      Wal.Ack urgent =
          wal.appendSync(RecordType.POSTED, new byte[] {5, 6, 7, 8}, FsyncPolicy.PER_COMMIT);
      if (lazy.forced() || !urgent.forced()) {
        throw new AssertionError("the per-commit override did not hold: " + lazy + " vs " + urgent);
      }
      if (urgent.durableThrough() < urgent.end() || wal.stats().forces() != 1) {
        throw new AssertionError(
            "one forced record in a no-fsync log issued " + wal.stats().forces() + " forces");
      }
      detail.append("override ok: unforced then forced in one log, exactly one fdatasync");
    }
    return detail.toString();
  }

  // --- the log under a ledger ------------------------------------------------------------

  private static String replayIsDeterministic(Path dir) throws IOException {
    Path ledgerDir = dir.resolve("ledger");
    List<String> rendered = new ArrayList<>();
    FsyncPolicy policy = FsyncPolicy.groupCommit(16, Duration.ofMillis(1));
    try (DurableLedger ledger = DurableLedger.open(ledgerDir, policy)) {
      RandomSource rnd = new RandomSource(20260915L);
      AccountId[] accounts = new AccountId[6];
      for (int i = 0; i < accounts.length; i++) {
        accounts[i] = new AccountId("acct-" + i);
        ledger.openAccount(accounts[i], AccountKind.values()[i % 3]);
      }
      for (int i = 0; i < 200; i++) {
        AccountId from = accounts[rnd.nextInt(accounts.length)];
        AccountId to = accounts[rnd.nextInt(accounts.length)];
        ledger.transfer(from, to, Money.ofMinor(1L + rnd.nextLong(100_000L)));
      }
      for (JournalEvent event : ledger.ledger().events()) {
        rendered.add(JournalDigest.canonical(event));
      }
      ledger.ledger().audit();
    }
    List<String> firstReplay = replay(ledgerDir, policy);
    List<String> secondReplay = replay(ledgerDir, policy);
    if (!firstReplay.equals(rendered) || !secondReplay.equals(firstReplay)) {
      throw new AssertionError(
          "replay is not deterministic: " + rendered.size() + " live, " + firstReplay.size()
              + " and " + secondReplay.size() + " replayed");
    }
    // Byte-identical in the stronger sense too: re-encoding every replayed event reproduces the
    // payload the log holds, so what a second process writes is what a first one wrote.
    WalRecovery.Scan scan = WalRecovery.scan(ledgerDir.resolve(Wal.FILE_NAME));
    int checked = 0;
    for (WalRecord record : scan.records()) {
      if (!record.type().isJournalEvent()) {
        continue;
      }
      JournalEvent event = EventCodec.decode(record.type(), record.payloadUnsafe(), 0L);
      if (!Arrays.equals(record.payloadUnsafe(), EventCodec.encode(event))) {
        throw new AssertionError("record " + record.lsn() + " does not re-encode to its own bytes");
      }
      checked++;
    }
    return firstReplay.size() + " events, folded identically twice; " + checked
        + " payloads re-encode byte-identically";
  }

  private static List<String> replay(Path dir, FsyncPolicy policy) throws IOException {
    try (DurableLedger reopened = DurableLedger.open(dir, policy)) {
      List<String> rendered = new ArrayList<>();
      for (JournalEvent event : reopened.ledger().events()) {
        rendered.add(JournalDigest.canonical(event));
      }
      reopened.ledger().audit();
      return rendered;
    }
  }

  private static String codecIsInjective(Path dir) throws IOException {
    RandomSource rnd = new RandomSource(20260914L);
    List<byte[]> seen = new ArrayList<>();
    int events = 0;
    for (int i = 0; i < 400; i++) {
      JournalEvent event = randomEvent(rnd, i);
      byte[] encoded = EventCodec.encode(event);
      if (encoded.length > WalFormat.MAX_PAYLOAD_BYTES) {
        throw new AssertionError("the codec produced a payload over the format's ceiling");
      }
      JournalEvent back = EventCodec.decode(EventCodec.typeOf(event), encoded, i);
      if (!canonical(event).equals(canonical(back))) {
        throw new AssertionError(
            "an event did not survive the codec: " + canonical(event) + " vs " + canonical(back));
      }
      for (byte[] other : seen) {
        if (Arrays.equals(other, encoded)) {
          throw new AssertionError("two distinct events encoded to identical bytes");
        }
      }
      seen.add(encoded);
      events++;
    }
    // The widest transaction the format can hold. Payload is 2 + 11n against a 65_536-byte ceiling,
    // so 5_957 is the entry-count ceiling ADR 0002 handed to this ticket and ADR 0003 §2 sets; a
    // 65_535-entry count field would be a field that cannot be honoured, and 2 bytes bound the
    // frame while the frame bounds the entries.
    Transaction widest = wide(5_900);
    byte[] widestBytes = EventCodec.encode(new JournalEvent.Posted(widest));
    Transaction widestBack =
        ((JournalEvent.Posted) EventCodec.decode(RecordType.POSTED, widestBytes, 1L)).transaction();
    if (widestBack.size() != 5_900 || !widestBack.isBalanced()) {
      throw new AssertionError("the widest legal transaction did not round-trip");
    }
    return events + " random events round-trip with no collisions, and a " + widestBytes.length
        + "-byte posting of " + widest.size() + " entries fits";
  }

  private static Transaction wide(int entries) {
    List<Entry> body = new ArrayList<>(entries);
    for (int i = 0; i + 1 < entries; i++) {
      body.add(Entry.debit(AccountId.of("a"), Money.ofMinor(1L)));
    }
    body.add(Entry.credit(AccountId.of("a"), Money.ofMinor(entries - 1L)));
    return new Transaction(body);
  }

  private static JournalEvent randomEvent(RandomSource rnd, int i) {
    if (rnd.chance(25)) {
      return open("acct-" + i + "-" + rnd.nextInt(1_000), rnd.pick(AccountKind.values()).name());
    }
    int entries = 2 + rnd.nextInt(11);
    List<Entry> body = new ArrayList<>(entries);
    long net = 0L;
    for (int e = 0; e + 1 < entries; e++) {
      long amount = 1L + rnd.nextLong(1L << 40);
      Side side = rnd.nextBoolean() ? Side.DEBIT : Side.CREDIT;
      net += side.sign() * amount;
      body.add(new Entry(new AccountId("a" + rnd.nextInt(64)), side, Money.ofMinor(amount)));
    }
    long closing = net == 0L ? 1L : Math.abs(net);
    body.add(
        new Entry(
            new AccountId("a" + rnd.nextInt(64)),
            net > 0L ? Side.CREDIT : Side.DEBIT,
            Money.ofMinor(closing)));
    return new JournalEvent.Posted(new Transaction(body));
  }

  private static String rejectionLeavesNoTrace(Path dir) throws IOException {
    Path ledgerDir = dir.resolve("ledger");
    Path file = ledgerDir.resolve(Wal.FILE_NAME);
    try (DurableLedger ledger = DurableLedger.open(ledgerDir, FsyncPolicy.PER_COMMIT)) {
      AccountId a = ledger.openAccount(new AccountId("a"), AccountKind.ASSET).id();
      AccountId b = ledger.openAccount(new AccountId("b"), AccountKind.LIABILITY).id();
      long bytes = Files.size(file);
      int frames = WalRecovery.scan(file).report().frames();
      try {
        ledger.post(
            new Transaction(
                List.of(
                    Entry.debit(a, Money.ofMinor(100L)), Entry.credit(b, Money.ofMinor(99L)))));
        throw new AssertionError("an unbalanced transaction was accepted");
      } catch (RejectedTransactionException refused) {
        if (Files.size(file) != bytes) {
          throw new AssertionError(
              "the refusal grew the log from " + bytes + " to " + Files.size(file) + " bytes");
        }
        if (WalRecovery.scan(file).report().frames() != frames) {
          throw new AssertionError("a refused transaction reached the log");
        }
        if (!ledger.ledger().balanceOf(a).isZero()) {
          throw new AssertionError("a refused transaction moved a balance");
        }
        return "refused with " + refused.reason() + "; the log stayed at " + bytes + " bytes and "
            + frames + " frames";
      }
    }
  }

  private static String invalidPostingIsFatal(Path dir) throws IOException {
    Path file = dir.resolve("wal.log");
    // A frame that is perfect — right length, right CRC, dense LSN — whose transaction does not
    // balance. No torn write can produce this, so recovery must refuse it rather than
    // truncate: what
    // follows it may be acked money, and deleting that is the failure the ack rule exists to
    // prevent.
    Transaction unbalanced =
        new Transaction(
            List.of(
                Entry.debit(new AccountId("a"), Money.ofMinor(100L)),
                Entry.credit(new AccountId("b"), Money.ofMinor(99L))));
    byte[] payload = EventCodec.encode(new JournalEvent.Posted(unbalanced));
    byte[] frame = rawFrame(1L, RecordType.POSTED.code(), payload);
    Files.write(
        file,
        concat(WalFormat.encodeSegmentHeader(), frame),
        StandardOpenOption.CREATE,
        StandardOpenOption.TRUNCATE_EXISTING);
    long size = Files.size(file);
    WalRecovery.Scan structural = WalRecovery.scan(file);
    if (structural.report().frames() != 1) {
      throw new AssertionError("the scan refused a frame that is structurally sound");
    }
    IOException refused =
        expectThrows(IOException.class, () -> DurableLedger.open(dir, FsyncPolicy.PER_COMMIT));
    if (!(refused instanceof UnrecoverableLogException explicit)
        || explicit.corruption() != Corruption.DOMAIN_REJECTED) {
      throw new AssertionError("the fold accepted an unbalanced posting: " + refused);
    }
    if (Files.size(file) != size) {
      throw new AssertionError("a refused open touched the log");
    }
    return "a valid frame the ledger refuses is " + Corruption.DOMAIN_REJECTED
        + ", not a torn tail; file untouched";
  }

  private static String payloadCeiling(Path dir) throws IOException {
    try (Wal wal = Wal.open(dir, FsyncPolicy.PER_COMMIT)) {
      wal.appendSync(RecordType.POSTED, new byte[WalFormat.MAX_PAYLOAD_BYTES]);
      if (wal.stats().bytes() != WalFormat.MAX_FRAME_BYTES) {
        throw new AssertionError(
            "the largest legal frame is " + wal.stats().bytes() + " bytes, expected "
                + WalFormat.MAX_FRAME_BYTES);
      }
      try {
        wal.appendSync(RecordType.POSTED, new byte[WalFormat.MAX_PAYLOAD_BYTES + 1]);
        throw new AssertionError("a payload over the ceiling was accepted");
      } catch (IllegalArgumentException tooBig) {
        if (wal.stats().frames() != 1) {
          throw new AssertionError("the overlong append still reached the log: " + wal.stats());
        }
        return WalFormat.MAX_PAYLOAD_BYTES + "-byte payloads fit in " + WalFormat.MAX_FRAME_BYTES
            + " bytes of frame; one byte more is refused before the queue";
      }
    }
  }

  // --- fixtures, so the hostile inputs do not come from the code under test ----------------

  private static JournalEvent.AccountOpened open(String id, String kind) {
    return open(new AccountId(id), AccountKind.valueOf(kind));
  }

  private static JournalEvent.AccountOpened open(String id, AccountKind kind) {
    return open(new AccountId(id), kind);
  }

  private static JournalEvent.AccountOpened open(AccountId id, AccountKind kind) {
    return new JournalEvent.AccountOpened(new Account(id, kind));
  }

  private static byte[] opened(String id, String kind) {
    return EventCodec.encode(open(id, kind));
  }

  private static String canonical(JournalEvent event) {
    return JournalDigest.canonical(event);
  }

  /** A frame with a self-consistent CRC, built here rather than by {@link WalRecord#encode()}. */
  private static byte[] rawFrame(long lsn, int type, byte[] payload) {
    int length = WalFormat.FRAME_OVERHEAD_BYTES + payload.length;
    byte[] frame = new byte[length];
    WalFormat.putInt(frame, 0, length);
    WalFormat.putLong(frame, 4, lsn);
    frame[12] = (byte) type;
    WalFormat.putShort(frame, 14, payload.length);
    System.arraycopy(payload, 0, frame, WalFormat.FRAME_HEADER_BYTES, payload.length);
    WalFormat.putInt(frame, length - 4, WalFormat.crc32c(frame, length - 4));
    return frame;
  }

  private static byte[] concat(byte[]... parts) {
    ByteBuffer buffer = ByteBuffer.allocate(Arrays.stream(parts).mapToInt(p -> p.length).sum());
    for (byte[] part : parts) {
      buffer.put(part);
    }
    return buffer.array();
  }

  private static UnrecoverableLogException scanRefuses(Path file) throws IOException {
    try {
      WalRecovery.scan(file);
    } catch (UnrecoverableLogException refused) {
      return refused;
    } catch (IOException other) {
      throw new AssertionError("the scan failed for another reason: " + other);
    }
    throw new AssertionError("the scan accepted a log it should have refused");
  }

  private static <T extends Throwable> T expectThrows(Class<T> type, Throwing body) {
    try {
      body.run();
    } catch (Throwable caught) {
      if (type.isInstance(caught)) {
        return type.cast(caught);
      }
      throw new AssertionError("expected " + type.getSimpleName() + ", got " + caught, caught);
    }
    throw new AssertionError("expected " + type.getSimpleName() + ", but nothing was thrown");
  }

  private interface Throwing {
    void run() throws Exception;
  }

  private static String hex(byte[] bytes) {
    StringBuilder text = new StringBuilder();
    for (byte b : bytes) {
      text.append(String.format("%02x", b));
    }
    return text.toString();
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
