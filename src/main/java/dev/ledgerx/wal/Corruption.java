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

  /** A record's payload is larger than the format allows, which no writer of this version emits. */
  OVERLONG_PAYLOAD
}

