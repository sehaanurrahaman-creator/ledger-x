package dev.ledgerx.wal;

/**
 * What a record <em>is</em>, as one byte.
 *
 * <p>The table is closed on purpose. Recovery treats an unknown type as a torn byte rather than
 * as a record to skip, which is only the right behaviour because every type a writer can emit is
 * listed here: an unlisted code is therefore either damage or a newer format, and both must stop
 * a scan rather than confuse it. Adding a type is a format-version change, and ADR 0003 §7
 * records what that costs on upgrade (a new reader must accept old logs; an old reader must
 * refuse a new one, and refusing is safe because the ack rule never depends on it).
 *
 * <p>Two of these are ledger events, in {@link dev.ledgerx.domain.JournalEvent}'s exact shape —
 * ADR 0002 decided the event log and said the record format was this ticket's, so the mapping is
 * one-to-one and adds nothing. The third is log-internal: it moves no money and is consumed by
 * recovery itself, so it never reaches the fold.
 */
public enum RecordType {

  /** A {@code JournalEvent.AccountOpened}: an id and a kind. Moves no money. */
  ACCOUNT_OPENED(0x01),

  /** A {@code JournalEvent.Posted}: a whole transaction, balanced, in one record. */
  POSTED(0x02),

  /**
   * A marker written after a recovery truncation and at the start of every segment. It carries no
   * ledger event; it exists so that the log records what recovery did to it, which is the only
   * way a post-mortem can tell "the writer died here" from "recovery cut here, and here is why".
   */
  RECOVERY_MARKER(0x03);

  /**
   * Zero is not a type: it is what an unwritten or zeroed byte reads as, and it must never
   * parse.
   */
  public static final int UNKNOWN_CODE = 0;

  private final int code;

  RecordType(int code) {
    this.code = code;
  }

  /** The byte that goes in the frame header. */
  public int code() {
    return code;
  }

  /** Whether this record is a ledger event, i.e. whether a fold over the log must apply it. */
  public boolean isJournalEvent() {
    return this != RECOVERY_MARKER;
  }

  /**
   * The type a code byte names, or {@code null} if it names none — and {@code null} is how
   * recovery distinguishes "unknown type" from "I have not read the byte yet".
   */
  public static RecordType fromCode(int code) {
    for (RecordType type : values()) {
      if (type.code == code) {
        return type;
      }
    }
    return null;
  }
}
