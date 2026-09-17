package dev.ledgerx.checkpoint;

import java.io.IOException;

/**
 * A checkpoint file that this reader will not use, carrying the {@link CheckpointRefusal} that
 * names why.
 *
 * <p>It is checked and it is not fatal — the two are separable on purpose. The store catches it,
 * records the refusal in its report, and moves on to the next candidate; a caller that reads a
 * checkpoint by path gets the exception and decides. The reason it is an exception rather than a
 * return value is that a decode has no meaningful partial result: half a state section is not a
 * state.
 */
public final class CorruptCheckpointException extends IOException {

  private static final long serialVersionUID = 1L;

  /**
   * Transient because nothing serializes a refusal and the finding is in the message anyway; a
   * non-transient field of a non-serializable type inside a {@code Serializable} class is a
   * javac {@code -Xlint:serial} warning, which ADR 0003 §8 records as the one lint this
   * repository's local compiler cannot see (an enum is serializable, but the rule is remembered
   * from that red CI run rather than re-derived per class).
   */
  private final transient CheckpointRefusal refusal;

  public CorruptCheckpointException(CheckpointRefusal refusal, String detail) {
    super(refusal + ": " + detail);
    this.refusal = refusal;
  }

  public CheckpointRefusal refusal() {
    return refusal;
  }
}
