package dev.ledgerx.journal;

import dev.ledgerx.checkpoint.Checkpoint;
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
import java.nio.file.Files;
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
  private final Checkpoint.Loaded checkpointAtOpen;
  private final Path directory;
  private final ReentrantLock commitLock = new ReentrantLock();

  private volatile IOException failure;
  private Wal.Ack lastAck;

  private DurableLedger(
      Wal wal,
      InMemoryLedger ledger,
      WalRecovery.Report recovery,
      Checkpoint.Loaded checkpointAtOpen,
      Path directory) {
    this.wal = wal;
    this.ledger = ledger;
    this.recovery = recovery;
    this.checkpointAtOpen = checkpointAtOpen;
    this.directory = directory;
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
   *
   * <p>Recovery is checkpoint plus tail: the directory's checkpoint — validated against the
   * scan first, discarded if its own bytes are damaged, refused if it names coverage the log
   * cannot prove — is the fold's base, and every record after its watermark folds on top.
   * With no checkpoint this is exactly the from-scratch fold it always was.
   */
  public static DurableLedger open(Path directory, FsyncPolicy policy, boolean repair)
      throws IOException {
    // The temp half of a checkpoint swap is garbage however its writer died: deleted before
    // anything is read, so no recovery path can ever meet one.
    Checkpoint.cleanTemp(directory);
    Path file = directory.resolve(Wal.FILE_NAME);
    if (!Files.exists(directory.resolve(Checkpoint.FILE_NAME))) {
      // The common path, and the old one: no checkpoint, so the log is the whole story and
      // one scan inside Wal.open is all the reading there is.
      Wal wal = Wal.open(file, policy, repair);
      InMemoryLedger replayed;
      try {
        replayed = Replay.fold(wal.recovered(), null);
      } catch (IOException | RuntimeException cannotReplay) {
        wal.close();
        throw cannotReplay;
      }
      return new DurableLedger(wal, replayed, wal.recovery(), null, directory);
    }
    // With a checkpoint, the cross-check runs BEFORE the repairing open: a checkpoint whose
    // coverage the log cannot prove is a data-loss finding, and the evidence — the log
    // exactly as it lies — must be intact when the refusal says so. Wal.open would have cut
    // a torn tail and appended a marker before the fold ever ran, which is the right repair
    // on the way into a ledger and the wrong one on the way out of a refusal.
    WalRecovery.Scan scanned = WalRecovery.scan(file);
    Checkpoint.Loaded checkpoint = Checkpoint.load(directory, scanned);
    Wal wal = Wal.open(file, policy, repair);
    InMemoryLedger replayed;
    try {
      replayed = Replay.fold(wal.recovered(), checkpoint);
    } catch (IOException | RuntimeException cannotReplay) {
      wal.close();
      throw cannotReplay;
    }
    return new DurableLedger(wal, replayed, wal.recovery(), checkpoint, directory);
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

  /**
   * Writes a checkpoint of the current state: force the log through its last written byte
   * (ADR 0001 puts {@code force(true)} at checkpoint; ADR 0003 §10 states the ordering as a
   * rule — the bytes the watermark names are durable before the snapshot claiming them
   * exists), serialize the state, and swap the file into place atomically.
   *
   * <p>The whole thing runs under the commit lock, which is what makes the watermark and
   * the serialized state the same moment: no commit can append (and no apply can land)
   * between the force and the encode, so the state on disk is exactly the state the log
   * proves through {@code watermarkLsn} / {@code watermarkOffset}.
   *
   * @return the checkpoint as written, watermark and hash included
   */
  public Checkpoint.Loaded writeCheckpoint() throws IOException {
    commitLock.lock();
    try {
      requireHealthy();
      long watermarkOffset = wal.force();
      long watermarkLsn = wal.nextLsn().value() - 1L;
      if (watermarkOffset > wal.durableThrough()) {
        throw new IOException(
            "the checkpoint's watermark (byte " + watermarkOffset
                + ") is past the offset the log's force covers (" + wal.durableThrough()
                + ") — refusing to claim durability the log did not promise");
      }
      byte[] state = StateHash.encode(ledger);
      return Checkpoint.write(directory, watermarkLsn, watermarkOffset, state);
    } finally {
      commitLock.unlock();
    }
  }

  /**
   * The checkpoint this open loaded and used, or {@code null} when there was none — which
   * includes the case of one that failed its own integrity checks and was discarded, since
   * the discard falls back to from-scratch replay. A caller that must distinguish those
   * looks for {@code checkpoint.rejected} beside the log.
   */
  public Checkpoint.Loaded checkpointAtOpen() {
    return checkpointAtOpen;
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
