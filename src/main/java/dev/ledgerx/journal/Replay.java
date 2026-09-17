package dev.ledgerx.journal;

import dev.ledgerx.domain.AccountState;
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
 * ADR 0004 turned that from an equality into a digest: {@link #foldFrom} continues a fold from a
 * checkpoint, and the state it reaches has to hash identically to a fold that started at byte 0.
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
   * Folds a tail on top of a checkpoint's baseline: the recovery path ADR 0004 buys.
   *
   * <p>It is the same fold as {@link #foldEvents} and deliberately has no rule of its own — no
   * merge, no "reconcile the snapshot against the log", no compensation. A checkpoint is a state
   * the log already produced, so continuing the fold from it is indistinguishable from having
   * folded all the way, which is the entire claim the checkpoint-and-replay harness tests.
   *
   * <p>The tail's records are re-validated exactly as a from-scratch fold validates them, so a
   * checkpoint cannot smuggle in a transaction the ledger would refuse: a refusal is still
   * {@link Corruption#DOMAIN_REJECTED}, and it is still fatal.
   *
   * @param baseline the accounts and balances the checkpoint covered, in any order
   * @param baselineEvents how many journal events it took to reach them
   * @param tail the records after the checkpoint's watermark, in log order
   */
  public static InMemoryLedger foldFrom(
      List<AccountState> baseline, long baselineEvents, List<WalRecord> tail) throws IOException {
    InMemoryLedger ledger = new InMemoryLedger(baseline, baselineEvents);
    for (WalRecord record : tail) {
      apply(ledger, record);
    }
    ledger.audit();
    return ledger;
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
