package dev.ledgerx.journal;

import dev.ledgerx.checkpoint.Checkpoint;
import dev.ledgerx.checkpoint.CheckpointListener;
import dev.ledgerx.checkpoint.CheckpointPolicy;
import dev.ledgerx.checkpoint.CheckpointStore;
import dev.ledgerx.checkpoint.LedgerState;
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
import dev.ledgerx.wal.WalFormat;
import dev.ledgerx.wal.WalRecovery;
import dev.ledgerx.wal.WalRecord;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A ledger you can trust across {@code kill -9}: ADR 0002's in-memory model with ADR 0003's log
 * underneath it, and — since ADR 0004 — a checkpoint above it.
 *
 * <p><strong>The commit protocol, in the order it happens.</strong> One critical section holds
 * {@link ReentrantLock} (never {@code synchronized}, per ADR 0001's pinning rule), validates the
 * candidate against live state, appends the record, waits for its ack, and only then applies it to
 * the in-memory index. A refused transaction never reaches the log — a rejected posting in a log
 * would be a record that {@link Replay} must refuse at recovery, which is an outage instead of a
 * bug report. An acked transaction is never <em>not</em> applied, because the apply cannot fail:
 * ADR 0002's {@code validate}-then-{@code apply} split means everything fallible already ran.
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
 *
 * <p><strong>Recovery is a checkpoint plus a tail, and the tail is the log's.</strong> Opening
 * scans the log exactly as it did before this ticket — a tear is cut, a marker records the
 * cut — and then asks the checkpoint store for the newest checkpoint that stands up against it.
 * A usable checkpoint seeds the ledger ({@link LedgerState#restore()}) and the fold starts at its
 * watermark ({@link Replay#foldAfter}); an unusable one changes nothing, because a checkpoint is a
 * cache of a fold and the log is the truth. Either way the ledger audits before it serves, so a
 * recovery that produced a state which disagrees with its own index refuses to open rather than
 * print a wrong balance.
 *
 * <p><strong>A checkpoint is taken inside the commit lock, after the ack.</strong> The state and
 * the position it claims must describe the same instant, and the lock is the only thing that makes
 * that true; it also means a checkpoint never sees an applied-but-unacked record, which is what
 * makes its coverage claim a claim about durable bytes. The bytes are then forced before anything
 * is written ({@link Wal#forceData()}), for the ordering rule ADR 0003 §10 handed over.
 * A <em>failed</em> checkpoint is not a failed ledger: nothing in memory changed, the log is
 * untouched, and the cache simply was not refreshed — so the commit that triggered an automatic
 * checkpoint still returns its transaction, and the failure is counted rather than thrown. A
 * caller that asks for a checkpoint explicitly gets the exception, because a caller that asks
 * deserves an answer.
 */
public final class DurableLedger implements AutoCloseable {

  private final Wal wal;
  private final InMemoryLedger ledger;
  private final WalRecovery.Report recovery;
  private final CheckpointStore checkpointStore;
  private final CheckpointPolicy checkpointPolicy;
  private final CheckpointStore.Load checkpointLoad;
  private final int replayedRecords;
  private final ReentrantLock commitLock = new ReentrantLock();

  private volatile IOException failure;
  private volatile CheckpointListener checkpointListener = CheckpointListener.NONE;
  private Wal.Ack lastAck;

  /** The LSN of the last journal record this ledger's state includes; 0 before the first one. */
  private long lastJournalLsn;

  /** The byte offset just past that record's frame, which is the coverage a checkpoint claims. */
  private long lastJournalEnd = WalFormat.SEGMENT_HEADER_BYTES;

  private int commitsSinceCheckpoint;
  private long checkpointsWritten;
  private long checkpointFailures;
  private String lastCheckpointFailure;

  private DurableLedger(
      Wal wal,
      InMemoryLedger ledger,
      WalRecovery.Report recovery,
      CheckpointStore checkpointStore,
      CheckpointPolicy checkpointPolicy,
      CheckpointStore.Load checkpointLoad,
      int replayedRecords,
      long lastJournalLsn,
      long lastJournalEnd) {
    this.wal = wal;
    this.ledger = ledger;
    this.recovery = recovery;
    this.checkpointStore = checkpointStore;
    this.checkpointPolicy = checkpointPolicy;
    this.checkpointLoad = checkpointLoad;
    this.replayedRecords = replayedRecords;
    this.lastJournalLsn = lastJournalLsn;
    this.lastJournalEnd = lastJournalEnd;
  }

  /**
   * Opens the ledger stored in {@code directory}: recover the log, cut a torn tail, replay the
   * clean prefix into a fresh ledger, and start accepting commits under {@code policy}.
   *
   * <p>Checkpoints are taken automatically under {@link CheckpointPolicy#DEFAULT}. The overload
   * that takes a policy is how a caller chooses otherwise, and {@link CheckpointPolicy#MANUAL} is
   * how it says "only when I ask".
   */
  public static DurableLedger open(Path directory, FsyncPolicy policy) throws IOException {
    return open(directory, policy, true, CheckpointPolicy.DEFAULT);
  }

  /**
   * The same, with recovery's repair switch exposed: {@code false} opens a torn log for inspection
   * only, which is what the harness uses to check a crash without editing the evidence.
   */
  public static DurableLedger open(Path directory, FsyncPolicy policy, boolean repair)
      throws IOException {
    return open(directory, policy, repair, CheckpointPolicy.DEFAULT);
  }

  /** The full open: the fsync policy, recovery's repair switch, and the checkpoint cadence. */
  public static DurableLedger open(
      Path directory, FsyncPolicy policy, boolean repair, CheckpointPolicy checkpoints)
      throws IOException {
    Objects.requireNonNull(directory, "ledger directory");
    Objects.requireNonNull(checkpoints, "checkpoint policy");
    Wal wal = Wal.open(directory.resolve(Wal.FILE_NAME), policy, repair);
    CheckpointStore store = new CheckpointStore(directory);
    CheckpointStore.Load load;
    InMemoryLedger replayed;
    int tail;
    try {
      load = store.load(wal.file(), wal.recovered());
      if (load.used()) {
        replayed = load.checkpoint().state().restore();
        tail = Replay.foldAfter(load.checkpoint().lastLsn(), wal.recovered(), replayed);
      } else {
        replayed = Replay.fold(wal.recovered());
        tail = replayed.size();
      }
    } catch (IOException | RuntimeException cannotReplay) {
      wal.close();
      throw cannotReplay;
    }
    long lastLsn = Replay.lastJournalLsn(wal.recovered());
    long lastEnd =
        lastLsn == 0L ? WalFormat.SEGMENT_HEADER_BYTES : wal.recovered().endOf(lastLsn);
    return new DurableLedger(
        wal, replayed, wal.recovery(), store, checkpoints, load, tail, lastLsn, lastEnd);
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
      Account opened = ledger.openAccount(id, kind);
      afterCommit();
      return opened;
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
      afterCommit();
      return accepted;
    } catch (IOException storageFailed) {
      failure = storageFailed;
      throw storageFailed;
    } finally {
      commitLock.unlock();
    }
  }

  /**
   * Takes a checkpoint of the state as it stands: the state hash of a run, the number a crash's
   * recovery has to reproduce.
   *
   * <p>The order is the ticket's: force the log through the coverage first, build the state and the
   * bindings, then write the file through the store's swap (temp, force, rename, sync the
   * directory, collect). The whole thing happens under the commit lock, so the watermark and the
   * state describe the same instant, and no append can slip in between them.
   *
   * @return the checkpoint that is now on disk
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

  /** The commit-lock-held body, shared by {@link #checkpoint()} and the automatic cadence. */
  private Checkpoint writeCheckpoint() throws IOException {
    // The ordering rule of ADR 0003 §10, in one line: a snapshot may only claim bytes that are
    // already durable, and under NO_FSYNC this is the only force those bytes will ever get.
    wal.forceData();
    LedgerState state = LedgerState.of(ledger, lastJournalLsn);
    Checkpoint written =
        Checkpoint.of(state, lastJournalEnd, wal.file());
    checkpointStore.write(written, checkpointPolicy.retain(), checkpointListener);
    commitsSinceCheckpoint = 0;
    checkpointsWritten++;
    return written;
  }

  /**
   * The cadence, after a commit has been acked and applied. An automatic checkpoint that fails does
   * not fail the commit that triggered it: the money is durable, the checkpoint is not the money,
   * and the failure is counted for whoever is reading the counters.
   */
  private void afterCommit() {
    commitsSinceCheckpoint++;
    if (!checkpointPolicy.due(commitsSinceCheckpoint)) {
      return;
    }
    try {
      writeCheckpoint();
    } catch (IOException couldNotCheckpoint) {
      checkpointFailures++;
      lastCheckpointFailure = couldNotCheckpoint.getMessage();
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

  /** What the checkpoint store found on the way in: which file, or none, and what it refused. */
  public CheckpointStore.Load checkpointLoad() {
    return checkpointLoad;
  }

  /** How many journal records the fold applied on the way in — the whole log, or just a tail. */
  public int replayedRecords() {
    return replayedRecords;
  }

  /**
   * The state hash of the ledger as it stands: SHA-256 over the canonical state at
   * {@link #lastJournalLsn()}, in the exact form ADR 0004 §3 defines.
   *
   * <p>Compare it to the hash a recovered ledger prints, or to the hash inside a checkpoint file.
   * Equality is the claim: same accounts, same kinds, same order, same balances, same position in
   * the log — not "equivalent money", the same bytes.
   */
  public String stateHash() {
    return LedgerState.of(ledger, lastJournalLsn).stateHash();
  }

  /** The state itself, for a caller that wants to compare more than a hash. */
  public LedgerState state() {
    return LedgerState.of(ledger, lastJournalLsn);
  }

  /** The LSN of the last journal record this ledger's state includes; 0 before the first. */
  public long lastJournalLsn() {
    return lastJournalLsn;
  }

  /** The offset just past that record's frame, which a checkpoint of this state would cover. */
  public long lastJournalEnd() {
    return lastJournalEnd;
  }

  /** How many checkpoints this instance has written, automatic ones included. */
  public long checkpointsWritten() {
    return checkpointsWritten;
  }

  /** How many automatic checkpoints failed. Non-zero means a stale cache, not lost money. */
  public long checkpointFailures() {
    return checkpointFailures;
  }

  /** The last automatic checkpoint's failure message, or {@code null}. */
  public String lastCheckpointFailure() {
    return lastCheckpointFailure;
  }

  /**
   * Watches the swap's stages. One listener, last one wins, and it runs on the committing thread
   * inside the commit lock — an operator's timer or a harness's crash point, and nothing else.
   */
  public void onCheckpointStage(CheckpointListener listener) {
    this.checkpointListener = listener == null ? CheckpointListener.NONE : listener;
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

  private void ack(RecordType type, byte[] payload, FsyncPolicy policy) throws IOException {
    Wal.Ack acked = wal.appendSync(type, payload, policy);
    lastAck = acked;
    if (type.isJournalEvent()) {
      lastJournalLsn = acked.lsn().value();
      lastJournalEnd = acked.end();
    }
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

  /** Every record the recovery scan validated, for a caller that wants to fold or hash it again. */
  public List<WalRecord> recoveredRecords() {
    return wal.recovered().records();
  }
}
