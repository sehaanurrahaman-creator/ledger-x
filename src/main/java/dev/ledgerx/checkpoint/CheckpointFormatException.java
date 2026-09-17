package dev.ledgerx.checkpoint;

/**
 * Canonical state bytes that are not a state this build writes.
 *
 * <p>Thrown by {@link StateHash#decode}, which is a checker's tool rather than a path recovery
 * takes: recovery never decodes canonical bytes without having just hashed them. It is unchecked
 * because every caller is a test or an operator, and because a malformed canonical form is a bug in
 * the writer rather than a condition a caller can sensibly handle.
 */
public class CheckpointFormatException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public CheckpointFormatException(String message) {
    super(message);
  }
}
