package dev.ledgerx.wal;

/**
 * Why a scan stopped, when the answer is "this log is damaged and I will not pretend otherwise".
 *
 * <p>These are the findings a torn write <em>cannot</em> produce, because each of them requires a
 * complete frame whose CRC matches: the bytes are exactly what a writer finished, and a writer
 * only finishes appending something it decided to keep. So the record is either real and
 * uninterpretable by this reader, or it is a lie about the ledger. Truncating at it would discard
 * every ack that came after it, which is the one failure the ack rule exists to prevent, so
 * recovery refuses the log instead — loudly, with the offset, and without touching the file.
 *
 * <p>ADR 0003 §4 states the rule and argues the asymmetry: the tail is where damage from a crash
 * can only live, so the tail is where it may be repaired; everything else is data.
 */
public enum Corruption {

  /** The segment header is present and complete but is not this format's, or its CRC fails. */
  BAD_SEGMENT_HEADER,

  /** A frame's type byte names nothing in {@link RecordType}. */
  UNKNOWN_RECORD_TYPE,

  /** A frame is intact but its payload does not decode to the event its type promises. */
  PAYLOAD_MALFORMED,

  /**
   * A {@code POSTED} record decodes fine and the ledger refuses it. The only ways that happens
   * are a writer that appended an unvalidated transaction or a payload that collides through
   * CRC32C — and a checksum collision on a record this small is a 1-in-4-billion event per
   * record, so the honest reading is "a bug", which is why this stops recovery rather than
   * quietly dropping a transaction.
   */
  DOMAIN_REJECTED,

  /**
   * A checkpoint file is intact and hash-verified, but names coverage the log cannot prove:
   * an offset past the log's surviving bytes, or an LSN whose frame does not end exactly
   * where the checkpoint says. A torn write cannot produce this — the covered bytes were
   * forced before the checkpoint existed (ADR 0001's table puts {@code force(true)} at
   * checkpoint, and ADR 0003 §10 states the ordering as a rule) — so the honest readings
   * are a log that lost acknowledged data or a checkpoint from another history, and the
   * first of those is exactly what replaying from scratch would paper over. Recovery
   * refuses; an operator who wants from-scratch replay deletes the checkpoint, visibly,
   * which makes the loss a decision instead of a surprise. (A checkpoint that fails its
   * <em>own</em> integrity checks is the other case and the opposite verdict: it is
   * discarded and replay proceeds from scratch, because a damaged optimization must never
   * become an outage. ADR 0004 §4 argues the asymmetry.)
   */
  CHECKPOINT_MISMATCH,

  /** A record's payload is larger than the format allows, which no writer of this version emits. */
  OVERLONG_PAYLOAD
}

