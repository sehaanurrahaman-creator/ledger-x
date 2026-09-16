package dev.ledgerx.wal;

import java.util.zip.CRC32C;

/**
 * The byte layout: ADR 0003 §2 in constants. Nothing else in the repository hard-codes an offset,
 * a size or a limit, so there is one place a format change has to be made.
 *
 * <pre>{@code
 * segment (a file, e.g. wal.log)
 *   +------------------------------------------------------------+
 *   | segment header, 16 bytes, written once at create and fsynced |
 *   +------------------------------------------------------------+
 *   | record frame 1                                             |
 *   | record frame 2                                             |
 *   | ...                     (append-only, never rewritten)     |
 *   +------------------------------------------------------------+  <- EOF; a tear cuts here
 *
 * segment header                          record frame
 *   +0  u32 magic      "LWAL"              +0  u32 frameLength   (16 + payloadLength + 4)
 *   +4  u8  format version                 +4  u64 lsn           (dense from 1)
 *   +5  u8  flags      (0)                 +12 u8  record type
 *   +6  u16 header size (16)               +13 u8  reserved      (0)
 *   +8  u32 reserved   (0)                 +14 u16 payloadLength
 *  +12  u32 crc32C over [0,12)             +16 payload
 *                                          +frameLength-4  u32 crc32C over [0,frameLength-4)
 *</pre>
 *
 * <p>Every number is big-endian, because {@link java.nio.ByteBuffer}'s default order is
 * {@code BIG_ENDIAN} and a hexdump of a big-endian file reads in numeric order, which is worth
 * bytes of debugging time on a format this small.
 *
 * <p>The {@code frameLength} at offset 0 covers the whole frame including itself and the CRC, so a
 * reader can find the next record without parsing this one, and can tell a short tail (a tear)
 * from a complete record whose contents are wrong. The CRC covers the length, the type, the LSN
 * and the payload, so a scribble on any of the four is detected rather than read as meaning.
 */
public final class WalFormat {

  /** "LWAL" — the file starts with something that cannot be money by accident. */
  public static final int SEGMENT_MAGIC = 0x4C57_414C;

  /** The only format version this code writes, and the only one it reads. */
  public static final byte FORMAT_VERSION = 1;

  /** Fixed-size part of the segment header. The header is not truncatable: ADR 0003 §4. */
  public static final int SEGMENT_HEADER_BYTES = 16;

  /** Fixed-size part of a frame header: length, LSN, type, reserved, payload length. */
  public static final int FRAME_HEADER_BYTES = 16;

  /** Trailing CRC. */
  public static final int CRC_BYTES = 4;

  /** Bytes a record costs before its payload does: 20. */
  public static final int FRAME_OVERHEAD_BYTES = FRAME_HEADER_BYTES + CRC_BYTES;

  /**
   * The ceiling on a payload, and therefore the entry-count ceiling ADR 0002 handed to this
   * ticket. At 64 KiB a {@code POSTED} record holds 6_553 entries of one-byte account ids, and
   * the smallest entry this ledger can name is 11 bytes (one-byte id, side, eight-byte amount),
   * which bounds a transaction at 5_957 entries — more than any posting, few enough that one
   * record still fits in one {@code write()} and one read buffer.
   */
  public static final int MAX_PAYLOAD_BYTES = 64 * 1024;

  /** The largest frame the format can describe. Bounded, so a corrupt length cannot OOM a scan. */
  public static final int MAX_FRAME_BYTES = FRAME_OVERHEAD_BYTES + MAX_PAYLOAD_BYTES;

  /**
   * An LSN is {@code long}-wide and this format will never exhaust it; the guard is the ceiling.
   */
  public static final long MAX_LSN = Long.MAX_VALUE;

  /**
   * Payload of a {@code RECOVERY_MARKER}: reason, bytes dropped, records kept, last surviving
   * LSN.
   */
  public static final int RECOVERY_MARKER_PAYLOAD_BYTES = 21;

  private WalFormat() {}

  /**
   * CRC32C — Castagnoli, the polynomial with a CPU instruction. {@link CRC32C} is in
   * {@code java.util.zip} since Java 9 and its {@code update} is a HotSpot intrinsic, so the
   * hardware {@code crc32} instruction runs on x86-64 with SSE4.2 (and on aarch64); elsewhere it
   * falls back to a table. ADR 0003 §2 says why this polynomial and not Adler-32.
   */
  public static int crc32c(byte[] bytes, int offset, int length) {
    CRC32C crc = new CRC32C();
    crc.update(bytes, offset, length);
    return (int) crc.getValue();
  }

  /** The same, over a prefix of {@code bytes} from 0 — the common case for a frame. */
  public static int crc32c(byte[] bytes, int length) {
    return crc32c(bytes, 0, length);
  }

  /** Encodes the 16-byte segment header. Reserved fields are zero, and a reader requires zero. */
  public static byte[] encodeSegmentHeader() {
    byte[] header = new byte[SEGMENT_HEADER_BYTES];
    putInt(header, 0, SEGMENT_MAGIC);
    header[4] = FORMAT_VERSION;
    putShort(header, 6, SEGMENT_HEADER_BYTES);
    putInt(header, 12, crc32c(header, 12));
    return header;
  }

  public static void putInt(byte[] bytes, int offset, int value) {
    bytes[offset] = (byte) (value >>> 24);
    bytes[offset + 1] = (byte) (value >>> 16);
    bytes[offset + 2] = (byte) (value >>> 8);
    bytes[offset + 3] = (byte) value;
  }

  public static void putLong(byte[] bytes, int offset, long value) {
    for (int i = 0; i < 8; i++) {
      bytes[offset + i] = (byte) (value >>> (56 - 8 * i));
    }
  }

  public static void putShort(byte[] bytes, int offset, int value) {
    bytes[offset] = (byte) (value >>> 8);
    bytes[offset + 1] = (byte) value;
  }

  public static int intAt(byte[] bytes, int offset) {
    return ((bytes[offset] & 0xFF) << 24)
        | ((bytes[offset + 1] & 0xFF) << 16)
        | ((bytes[offset + 2] & 0xFF) << 8)
        | (bytes[offset + 3] & 0xFF);
  }

  public static long longAt(byte[] bytes, int offset) {
    long value = 0;
    for (int i = 0; i < 8; i++) {
      value = (value << 8) | (bytes[offset + i] & 0xFFL);
    }
    return value;
  }

  public static int shortAt(byte[] bytes, int offset) {
    return ((bytes[offset] & 0xFF) << 8) | (bytes[offset + 1] & 0xFF);
  }

  public static int unsignedByteAt(byte[] bytes, int offset) {
    return bytes[offset] & 0xFF;
  }
}
