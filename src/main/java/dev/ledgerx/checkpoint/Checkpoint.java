package dev.ledgerx.checkpoint;

import dev.ledgerx.domain.AccountState;
import dev.ledgerx.wal.WalFormat;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * A checkpoint: the whole materialized state, plus the two numbers that say which log it belongs
 * to.
 *
 * <p><strong>The format.</strong>
 *
 * <pre>{@code
 * +0   u32  magic "LCKP"                 0x4C434B50
 * +4   u8   checkpoint format version    1
 * +5   u8   flags                        0
 * +6   u16  header size                  68
 * +8   u64  lastLsn                      the log watermark: every record up to here is folded in
 * +16  u64  walBytes                     the WAL offset that record ends at
 * +24  u64  journalEvents                journal events folded, openings included
 * +32  u32  payload length
 * +36  u8[32] stateHash                  SHA-256 of the payload — StateHash.digest
 * +68  payload                           the canonical state bytes, verbatim
 * +68+payloadLength  u32 crc32C over [0, 68+payloadLength)
 * }</pre>
 *
 * <p><strong>The payload <em>is</em> the hash preimage.</strong> That is not a coincidence to be
 * admired, it is the thing that makes the file checkable: SHA-256 over the bytes after the header
 * must equal the digest in the header, using the same {@link StateHash} a live ledger hashes
 * itself with. So a checkpoint that loaded and a state that hashed are compared by one function,
 * and "the file says X and the code computed Y" cannot be a disagreement between two encodings.
 *
 * <p><strong>Two integrity layers, because they answer different questions.</strong> The CRC32C
 * covers the header and the payload and detects a torn or scribbled file — the same job it does
 * in a WAL frame, at the same cost. SHA-256 covers the state alone and detects a writer that stored
 * the wrong state, which no CRC can see because a CRC over the wrong bytes is a perfectly good CRC.
 * The header's own fields need no third layer: {@code lastLsn} and {@code walBytes} are checked
 * against the log itself at recovery, which is a stronger check than any checksum, and
 * {@code journalEvents} is cross-checked against the fold.
 *
 * <p><strong>What is deliberately absent.</strong> No timestamp — a clock read inside a
 * checkpoint would make two runs of the same history produce different files, and this harness
 * compares checkpoint bytes, not just hashes. No host name, no process id, no monotonic counter:
 * nothing that is true of the writer rather than of the state. And no copy of the log's bytes,
 * which is what makes a checkpoint a checkpoint.
 *
 * @param lastLsn the log watermark; every record with an LSN at or below this is folded in
 * @param walBytes the WAL offset the record at {@code lastLsn} ends at
 * @param journalEvents how many journal events it took to reach this state
 * @param stateHash SHA-256 of the canonical state bytes, copied in and copied out
 * @param rows the whole state, which {@link #encode()} serializes in canonical order
 */
public record Checkpoint(
    long lastLsn, long walBytes, long journalEvents, byte[] stateHash, List<AccountState> rows) {

  /** {@code "LCKP"}: a checkpoint file starts with something that cannot be money by accident. */
  public static final int MAGIC = 0x4C43_4B50;

  /** The only checkpoint format version this code writes, and the only one it reads. */
  public static final byte VERSION = 1;

  /** Fixed-size part of the file, before the payload. */
  public static final int HEADER_BYTES = 68;

  /** The trailing CRC. */
  public static final int CRC_BYTES = 4;

  private static final int MAGIC_AT = 0;
  private static final int VERSION_AT = 4;
  private static final int FLAGS_AT = 5;
  private static final int HEADER_SIZE_AT = 6;
  private static final int LAST_LSN_AT = 8;
  private static final int WAL_BYTES_AT = 16;
  private static final int JOURNAL_EVENTS_AT = 24;
  private static final int PAYLOAD_LENGTH_AT = 32;
  private static final int STATE_HASH_AT = 36;

  public Checkpoint {
    Objects.requireNonNull(stateHash, "state hash");
    Objects.requireNonNull(rows, "state rows");
    if (stateHash.length != StateHash.DIGEST_BYTES) {
      throw new IllegalArgumentException(
          "a state hash is " + StateHash.DIGEST_BYTES + " bytes, this is " + stateHash.length);
    }
    if (lastLsn < 0L || walBytes < 0L || journalEvents < 0L) {
      throw new IllegalArgumentException(
          "a checkpoint's watermark cannot be negative: lsn=" + lastLsn + " walBytes=" + walBytes
              + " journalEvents=" + journalEvents);
    }
    stateHash = stateHash.clone();
    rows = List.copyOf(rows);
  }

  /** The checkpoint of a state at a point in the log. Hashes the rows itself. */
  public static Checkpoint of(
      long lastLsn, long walBytes, long journalEvents, List<AccountState> rows) {
    return new Checkpoint(lastLsn, walBytes, journalEvents, StateHash.digest(rows), rows);
  }

  /** The file's bytes, ready for a write-then-rename. Deterministic: same state, same bytes. */
  public byte[] encode() {
    byte[] payload = StateHash.canonical(rows);
    byte[] file = new byte[HEADER_BYTES + payload.length + CRC_BYTES];
    WalFormat.putInt(file, MAGIC_AT, MAGIC);
    file[VERSION_AT] = VERSION;
    file[FLAGS_AT] = 0;
    WalFormat.putShort(file, HEADER_SIZE_AT, HEADER_BYTES);
    WalFormat.putLong(file, LAST_LSN_AT, lastLsn);
    WalFormat.putLong(file, WAL_BYTES_AT, walBytes);
    WalFormat.putLong(file, JOURNAL_EVENTS_AT, journalEvents);
    WalFormat.putInt(file, PAYLOAD_LENGTH_AT, payload.length);
    System.arraycopy(stateHash, 0, file, STATE_HASH_AT, StateHash.DIGEST_BYTES);
    System.arraycopy(payload, 0, file, HEADER_BYTES, payload.length);
    int crcAt = file.length - CRC_BYTES;
    WalFormat.putInt(file, crcAt, WalFormat.crc32c(file, crcAt));
    return file;
  }

  /** The canonical state bytes alone — the payload a reader can re-hash. */
  public byte[] payload() {
    return StateHash.canonical(rows);
  }

  public String stateHashHex() {
    return StateHash.hex(stateHash);
  }

  /** How many accounts this checkpoint carries. */
  public int accounts() {
    return rows.size();
  }

  /**
   * Reads a checkpoint file's bytes.
   *
   * <p>Returns a verdict rather than throwing, for the reason {@code WalRecord.inspect} gives:
   * recovery has to tell "this file is damaged" from "this file is fine" in order to report which
   * happened, and an exception type per cause is a worse report than a value.
   */
  public static Decode decode(byte[] file) {
    if (file.length < HEADER_BYTES + CRC_BYTES) {
      return new Rejected(
          CheckpointRejection.SHORT_FILE,
          file.length + " bytes, a checkpoint needs at least " + (HEADER_BYTES + CRC_BYTES));
    }
    int magic = WalFormat.intAt(file, MAGIC_AT);
    if (magic != MAGIC) {
      return new Rejected(
          CheckpointRejection.BAD_MAGIC,
          "magic is 0x" + Integer.toHexString(magic) + ", not 0x" + Integer.toHexString(MAGIC));
    }
    if (file[VERSION_AT] != VERSION) {
      return new Rejected(
          CheckpointRejection.BAD_VERSION,
          "checkpoint format version is " + file[VERSION_AT] + " and this build writes " + VERSION);
    }
    if (file[FLAGS_AT] != 0 || WalFormat.shortAt(file, HEADER_SIZE_AT) != HEADER_BYTES) {
      return new Rejected(
          CheckpointRejection.BAD_HEADER,
          "flags " + file[FLAGS_AT] + " and header size "
              + WalFormat.shortAt(file, HEADER_SIZE_AT) + " are not what version " + VERSION
              + " promises");
    }
    int payloadLength = WalFormat.intAt(file, PAYLOAD_LENGTH_AT);
    if (payloadLength < 0 || payloadLength != file.length - HEADER_BYTES - CRC_BYTES) {
      return new Rejected(
          CheckpointRejection.PAYLOAD_MALFORMED,
          "the payload is declared as " + payloadLength + " bytes and the file holds "
              + (file.length - HEADER_BYTES - CRC_BYTES));
    }
    int stored = WalFormat.intAt(file, file.length - CRC_BYTES);
    int computed = WalFormat.crc32c(file, file.length - CRC_BYTES);
    if (stored != computed) {
      return new Rejected(
          CheckpointRejection.CRC_MISMATCH,
          "stored 0x" + Integer.toHexString(stored) + ", computed 0x"
              + Integer.toHexString(computed));
    }
    byte[] payload = Arrays.copyOfRange(file, HEADER_BYTES, HEADER_BYTES + payloadLength);
    byte[] digest = StateHash.digest(payload);
    byte[] claimed =
        Arrays.copyOfRange(file, STATE_HASH_AT, STATE_HASH_AT + StateHash.DIGEST_BYTES);
    if (!Arrays.equals(digest, claimed)) {
      return new Rejected(
          CheckpointRejection.HASH_MISMATCH,
          "SHA-256 of the payload is " + StateHash.shortHex(digest) + "…, the header claims "
              + StateHash.shortHex(claimed) + "…");
    }
    List<AccountState> rows;
    try {
      rows = StateHash.decode(payload);
    } catch (CheckpointFormatException malformed) {
      return new Rejected(CheckpointRejection.PAYLOAD_MALFORMED, malformed.getMessage());
    }
    return new Decoded(
        new Checkpoint(
            WalFormat.longAt(file, LAST_LSN_AT),
            WalFormat.longAt(file, WAL_BYTES_AT),
            WalFormat.longAt(file, JOURNAL_EVENTS_AT),
            digest,
            rows));
  }

  @Override
  public byte[] stateHash() {
    return stateHash.clone();
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof Checkpoint checkpoint
        && lastLsn == checkpoint.lastLsn
        && walBytes == checkpoint.walBytes
        && journalEvents == checkpoint.journalEvents
        && Arrays.equals(stateHash, checkpoint.stateHash)
        && rows.equals(checkpoint.rows);
  }

  @Override
  public int hashCode() {
    return 31 * Long.hashCode(lastLsn) + Arrays.hashCode(stateHash);
  }

  @Override
  public String toString() {
    return "Checkpoint[lsn<="
        + lastLsn
        + " @byte "
        + walBytes
        + ", "
        + journalEvents
        + " events, "
        + rows.size()
        + " accounts, "
        + StateHash.shortHex(stateHash)
        + "…]";
  }

  /** The verdict type: a checkpoint, or the reason there is not one. */
  public sealed interface Decode permits Decoded, Rejected {}

  /** A checkpoint the file described completely and consistently. */
  public record Decoded(Checkpoint checkpoint) implements Decode {}

  /** A file that is not a usable checkpoint. Recovery discards it and folds the log instead. */
  public record Rejected(CheckpointRejection cause, String detail) implements Decode {

    @Override
    public String toString() {
      return cause + " (" + detail + ")";
    }
  }
}
