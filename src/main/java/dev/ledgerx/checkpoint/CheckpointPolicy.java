package dev.ledgerx.checkpoint;

/**
 * When to take a checkpoint, and how many to keep: the cadence decision of ADR 0004 §7 and the
 * retention rule of §5, as one value.
 *
 * <p>Two knobs, because they are the two questions a checkpoint raises and they have different
 * answers. <strong>Cadence</strong> trades recovery time for write amplification: a checkpoint
 * costs one read of the log's covered prefix (the binding digest), one write of the state, two
 * forces and a rename, and it shortens the tail a recovery has to fold. Every commit is therefore
 * the wrong default — it makes the ledger O(log) <em>per commit</em> — and never is also wrong,
 * because recovery stays O(log) forever. {@link #DEFAULT} takes one every 1,024 commits, which
 * bounds a recovery's fold to 1,024 records and amortises the checkpoint's cost over them.
 *
 * <p><strong>Retention</strong> is about a different failure: a checkpoint file that is itself torn
 * or damaged. The protocol in ADR 0004 §5 makes that event rare — write, force, rename, sync the
 * directory — but it cannot make it impossible on media that lies, so the rule is "keep the
 * newest {@code retain}, drop the rest", default two: one to use and one to fall back to. Old
 * checkpoints are dropped by garbage collection
 * which needs no atomicity of its own precisely because the files it deletes are caches, not
 * state — if a crash resurrects one, recovery picks the highest watermark and never notices
 * (ADR 0004 §6).
 *
 * @param everyCommits commits between automatic checkpoints; 0 means never automatically
 * @param retain how many checkpoint files to keep after a successful swap; at least one
 */
public record CheckpointPolicy(int everyCommits, int retain) {

  /** Keep the newest two: one to use, one to fall back to. */
  public static final int DEFAULT_RETAIN = 2;

  /** Take a checkpoint only when a caller asks. */
  public static final CheckpointPolicy MANUAL = new CheckpointPolicy(0, DEFAULT_RETAIN);

  /** The ledger's default: every 1,024 commits, newest two kept. */
  public static final CheckpointPolicy DEFAULT = new CheckpointPolicy(1_024, DEFAULT_RETAIN);

  public CheckpointPolicy {
    if (everyCommits < 0) {
      throw new IllegalArgumentException(
          "a cadence of " + everyCommits + " commits is not a cadence; 0 means manual");
    }
    if (retain < 1) {
      throw new IllegalArgumentException(
          "retention of " + retain + " would delete the checkpoint just written");
    }
  }

  /** The same retention, a different cadence. */
  public static CheckpointPolicy everyCommits(int commits) {
    return new CheckpointPolicy(commits, DEFAULT_RETAIN);
  }

  /** The same cadence, a different retention. */
  public CheckpointPolicy retaining(int files) {
    return new CheckpointPolicy(everyCommits, files);
  }

  /** Whether commits take checkpoints on their own. */
  public boolean automatic() {
    return everyCommits > 0;
  }

  /** Whether {@code commitsSinceLast} commits mean it is time. */
  public boolean due(int commitsSinceLast) {
    return automatic() && commitsSinceLast >= everyCommits;
  }

  @Override
  public String toString() {
    return automatic()
        ? "a checkpoint every " + everyCommits + " commits, newest " + retain + " kept"
        : "checkpoints only on request, newest " + retain + " kept";
  }
}
