package dev.ledgerx.checkpoint;

/**
 * Why a checkpoint on disk was not used, when it was not.
 *
 * <p><strong>Every one of these is a cache miss, not an outage — and that asymmetry is the whole
 * design.</strong> ADR 0003 made a damaged <em>log</em> either a repair or a refusal, because the
 * log is the only record of what was promised to a client. A checkpoint is derived data:
 * whatever it claims, the log can still be folded from byte 0, and that fold is provably the same
 * state. So the
 * correct response to any doubt about a checkpoint is to throw it away and pay the scan, which is
 * expensive and correct, rather than to guess, which is cheap and might be money.
 *
 * <p>The two families differ in what they say about the system, which is why they are
 * distinguishable at all:
 *
 * <ul>
 *   <li><strong>Damaged</strong> ({@link #damaged()}): the file does not describe a state this
 *       build can read. Expected after a crash mid-write or a bad device; harmless, and counted.
 *   <li><strong>Stale</strong>: the file is perfectly readable but describes more log than the log
 *       holds. That cannot happen to a writer that follows ADR 0004's ordering rule, so it is
 *       evidence of a bug or of a WAL that was replaced or truncated behind the checkpoint's
 *       back — and it is the case where silently using the checkpoint would resurrect
 *       transactions a recovery had cut, which is data loss wearing a checkpoint's clothes.
 *       recovery had cut, which is data loss wearing a checkpoint's clothes.
 * </ul>
 */
public enum CheckpointRejection {

  /** Fewer bytes than the fixed header plus the trailing CRC. */
  SHORT_FILE,

  /** Not {@code "LCKP"}: not a ledger-x checkpoint at all. */
  BAD_MAGIC,

  /** A format version this build does not write. Refused rather than guessed at. */
  BAD_VERSION,

  /** A non-zero reserved field, or a header size that is not this version's. */
  BAD_HEADER,

  /** The file's CRC32C does not match its bytes: a torn or scribbled checkpoint. */
  CRC_MISMATCH,

  /** The account table does not parse, or does not fill the payload it claims. */
  PAYLOAD_MALFORMED,

  /**
   * The bytes parse and the CRC passes, but SHA-256 of the account table is not the digest the
   * header carries. This is the check that catches a writer that hashed one state and stored
   * another, which no CRC can see.
   */
  HASH_MISMATCH,

  /** The baseline's balances do not sum to zero, so it describes an impossible ledger. */
  IMPOSSIBLE_STATE,

  /** The checkpoint claims more WAL bytes than the file holds. */
  STALE_WAL_BYTES,

  /** The checkpoint claims an LSN past the log's last surviving record. */
  STALE_LSN,

  /**
   * The claimed byte offset is not the end of the record it names. This is the check that catches a
   * log that was cut and then regrown: the LSNs came back and the bytes behind them did not, so the
   * checkpoint would fold a tail on top of transactions recovery had already discarded.
   */
  NOT_A_RECORD_BOUNDARY,

  /** A checkpoint covering no journal events, which this writer never produces. */
  EMPTY_SNAPSHOT;

  /** Whether the file itself is unreadable, as opposed to merely out of date. */
  public boolean damaged() {
    return this == SHORT_FILE
        || this == BAD_MAGIC
        || this == BAD_VERSION
        || this == BAD_HEADER
        || this == CRC_MISMATCH
        || this == PAYLOAD_MALFORMED
        || this == HASH_MISMATCH
        || this == IMPOSSIBLE_STATE;
  }
}
