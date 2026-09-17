package dev.ledgerx.checkpoint;

/**
 * The stages of the atomic swap, in the order they happen: ADR 0004 §5's protocol, one constant
 * per step.
 *
 * <p>They exist in the main source rather than in a test because they are an operational fact, not
 * a test seam: an operator wants to know how long a checkpoint spends being written versus waiting
 * for a device, and the stages are where those timings live. That the crash harness also uses them
 * to kill a writer at an exact instant inside the swap is the second use of the same list — and
 * it is the honest way to test this part of the design, because the property being tested is a
 * property of the <em>ordering</em>: a kill at any stage must leave a directory in which recovery
 * finds a valid checkpoint or an older valid one, and never a torn file that is selected.
 *
 * <p>The stages bracket each syscall rather than describing them, so that a kill between two of
 * them is a state a real crash can produce:
 *
 * <ol>
 *   <li>{@link #TEMP_WRITTEN} — bytes are in the temp file, nothing has been forced.
 *   <li>{@link #TEMP_FORCED} — the temp file is durable; the name is not yet the checkpoint's.
 *   <li>{@link #RENAMED} — the swap has happened in the page cache; the directory entry is not
 *       durable until the next stage.
 *   <li>{@link #DIRECTORY_SYNCED} — the checkpoint is the checkpoint; older files may now go.
 *   <li>{@link #COLLECTED} — retention has run and stale temps are gone.
 * </ol>
 */
public enum CheckpointStage {

  /** The temp file holds the whole checkpoint and no force has been issued for it. */
  TEMP_WRITTEN,

  /** The temp file's bytes and metadata are on the device; the rename has not happened. */
  TEMP_FORCED,

  /** {@code rename(2)} has replaced the checkpoint name; the directory is not yet synced. */
  RENAMED,

  /** The directory is synced, so the swap survives a crash; garbage collection may run. */
  DIRECTORY_SYNCED,

  /** Retention has deleted what it deletes; the swap is over. */
  COLLECTED
}
