package dev.ledgerx.journal;

import dev.ledgerx.domain.InMemoryLedger;
import dev.ledgerx.domain.JournalEvent;
import dev.ledgerx.domain.RejectedTransactionException;
import dev.ledgerx.wal.Corruption;
import dev.ledgerx.wal.RecordType;
import dev.ledgerx.wal.UnrecoverableLogException;
import dev.ledgerx.wal.WalRecovery;
import dev.ledgerx.wal.WalRecord;
import java.io.IOException;
import java.util.List;

/**
 * Replay: the clean prefix the log vouches for, folded into a ledger.
 *
 * <p>This is the half of durability the record format buys. ADR 0002 proved that its
 * {@code InMemoryLedger} is a fold over an event list; ADR 0003's claim is that the log <em>is</em>
 * that list, so recovery is the same fold over the same events and needs no rule of its own — no
 * merge, no compensation, no "fix up the balances after a crash" step, because there is nothing
 * besides the fold. What recovery adds is re-validation, below.
 *
 * <p><strong>Recovery validates, and a refusal is a crash-level finding.</strong> A fold that
 * skipped validation would happily apply a transaction the domain forbids, and the log would then
 * disagree with every invariant ADR 0002 proved — which is worse than refusing to open,
 * because at
 * least a refusal cannot print a wrong balance. So a {@code POSTED} record whose transaction the
 * ledger would reject is {@link Corruption#DOMAIN_REJECTED}: the bytes are intact (the CRC says
 * so), the ledger says they should not be, and the one reading is that something upstream of this
 * class is broken. This is also the check that makes the format's opaqueness safe: the log does not
 * understand records, so somebody must, and it is here.
 *
 * <p>Determinism, which is the destination the map names, is a property of this fold and not of the
 * log: same events in, same ledger out, in the same order, with no clock, no map iteration order,
 * no random and no float anywhere in the path. Two folds of one clean prefix produce equal
 * {@code events()} lists and equal balances, and {@code WalContract} asserts it by folding twice.
 */
public final class Replay {

  private Replay() {}

  /** Folds a scan's ledger events, markers skipped, into a fresh ledger. */
  public static InMemoryLedger fold(WalRecovery.Scan scan) throws IOException {
    return foldEvents(scan.journalRecords());
  }

  /** Folds records in log order. The list is the log; nothing here re-sorts or de-duplicates. */
  public static InMemoryLedger foldEvents(List<WalRecord> records) throws IOException {
    InMemoryLedger ledger = new InMemoryLedger();
    for (WalRecord record : records) {
      apply(ledger, record);
    }
    ledger.audit();
    return ledger;
  }

  /**
   * Folds the records <em>after</em> a checkpoint's watermark into a ledger a checkpoint seeded.
   *
   * <p>This is the second half of "recovery = checkpoint + tail replay" and it is deliberately the
   * same fold as {@link #foldEvents}, started later: the same validation, the same refusal of a
   * posting the domain rejects, the same rule that a marker moves no money. What a checkpoint
   * changes is where the fold starts, not what it does — which is why a checkpoint + tail and a
   * replay from byte zero can be compared as one equality on the state hash rather than as a
   * proof that two code paths agree.
   *
   * <p>Records at or below the watermark are skipped, not re-applied: re-applying a posting would
   * move money twice, and the watermark is exactly the boundary that says which side of the
   * checkpoint an event is on. Markers above it are skipped by the fold itself, as always.
   *
   * @param checkpointLsn the watermark of the state {@code ledger} was seeded with
   * @return how many journal records were applied, which is the tail's real length
   */
  public static int foldAfter(long checkpointLsn, WalRecovery.Scan scan, InMemoryLedger ledger)
      throws IOException {
    int applied = 0;
    for (WalRecord record : scan.records()) {
      if (record.lsn().value() <= checkpointLsn) {
        continue;
      }
      if (record.type().isJournalEvent()) {
        applied++;
      }
      apply(ledger, record);
    }
    ledger.audit();
    return applied;
  }

  /**
   * The LSN of the last journal event a scan holds, 0 if it holds none.
   *
   * <p>It is the ledger's position as opposed to the log's: the recovery marker ADR 0003 appends
   * after cutting a tear carries an LSN and moves no money, so a state's watermark is the last
   * <em>journal</em> record, not the last frame in the file. That distinction is what keeps a state
   * hash stable across a recovery: cutting a tear and marking the cut does not change any money,
   * and it must not change the number a run is identified by.
   */
  public static long lastJournalLsn(WalRecovery.Scan scan) {
    long last = 0L;
    for (WalRecord record : scan.records()) {
      if (record.type().isJournalEvent()) {
        last = record.lsn().value();
      }
    }
    return last;
  }

  /**
   * Applies one record to a ledger, or refuses the log.
   *
   * @return the event that was applied, so a caller can log or check it
   */
  public static JournalEvent apply(InMemoryLedger ledger, WalRecord record) throws IOException {
    if (!record.type().isJournalEvent()) {
      return null;
    }
    JournalEvent event =
        EventCodec.decode(record.type(), record.payloadUnsafe(), record.lsn().value());
    if (event instanceof JournalEvent.AccountOpened opened) {
      if (ledger.isKnown(opened.account().id())) {
        throw new UnrecoverableLogException(
            Corruption.DOMAIN_REJECTED,
            -1L,
            record.lsn(),
            "the log opens " + opened.account().id() + " twice, which no writer of this format"
                + " can do, so the log is not what this build wrote",
            null);
      }
      ledger.openAccount(opened.account().id(), opened.account().kind());
      return event;
    }
    try {
      ledger.post(((JournalEvent.Posted) event).transaction());
    } catch (RejectedTransactionException refused) {
      throw new UnrecoverableLogException(
          Corruption.DOMAIN_REJECTED,
          -1L,
          record.lsn(),
          "a " + record.type() + " record the ledger refuses: " + refused.getMessage(),
          refused);
    }
    return event;
  }

  /** The record type an event would be appended as — the codec's inverse, for callers. */
  public static RecordType typeOf(JournalEvent event) {
    return EventCodec.typeOf(event);
  }
}
