package dev.ledgerx.checkpoint;

import dev.ledgerx.domain.RequestFingerprint;
import dev.ledgerx.wal.WalFormat;

/**
 * The checkpoint file's byte layout: ADR 0004 §2, extended by ADR 0005 §6. Nothing else in the
 * repository hard-codes an offset, a size or a limit of this format.
 *
 * <pre>{@code
 * checkpoints/checkpoint-0000000000000000042.lckp
 *   +-------------------------------------------------------------+
 *   | header, 72 bytes                                            |
 *   +-------------------------------------------------------------+
 *   | canonical state section, stateBytes bytes (hashed)          |
 *   +-------------------------------------------------------------+
 *   | timing section, timingBytes bytes (not hashed)              |
 *   +-------------------------------------------------------------+
 *   | trailer: u8[32] stateHash | u32 crc32C over everything before|
 *   +-------------------------------------------------------------+
 *
 *   header (@0)                     state section (@72) — hashed
 *     +0  u32 magic "LCKP"            u8  canonical version (2)
 *     +4  u8  format version (2)      u64 lastLsn
 *     +5  u8  flags (0)               u32 accountCount
 *     +6  u16 header size (72)        account*, in opening order:
 *     +8  u32 reserved (0)              u8  kindNameLength
 *     +12 u32 reserved (0)              u8[] kindName (ASCII)
 *     +16 u64 lastLsn                   u8  idLength
 *     +24 u64 coveredBytes              u8[] id (7-bit ASCII)
 *     +32 u32 stateBytes                i64 balanceMinorUnits
 *     +36 u32 timingBytes               u32 bindingCount
 *     +40 u8[32] walDigest              binding*, in binding (log) order:
 *   = 72                                 u8  merchantLength | merchant
 *                                        u8  keyLength       | key
 *   timing section (@72+stateBytes)      u8[32] requestFingerprint
 *     bindingCount x u64                 u64 responseLsn
 *       capturedAtMillis, one per
 *       binding, in the same order
 *}</pre>
 *
 * <p>Every number is big-endian, and the primitives are {@link WalFormat}'s, so that the two
 * formats of this repository cannot disagree about what "big-endian" or "a length" means. The
 * file is written as a unit and read as a unit — unlike a WAL segment it is never appended to,
 * so its length is exact: {@code 72 + stateBytes + timingBytes + 36} or the file is refused.
 *
 * <p><strong>Three integrity layers, three different jobs — and a fourth section that is
 * deliberately outside two of them.</strong> The {@code crc32C} trailer catches a torn or
 * bit-flipped file, the way a WAL frame's CRC does. The {@code stateHash} catches a state section
 * that is not the state it claims to be, and it is the number an operator compares between a live
 * run and a recovered one (ADR 0004 §3). The {@code walDigest} catches a checkpoint being applied
 * to a log prefix other than the one it was taken over — which is not a hypothetical, because
 * ADR 0003 §2 lets LSNs be <em>reused</em> after a torn tail is cut, so a watermark alone is not
 * an identity (ADR 0004 §5). The <em>timing section</em> — one capture instant per key binding
 * —
 * is covered by the CRC and paired with the state section's bindings by count, but it is not
 * hashed, on the rule ADR 0004 §9 handed down: the state hash says what was bound, never when,
 * so two runs of one history hash the same even though their capture instants differ
 * (ADR 0005 §6). The price of the section is a canonical-version bump: a version-1 checkpoint
 * predates bindings and is refused — a refusal that costs time, never money, because a
 * checkpoint is a cache of a fold.
 */
public final class CheckpointFormat {

  /** "LCKP" — a checkpoint file starts with something that cannot be money by accident. */
  public static final int MAGIC = 0x4C43_4B50;

  /**
   * The checkpoint version this code writes, and the only one it reads. Version 2 added the key
   * binding rows to the state section and the timing section beside it (ADR 0005 §6); version 1
   * files are refused as caches of a fold the log can still produce.
   */
  public static final byte FORMAT_VERSION = 2;

  /** Fixed header: magic, version, flags, sizes, watermark, coverage, lengths, digest. */
  public static final int HEADER_BYTES = 72;

  /** SHA-256, sixty-four hex digits, the identity of a state and of a log prefix. */
  public static final int DIGEST_BYTES = 32;

  /** Trailer: the state hash, then the CRC over everything before it. */
  public static final int TRAILER_BYTES = DIGEST_BYTES + WalFormat.CRC_BYTES;

  /** Bytes a checkpoint costs before its state and timing do. */
  public static final int FILE_OVERHEAD_BYTES = HEADER_BYTES + TRAILER_BYTES;

  /** The canonical state section's own version, first byte of the hashed region. */
  public static final byte CANONICAL_VERSION = 2;

  /** Bytes of a canonical state section before the first account: version, LSN, count. */
  public static final int STATE_PREFIX_BYTES = 1 + 8 + 4;

  /** Bytes an account costs beyond its two names: the id length, the kind length, the balance. */
  public static final int ACCOUNT_FIXED_BYTES = 1 + 1 + 8;

  /** Bytes of the binding count, between the accounts and the bindings. */
  public static final int BINDING_COUNT_BYTES = 4;

  /**
   * Bytes a binding costs beyond its two names: the merchant length, the key length, the request
   * fingerprint, and the LSN of the record that made the binding.
   */
  public static final int BINDING_FIXED_BYTES = 1 + 1 + RequestFingerprint.BYTES + 8;

  /** One capture instant per binding, in the timing section that follows the state section. */
  public static final int TIMING_BYTES_PER_BINDING = 8;

  /** The checkpoint file's name, before the watermark and the extension. */
  public static final String NAME_PREFIX = "checkpoint-";

  /** The name's extension. A file that does not end with it is not a checkpoint. */
  public static final String NAME_SUFFIX = ".lckp";

  /**
   * The extension a checkpoint is written under before it is renamed into place. Deliberately
   * <em>not</em> a checkpoint name: recovery lists by the pattern above, so a half-written temp
   * file cannot be selected by a reader that is looking for a checkpoint — it is skipped by name,
   * before any byte of it is trusted (ADR 0004 §5).
   */
  public static final String TEMP_SUFFIX = ".tmp";

  /**
   * Decimal digits a watermark is zero-padded to in the name. Nineteen is the width of
   * {@link Long#MAX_VALUE}, so no two watermarks can collide and lexicographic order of the file
   * names <em>is</em> numeric order of the watermarks — which is what lets recovery pick the
   * newest without trusting the order a directory listing happens to return.
   */
  public static final int WATERMARK_DIGITS = 19;

  /** A long's decimal digits, at most. */
  public static final int MAX_WATERMARK_DIGITS = 19;

  /**
   * The ceiling on a checkpoint's state section. Not a format limit — a checkpoint is bounded by
   * the ledger it describes — but a reader that trusts a corrupt {@code stateBytes} must not
   * allocate a gigabyte before discovering the file is 300 bytes long. The reader therefore
   * bounds the declared length by the file's own size and by this, and refuses anything else.
   */
  public static final long MAX_STATE_BYTES = 256L * 1024L * 1024L;

  private CheckpointFormat() {}

  /** The name a checkpoint with this watermark is stored under. */
  public static String nameFor(long lastLsn) {
    if (lastLsn < 0L) {
      throw new IllegalArgumentException("a watermark is a record count and cannot be " + lastLsn);
    }
    String digits = Long.toString(lastLsn);
    StringBuilder name =
        new StringBuilder(NAME_PREFIX.length() + WATERMARK_DIGITS + NAME_SUFFIX.length());
    name.append(NAME_PREFIX);
    for (int i = digits.length(); i < WATERMARK_DIGITS; i++) {
      name.append('0');
    }
    return name.append(digits).append(NAME_SUFFIX).toString();
  }

  /**
   * The watermark a file name claims, or {@code -1} if the name is not a checkpoint name. The
   * name is a claim like any other field and is checked against the header on read, because a
   * file's name is the one part of it a rename can change without touching a byte.
   */
  public static long watermarkOf(String fileName) {
    if (!fileName.startsWith(NAME_PREFIX) || !fileName.endsWith(NAME_SUFFIX)) {
      return -1L;
    }
    String digits =
        fileName.substring(NAME_PREFIX.length(), fileName.length() - NAME_SUFFIX.length());
    if (digits.isEmpty() || digits.length() > MAX_WATERMARK_DIGITS) {
      return -1L;
    }
    for (int i = 0; i < digits.length(); i++) {
      if (digits.charAt(i) < '0' || digits.charAt(i) > '9') {
        return -1L;
      }
    }
    try {
      return Long.parseLong(digits);
    } catch (NumberFormatException beyondALong) {
      return -1L;
    }
  }

  /** Whether a file name is a checkpoint this code would read. Temp files are not. */
  public static boolean isCheckpointName(String fileName) {
    return watermarkOf(fileName) >= 0L;
  }

  /** Whether a file name is the temporary form a checkpoint is written under. */
  public static boolean isTempName(String fileName) {
    return fileName.endsWith(TEMP_SUFFIX) && fileName.startsWith(NAME_PREFIX);
  }
}
