package dev.ledgerx.wal;

import java.io.IOException;

/**
 * Recovery's "I will not open this log" signal, and the only exception a scan throws.
 *
 * <p>It carries the offset it stopped at, because the difference between "the last 40 bytes were a
 * torn write" and "byte 9_000_000 is a lie" is the whole diagnosis, and the difference between a
 * truncatable tear and one of these is the whole design. The message therefore names the record,
 * the offset and the finding; ADR 0003 §4 is the argument for why refusing is safer than skipping
 * and why truncating at these findings would be unsafe.
 */
public final class UnrecoverableLogException extends IOException {

  private static final long serialVersionUID = 1L;

  private final Corruption cause;
  private final long offset;
  private final Lsn lsn;

  public UnrecoverableLogException(
      Corruption cause, long offset, Lsn lsn, String detail, Throwable reason) {
    super(
        "refusing to open the log: "
            + cause
            + " at "
            + (offset < 0L ? "an offset this layer does not know" : "byte " + offset)
            + " ("
            + (lsn == null ? "no LSN" : "lsn " + lsn.value())
            + ") — "
            + detail,
        reason);
    this.cause = cause;
    this.offset = offset;
    this.lsn = lsn;
  }

  public Corruption corruption() {
    return cause;
  }

  /** The byte offset recovery stopped at. */
  public long offset() {
    return offset;
  }

  /** The LSN of the offending frame, or {@code null} when it never got that far. */
  public Lsn lsn() {
    return lsn;
  }
}
