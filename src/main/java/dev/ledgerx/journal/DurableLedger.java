package dev.ledgerx.journal;

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

  private final Wal wal;
  private final InMemoryLedger ledger;
  private final WalRecovery.Report recovery;
  private final ReentrantLock commitLock = new ReentrantLock();

  private volatile IOException failure;
  private Wal.Ack lastAck;

  private DurableLedger(Wal wal, InMemoryLedger ledger, WalRecovery.Report recovery) {
    this.wal = wal;
    this.ledger = ledger;
    this.recovery = recovery;
  }

  /**
   * Opens the ledger stored in {@code directory}: recover the log, cut a torn tail, replay the
   * clean prefix into a fresh ledger, and start accepting commits under {@code policy}.
   */
  public static DurableLedger open(Path directory, FsyncPolicy policy) throws IOException {
    return open(directory, policy, true);
  }

  /**
   * The same, with recovery's repair switch exposed: {@code false} opens a torn log for inspection
   * only, which is what the harness uses to check a crash without editing the evidence.
   */
  public static DurableLedger open(Path directory, FsyncPolicy policy, boolean repair)
      throws IOException {
    Wal wal = Wal.open(directory.resolve(Wal.FILE_NAME), policy, repair);
    InMemoryLedger replayed;
    try {
      replayed = Replay.fold(wal.recovered());
    } catch (IOException | RuntimeException cannotReplay) {
      wal.close();
      throw cannotReplay;
    }
    return new DurableLedger(wal, replayed, wal.recovery());
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

  private void ack(RecordType type, byte[] payload, FsyncPolicy policy) throws IOException {
    Wal.Ack acked = wal.appendSync(type, payload, policy);
    lastAck = acked;
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
