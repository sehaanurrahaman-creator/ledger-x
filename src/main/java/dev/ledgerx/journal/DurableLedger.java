package dev.ledgerx.journal;

import dev.ledgerx.checkpoint.Checkpoint;
import dev.ledgerx.checkpoint.CheckpointListener;
import dev.ledgerx.checkpoint.CheckpointPolicy;
import dev.ledgerx.checkpoint.CheckpointStore;
import dev.ledgerx.checkpoint.LedgerState;
import dev.ledgerx.domain.Account;
import dev.ledgerx.domain.AccountId;
import dev.ledgerx.domain.AccountKind;
import dev.ledgerx.domain.BoundKey;
import dev.ledgerx.domain.IdempotencyKey;
import dev.ledgerx.domain.InMemoryLedger;
import dev.ledgerx.domain.JournalEvent;
import dev.ledgerx.domain.KeyBinding;
import dev.ledgerx.domain.MerchantId;
import dev.ledgerx.domain.Money;
import dev.ledgerx.domain.RequestFingerprint;
import dev.ledgerx.domain.Transaction;
import dev.ledgerx.idempotency.IdempotentReceipt;
import dev.ledgerx.idempotency.IdempotencyConflictException;
import dev.ledgerx.idempotency.IdempotencyExpiredException;
import dev.ledgerx.idempotency.IdempotencyPolicy;
import dev.ledgerx.wal.Corruption;
import dev.ledgerx.wal.FsyncPolicy;
import dev.ledgerx.wal.RecordType;
import dev.ledgerx.wal.UnrecoverableLogException;
import dev.ledgerx.wal.Wal;
import dev.ledgerx.wal.WalFormat;
import dev.ledgerx.wal.WalRecovery;
import dev.ledgerx.wal.WalRecord;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
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
  private final IdempotencyPolicy idempotency;
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
      IdempotencyPolicy idempotency,
      CheckpointStore.Load checkpointLoad,
      int replayedRecords,
      long lastJournalLsn,
      long lastJournalEnd) {
    this.wal = wal;
    this.ledger = ledger;
    this.recovery = recovery;
    this.checkpointStore = checkpointStore;
    this.checkpointPolicy = checkpointPolicy;
    this.idempotency = idempotency;
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
    return open(directory, policy, true, CheckpointPolicy.DEFAULT, IdempotencyPolicy.system());
  }

  /**
   * The same, with recovery's repair switch exposed: {@code false} opens a torn log for inspection
   * only, which is what the harness uses to check a crash without editing the evidence.
   */
  public static DurableLedger open(Path directory, FsyncPolicy policy, boolean repair)
      throws IOException {
    return open(directory, policy, repair, CheckpointPolicy.DEFAULT, IdempotencyPolicy.system());
  }

  /**
   * The same, with the checkpoint cadence exposed and idempotency at its production default.
   */
  public static DurableLedger open(
      Path directory, FsyncPolicy policy, boolean repair, CheckpointPolicy checkpoints)
      throws IOException {
    return open(directory, policy, repair, checkpoints, IdempotencyPolicy.system());
  }

  /**
   * The full open: the fsync policy, recovery's repair switch, the checkpoint cadence, and the
   * idempotency policy — which clock to consult, and how long a stored response is replayed
   * before it is refused.
   *
   * <p><strong>Bindings are seeded in three moves.</strong> A usable checkpoint's state section
   * holds the identity rows; its timing section holds their capture instants; and each row's
   * {@code responseLsn} names the record that carries the stored response, which is re-read from
   * the log rather than duplicated into the checkpoint — the log is the history, the checkpoint
   * is a cache, and a response the log still holds is never copied beside it (ADR 0005 §6). The
   * tail is folded afterwards, exactly as before: a keyed record above the watermark binds through
   * the fold, and a duplicate across the two would be a log this build did not write, which the
   * fold refuses.
   */
  public static DurableLedger open(
      Path directory,
      FsyncPolicy policy,
      boolean repair,
      CheckpointPolicy checkpoints,
      IdempotencyPolicy idempotency)
      throws IOException {
    Objects.requireNonNull(directory, "ledger directory");
    Objects.requireNonNull(checkpoints, "checkpoint policy");
    Objects.requireNonNull(idempotency, "idempotency policy");
    Wal wal = Wal.open(directory.resolve(Wal.FILE_NAME), policy, repair);
    CheckpointStore store = new CheckpointStore(directory);
    CheckpointStore.Load load;
    InMemoryLedger replayed;
    int tail;
    try {
      load = store.load(wal.file(), wal.recovered());
      if (load.used()) {
        Checkpoint checkpoint = load.checkpoint();
        replayed = checkpoint.state().restore();
        List<KeyBinding> bindings = checkpoint.state().bindings();
        List<Long> instants = checkpoint.captureInstants();
        for (int i = 0; i < bindings.size(); i++) {
          KeyBinding binding = bindings.get(i);
          replayed.restoreBinding(
              binding, instants.get(i), storedResponse(wal.recovered(), binding.responseLsn()));
        }
        tail = Replay.foldAfter(checkpoint.lastLsn(), wal.recovered(), replayed);
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
        wal, replayed, wal.recovery(), store, checkpoints, idempotency, load, tail, lastLsn,
        lastEnd);
  }

  /**
   * The stored response of a binding, re-read from the record that made it — dense LSNs make the
   * record list an array indexed by position, which is the one place this repository gets random
   * access for free.
   *
   * <p>Every claim is checked, not assumed: the record at that index must carry exactly that LSN
   * and be an {@code IDEMPOTENT_POSTING}, or the checkpoint and the log disagree about which
   * record made a binding — a disagreement that cannot come from a crash, only from damage, and
   * damage is refused rather than folded around ({@code DOMAIN_REJECTED}, the same refusal the
   * replay itself would make).
   */
  private static Transaction storedResponse(WalRecovery.Scan scan, long responseLsn)
      throws IOException {
    List<WalRecord> records = scan.records();
    if (responseLsn < 1L || responseLsn > records.size()) {
      throw new UnrecoverableLogException(
          Corruption.DOMAIN_REJECTED,
          -1L,
          null,
          "a binding names lsn " + responseLsn + " and the clean prefix holds "
              + records.size() + " records",
          null);
    }
    WalRecord record = records.get((int) (responseLsn - 1L));
    if (record.lsn().value() != responseLsn || record.type() != RecordType.IDEMPOTENT_POSTING) {
      throw new UnrecoverableLogException(
          Corruption.DOMAIN_REJECTED,
          -1L,
          record.lsn(),
          "a binding names lsn " + responseLsn + " as its keyed posting, and the record there"
              + " is a " + record.type() + " at lsn " + record.lsn().value(),
          null);
    }
    try {
      JournalEvent event =
          EventCodec.decode(record.type(), record.payloadUnsafe(), record.lsn().value());
      return ((JournalEvent.PostedIdempotently) event).transaction();
    } catch (IOException malformed) {
      throw new UnrecoverableLogException(
          Corruption.DOMAIN_REJECTED,
          -1L,
          record.lsn(),
          "the stored response at lsn " + responseLsn + " does not decode: " + malformed,
          malformed);
    }
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
   * Posts a transaction under an idempotency key: the whole of the charter's key contract, in one
   * critical section. ADR 0005 §2, as code.
   *
   * <p>The decision tree, in the order it is evaluated — an order the ADR fixes, because each
   * branch's outcome must not depend on the ones below it:
   *
   * <ol>
   *   <li><strong>Unbound key:</strong> check the candidate against the domain, commit one record
   *       carrying both the key material and the entries (one append, one force, one ack), apply
   *       it, and return the receipt with {@code replayed = false}. A refused candidate leaves no
   *       trace — nothing appended, no binding, the key still usable.
   *   <li><strong>Bound, different body:</strong> {@link IdempotencyConflictException} — the 409,
   *       unconditionally, at any age, with nothing changed. This is checked before expiry on
   *       purpose: a client bug is louder than an old response, and the louder finding is the one
   *       a caller must see.
   *   <li><strong>Bound, same body, within retention:</strong> the stored response, with
   *       {@code replayed = true} and the original's LSN — no append, no money movement, no state
   *       change at all.
   *   <li><strong>Bound, same body, past retention:</strong>
   *       {@link IdempotencyExpiredException} — refused, never replayed, never re-executed.
   * </ol>
   *
   * <p><strong>The ordering inside the critical section is the atomicity argument.</strong> The
   * binding check, the body check, the capture instant and the append all happen under the one
   * lock, so a duplicate — concurrent or retried — observes either "unbound" or "bound", never
   * "half-bound": there is no in-flight state to be in, which is why ledger-x's 409 means exactly
   * one thing (different body) and never Stripe's "in-flight, retry me". A hundred concurrent
   * retries of one key serialize here; one binds, ninety-nine replay, none re-post — the
   * duplicate
   * storm, by construction rather than by lock contention.
   *
   * <p><strong>This path applies after the ack</strong>, where {@link #post} applies before the
   * append: the binding row names the LSN of its own record, a number that exists only once the
   * append is acknowledged, and ADR 0003 §9's asked-for validate/apply split is what makes the
   * sequencing legal ({@link InMemoryLedger#check} then {@link InMemoryLedger#postIdempotently}).
   * A failure to apply after a successful ack is terminal for this instance — the log would hold
   * money memory has not — and is marked exactly like a storage failure.
   *
   * @throws IdempotencyConflictException same key, different body — at any age, nothing changed
   * @throws IdempotencyExpiredException same key and body, past retention — refused, nothing
   *     changed
   * @throws dev.ledgerx.domain.RejectedTransactionException an unbound key whose candidate breaks
   *     the grammar, with nothing appended and no binding made
   */
  public IdempotentReceipt postIdempotent(
      MerchantId merchant, IdempotencyKey key, Transaction candidate) throws IOException {
    return postIdempotent(merchant, key, candidate, wal.policy());
  }

  /** The same, under an explicit fsync policy — a payout intent demanding its own force. */
  public IdempotentReceipt postIdempotent(
      MerchantId merchant, IdempotencyKey key, Transaction candidate, FsyncPolicy policy)
      throws IOException {
    Objects.requireNonNull(merchant, "merchant");
    Objects.requireNonNull(key, "idempotency key");
    Objects.requireNonNull(candidate, "candidate transaction");
    Objects.requireNonNull(policy, "fsync policy");
    commitLock.lock();
    try {
      requireHealthy();
      long now = idempotency.clock().millis();
      BoundKey bound = ledger.boundKey(merchant, key);
      if (bound != null) {
        RequestFingerprint presented = EventCodec.fingerprintOf(candidate);
        if (!presented.equals(bound.binding().fingerprint())) {
          throw new IdempotencyConflictException(
              merchant, key, bound.binding().fingerprint(), presented);
        }
        if (now - bound.capturedAtMillis() < idempotency.retentionMillis()) {
          return new IdempotentReceipt(
              merchant,
              key,
              bound.response(),
              true,
              bound.binding().responseLsn(),
              bound.capturedAtMillis());
        }
        throw new IdempotencyExpiredException(
            merchant, key, bound.capturedAtMillis(), idempotency.retentionMillis());
      }
      ledger.check(candidate);
      RequestFingerprint fingerprint = EventCodec.fingerprintOf(candidate);
      long capturedAt = now;
      JournalEvent event =
          new JournalEvent.PostedIdempotently(merchant, key, fingerprint, capturedAt, candidate);
      Wal.Ack acked = ack(EventCodec.typeOf(event), EventCodec.encode(event), policy);
      try {
        ledger.postIdempotently(
            merchant, key, fingerprint, capturedAt, acked.lsn().value(), candidate);
      } catch (RuntimeException applyBroke) {
        // The record is durable and memory does not have it: the one state this class cannot
        // serve from, and the reason the instance stops here rather than hoping the next
        // operation repairs what it cannot.
        failure =
            new IOException(
                "the keyed posting at lsn " + acked.lsn().value()
                    + " was acknowledged but could not be applied; this instance will not"
                    + " serve a memory the log disagrees with — reopen and replay",
                applyBroke);
        throw applyBroke;
      }
      afterCommit();
      return new IdempotentReceipt(
          merchant, key, candidate, false, acked.lsn().value(), capturedAt);
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
    List<Long> instants = new ArrayList<>(state.bindingCount());
    for (KeyBinding binding : state.bindings()) {
      instants.add(ledger.boundKey(binding.merchant(), binding.key()).capturedAtMillis());
    }
    Checkpoint written =
        Checkpoint.of(state, instants, lastJournalEnd, wal.file());
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

  private Wal.Ack ack(RecordType type, byte[] payload, FsyncPolicy policy) throws IOException {
    Wal.Ack acked = wal.appendSync(type, payload, policy);
    lastAck = acked;
    if (type.isJournalEvent()) {
      lastJournalLsn = acked.lsn().value();
      lastJournalEnd = acked.end();
    }
    return acked;
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
