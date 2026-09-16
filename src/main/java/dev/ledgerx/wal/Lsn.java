package dev.ledgerx.wal;

/**
 * The log sequence number: a record's position in the append order, dense from 1.
 *
 * <p><strong>Dense is the whole point.</strong> A {@code long} that merely increases would let
 * recovery accept a log with a hole in it, and a hole is exactly the shape of two failures this
 * project must tell apart: a lost record (the ack rule failed) and a stale record that survived
 * a truncation (the torn-tail rule failed). Under the dense rule — frame <em>n</em> carries
 * {@code n}, and recovery stops at the first frame whose LSN is not {@code previous + 1} — both
 * become a tear at a known offset instead of a silent gap. ADR 0003 §2 argues the trade: the log
 * can be rewritten from position 1 after a truncation, and because truncation always lands past
 * the last acked record, re-issuing a number can never resurrect a transaction.
 *
 * <p>One {@code long} costs eight bytes per record, which is real write amplification; ADR 0003
 * §6 prices it and keeps it anyway, because the alternative (deriving the sequence from the byte
 * offset) cannot distinguish a lost record from a deliberately skipped one.
 *
 * @param value the sequence number, 1 or greater
 */
public record Lsn(long value) implements Comparable<Lsn> {

  /** The number a record appended to an empty log carries. Zero means "no record yet". */
  public static final long FIRST = 1L;

  public Lsn {
    if (value < FIRST) {
      throw new IllegalArgumentException(
          "an LSN counts records from " + FIRST + ", found " + value);
    }
  }

  public static Lsn of(long value) {
    return new Lsn(value);
  }

  /** The number the next appended record must carry. Overflow is a bug, not a wraparound. */
  public Lsn next() {
    return new Lsn(Math.addExact(value, 1L));
  }

  public boolean isAfter(Lsn other) {
    return value > other.value;
  }

  public boolean isAtLeast(Lsn other) {
    return value >= other.value;
  }

  @Override
  public int compareTo(Lsn other) {
    return Long.compare(value, other.value);
  }

  @Override
  public String toString() {
    return "lsn " + value;
  }
}
