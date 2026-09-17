package dev.ledgerx.checkpoint;

import dev.ledgerx.domain.AccountState;
import dev.ledgerx.domain.InMemoryLedger;
import dev.ledgerx.journal.Replay;
import dev.ledgerx.wal.WalRecovery;
import dev.ledgerx.wal.WalRecord;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Deciding what a checkpoint is worth, and folding the tail on top of the ones that are.
 *
 * <p><strong>Recovery is checkpoint + tail, and the tail is defined by one number.</strong> A
 * checkpoint that survives its checks says "everything through LSN <em>k</em> is folded in, and it
 * ends at byte <em>b</em> of the log". Recovery then folds exactly the records with an LSN above
 * <em>k</em>. Because ADR 0003 makes LSNs dense from 1 within a surviving prefix, "above
 * <em>k</em>" and "past byte <em>b</em>" are the same set — and recovery <em>checks</em> that
 * they are, which
 * is what makes the two numbers a watermark rather than a hope.
 *
 * <p><strong>Three rules, in the order they are applied, each with the failure it exists
 * for.</strong>
 *
 * <ul>
 *   <li><strong>The file must parse and self-check</strong> (magic, version, CRC, SHA-256, a
 *       payload that fills exactly what it claims). A checkpoint written by a crash or a bad device
 *       here, and the answer is to fold the log from byte 0 — slow, correct, and the reason a
 *       checkpoint is allowed to be a cache at all.
 *   <li><strong>The state must be a possible one.</strong> Σ balances = 0 is invariant, so a
 *       baseline that does not sum to zero describes a ledger this log could not have produced.
 *   <li><strong>The watermark must still be true of the log.</strong> The claimed byte offset must
 *       not be past the log's clean prefix, the claimed LSN must not be past the last surviving
 *       record, and — the load-bearing one — the claimed offset must be exactly where that
 *       record ends <em>now</em>. ADR 0003 §2 warns that LSNs are unique only within a surviving
 *       prefix, so
 *       a log that was cut and then regrown hands out the same LSNs to different records. Without
 *       this check a checkpoint from before the cut would be accepted, its tail would start at an
 *       LSN the log has since reused, and recovery would fold new transactions on top of old ones
 *       that recovery had already discarded — resurrection, which is the exact failure the cut
 *       exists to prevent.
 * </ul>
 *
 * <p><strong>Discard, and say so.</strong> Every one of those produces a {@link Decision}
 * naming the cause rather than an exception, because a discarded checkpoint is a normal
 * event that an operator and a harness both need to be able to count. Nothing here repairs a
 * checkpoint: there is nothing
 * to repair it <em>from</em> except the log, and the log is what gets folded.
 */
public final class CheckpointRecovery {

  private CheckpointRecovery() {}

  /** What recovery did with the checkpoint it found. */
  public enum Outcome {

    /** A usable checkpoint: the baseline came from it and only the tail was replayed. */
    USED,

    /** A checkpoint was there and was thrown away. The reason is in {@link Decision#cause()}. */
    DISCARDED,

    /** No checkpoint file. Recovery folded the whole log, which is also what a first open does. */
    ABSENT
  }

  /**
   * The verdict on one checkpoint file.
   *
   * @param outcome what recovery did
   * @param checkpoint the snapshot, when there was a usable one
   * @param tail the records replayed on top of it — empty when the checkpoint reached the end of
   *     the log, and the whole log when there was no checkpoint
   * @param cause why a checkpoint was discarded, or {@code null}
   * @param detail the offending field, for a message a human can act on
   */
  public record Decision(
      Outcome outcome,
      Checkpoint checkpoint,
      List<WalRecord> tail,
      CheckpointRejection cause,
      String detail) {

    public boolean used() {
      return outcome == Outcome.USED;
    }

    @Override
    public String toString() {
      return switch (outcome) {
        case USED -> "checkpoint used: " + checkpoint + ", " + tail.size() + " record(s) replayed";
        case DISCARDED -> "checkpoint discarded: " + cause + " — " + detail;
        case ABSENT -> "no checkpoint; the whole log was folded";
      };
    }
  }

  /** A recovered ledger and the account of how it was reached. */
  public record Recovered(InMemoryLedger ledger, Decision decision) {

    /** How many records the fold had to replay, which is what a checkpoint is measured by. */
    public int replayed() {
      return decision.tail().size();
    }
  }

  /**
   * Recovers {@code directory}'s state from its checkpoint and its log.
   *
   * <p>This is the only entry point recovery needs: it decides, folds, and returns both halves so
   * the caller can report the decision rather than infer it.
   */
  public static Recovered recover(Path directory, WalRecovery.Scan scan) throws IOException {
    Decision decision = decide(directory, scan);
    InMemoryLedger ledger;
    if (decision.used()) {
      ledger =
          Replay.foldFrom(
              decision.checkpoint().rows(), decision.checkpoint().journalEvents(),
              decision.tail());
    } else {
      ledger = Replay.foldEvents(scan.journalRecords());
    }
    return new Recovered(ledger, decision);
  }

  /**
   * Reads and validates the checkpoint file against a scan of the log, changing nothing.
   *
   * @throws IOException only if the file cannot be read at all; every other problem is a
   *     {@link Decision}, because recovery has a correct answer for all of them
   */
  public static Decision decide(Path directory, WalRecovery.Scan scan) throws IOException {
    Path file = CheckpointWriter.liveFile(directory);
    if (!Files.exists(file)) {
      return new Decision(
          Outcome.ABSENT, null, scan.records(), null, "there is no checkpoint file");
    }
    byte[] bytes = Files.readAllBytes(file);
    Checkpoint.Decode decoded = Checkpoint.decode(bytes);
    if (decoded instanceof Checkpoint.Rejected rejected) {
      return discarded(scan, rejected.cause(), rejected.detail());
    }
    Checkpoint checkpoint = ((Checkpoint.Decoded) decoded).checkpoint();

    if (checkpoint.lastLsn() < 1L || checkpoint.journalEvents() < 1L) {
      return discarded(
          scan,
          CheckpointRejection.EMPTY_SNAPSHOT,
          "a checkpoint covering " + checkpoint.journalEvents() + " journal events at LSN "
              + checkpoint.lastLsn() + " is not one this writer produces");
    }
    // Σ balances = 0 is invariant, so a baseline that breaks it is not a state this log reached.
    long total = 0L;
    for (AccountState row : checkpoint.rows()) {
      total += row.balance().minorUnits();
    }
    if (total != 0L) {
      return discarded(
          scan,
          CheckpointRejection.IMPOSSIBLE_STATE,
          "the baseline's balances sum to " + total + " minor units; Σ balances = 0 is invariant");
    }

    long cleanBytes = scan.report().cleanBytes();
    if (checkpoint.walBytes() > cleanBytes) {
      return discarded(
          scan,
          CheckpointRejection.STALE_WAL_BYTES,
          "the checkpoint covers the log through byte " + checkpoint.walBytes()
              + " and the log's clean prefix ends at " + cleanBytes
              + " — the snapshot is ahead of the log it belongs to");
    }
    if (checkpoint.lastLsn() > scan.report().lastLsn()) {
      return discarded(
          scan,
          CheckpointRejection.STALE_LSN,
          "the checkpoint covers through LSN " + checkpoint.lastLsn()
              + " and the log's last surviving record is " + scan.report().lastLsn());
    }
    long actualEnd = scan.endOffsetOf(checkpoint.lastLsn());
    if (actualEnd != checkpoint.walBytes()) {
      // The log was cut and regrown: the LSN came back and the bytes behind it did not. Using this
      // checkpoint would fold a tail onto transactions recovery already discarded.
      return discarded(
          scan,
          CheckpointRejection.NOT_A_RECORD_BOUNDARY,
          "the checkpoint says LSN " + checkpoint.lastLsn() + " ends at byte "
              + checkpoint.walBytes() + " and in this log it ends at " + actualEnd
              + " — the log has been cut and regrown since this snapshot was written");
    }

    List<WalRecord> tail = new ArrayList<>();
    for (WalRecord record : scan.records()) {
      if (record.lsn().value() > checkpoint.lastLsn()) {
        tail.add(record);
      }
    }
    if (!tail.isEmpty() && tail.get(0).lsn().value() != checkpoint.lastLsn() + 1L) {
      return discarded(
          scan,
          CheckpointRejection.NOT_A_RECORD_BOUNDARY,
          "the tail after LSN " + checkpoint.lastLsn() + " starts at "
              + tail.get(0).lsn().value() + ", and a dense log has no hole to step over");
    }
    return new Decision(Outcome.USED, checkpoint, List.copyOf(tail), null, "");
  }

  private static Decision discarded(
      WalRecovery.Scan scan, CheckpointRejection cause, String detail) {
    return new Decision(Outcome.DISCARDED, null, scan.records(), cause, detail);
  }
}
