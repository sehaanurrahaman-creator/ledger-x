package dev.ledgerx.wal;

import dev.ledgerx.substrate.DurableChannel;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The append-only log: the write path ADR 0001 sketched and ADR 0003 decides.
 *
 * <p><strong>One writer, by construction.</strong> A caller serializes its payload, hands the
 * bytes to {@link #append}, and parks. A single committer thread drains the queue, assigns each
 * record its LSN, writes the whole group with one {@code write()}, issues at most one
 * {@code forceData()} for it, then completes the futures. LSN order is therefore <em>file</em>
 * order by construction rather than by agreement — which is what makes recovery's dense-sequence
 * check a proof instead of a hope.
 *
 * <p><strong>Opening is recovery.</strong> There is no way to get a writer without a scan:
 * {@link #open} reads the log, truncates a torn tail if there is one, appends a
 * {@code RECOVERY_MARKER} recording what it dropped, fsyncs, and only then starts accepting
 * appends. A writer that had not looked at the tail would append <em>behind</em> a hole, and a
 * record behind a hole is invisible to the next recovery — which is ADR 0003 §4's argument for
 * why repairing means cutting, not skipping.
 *
 * <p><strong>The ack rule, in code.</strong> A future completes only after its bytes are durable
 * under the policy that governs them: its own force under {@code PER_COMMIT}, the group's force
 * under {@code GROUP_COMMIT}, the return of {@code write()} under {@code NO_FSYNC} — the one mode
 * where "durable" means only "this process can no longer lose it". Nothing here knows what a
 * transaction is, so nothing here can ack early by misunderstanding a payload.
 *
 * <p>Any number of virtual threads may append — the queue is the serialization point — but
 * there
 * is one log per file and no second-writer API. Per-account ordering, backpressure and the lock
 * shape of the hot path are ticket #8's; there is deliberately no lock here, so that ticket starts
 * from the same clean sheet ADR 0002 left for the domain.
 */
public final class Wal implements AutoCloseable {

  /** The one file name. Segment numbering and rotation are ticket #6's; the format is ready. */
  public static final String FILE_NAME = "wal.log";

  /** Appends park here behind the committer. The depth is backpressure, sized for a burst. */
  private static final int QUEUE_CAPACITY = 4_096;

  private final Path file;
  private final DurableChannel channel;
  private final FsyncPolicy defaultPolicy;
  private final WalRecovery.Scan scan;

  private final ArrayBlockingQueue<Pending> queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
  private final Pending stopSignal = new Pending(null, null, null, null);
  private Thread committer;

  private volatile boolean closed;
  private volatile IOException failure;

  /** Committer-confined: one thread assigns these, which is the whole sequencing argument. */
  private long nextLsn = Lsn.FIRST;

  private long writtenThrough;

  /** Bytes a completed force covers. The durability watermark the ack rule is stated against. */
  private volatile long durableThrough;

  private final AtomicLong framesWritten = new AtomicLong();
  private final AtomicLong bytesWritten = new AtomicLong();
  private final AtomicLong groupsWritten = new AtomicLong();
  private final AtomicLong forcesIssued = new AtomicLong();

  private Wal(
      Path file,
      DurableChannel channel,
      FsyncPolicy defaultPolicy,
      WalRecovery.Scan scan,
      long lastLsn,
      long startBytes) {
    this.file = file;
    this.channel = channel;
    this.defaultPolicy = defaultPolicy;
    this.scan = scan;
    this.nextLsn = lastLsn + 1L;
    this.writtenThrough = startBytes;
    this.durableThrough = startBytes;
  }

  /**
   * Starts the committer. A method rather than the constructor, because a thread built from
   * {@code this::serve} inside {@code new Wal(..)} escapes a half-initialised object, and Java 21's
   * {@code -Xlint:this-escape} is right to say so.
   */
  private void startCommitter() {
    committer = Thread.ofPlatform().name("wal-committer").daemon(true).unstarted(this::serve);
    committer.start();
  }

  /** Opens {@code directory}'s WAL: recover, cut a tear if there is one, mark the cut, append. */
  public static Wal open(Path directory, FsyncPolicy policy) throws IOException {
    return open(directory.resolve(FILE_NAME), policy, true);
  }

  /**
   * The full open, with the choice that matters exposed.
   *
   * @param truncateTornTail {@code false} turns a tear into an {@link IOException} instead of
   *     repairing it, which is how a checker — the harness, an operator, a post-mortem —
   *     reads a
   *     log without changing it
   */
  public static Wal open(Path file, FsyncPolicy policy, boolean repair) throws IOException {
    Objects.requireNonNull(policy, "fsync policy");
    WalRecovery.Scan scan = WalRecovery.scan(file);
    if (scan.report().torn() && !repair) {
      throw new IOException(
          "the log is torn ("
              + scan.report().tail()
              + " at byte "
              + scan.report().cleanBytes()
              + ", "
              + scan.report().truncatedBytes()
              + " bytes past it) and repair is off");
    }
    if (scan.report().torn()) {
      WalRecovery.truncateTo(file, scan.report().cleanBytes());
    }
    boolean created = !Files.exists(file) || Files.size(file) == 0L;
    if (created) {
      if (file.toAbsolutePath().getParent() != null) {
        Files.createDirectories(file.toAbsolutePath().getParent());
      }
      Files.write(
          file,
          WalFormat.encodeSegmentHeader(),
          StandardOpenOption.CREATE,
          StandardOpenOption.WRITE,
          StandardOpenOption.TRUNCATE_EXISTING);
    }
    Wal wal =
        new Wal(
            file,
            DurableChannel.openForAppend(file),
            policy,
            scan,
            scan.report().lastLsn(),
            created ? WalFormat.SEGMENT_HEADER_BYTES : scan.report().cleanBytes());
    // ADR 0001 puts an fsync at open, and the reason is this line: forcing the recovered prefix
    // before the first append is what makes "everything the log held when we opened it" durable,
    // including tail bytes a NO_FSYNC predecessor left in the page cache.
    wal.channel.forceAll();
    if (created) {
      DurableChannel.syncDirectory(file.toAbsolutePath().getParent());
    }
    wal.startCommitter();
    if (scan.report().torn()) {
      // The log records its own repair, forced whatever the active policy says: a marker that
      // could be lost makes the repair unauditable in exactly the case worth auditing, and a
      // force is never a promise broken — only one kept early.
      wal.appendSync(
          RecordType.RECOVERY_MARKER,
          WalRecord.recoveryMarkerPayload(
              scan.report().tail(), scan.report().truncatedBytes(), scan.report().frames(),
              scan.report().lastLsn()),
          FsyncPolicy.PER_COMMIT);
    }
    return wal;
  }

  /** What recovery found and did, including the tear it cut. Never {@code null}. */
  public WalRecovery.Report recovery() {
    return scan.report();
  }

  /**
   * The scan recovery performed <em>at open</em>: its report and the records the clean prefix held
   * then. This is not a live read of the file, since the log has been appended to since: a checker
   * that wants the log as it now stands calls {@link WalRecovery#scan} instead. The distinction is
   * load-bearing: the recovery marker this WAL may have appended is in the file and not in here.
   */
  public WalRecovery.Scan recovered() {
    return scan;
  }

  public Path file() {
    return file;
  }

  public FsyncPolicy policy() {
    return defaultPolicy;
  }

  /**
   * Appends one record under the log's default policy. The LSN is the log's to assign: a caller
   * that could name one could name a stale one, and dense numbering is a proof only if it is not
   * negotiable.
   *
   * @return a future completed with the {@link Ack} when the record is durable as that policy
   *     promises, or completed exceptionally if the log dies first
   */
  public CompletableFuture<Ack> append(RecordType type, byte[] payload) {
    return append(type, payload, defaultPolicy);
  }

  /** Appends with an explicit per-commit policy: the menu, per commit. */
  public CompletableFuture<Ack> append(RecordType type, byte[] payload, FsyncPolicy policy) {
    Objects.requireNonNull(type, "record type");
    Objects.requireNonNull(payload, "record payload");
    Objects.requireNonNull(policy, "fsync policy");
    if (payload.length > WalFormat.MAX_PAYLOAD_BYTES) {
      throw new IllegalArgumentException(
          "a payload of " + payload.length + " bytes exceeds the format's ceiling of "
              + WalFormat.MAX_PAYLOAD_BYTES + " bytes (ADR 0003 \u00a72)");
    }
    IOException dead = failure;
    if (dead != null) {
      throw new IllegalStateException("this WAL has failed: " + dead.getMessage(), dead);
    }
    if (closed) {
      throw new IllegalStateException("this WAL is closed");
    }
    // Copied in, so a caller that reuses its buffer cannot change bytes the CRC was computed over.
    Pending pending = new Pending(type, payload.clone(), policy, new CompletableFuture<>());
    if (!queue.offer(pending)) {
      throw new IllegalStateException(
          "the WAL's append queue is full ("
              + QUEUE_CAPACITY
              + " appends unacknowledged); queue depth, backpressure and the per-account locks"
              + " that shape it are ticket #8's, so this refuses rather than pinning a thread");
    }
    return pending.future;
  }

  /** Appends and waits — the whole commit protocol, blocking style. */
  public Ack appendSync(RecordType type, byte[] payload) throws IOException {
    return appendSync(type, payload, defaultPolicy);
  }

  /** Appends under an explicit policy and waits for the ack. */
  public Ack appendSync(RecordType type, byte[] payload, FsyncPolicy policy) throws IOException {
    try {
      return append(type, payload, policy).get();
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IOException("interrupted while waiting for the WAL's ack", interrupted);
    } catch (ExecutionException failed) {
      Throwable cause = failed.getCause();
      if (cause instanceof IOException io) {
        throw io;
      }
      throw new IOException("the WAL failed", cause);
    }
  }

  /** Bytes a completed force covers. Every record acked under a forcing policy ends at or below. */
  public long durableThrough() {
    return durableThrough;
  }

  /**
   * Forces everything written so far and returns the byte offset the force made durable.
   *
   * <p>This is ADR 0001's checkpoint fsync, and it routes through the committer rather than
   * calling {@code force} directly on the channel: a force issued beside an in-flight group
   * could return before that group's {@code write()} has landed, and a watermark built on it
   * would claim durability for bytes that have no claim to it. So the request joins the queue
   * as a barrier — the committer processes it between groups, forces the channel, and only
   * then publishes {@code durableThrough = writtenThrough} and completes the caller. The
   * returned offset is the committer's own number, and everything at or below it is durable.
   *
   * <p>The checkpoint path calls this before it serializes state, per ADR 0003 §10's ordering
   * rule: the bytes a watermark names are durable before the snapshot claiming them exists —
   * never the reverse. Under the ledger's commit lock the queue is empty when this runs,
   * which is what makes the watermark and the serialized state the same moment.
   *
   * @throws IllegalStateException if the WAL is closed, has failed, or has a full append
   *     queue (a checkpoint of a log with 4,096 unacked appends is refused — backpressure is
   *     ticket #8's, and this refuses rather than forcing less than it returns)
   */
  public long force() throws IOException {
    IOException dead = failure;
    if (dead != null) {
      throw new IllegalStateException("this WAL has failed: " + dead.getMessage(), dead);
    }
    if (closed) {
      throw new IllegalStateException("this WAL is closed");
    }
    Pending barrier = new Pending(null, null, null, null, new CompletableFuture<>());
    if (!queue.offer(barrier)) {
      throw new IllegalStateException(
          "the WAL's append queue is full ("
              + QUEUE_CAPACITY
              + " appends unacknowledged), so a checkpoint force cannot join it; queue depth and"
              + " backpressure are ticket #8's");
    }
    try {
      // Bounded, so a close that races this call turns into a loud failure rather than a
      // quiet hang: once the committer is gone the barrier would never be processed.
      return barrier.forced.get(30, TimeUnit.SECONDS);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IOException("interrupted while waiting for the WAL's force", interrupted);
    } catch (ExecutionException failed) {
      Throwable cause = failed.getCause();
      throw cause instanceof IOException io
          ? io
          : new IOException("the WAL's force failed", cause);
    } catch (java.util.concurrent.TimeoutException nobodyWillForce) {
      throw new IOException("the WAL's committer did not process the force within 30s");
    }
  }

  /** Bytes {@code write()} has been asked to put in the file, forced or not. */
  public long writtenThrough() {
    return writtenThrough;
  }

  /** The LSN the next record will carry. */
  public Lsn nextLsn() {
    return Lsn.of(nextLsn);
  }

  public Stats stats() {
    return new Stats(
        framesWritten.get(), bytesWritten.get(), groupsWritten.get(), forcesIssued.get(),
        queue.size());
  }

  /**
   * Counters for the write-amplification table ADR 0003 §6 quotes and the benchmark re-measures.
   */
  public record Stats(long frames, long bytes, long groups, long forces, int queued) {

    @Override
    public String toString() {
      return "frames=" + frames
          + " bytes=" + bytes
          + " groups=" + groups
          + " forces=" + forces
          + " queued=" + queued;
    }
  }

  /**
   * What a caller is told when its record is durable: the record's identity and the watermark the
   * promise reaches, which is everything an oracle needs to check the ack rule from outside.
   *
   * @param lsn the sequence number the committer assigned
   * @param offset the byte offset the frame starts at
   * @param frameLength how many bytes it occupies
   * @param forced whether a {@code force} completed for it before the ack
   * @param durableThrough for a forced ack, the offset that force covers; for an unforced one,
   *     merely where the written bytes end
 */
  public record Ack(Lsn lsn, long offset, int frameLength, boolean forced, long durableThrough) {

    /** The offset just past this record's last byte. */
    public long end() {
      return offset + frameLength;
    }

    @Override
    public String toString() {
      return "Ack["
          + lsn
          + " @"
          + offset
          + "+"
          + frameLength
          + (forced ? ", forced through " + durableThrough : ", unforced")
          + "]";
    }
  }

  @Override
  public void close() throws IOException {
    if (closed) {
      return;
    }
    closed = true;
    try {
      if (!queue.offer(stopSignal, 30, TimeUnit.SECONDS)) {
        throw new IOException("the WAL's committer stopped accepting appends");
      }
      committer.join(TimeUnit.SECONDS.toMillis(30));
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      committer.interrupt();
    }
    if (failure != null) {
      throw failure;
    }
    // A clean exit is not a crash. What was written but never forced is flushed rather than
    // abandoned, so closing a log never discards bytes the log already holds.
    channel.forceAll();
    channel.close();
  }

  /** The committer's loop: take a group, write it, force it, ack it. */
  private void serve() {
    List<Pending> group = new ArrayList<>(FsyncPolicy.GROUP_COMMIT.maxRecords());
    try {
      while (true) {
        Pending head = queue.take();
        group.clear();
        if (head == stopSignal) {
          return;
        }
        if (head.forced != null) {
          // A force barrier travels alone: it is not a record, it joins no group, and its
          // whole job is to be processed between groups so the watermark it publishes is
          // the committer's own.
          forceNow(head.forced);
          continue;
        }
        group.add(head);
        if (fillGroup(head, group)) {
          flush(group);
          return;
        }
        flush(group);
      }
    } catch (InterruptedException stoppedNow) {
      Thread.currentThread().interrupt();
      fail(new IOException("the WAL's committer was interrupted", stoppedNow));
    } catch (IOException failed) {
      fail(failed);
    } finally {
      abandonLeftovers();
    }
  }

  /** The only place {@code durableThrough} advances past a force: on the committer. */
  private void forceNow(CompletableFuture<Long> done) {
    try {
      channel.forceAll();
      durableThrough = writtenThrough;
      done.complete(durableThrough);
    } catch (IOException failed) {
      fail(failed);
      done.completeExceptionally(failed);
    }
  }

  /**
   * Collects the rest of this group. The head's policy sets the budget, and the wait is capped by
   * one rule worth stating because it is where a naive group commit stalls: <strong>the committer
   * only waits while there is already more work queued</strong>. A batch window is not a latency
   * budget to spend waiting for arrivals that have not happened — the writer whose record is
   * already in hand is the very thread the ack would unblock, so parking it for 5 ms to see whether
   * a stranger shows up turns an idle log into one with a 5 ms floor per commit. Under load the
   * queue is non-empty, the window applies, and batching happens; at 1 thread a group is a record,
   * which is the honest result the benchmark table is going to print.
   *
   * @return {@code true} if the stop signal arrived and the caller must return after flushing
   */
  private boolean fillGroup(Pending head, List<Pending> group) throws InterruptedException {
    FsyncPolicy policy = head.policy;
    long deadline =
        policy.maxDelayNanos() > 0L ? System.nanoTime() + policy.maxDelayNanos() : 0L;
    while (group.size() < policy.maxRecords() && !queue.isEmpty()) {
      if (deadline != 0L) {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0L) {
          return false;
        }
      }
      Pending next = queue.poll();
      if (next == null) {
        return false;
      }
      if (next == stopSignal) {
        return true;
      }
      if (next.forced != null) {
        // A barrier met mid-collection forces what the group has written so far — nothing,
        // since this group has not been written yet — which is exactly the promise: the
        // watermark it returns covers every completed write and no in-flight one.
        forceNow(next.forced);
        continue;
      }
      group.add(next);
    }
    return false;
  }

  /** One {@code write()}, at most one {@code forceData()}, then the acks — in that order. */
  private void flush(List<Pending> group) throws IOException {
    int total = 0;
    for (Pending pending : group) {
      if (nextLsn > WalFormat.MAX_LSN) {
        throw new IOException("the log has no sequence numbers left at " + nextLsn);
      }
      pending.lsn = Lsn.of(nextLsn);
      pending.frame = new WalRecord(Lsn.of(nextLsn), pending.type, pending.payload).encode();
      pending.offset = writtenThrough;
      nextLsn = nextLsn + 1L;
      total += pending.frame.length;
      writtenThrough += pending.frame.length;
    }
    channel.write(flatten(group, total));
    framesWritten.addAndGet(group.size());
    bytesWritten.addAndGet(total);
    groupsWritten.incrementAndGet();

    for (Pending pending : group) {
      if (!pending.policy.forces()) {
        pending.future.complete(ack(pending, false, writtenThrough));
      }
    }
    boolean anyForced = false;
    for (Pending pending : group) {
      anyForced |= pending.policy.forces();
    }
    if (anyForced) {
      channel.forceData();
      forcesIssued.incrementAndGet();
      durableThrough = writtenThrough;
      for (Pending pending : group) {
        if (pending.policy.forces()) {
          pending.future.complete(ack(pending, true, durableThrough));
        }
      }
    }
  }

  private static Ack ack(Pending pending, boolean forced, long through) {
    return new Ack(pending.lsn, pending.offset, pending.frame.length, forced, through);
  }

  private static ByteBuffer flatten(List<Pending> group, int total) {
    ByteBuffer buffer = ByteBuffer.allocate(total);
    for (Pending pending : group) {
      buffer.put(pending.frame);
    }
    buffer.flip();
    return buffer;
  }

  /** A failed force or write is a dead log: no append can promise anything after it. */
  private void fail(IOException why) {
    if (failure == null) {
      failure = why;
    }
  }

  private void abandonLeftovers() {
    IOException why = failure;
    if (why == null) {
      why = new IOException("the WAL's committer stopped before this append was written");
    }
    for (Pending pending : queue.toArray(new Pending[0])) {
      if (pending != stopSignal && !pending.future.isDone()) {
        pending.future.completeExceptionally(why);
      }
    }
  }

  /**
   * One append in flight — or a force barrier, whose type is null. The committer-filled
   * fields are confined to that thread.
   */
  private static final class Pending {
    final RecordType type;
    final byte[] payload;
    final FsyncPolicy policy;
    final CompletableFuture<Ack> future;

    /** Non-null only on a force barrier: the future the caller waits on for the watermark. */
    final CompletableFuture<Long> forced;
    Lsn lsn;
    byte[] frame;
    long offset;

    Pending(
        RecordType type,
        byte[] payload,
        FsyncPolicy policy,
        CompletableFuture<Ack> future) {
      this(type, payload, policy, future, null);
    }

    Pending(
        RecordType type,
        byte[] payload,
        FsyncPolicy policy,
        CompletableFuture<Ack> future,
        CompletableFuture<Long> forced) {
      this.type = type;
      this.payload = payload;
      this.policy = policy;
      this.future = future;
      this.forced = forced;
    }
  }
}
