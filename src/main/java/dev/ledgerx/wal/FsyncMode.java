package dev.ledgerx.wal;

/**
 * The three ways a durable write can be promised, and the axis every other durability statement in
 * this repository is relative to.
 *
 * <p>Top-level rather than nested inside {@link FsyncPolicy} for a reason worth keeping:
 * nested, its constants would shadow the policy presets of the same name —
 * {@code Mode.PER_COMMIT} hiding {@code FsyncPolicy.PER_COMMIT} — and a name that means two
 * things in one file is not a name worth having. Java 21's {@code -Xlint:all} and ECJ's
 * {@code -err:+hiding} both object, which is a compiler finding a readability bug.
 */
public enum FsyncMode {

  /** Force per transaction. Nothing is batched, so nothing waits for a stranger. */
  PER_COMMIT,

  /** Force per group: one {@code fdatasync} covers everything written since the last one. */
  GROUP_COMMIT,

  /** Never force. {@code write()} is the whole promise. */
  NO_FSYNC;

  /** Whether an ack under this mode waits for a {@code force} call at all. */
  public boolean forces() {
    return this != NO_FSYNC;
  }

  /** The promise, in ADR 0003's words, for logs and failure messages. */
  public String promise() {
    return switch (this) {
      case PER_COMMIT -> "acked after its own fdatasync; survives process, OS and power loss";
      case GROUP_COMMIT -> "acked after its group's fdatasync; survives process, OS and power loss";
      case NO_FSYNC -> "acked after write(); survives a process crash only";
    };
  }
}
