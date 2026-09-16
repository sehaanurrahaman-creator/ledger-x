package dev.ledgerx.wal;

import java.time.Duration;

/**
 * The fsync policy menu, per ADR 0003 §5: three modes, and the two knobs that make group commit a
 * decision rather than a slogan.
 *
 * <p>The modes differ in exactly one thing — <strong>which {@code force} call the ack waits
 * for</strong> — and the ack rule is what every other ticket leans on, so the promise of each is
 * stated here in the words the ADR uses:
 *
 * <ul>
 *   <li>{@link Mode#PER_COMMIT} — a transaction is acked when <em>its own</em> bytes have been
 *       forced. Survives a process crash, an OS crash and a power cut. Costs one syscall per
 *       transaction, so its latency floor is the device's flush time.
 *   <li>{@link Mode#GROUP_COMMIT} — a transaction is acked when the <em>group</em> it landed in
 *       has been forced. Same survival, one syscall per group, so a lone writer gets
 *       {@code PER_COMMIT}'s cost and a busy one gets batching for free. This is the default.
 *   <li>{@link Mode#NO_FSYNC} — a transaction is acked when {@code write()} returned. Survives a
 *       process crash, because the page cache outlives the process; survives nothing else. It is
 *       on the menu because the benchmark needs its number and the harness needs to be able to
 *       <em>fail</em> it, not because it is ever a correct choice for money.
 * </ul>
 *
 * <p>A policy is a value, not a global: {@link Wal#append} takes an override, so one commit can
 * demand a force inside a no-fsync log (a payout intent) or waive one inside a forced log (a
 * diagnostic). What it may not do is ack earlier than the mode it names permits for a transaction
 * the caller asked to be durable — the modes bound the promise, the override picks a bound.
 *
 * @param mode which {@code force} the ack waits for, per {@link FsyncMode}
 * @param maxRecords how many appends one group may hold before the committer cuts it
 * @param maxDelayNanos how long the committer will wait for that many, from the first arrival
 */
public record FsyncPolicy(FsyncMode mode, int maxRecords, long maxDelayNanos) {

  /** One record per group, so one force per transaction. The slow, simple floor of the menu. */
  public static final FsyncPolicy PER_COMMIT = new FsyncPolicy(FsyncMode.PER_COMMIT, 1, 0L);

  /** The default: up to 64 records or 2 ms per group. Latency ceiling 2 ms, syscall floor /64. */
  public static final FsyncPolicy GROUP_COMMIT =
      new FsyncPolicy(FsyncMode.GROUP_COMMIT, 64, Duration.ofMillis(2).toNanos());

  /** No force ever. Present for the benchmark and for the harness that proves what it costs. */
  public static final FsyncPolicy NO_FSYNC = new FsyncPolicy(FsyncMode.NO_FSYNC, 64, 0L);

  /** The whole menu, in the order the ADR tables list it. */
  public static final FsyncPolicy[] MENU = {PER_COMMIT, GROUP_COMMIT, NO_FSYNC};

  public FsyncPolicy {
    if (maxRecords < 1) {
      throw new IllegalArgumentException("a group holds at least one record, not " + maxRecords);
    }
    if (maxDelayNanos < 0) {
      throw new IllegalArgumentException("a group cannot wait a negative time: " + maxDelayNanos);
    }
    if (mode == FsyncMode.PER_COMMIT && (maxRecords != 1 || maxDelayNanos != 0)) {
      throw new IllegalArgumentException(
          "PER_COMMIT means one record per group and no wait; use GROUP_COMMIT to batch");
    }
    if (mode == FsyncMode.NO_FSYNC && maxDelayNanos != 0) {
      throw new IllegalArgumentException(
          "NO_FSYNC has nothing to wait for: a force it will never issue");
    }
  }

  /** Group commit with an explicit budget — the shape the benchmark sweeps. */
  public static FsyncPolicy groupCommit(int maxRecords, Duration maxDelay) {
    return new FsyncPolicy(
        FsyncMode.GROUP_COMMIT, maxRecords, maxDelay == null ? 0L : maxDelay.toNanos());
  }

  /** The same mode, with a different batch budget; how the menu is swept without a new type. */
  public FsyncPolicy withBatch(int records, Duration maxDelay) {
    return new FsyncPolicy(mode, records, maxDelay == null ? 0L : maxDelay.toNanos());
  }

  /** Whether an append under this policy waits for {@code forceData} before it is acked. */
  public boolean forces() {
    return mode.forces();
  }

  /** How long a group may sit open, for logs and for the ADR's tables. */
  public Duration maxDelay() {
    return Duration.ofNanos(maxDelayNanos);
  }

  @Override
  public String toString() {
    return switch (mode) {
      case PER_COMMIT -> "per-commit fsync";
      case GROUP_COMMIT ->
          "group commit (<=" + maxRecords + " records, <=" + maxDelayNanos / 1000 + " ms)";
      case NO_FSYNC -> "no fsync";
    };
  }
}
