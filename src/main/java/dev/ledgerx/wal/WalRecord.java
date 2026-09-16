package dev.ledgerx.wal;

import java.util.Arrays;
import java.util.Objects;

/**
 * One record: a sequence number, a type, and bytes the WAL does not interpret.
 *
 * <p>The payload is opaque <em>on purpose</em>. The log's job is to make bytes durable in order and
 * to prove that it did, and the only way it can be trusted with that is to not understand what it
 * is carrying — a writer that understood double-entry would be tempted to be clever about a
 * record
 * it did not like, and recovery's whole argument (ADR 0003 §4) rests on the log cutting tears and
 * nothing else. What the bytes mean is {@link dev.ledgerx.journal}'s business.
 *
 * <p>An LSN is a field of a record only as read back from the log; a writer never supplies one.
 * {@link Wal}'s committer assigns it, which is what keeps the numbering dense without a lock, and
 * why the append API takes {@code (type, payload)} rather than a record.
 *
 * @param lsn the dense sequence number this frame carries
 * @param type which {@link RecordType} the payload is
 * @param payload the encoded body, copied in and copied out — a record whose bytes change after
 *     the CRC was computed over them is the exact failure this format exists to prevent
 */
public record WalRecord(Lsn lsn, RecordType type, byte[] payload) {

  public WalRecord {
    Objects.requireNonNull(lsn, "record lsn");
    Objects.requireNonNull(type, "record type");
    Objects.requireNonNull(payload, "record payload");
    if (payload.length > WalFormat.MAX_PAYLOAD_BYTES) {
      throw new IllegalArgumentException(
          "a payload of "
              + payload.length
              + " bytes exceeds the format's ceiling of "
              + WalFormat.MAX_PAYLOAD_BYTES
              + " bytes (ADR 0003 §2)");
    }
    payload = payload.clone();
  }

  /** Bytes this record occupies in the file, framing included. */
  public int frameLength() {
    return WalFormat.FRAME_OVERHEAD_BYTES + payload.length;
  }

  /** The frame as it goes to the channel: header, payload, then the CRC over both. */
  public byte[] encode() {
    int length = frameLength();
    byte[] frame = new byte[length];
    WalFormat.putInt(frame, 0, length);
    WalFormat.putLong(frame, 4, lsn.value());
    frame[12] = (byte) type.code();
    frame[13] = 0;
    WalFormat.putShort(frame, 14, payload.length);
    System.arraycopy(payload, 0, frame, WalFormat.FRAME_HEADER_BYTES, payload.length);
    WalFormat.putInt(frame, length - WalFormat.CRC_BYTES, WalFormat.crc32c(frame, length - 4));
    return frame;
  }

  /**
   * Classifies one frame against the sequence number it must carry.
   *
   * <p>Returns a verdict instead of throwing, because a scan's two failure modes are this
   * design's central distinction — {@link Tail} means "cut here", {@link Corruption} means "stop"
   * — and one exception type would flatten it. {@code length} is the whole frame, which the
   * caller
   * has already proved is present.
   */
  public static FrameCheck inspect(byte[] frame, int length, Lsn expected) {
    if (length < WalFormat.FRAME_OVERHEAD_BYTES) {
      return new TornFrame(Tail.BAD_FRAME_LENGTH, "shorter than the "
          + WalFormat.FRAME_OVERHEAD_BYTES + "-byte framing overhead");
    }
    int declared = WalFormat.intAt(frame, 0);
    int payloadLength = WalFormat.shortAt(frame, 14);
    if (declared != length) {
      return new TornFrame(
          Tail.BAD_FRAME_LENGTH, "declares " + declared + " bytes, holds " + length);
    }
    if (payloadLength + WalFormat.FRAME_OVERHEAD_BYTES != length) {
      return new TornFrame(
          Tail.BAD_FRAME_LENGTH, "payload length " + payloadLength + " and frame length " + length
              + " do not agree");
    }
    int stored = WalFormat.intAt(frame, length - WalFormat.CRC_BYTES);
    int computed = WalFormat.crc32c(frame, length - WalFormat.CRC_BYTES);
    if (stored != computed) {
      return new TornFrame(
          Tail.CRC_MISMATCH,
          "stored 0x" + Integer.toHexString(stored) + ", computed 0x"
              + Integer.toHexString(computed));
    }
    // Everything below this line is a finding a torn write cannot produce, because the CRC over the
    // whole frame matched: these must stop recovery rather than cut it.
    long lsn = WalFormat.longAt(frame, 4);
    if (lsn != expected.value()) {
      return new TornFrame(Tail.LSN_MISMATCH, "expected " + expected.value() + ", holds " + lsn);
    }
    if (frame[13] != 0) {
      return new TornFrame(Tail.CRC_MISMATCH, "the reserved byte is " + frame[13]);
    }
    int code = WalFormat.unsignedByteAt(frame, 12);
    RecordType type = RecordType.fromCode(code);
    if (type == null) {
      return new FatalFrame(Corruption.UNKNOWN_RECORD_TYPE, "the type byte is 0x"
          + Integer.toHexString(code));
    }
    if (type == RecordType.RECOVERY_MARKER
        && payloadLength != WalFormat.RECOVERY_MARKER_PAYLOAD_BYTES) {
      return new FatalFrame(
          Corruption.PAYLOAD_MALFORMED,
          "a recovery marker is " + WalFormat.RECOVERY_MARKER_PAYLOAD_BYTES + " bytes and this one"
              + " is " + payloadLength);
    }
    return new ValidFrame(
        new WalRecord(
            Lsn.of(lsn),
            type,
            Arrays.copyOfRange(frame, WalFormat.FRAME_HEADER_BYTES, WalFormat.FRAME_HEADER_BYTES
                + payloadLength)));
  }

  /**
   * The payload of a {@code RECOVERY_MARKER}: the log's own account of a cut it made.
   *
   * <p>It is <em>not</em> a correctness mechanism and this ADR would rather say so than let the
   * reader assume otherwise: dense LSNs plus truncation already prevent a stale tail from being
   * replayed. The marker exists so a post-mortem can tell "the writer died at this byte" from
   * "recovery cut here, at this many bytes, for this reason", and so that a future segment
   * boundary has something to carry across.
   */
  public static byte[] recoveryMarkerPayload(Tail reason, long bytesDropped, int framesKept,
      long lastSurvivingLsn) {
    byte[] payload = new byte[WalFormat.RECOVERY_MARKER_PAYLOAD_BYTES];
    payload[0] = (byte) reason.ordinal();
    WalFormat.putInt(payload, 1, (int) Math.min(bytesDropped, 0xFFFF_FFFFL));
    WalFormat.putInt(payload, 5, framesKept);
    WalFormat.putLong(payload, 9, lastSurvivingLsn);
    return payload;
  }

  /** Which tear the marker was written for. */
  public static Tail markerReason(byte[] payload) {
    int ordinal = WalFormat.unsignedByteAt(payload, 0);
    Tail[] all = Tail.values();
    return ordinal < all.length ? all[ordinal] : Tail.CLEAN_EOF;
  }

  /** How many bytes recovery discarded. */
  public static long markerBytesDropped(byte[] payload) {
    return Integer.toUnsignedLong(WalFormat.intAt(payload, 1));
  }

  /** How many records survived the cut. */
  public static int markerFramesKept(byte[] payload) {
    return WalFormat.intAt(payload, 5);
  }

  /** The last LSN before the cut. */
  public static Lsn markerLastLsn(byte[] payload) {
    return Lsn.of(WalFormat.longAt(payload, 9));
  }

  @Override
  public byte[] payload() {
    return payload.clone();
  }

  /**
   * The payload without copying, for a reader that will only look at it. Never store the result.
   */
  public byte[] payloadUnsafe() {
    return payload;
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof WalRecord record
        && lsn.equals(record.lsn)
        && type == record.type
        && Arrays.equals(payload, record.payload);
  }

  @Override
  public int hashCode() {
    return 31 * Objects.hash(lsn, type) + Arrays.hashCode(payload);
  }

  @Override
  public String toString() {
    return "WalRecord[" + lsn + ", " + type + ", " + payload.length + " byte payload]";
  }

  /** The verdict type. Recovery's entire decision is which of these three it got. */
  public sealed interface FrameCheck permits ValidFrame, TornFrame, FatalFrame {}

  /** A frame that is what its header says, decoded. */
  public record ValidFrame(WalRecord record) implements FrameCheck {}

  /** A frame a partial write could have produced. Cut here; the ack rule says nothing was lost. */
  public record TornFrame(Tail reason, String detail) implements FrameCheck {}

  /** A complete, CRC-valid frame this reader cannot honour. Refuse the log; do not cut it. */
  public record FatalFrame(Corruption cause, String detail) implements FrameCheck {}
}
