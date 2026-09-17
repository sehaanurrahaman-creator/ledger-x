package dev.ledgerx.checkpoint;

/**
 * Why a checkpoint file was refused. A checkpoint is a <em>cache of a fold</em>, not the truth —
 * the log is the truth — so every finding here has the same consequence: skip this file, try the
 * next newest, and if none stands up, fold the log from the beginning. There is no refusal that
 * loses money, which is the property that makes it safe to be strict (ADR 0004 §6).
 *
 * <p>The list is closed, and the distinction it draws is the same one ADR 0003 draws between a
 * torn tail and an unreadable frame: everything a crash can produce is <em>here</em>, and
 * everything that requires a complete, checksum-valid file to be wrong is also here — because
 * unlike the WAL, a checkpoint has nothing behind it to lose. A torn tail is cuttable because
 * everything past it is unacked; a torn checkpoint is skippable for the same reason.
 */
public enum CheckpointRefusal {

  /** The file does not start with "LCKP". */
  BAD_MAGIC,

  /** Magic is right; the version, flags, header size or a reserved field is not. */
  BAD_HEADER,

  /** The declared state length does not account for exactly the bytes the file holds. */
  LENGTH_MISMATCH,

  /** The trailing CRC32C does not match the file's contents: a tear or a flip. */
  CRC_MISMATCH,

  /** The trailing SHA-256 does not match the state section: the file is not self-consistent. */
  STATE_DIGEST_MISMATCH,

  /** The state section will not decode: a bad canonical version, count, or an overrun. */
  STATE_MALFORMED,

  /** The name says one watermark and the header says another. */
  NAME_MISMATCH,

  /**
   * The log no longer holds the frame this checkpoint claims to cover — the stale-snapshot case
   * ADR 0003 §10 handed to this ticket, and the one refusal that is data loss if it is ever got
   * wrong the other way: applying a snapshot whose bytes are gone would resurrect money.
   */
  COVERAGE_GONE,

  /**
   * The log holds the claimed bytes and a different history wrote them. This is the reason the
   * format carries a digest at all: LSNs are reused after a cut, so a watermark cannot tell one
   * history's record 42 from another's.
   */
  WAL_DIGEST_MISMATCH,

  /** The file could not be read at all — vanished under a garbage collection, or unreadable. */
  UNREADABLE
}
