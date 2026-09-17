package dev.ledgerx.checkpoint;

/**
 * A sink for {@link CheckpointStage}s. {@link #NONE} is the default, and a listener that blocks
 * blocks the commit thread that triggered the checkpoint, which is the honest place for it: the
 * swap happens inside the commit critical section on purpose (ADR 0004 §5).
 */
@FunctionalInterface
public interface CheckpointListener {

  /** The default: a checkpoint that nobody is watching. */
  CheckpointListener NONE = stage -> {};

  /** Called as each stage of the swap begins, in {@link CheckpointStage}'s declared order. */
  void reached(CheckpointStage stage);
}
