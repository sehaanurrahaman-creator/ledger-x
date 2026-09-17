package dev.ledgerx.checkpoint;

import dev.ledgerx.wal.WalFormat;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Objects;

/**
 * One checkpoint: a state, the log position it covers, and the digest that binds the two together.
 * ADR 0004 §2 is the layout; this class is the encode, the decode and the checks that a file is
 * what it says it is.
 *
 * <p><strong>A checkpoint is a claim with three parts, and each is checked separately.</strong>
 *
 * <ul>
 *   <li>{@code state} — the materialized state at the watermark, hashed by
 *       {@link LedgerState#stateHash()}. It is checked against itself: the trailer's digest must be
 *       the digest of the section.
 *   <li>{@code lastLsn} / {@code coveredBytes} — the watermark, named three times (file name,
 *       header, state section) and required to agree. The bytes are the resume point: the frame
 *       after {@code coveredBytes} carries {@code lastLsn + 1}, which the log's dense numbering
 *       makes checkable.
 *   <li>{@code walDigest} — SHA-256 over the log's bytes before {@code coveredBytes}. This is the
 *       part that turns "the state was once true" into "the state belongs to <em>this</em> log":
 *       ADR 0003 §2 allows LSNs to be re-used after a torn tail is cut, so the same watermark can
 *       name two different histories, and only the bytes can tell them apart (ADR 0004 §5).
 * </ul>
 *
 * <p>What a checkpoint deliberately does <em>not</em> carry is a lease on being believed. It is a
 * cache of a fold of the log; refusal is always an option and never loses anything, which is what
 * lets the reader be strict — and is why the digest of the state is stored even though the state
 * is in the same file: a checksum says "the bytes survived", a digest says "these are the bytes".
 *
 * @param state the materialized state, whose {@code lastLsn} is this checkpoint's watermark
 * @param coveredBytes how many bytes of the log the state accounts for, counted from the file's
 *     start: the end of the frame carrying {@code lastLsn}, or the segment header's own 16 if the
 *     state is empty
 * @param walDigest SHA-256 of exactly those bytes
 */
public record Checkpoint(LedgerState state, long coveredBytes, byte[] walDigest) {

  public Checkpoint {
    Objects.requireNonNull(state, "checkpoint state");
    Objects.requireNonNull(walDigest, "wal digest");
    if (coveredBytes < WalFormat.SEGMENT_HEADER_BYTES) {
      throw new IllegalArgumentException(
          "a checkpoint covers at least the "
              + WalFormat.SEGMENT_HEADER_BYTES
              + "-byte segment header, not "
              + coveredBytes
              + " bytes");
    }
    if (walDigest.length != CheckpointFormat.DIGEST_BYTES) {
      throw new IllegalArgumentException(
          "a digest is " + CheckpointFormat.DIGEST_BYTES + " bytes, not " + walDigest.length);
    }
    walDigest = walDigest.clone();
  }

  /**
   * Takes a checkpoint of a state, reading the log it covers to compute the binding digest.
   *
   * <p>This is the only place a checkpoint's bytes are read from disk on the write path, and it is
   * O(covered bytes) — priced in ADR 0004 §7 and the reason the default cadence is per N commits
   * rather than per commit. It reads the log through {@link Sha256#ofFilePrefix}, which is the same
   * read the loader verifies with, so a writer and a reader cannot disagree about what "the
   * covered bytes" are.
   */
  public static Checkpoint of(LedgerState state, long coveredBytes, Path walFile)
      throws IOException {
    Objects.requireNonNull(state, "checkpoint state");
    return new Checkpoint(state, coveredBytes, Sha256.ofFilePrefix(walFile, coveredBytes));
  }

  /** The LSN of the last journal record the state includes; 0 if the state is empty. */
  public long lastLsn() {
    return state.lastLsn();
  }

  /** The state hash, as the file stores it and as an operator quotes it. */
  public String stateHash() {
    return state.stateHash();
  }

  @Override
  public byte[] walDigest() {
    return walDigest.clone();
  }

  /** The digest without copying, for a reader that compares and discards. Never store it. */
  public byte[] walDigestUnsafe() {
    return walDigest;
  }

  /**
   * The whole file: header, state section, trailer.
   *
   * <p>The state hash goes in the trailer rather than in the header because a header is read by a
   * selector that has not read the state yet, and a digest of bytes the file has not held is a
   * promise rather than a check. The CRC is last, covering everything before it, which is the WAL's
   * own convention (ADR 0003 §2) for the same reason: a scan can find the end of what it must
   * verify without understanding any of it.
   */
  public byte[] encode() {
    byte[] section = state.canonicalBytes();
    byte[] file = new byte[CheckpointFormat.FILE_OVERHEAD_BYTES + section.length];
    WalFormat.putInt(file, 0, CheckpointFormat.MAGIC);
    file[4] = CheckpointFormat.FORMAT_VERSION;
    file[5] = 0;
    WalFormat.putShort(file, 6, CheckpointFormat.HEADER_BYTES);
    WalFormat.putInt(file, 8, 0);
    WalFormat.putInt(file, 12, 0);
    WalFormat.putLong(file, 16, state.lastLsn());
    WalFormat.putLong(file, 24, coveredBytes);
    WalFormat.putInt(file, 32, section.length);
    WalFormat.putInt(file, 36, 0);
    System.arraycopy(walDigest, 0, file, 40, CheckpointFormat.DIGEST_BYTES);
    System.arraycopy(section, 0, file, CheckpointFormat.HEADER_BYTES, section.length);
    System.arraycopy(
        state.stateHashBytes(), 0, file, CheckpointFormat.HEADER_BYTES + section.length,
        CheckpointFormat.DIGEST_BYTES);
    WalFormat.putInt(
        file, file.length - WalFormat.CRC_BYTES,
        WalFormat.crc32c(file, file.length - WalFormat.CRC_BYTES));
    return file;
  }

  /**
   * Reads a whole file. Everything a file can get wrong is checked here, in the order that costs
   * the least before the check that costs the most: lengths, then the CRC, then the state digest,
   * then the state's own version and bounds.
   *
   * @throws CorruptCheckpointException with the {@link CheckpointRefusal} that names the finding
   */
  public static Checkpoint decode(byte[] file) throws CorruptCheckpointException {
    if (file.length < CheckpointFormat.FILE_OVERHEAD_BYTES + CheckpointFormat.STATE_PREFIX_BYTES) {
      throw refuse(
          CheckpointRefusal.LENGTH_MISMATCH,
          "a checkpoint is at least "
              + (CheckpointFormat.FILE_OVERHEAD_BYTES + CheckpointFormat.STATE_PREFIX_BYTES)
              + " bytes and this file is "
              + file.length);
    }
    if (WalFormat.intAt(file, 0) != CheckpointFormat.MAGIC) {
      throw refuse(
          CheckpointRefusal.BAD_MAGIC,
          "magic is 0x"
              + Integer.toHexString(WalFormat.intAt(file, 0))
              + ", not 0x"
              + Integer.toHexString(CheckpointFormat.MAGIC));
    }
    int version = file[4] & 0xFF;
    int flags = file[5] & 0xFF;
    int headerSize = WalFormat.shortAt(file, 6);
    int reserved0 = WalFormat.intAt(file, 8);
    int reserved1 = WalFormat.intAt(file, 12);
    if (version != CheckpointFormat.FORMAT_VERSION
        || flags != 0
        || headerSize != CheckpointFormat.HEADER_BYTES
        || reserved0 != 0
        || reserved1 != 0) {
      throw refuse(
          CheckpointRefusal.BAD_HEADER,
          "version "
              + version
              + ", flags "
              + flags
              + ", header size "
              + headerSize
              + ", reserved 0x"
              + Integer.toHexString(reserved0)
              + "/0x"
              + Integer.toHexString(reserved1)
              + " are not what version "
              + CheckpointFormat.FORMAT_VERSION
              + " promises");
    }
    long lastLsn = WalFormat.longAt(file, 16);
    long coveredBytes = WalFormat.longAt(file, 24);
    long stateBytes = Integer.toUnsignedLong(WalFormat.intAt(file, 32));
    if (lastLsn < 0L || coveredBytes < 0L || stateBytes > CheckpointFormat.MAX_STATE_BYTES) {
      throw refuse(
          CheckpointRefusal.BAD_HEADER,
          "watermark " + lastLsn + ", coverage " + coveredBytes + ", state " + stateBytes
              + " bytes — one of them is not a length this format can hold");
    }
    if (CheckpointFormat.FILE_OVERHEAD_BYTES + stateBytes != file.length) {
      throw refuse(
          CheckpointRefusal.LENGTH_MISMATCH,
          "the header declares "
              + stateBytes
              + " bytes of state, so the file is "
              + (CheckpointFormat.FILE_OVERHEAD_BYTES + stateBytes)
              + " bytes, and it is "
              + file.length);
    }
    int storedCrc = WalFormat.intAt(file, file.length - WalFormat.CRC_BYTES);
    int computedCrc = WalFormat.crc32c(file, file.length - WalFormat.CRC_BYTES);
    if (storedCrc != computedCrc) {
      throw refuse(
          CheckpointRefusal.CRC_MISMATCH,
          "crc is 0x"
              + Integer.toHexString(storedCrc)
              + ", computed 0x"
              + Integer.toHexString(computedCrc));
    }
    int stateOffset = CheckpointFormat.HEADER_BYTES;
    int digestOffset = stateOffset + (int) stateBytes;
    byte[] storedStateHash =
        Arrays.copyOfRange(file, digestOffset, digestOffset + CheckpointFormat.DIGEST_BYTES);
    byte[] computedStateHash =
        Sha256.of(Arrays.copyOfRange(file, stateOffset, digestOffset));
    if (!Sha256.same(storedStateHash, computedStateHash)) {
      throw refuse(
          CheckpointRefusal.STATE_DIGEST_MISMATCH,
          "the trailer says "
              + Sha256.hex(storedStateHash)
              + " and the state section hashes to "
              + Sha256.hex(computedStateHash));
    }
    LedgerState state = LedgerState.decode(file, stateOffset, (int) stateBytes);
    if (state.lastLsn() != lastLsn) {
      throw refuse(
          CheckpointRefusal.BAD_HEADER,
          "the header says the state is at lsn "
              + lastLsn
              + " and the state says "
              + state.lastLsn()
              + "; one of them was edited");
    }
    return new Checkpoint(
        state,
        coveredBytes,
        Arrays.copyOfRange(file, 40, 40 + CheckpointFormat.DIGEST_BYTES));
  }

  /** Whether two checkpoints are the same claim, digest included. */
  public boolean sameAs(Checkpoint other) {
    return coveredBytes == other.coveredBytes
        && lastLsn() == other.lastLsn()
        && state.stateHash().equals(other.state.stateHash())
        && Arrays.equals(walDigest, other.walDigest);
  }

  @Override
  public String toString() {
    return "Checkpoint["
        + state.accountCount()
        + " accounts at lsn "
        + lastLsn()
        + ", covering "
        + coveredBytes
        + " log bytes, state "
        + stateHash().substring(0, 16)
        + "..., wal "
        + Sha256.hex(walDigest).substring(0, 16)
        + "...]";
  }

  private static CorruptCheckpointException refuse(CheckpointRefusal why, String detail) {
    return new CorruptCheckpointException(why, detail);
  }
}
