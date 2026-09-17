package dev.ledgerx.wal;

import dev.ledgerx.substrate.DurableChannel;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Recovery: read the log, find where it stops making sense, and cut.
 *
 * <p>The scan is deliberately boring and deliberately complete. It starts at byte 0, walks frames
 * while they are <em>structurally</em> intact, and stops at the first one that is not — where
 * "structurally" is exactly {@link Tail}'s four shapes plus {@link Tail#LSN_MISMATCH}, every one
 * of which a partial write could have produced. Anything a partial write could <em>not</em>
 * produce — a complete frame with a matching CRC whose contents this reader cannot honour — is
 * {@link Corruption}, and recovery throws instead of cutting. That asymmetry is ADR 0003 §4 and
 * it is the whole torn-tail rule; the argument for why cutting at a tear loses nothing an ack ever
 * referred to lives there, not here.
 *
 * <p>What the scan does <em>not</em> do: interpret payloads as money, decide what a checkpoint is,
 * or promise that the prefix it returns is what the caller acked. It returns bytes that are what
 * the writer said they were. {@link dev.ledgerx.journal.Replay} turns them into a ledger, and it
 * is the fold that re-validates each transaction and can therefore report
 * {@link Corruption#DOMAIN_REJECTED}.
 *
 * <p>The scan reads the whole log from byte 0, which is O(log) and is the honest cost of a log
 * with no index; the checkpoint ticket buys back the difference and inherits this class's rule
 * that whatever a snapshot claims to cover must still be true of the file.
 */
public final class WalRecovery {

  private WalRecovery() {}

  /**
   * What a scan found.
   *
   * @param fileBytes the size of the file before anything was cut
   * @param cleanBytes the size of the prefix that survived validation, which is where the cut went
   * @param truncatedBytes bytes discarded because they were a tear — never bytes discarded
   *     because
   *     they were inconvenient, which is what {@link Corruption} is for
   * @param frames frames in the clean prefix, log-internal markers included
   * @param journalRecords frames a fold must apply
   * @param markers recovery markers already in the log
   * @param lastLsn the LSN of the last frame in the clean prefix, 0 if there were none
   * @param tail why the scan stopped
   * @param detail the offending field, for a message a human can act on
   */
  public record Report(
      long fileBytes,
      long cleanBytes,
      long truncatedBytes,
      int frames,
      int journalRecords,
      int markers,
      long lastLsn,
      Tail tail,
      String detail) {

    /** Whether this scan cut anything. */
    public boolean torn() {
      return truncatedBytes > 0L;
    }

    /** Whether the log held nothing at all — a fresh file, or one cut to empty. */
    public boolean empty() {
      return frames == 0;
    }

    @Override
    public String toString() {
      return "recovered "
          + frames
          + " frames ("
          + journalRecords
          + " ledger events) through byte "
          + cleanBytes
          + " of "
          + fileBytes
          + ", stopped at "
          + tail
          + (truncatedBytes > 0L ? ", dropped " + truncatedBytes + " bytes" : "");
    }
  }

  /** A report and the records it validated, in log order. Markers are in the list, and skipped. */
  public record Scan(Report report, List<WalRecord> records) {

    /** Only the frames a fold must apply. */
    public List<WalRecord> journalRecords() {
      List<WalRecord> events = new ArrayList<>(records.size());
      for (WalRecord record : records) {
        if (record.type().isJournalEvent()) {
          events.add(record);
        }
      }
      return List.copyOf(events);
    }

    /**
     * The byte offset just past the frame carrying {@code lsn} — which is what a checkpoint
     * <em>covers</em>, and therefore the offset a recovery would resume reading at.
     *
     * <p>It is a walk over the frames already in memory rather than a field, because an offset is
     * derivable from the frames (they are all present, and each declares its own length) and a
     * second stored copy of a position is a second source of truth about it. Density is what makes
     * the walk a proof: the frames here are {@code 1..n} in order, so "the end of frame 42" is the
     * same number whether the walk counts lengths or the writer recorded its ack.
     *
     * @param lsn the sequence number, or 0 for "before the first record" — the empty state's
     *     coverage, which is exactly the segment header
     * @throws IllegalArgumentException if the clean prefix does not hold that frame, which is how a
     *     checkpoint whose coverage the log no longer has becomes a refusal instead of a bug
     */
    public long endOf(long lsn) {
      if (lsn == 0L) {
        return WalFormat.SEGMENT_HEADER_BYTES;
      }
      long offset = WalFormat.SEGMENT_HEADER_BYTES;
      for (WalRecord record : records) {
        offset += record.frameLength();
        if (record.lsn().value() == lsn) {
          return offset;
        }
        if (record.lsn().value() > lsn) {
          break;
        }
      }
      throw new IllegalArgumentException(
          "the clean prefix ends at lsn " + report.lastLsn() + " and holds no frame at lsn " + lsn);
    }

    /** The last {@code RECOVERY_MARKER} in the log, or {@code null} if there is none. */
    public WalRecord lastMarker() {
      for (int i = records.size() - 1; i >= 0; i--) {
        if (records.get(i).type() == RecordType.RECOVERY_MARKER) {
          return records.get(i);
        }
      }
      return null;
    }
  }

  /**
   * Validates the log at {@code file} and returns what it found, changing nothing.
   *
   * @throws UnrecoverableLogException on any {@link Corruption}: this method refuses rather than
   *     repairs, because refusing is reversible and repairing is not
   */
  public static Scan scan(Path file) throws IOException {
    long size = Files.exists(file) ? Files.size(file) : 0L;
    if (size == 0L) {
      return new Scan(
          new Report(0L, 0L, 0L, 0, 0, 0, 0L, Tail.EMPTY_FILE, "the file holds no bytes"),
          List.of());
    }
    List<WalRecord> records = new ArrayList<>();
    long position = WalFormat.SEGMENT_HEADER_BYTES;
    long nextLsn = Lsn.FIRST;
    Tail tail = Tail.CLEAN_EOF;
    String detail = "the file ends where the log does";
    try (DurableChannel in = DurableChannel.openForRead(file)) {
      byte[] header = new byte[WalFormat.SEGMENT_HEADER_BYTES];
      int got = readAt(in, header, 0L);
      if (got < WalFormat.SEGMENT_HEADER_BYTES) {
        throw new UnrecoverableLogException(
            Corruption.BAD_SEGMENT_HEADER, 0L, null,
            "the file holds " + got + " bytes, fewer than the "
                + WalFormat.SEGMENT_HEADER_BYTES + "-byte header; the head of a log is never"
                + " truncatable, because without it no record boundary can be trusted",
            null);
      }
      int segmentVersion = checkSegmentHeader(header);

      byte[] frame = new byte[WalFormat.MAX_FRAME_BYTES];
      while (true) {
        long remaining = size - position;
        if (remaining == 0L) {
          break;
        }
        if (remaining < WalFormat.FRAME_HEADER_BYTES) {
          tail = Tail.SHORT_FRAME_HEADER;
          detail = remaining + " bytes left, a frame header needs "
              + WalFormat.FRAME_HEADER_BYTES;
          break;
        }
        if (readAt(in, frame, position, WalFormat.FRAME_HEADER_BYTES)
            < WalFormat.FRAME_HEADER_BYTES) {
          // The file's size promised a header and the read came back short: a hole, which is the
          // same finding as a tear and gets the same treatment.
          tail = Tail.SHORT_FRAME_HEADER;
          detail = "the frame header at byte " + position + " could not be read in full";
          break;
        }
        int declared = WalFormat.intAt(frame, 0);
        if (declared < WalFormat.FRAME_OVERHEAD_BYTES || declared > WalFormat.MAX_FRAME_BYTES
            || declared > remaining) {
          // A short read here is the same finding as a bad length: what follows is not a frame
          // this reader can bound, and both are truncatable because only an unfinished write
          // leaves a length the file cannot satisfy.
          if (declared >= WalFormat.FRAME_OVERHEAD_BYTES && declared <= WalFormat.MAX_FRAME_BYTES) {
            tail = Tail.SHORT_FRAME;
            detail = "frame declares " + declared + " bytes, " + remaining + " remain";
          } else {
            tail = Tail.BAD_FRAME_LENGTH;
            detail = "frame declares " + declared + " bytes at byte " + position;
          }
          break;
        }
        int read = readAt(in, frame, position, declared);
        if (read < declared) {
          tail = Tail.SHORT_FRAME;
          detail = "only " + read + " of " + declared + " bytes could be read";
          break;
        }
        WalRecord.FrameCheck check =
            WalRecord.inspect(frame, declared, Lsn.of(nextLsn));
        if (check instanceof WalRecord.TornFrame torn) {
          tail = torn.reason();
          detail = torn.detail();
          break;
        }
        if (check instanceof WalRecord.FatalFrame fatal) {
          throw new UnrecoverableLogException(
              fatal.cause(), position, Lsn.of(nextLsn), fatal.detail(), null);
        }
        WalRecord record = ((WalRecord.ValidFrame) check).record();
        if (segmentVersion == 1 && record.type() == RecordType.IDEMPOTENT_POSTING) {
          // The version byte is a promise about the record vocabulary, and this is the check that
          // keeps it one: a version-1 segment predates this type, so a frame carrying it inside
          // one is not a version-1 log however well it parses — it is damage wearing a header,
          // and it stops the scan rather than being folded by a reader that was never asked
          // whether it understands what it found.
          throw new UnrecoverableLogException(
              Corruption.UNKNOWN_RECORD_TYPE, position, record.lsn(),
              "a version-" + segmentVersion + " segment cannot hold a "
                  + record.type() + " record; the version byte is a promise about the record"
                  + " vocabulary (ADR 0005 §4)",
              null);
        }
        records.add(record);
        if (record.type() == RecordType.RECOVERY_MARKER) {
          // A marker is a record like any other, and its payload is the log's own account of the
          // cut before it. Checked here rather than left to a fold, because nothing else reads it.
          if (record.payloadUnsafe().length != WalFormat.RECOVERY_MARKER_PAYLOAD_BYTES) {
            throw new UnrecoverableLogException(
                Corruption.PAYLOAD_MALFORMED, position, record.lsn(),
                "a recovery marker is " + WalFormat.RECOVERY_MARKER_PAYLOAD_BYTES
                    + " bytes and this one is " + record.payloadUnsafe().length,
                null);
          }
        }
        nextLsn = record.lsn().value() + 1L;
        position += declared;
      }
    }
    long lastLsn = nextLsn - 1L;
    int markers = 0;
    for (WalRecord record : records) {
      if (record.type() == RecordType.RECOVERY_MARKER) {
        markers++;
      }
    }
    Report report =
        new Report(
            size, position, size - position, records.size(), records.size() - markers, markers,
            lastLsn, tail, detail);
    return new Scan(report, List.copyOf(records));
  }

  /** Cuts the file at {@code bytes} and makes the cut durable. The only destructive call here. */
  public static void truncateTo(Path file, long bytes) throws IOException {
    try (DurableChannel out = DurableChannel.openForTruncate(file)) {
      out.truncateTo(bytes);
      // force(true), not force(false): the truncation is a size change, and while Linux flushes a
      // grown file's size on fdatasync, POSIX does not promise it for a shrunk one. The tail is
      // the cheap end of the log to be wrong about, and this is not a hot path.
      out.forceAll();
    }
  }

  /** Magic, version, header size and CRC — and a reserved field that must still read zero. */
  /**
   * Magic, version, header size and CRC — and a reserved field that must still read zero.
   *
   * <p>Returns the segment's version, because the scan needs it for the one rule that makes the
   * version byte mean something: a version-1 segment may not hold a record type that did not
   * exist when version 1 was written (ADR 0005 §4). Without the return, the caller would have to
   * re-read the byte, and a check that re-derives its input is a check that can be half-updated.
   */
  private static int checkSegmentHeader(byte[] header) throws UnrecoverableLogException {
    int magic = WalFormat.intAt(header, 0);
    int version = header[4] & 0xFF;
    int headerSize = WalFormat.shortAt(header, 6);
    int reserved = WalFormat.intAt(header, 8);
    int stored = WalFormat.intAt(header, 12);
    int computed = WalFormat.crc32c(header, 12);
    if (magic != WalFormat.SEGMENT_MAGIC) {
      throw new UnrecoverableLogException(
          Corruption.BAD_SEGMENT_HEADER, 0L, null,
          "magic is 0x" + Integer.toHexString(magic) + ", not 0x"
              + Integer.toHexString(WalFormat.SEGMENT_MAGIC) + " — not a ledger-x WAL",
          null);
    }
    if (version < WalFormat.OLDEST_READABLE_FORMAT_VERSION
        || version > WalFormat.FORMAT_VERSION) {
      throw new UnrecoverableLogException(
          Corruption.BAD_SEGMENT_HEADER, 0L, null,
          "format version is " + version + " and this build reads "
              + WalFormat.OLDEST_READABLE_FORMAT_VERSION + " through "
              + WalFormat.FORMAT_VERSION,
          null);
    }
    if (headerSize != WalFormat.SEGMENT_HEADER_BYTES || reserved != 0) {
      throw new UnrecoverableLogException(
          Corruption.BAD_SEGMENT_HEADER, 0L, null,
          "header size " + headerSize + " and reserved field 0x" + Integer.toHexString(reserved)
              + " are not what version " + version + " promises",
          null);
    }
    if (stored != computed) {
      throw new UnrecoverableLogException(
          Corruption.BAD_SEGMENT_HEADER, 0L, null,
          "header crc is 0x" + Integer.toHexString(stored) + ", computed 0x"
              + Integer.toHexString(computed),
          null);
    }
    return version;
  }

  private static int readAt(DurableChannel in, byte[] dst, long position) throws IOException {
    return readAt(in, dst, position, dst.length);
  }

  /**
   * One call, no loop: {@link DurableChannel#readAt} already reads until the buffer is full or the
   * file ends. A second loop here was a real hang, found by the contract suite — it spun on the
   * {@code 0} that means "at EOF", which is an answer and not a request to retry.
   */
  private static int readAt(DurableChannel in, byte[] dst, long position, int length)
      throws IOException {
    return in.readAt(ByteBuffer.wrap(dst, 0, length), position);
  }
}
