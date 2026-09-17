package dev.ledgerx.journal;

import dev.ledgerx.checkpoint.Checkpoint;
import dev.ledgerx.checkpoint.CheckpointRecovery;
import dev.ledgerx.checkpoint.CheckpointWriter;
import dev.ledgerx.checkpoint.StateHash;
import dev.ledgerx.domain.Account;
import dev.ledgerx.domain.AccountId;
import dev.ledgerx.domain.AccountKind;
import dev.ledgerx.domain.InMemoryLedger;
import dev.ledgerx.domain.JournalEvent;
import dev.ledgerx.domain.Money;
import dev.ledgerx.domain.Transaction;
import dev.ledgerx.wal.FsyncPolicy;
import dev.ledgerx.wal.RecordType;
import dev.ledgerx.wal.Wal;
import dev.ledgerx.wal.WalRecovery;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A ledger you can trust across {@code kill -9}: ADR 0002's in-memory model with ADR 0003's log
 * underneath it, and the thing the record format and the fsync menu are for.
 *
 * <p><strong>The commit protocol, in the order it happens.</strong> One critical section holds
 * {@link ReentrantLock} (never {@code synchronized}, per ADR 0001's pinning rule), validates the
 * candidate against live state, appends the record, waits for its ack, and only then applies it to
 * the in-memory index. A refused transaction never reaches the log — a rejected posting in a log
 * would be a record that {@link Replay} must refuse at recovery, which is an outage instead of a
 * bug report. An acked transaction is never <em>not</em> applied, because the apply cannot fail:
 * ADR 0002's {@code validate}-then-{@code apply} split means everything fallible already ran.
 *
 * <p><strong>Recovery is checkpoint + tail, and the state it reaches is a digest.</strong> Opening
 * loads a checkpoint if there is one that survives being checked against the log, folds only the
 * records after its watermark, and — if the fold was long enough to be worth saving — writes
 * a new checkpoint atomically before returning. {@link #stateHash()} is SHA-256 over the canonical
 * serialization of the whole state, so "this crash lost nothing" is an equality between two digests
 * rather than an argument. ADR 0004 defines both.
 *
 * <p><strong>One real constraint this class inherits rather than chooses.</strong> The apply
 * happens
 * after the ack, so the in-memory state is briefly ahead of the log — a window one thread wide,
 * closed by the lock, and the reason a failed append cannot simply be returned to the caller: the
 * ledger would go on serving from memory the log has never heard of. So a storage failure is
 * terminal for this instance. It throws, and it refuses every later write, and the way back is to
 * reopen and replay. A production ledger would instead want the apply to happen after the
 * append and
 * before the ack, which needs the domain's validation exposed as a separate step; ADR 0003 §9
 * records that as the one thing this ticket had to ask of ADR 0002 and did not, and hands it to the
 * concurrency ticket, where the same critical section is also where per-account ordering lives.
 */
public final class DurableLedger implements AutoCloseable {

  /**
   * How many journal events a fold has to cover before a checkpoint is worth writing. Below this,
   * the snapshot costs more in fsyncs than it saves in replay, and a checkpoint every open would
   * turn recovery into a write.
   */
  public static final int MIN_CHECKPOINT_EVENTS = 8;

  private final Path directory;
  private final Wal wal;
  private final InMemoryLedger ledger;
  private final WalRecovery.Report recovery;
  private final CheckpointRecovery.Decision checkpointDecision;
  private final ReentrantLock commitLock = new ReentrantLock();

  private volatile IOException failure;
  private Wal.Ack lastAck;

  /**
   * The LSN of the last record applied to memory, and the LSN of the last record a completed force
   * covers. These two are the checkpoint's soundness condition: a snapshot may only be written when
   * they agree, because a snapshot of a record the log has not made durable is a snapshot of money
   * that a crash can still take away — and a recovery that trusts it would hand the money back.
   * They can only disagree under {@code NO_FSYNC}, which never forces and therefore never
   * checkpoints past the prefix it recovered.
   */
  private long appliedLsn;

  private long forcedLsn;

  private DurableLedger(
      Path directory,
      Wal wal,
      InMemoryLedger ledger,
      WalRecovery.Report recovery,
      CheckpointRecovery.Decision checkpointDecision) {
    this.directory = directory;
    this.wal = wal;
    this.ledger = ledger;
    this.recovery = recovery;
    this.checkpointDecision = checkpointDecision;
  }

  /**
   * Opens the ledger stored in {@code directory}: recover the log, cut a torn tail, load a
   * checkpoint if there is a usable one, replay whatever it left, and start accepting commits under
   * {@code policy}.
   */
  public static DurableLedger open(Path directory, FsyncPolicy policy) throws IOException {
    return open(directory, policy, true);
  }

  /**
   * The same, with recovery's repair switch exposed: {@code false} opens a torn log for inspection
   * only, which is what the harness uses to check a crash without editing the evidence.
   *
   * <p>Recovery is checkpoint + tail (ADR 0004): the checkpoint is validated against the log before
   * it is trusted, and a checkpoint that fails any check is discarded and the whole log folded
   * instead, so a corrupt or stale snapshot costs time and never costs money.
   */
  public static DurableLedger open(Path directory, FsyncPolicy policy, boolean repair)
      throws IOException {
    Wal wal = Wal.open(directory.resolve(Wal.FILE_NAME), policy, repair);
    CheckpointRecovery.Recovered recovered;
    try {
      recovered = CheckpointRecovery.recover(directory, wal.recovered());
    } catch (IOException | RuntimeException cannotReplay) {
      wal.close();
      throw cannotReplay;
    }
    DurableLedger opened =
        new DurableLedger(directory, wal, recovered.ledger(), wal.recovery(), recovered.decision());
    // Wal.open forced the recovered prefix before it returned, so every record in it is durable,
    // marker included: memory and the log agree on how far they go, which is the condition a
    // checkpoint needs.
    opened.appliedLsn = wal.nextLsn().value() - 1L;
    opened.forcedLsn = opened.appliedLsn;
    opened.maybeCheckpoint();
    return opened;
  }

  /**
   * Opens an account: appended and acked before it exists in memory, so the log never learns about
   * an account the ledger will not show.
   *
   * @throws IllegalArgumentException if the id is already open — checked before the append, so a
   *     duplicate opening costs the log nothing and leaves no trace in it
   */
  public Account openAccount(AccountId id, AccountKind kind) throws IOException {
    Objects.requireNonNull(id, "account id");
    Objects.requireNonNull(kind, "account kind");
    if (ledger.isKnown(id)) {
      throw new IllegalArgumentException("account already open: " + id + " (" + kind + ")");
    }
    commitLock.lock();
    try {
      requireHealthy();
      JournalEvent event = new JournalEvent.AccountOpened(new Account(id, kind));
      ack(EventCodec.typeOf(event), EventCodec.encode(event), wal.policy());
      return ledger.openAccount(id, kind);
    } finally {
      commitLock.unlock();
    }
  }

  /**
   * Posts a transaction: validate, append, ack, apply. The returned transaction is the one that is
   * durable, and it is durable before this method returns — under {@code PER_COMMIT} and
   * {@code GROUP_COMMIT} that means the device has it, and under {@code NO_FSYNC} it means the
   * kernel does, which is the whole content of "acknowledged" and the whole contract of this class.
   *
   * @throws dev.ledgerx.domain.RejectedTransactionException if the candidate breaks the grammar,
   *     with nothing appended and nothing changed, exactly as {@link InMemoryLedger#post} promises
   */
  public Transaction post(Transaction candidate) throws IOException {
    return post(candidate, wal.policy());
  }

  /** The ticket's headline case: two accounts, one record, one unit of commit. */
  public Transaction transfer(AccountId from, AccountId to, Money amount) throws IOException {
    return post(Transaction.transfer(from, to, amount));
  }

  /** Appends under an explicit policy, for a commit that wants a different bound than the log's. */
  public Transaction post(Transaction candidate, FsyncPolicy policy) throws IOException {
    Objects.requireNonNull(candidate, "candidate transaction");
    Objects.requireNonNull(policy, "fsync policy");
    commitLock.lock();
    try {
      requireHealthy();
      Transaction accepted = ledger.post(candidate);
      JournalEvent event = new JournalEvent.Posted(accepted);
      ack(EventCodec.typeOf(event), EventCodec.encode(event), policy);
      return accepted;
    } catch (IOException storageFailed) {
      failure = storageFailed;
      throw storageFailed;
    } finally {
      commitLock.unlock();
    }
  }

  /** The ledger behind the log: live balances, the event list, the auditor. */
  public InMemoryLedger ledger() {
    return ledger;
  }

  public Wal wal() {
    return wal;
  }

  /** What recovery found and did on the way in, including any tear it cut. */
  public WalRecovery.Report recovery() {
    return recovery;
  }

  /**
   * The ack of the last record appended: what the log promised, and through which byte offset. A
   * caller that has to prove the ack rule from outside — the harness, an operator — reads this.
   */
  public Wal.Ack lastAck() {
    return lastAck;
  }

  /** Counters for the write-amplification table: ADR 0003 §6 quotes these, not estimates. */
  public Wal.Stats stats() {
    return wal.stats();
  }

  /** Bytes a completed force covers, which is what "durable" means for every ack below it. */
  public long durableThrough() {
    return wal.durableThrough();
  }

  /**
   * The SHA-256 of this ledger's canonical state — the digest ADR 0004 defines and the one number
   * that makes "byte-identical after a crash" a checkable claim.
   *
   * <p>Computed on demand over the whole state rather than maintained incrementally, which is
   * O(accounts) per call. That is the honest cost of a prototype whose job is to prove the
   * property; the scale answer is a Merkle or rolling structure over the same canonical
   * rows, and it changes nothing about what is hashed.
   */
  public byte[] stateHash() {
    return StateHash.digest(ledger);
  }

  /** The same digest as lower-case hex, for a log line or a table. */
  public String stateHashHex() {
    return StateHash.hex(stateHash());
  }

  /** What recovery did with the checkpoint on disk: how a caller tells a miss from a hit. */
  public CheckpointRecovery.Decision checkpointDecision() {
    return checkpointDecision;
  }

  /** The directory holding {@code wal.log} and {@code checkpoint.ckpt}. */
  public Path directory() {
    return directory;
  }

  /**
   * Writes a checkpoint of the current state, atomically, and returns it.
   *
   * <p>Refuses rather than snapshots when memory is ahead of the durable log, which is the one
   * mistake a checkpoint can make that loses money: the watermark it would claim covers records a
   * crash could still discard, and a recovery that believed it would resurrect them. Under
   * {@code PER_COMMIT} and {@code GROUP_COMMIT} that never happens, because every applied
   * record was acked by a completed force; under {@code NO_FSYNC} it happens on the first append,
   * which is why
   * that policy never checkpoints past the prefix it recovered.
   *
   * @throws IOException if the state cannot be snapshotted soundly, or if the write fails
   */
  public Checkpoint checkpoint() throws IOException {
    commitLock.lock();
    try {
      requireHealthy();
      return writeCheckpoint();
    } finally {
      commitLock.unlock();
    }
  }

  private void ack(RecordType type, byte[] payload, FsyncPolicy policy) throws IOException {
    Wal.Ack acked = wal.appendSync(type, payload, policy);
    lastAck = acked;
    appliedLsn = acked.lsn().value();
    if (acked.forced()) {
      forcedLsn = appliedLsn;
    }
  }

  /**
   * Writes a checkpoint if this open needs one: none was usable, or the fold it left was long
   * enough that the next recovery should not pay it again.
   */
  private void maybeCheckpoint() throws IOException {
    long lastLsn = wal.nextLsn().value() - 1L;
    if (lastLsn < 1L || ledger.journalEvents() < MIN_CHECKPOINT_EVENTS) {
      return;
    }
    long coveredBy = checkpointDecision.used() ? checkpointDecision.checkpoint().lastLsn() : 0L;
    if (lastLsn - coveredBy < MIN_CHECKPOINT_EVENTS) {
      return;
    }
    writeCheckpoint();
  }

  /** Commits the snapshot itself. Caller holds the commit lock, so no append is in flight. */
  private Checkpoint writeCheckpoint() throws IOException {
    long lastLsn = wal.nextLsn().value() - 1L;
    long walBytes = wal.durableThrough();
    if (lastLsn < 1L) {
      throw new IOException("there is nothing to checkpoint: the log holds no records");
    }
    if (appliedLsn != forcedLsn) {
      throw new IOException(
          "memory holds LSN " + appliedLsn + " but only LSN " + forcedLsn
              + " is durable; a checkpoint of unforced records is a promise the log cannot keep,"
              + " which is what the NO_FSYNC policy is");
    }
    Checkpoint checkpoint =
        Checkpoint.of(lastLsn, walBytes, ledger.journalEvents(), ledger.state());
    CheckpointWriter.write(directory, checkpoint);
    return checkpoint;
  }

  private void requireHealthy() {
    IOException dead = failure;
    if (dead != null) {
      throw new IllegalStateException(
          "this DurableLedger stopped after a storage failure and will not keep serving from memory"
              + " the log has never seen; reopen it and replay",
          dead);
    }
  }

  /** Flushes what was written (a clean exit is not a crash) and releases the log. */
  @Override
  public void close() throws IOException {
    wal.close();
  }
}
