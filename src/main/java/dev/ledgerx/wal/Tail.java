package dev.ledgerx.wal;

/**
 * Why a scan stopped, when the answer is "there is a tear here" — the input to the decided
 * torn-tail rule. Every reason in this enum is <em>truncatable</em>: only a write that never
 * finished can produce it, so nothing past it was ever acknowledged and recovery may cut it.
 *
 * <p>The list is closed, and that is the substance of ADR 0003 §4. Four reasons are structural —
 * a short frame header, a length the format cannot describe, a frame that runs past EOF, a CRC
 * that does not match — and the fifth, {@link #LSN_MISMATCH}, is a hole. A hole gets the same
 * treatment for a different reason: continuing past one would put new records <em>behind</em> it,
 * where the next scan would never reach them, so the safe answer is that the log ends where
 * understanding did.
 *
 * <p>What is deliberately absent is anything a complete, CRC-matching frame can produce — an
 * unknown type, a payload that will not decode, a transaction the domain refuses. Those are
 * {@link Corruption}, and truncating at them would delete acked work.
 */
public enum Tail {

  /** The file holds nothing: created and unused, or cut to empty. Legal, and not a tear. */
  EMPTY_FILE,

  /** EOF with no leftover bytes. The log is intact; there is nothing to truncate. */
  CLEAN_EOF,

  /** Fewer bytes remain than a frame header needs. */
  SHORT_FRAME_HEADER,

  /** The header decodes to a length the format cannot describe, or one it contradicts. */
  BAD_FRAME_LENGTH,

  /** The header is complete and honest, but its frame runs past EOF. */
  SHORT_FRAME,

  /** The frame is present in full and its CRC32C does not match its contents. */
  CRC_MISMATCH,

  /** The frame is intact but its LSN is not {@code previous + 1}: a gap, a repeat, a rewind. */
  LSN_MISMATCH;

  /** Whether this finding means "cut the file here". */
  public boolean isTear() {
    return this != EMPTY_FILE && this != CLEAN_EOF;
  }
}
