package dev.ledgerx.checkpoint;

import dev.ledgerx.substrate.DurableChannel;
import dev.ledgerx.wal.Corruption;
import dev.ledgerx.wal.UnrecoverableLogException;
import dev.ledgerx.wal.WalFormat;
import dev.ledgerx.wal.WalRecovery;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * The checkpoint: materialized state plus the log position it covers, swapped into place
 * atomically or not at all.
 *
 * <p><strong>The file, version 1</strong> — 36 bytes of header, the canonical state bytes
 * ({@link StateHash} is the encoder), a 32-byte SHA-256 of those bytes, and a CRC32C over
 * everything before it:
 *
 * <pre>{@code
 *  +0  u32 magic      "LCHK"
 *  +4  u8  format version (1)
 *  +5  u8  flags (0)
 *  +6  u16 header size (36)
 *  +8  u32 reserved (0)
 * +12  u64 watermarkLsn       the last LSN the state includes (0 = an empty log)
 * +20  u64 watermarkOffset    the log's byte length through that record, header included
 * +28  u32 stateLength
 * +32  u32 crc32C over [0,32)
 * +36  state bytes (stateLength)
 *      u8[32] sha-256 of the state bytes
 *      u32 crc32C over [0, file length - 4)
 * }</pre>
 *
 * <p>Three integrity layers, each with one job. The state hash is the <em>identity</em>: it
 * is the same function the replay proof compares, so a checkpoint's state and a replayed
 * state are comparable by construction. The whole-file CRC is the <em>damage</em> detector:
 * any scribble anywhere in the file is caught before a byte of it is believed. The header
 * CRC exists so a reader can reject a foreign file after 32 bytes instead of after all of
 * them. Nothing here is trusted because it says so; every field is either covered by a
 * checksum or cross-checked against the log.
 *
 * <p><strong>The swap protocol</strong> is the five steps ADR 0001's table commits to, in
 * the only order that survives a crash at any of them: write {@code checkpoint.tmp}; fsync
 * the temp ({@code force(true)} — a new file's size is metadata); rename it onto
 * {@code checkpoint.bin} atomically; fsync the directory, so the rename itself is durable.
 * A crash before the rename leaves a temp file — garbage by definition, deleted at open and
 * overwritten before every write. A crash after it leaves the new checkpoint complete or
 * not there. There is no instant at which {@code checkpoint.bin} is half a checkpoint,
 * which is the whole reason the protocol writes a temp instead of the live name.
 *
 * <p><strong>The retention rule is one line, because the log is not truncated.</strong>
 * Exactly one live checkpoint per directory, superseded in place by the atomic rename; the
 * temp file is the only garbage there ever is. Old checkpoints do not accumulate, so
 * nothing needs collecting; and since the WAL keeps its whole history (rotation is another
 * ticket), replay-from-scratch never depends on the checkpoint at all — deleting every
 * checkpoint file is always safe, which is the strongest retention rule a checkpoint can
 * have. When segment rotation lands, numbered checkpoints and a keep-newest-K rule
 * supersede this one; ADR 0004 §4 records why they were not built now.
 *
 * <p><strong>Damage is discardable; disagreement is fatal.</strong> A checkpoint that fails
 * its own integrity checks is renamed to {@code checkpoint.rejected} and recovery proceeds
 * from scratch — the checkpoint is an optimization, and from-scratch replay is proven to
 * reconstruct the identical state, so a damaged optimization must never become an outage.
 * But a checkpoint that is intact and names coverage the log cannot prove — an offset past
 * the log's surviving bytes, or an LSN whose frame does not end exactly at the claimed
 * offset — is {@link Corruption#CHECKPOINT_MISMATCH} and refuses to open: the covered bytes
 * were forced before the checkpoint existed (that ordering is ADR 0003 §10's rule, enforced
 * by {@code DurableLedger.writeCheckpoint}), so bytes the checkpoint vouches for but the
 * log has lost are lost acknowledgements, and silently replaying from scratch would paper
 * over exactly the data loss the charter exists to surface. Refusal makes the loss visible;
 * deleting the checkpoint is how an operator chooses from-scratch replay anyway — a
 * decision, not a surprise.
 */
public final class Checkpoint {

  /** The one live checkpoint per directory. */
  public static final String FILE_NAME = "checkpoint.bin";

  /** The write-temp half of the swap. Garbage by definition; deleted at open. */
  public static final String TEMP_NAME = "checkpoint.tmp";

  /** Where a checkpoint that failed its own integrity checks goes, evidence kept. */
  public static final String REJECTED_NAME = "checkpoint.rejected";

  /** "LCHK" — a file that cannot be money by accident, and not a WAL either. */
  public static final int SEGMENT_MAGIC = 0x4C43_484B;

  /** The only format version this code writes, and the only one it reads. */
  public static final byte FORMAT_VERSION = 1;

  /** Fixed-size part of the header: through the header's own CRC. */
  public static final int HEADER_BYTES = 36;

  /** SHA-256 is 32 bytes, wide enough that finding a second preimage is nobody's plan. */
  public static final int DIGEST_BYTES = 32;

  /** The trailer: the digest and the whole-file CRC. */
  public static final int TRAILER_BYTES = DIGEST_BYTES + 4;

  private Checkpoint() {}

  /**
   * One loaded checkpoint: the log position it covers, the canonical state bytes, and the
   * hash of those bytes — which is the value the replay proof compares.
   */
  public record Loaded(long watermarkLsn, long watermarkOffset, byte[] state, byte[] hash) {

    public Loaded {
      state = state.clone();
      hash = hash.clone();
    }

    /** The state section's length, which is derivable but read ten times on the way in. */
    public int stateLength() {
      return state.length;
    }

    @Override
    public String toString() {
      return "checkpoint[lsn " + watermarkLsn + " @ byte " + watermarkOffset + ", state "
          + state.length + " bytes, sha-256 " + StateHash.hex(hash) + "]";
    }
  }

  /**
   * Assembles the full file bytes for a checkpoint of {@code state} covering
   * {@code watermarkLsn} / {@code watermarkOffset}. Exposed because the contract builds
   * malformed files from real ones rather than trusting the writer to write what it reads.
   */
  public static byte[] assemble(long watermarkLsn, long watermarkOffset, byte[] state) {
    if (watermarkLsn < 0L) {
      throw new IllegalArgumentException("a watermark lsn counts from 0 (an empty log)");
    }
    if (watermarkOffset < 0L) {
      throw new IllegalArgumentException("a watermark offset counts from 0");
    }
    byte[] file = new byte[HEADER_BYTES + state.length + TRAILER_BYTES];
    WalFormat.putInt(file, 0, SEGMENT_MAGIC);
    file[4] = FORMAT_VERSION;
    file[5] = 0;
    WalFormat.putShort(file, 6, HEADER_BYTES);
    WalFormat.putInt(file, 8, 0);
    WalFormat.putLong(file, 12, watermarkLsn);
    WalFormat.putLong(file, 20, watermarkOffset);
    WalFormat.putInt(file, 28, state.length);
    WalFormat.putInt(file, 32, WalFormat.crc32c(file, 32));
    System.arraycopy(state, 0, file, HEADER_BYTES, state.length);
    byte[] digest = sha256(state);
    System.arraycopy(digest, 0, file, HEADER_BYTES + state.length, DIGEST_BYTES);
    int crcAt = file.length - 4;
    WalFormat.putInt(file, crcAt, WalFormat.crc32c(file, crcAt));
    return file;
  }

  /**
   * The swap: assemble, write the temp, fsync it, rename it over the live name, fsync the
   * directory. Returns what was written, so the caller can log the watermark it just made
   * durable-on-disk.
   */
  public static Loaded write(Path directory, long watermarkLsn, long watermarkOffset,
      byte[] state) throws IOException {
    Files.createDirectories(directory);
    Path temp = directory.resolve(TEMP_NAME);
    Path live = directory.resolve(FILE_NAME);
    byte[] file = assemble(watermarkLsn, watermarkOffset, state);
    try {
      // A temp left by a crash is append-target for this channel, so it goes first: the
      // temp is never state, and writing over it is the protocol, not a repair.
      Files.deleteIfExists(temp);
      try (DurableChannel out = DurableChannel.openForAppend(temp)) {
        out.write(ByteBuffer.wrap(file));
        // force(true): the file is new, so its size is metadata, and a rename of a file
        // whose bytes are not durable would publish a checkpoint nobody can read back.
        out.forceAll();
      }
      try {
        Files.move(temp, live, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
      } catch (AtomicMoveNotSupportedException refused) {
        throw new IOException(
            "this filesystem does not support atomic rename, and the checkpoint swap"
                + " refuses to be non-atomic (ADR 0004 §4)",
            refused);
      }
      // The rename is only as durable as the directory entry it changed.
      DurableChannel.syncDirectory(directory);
    } finally {
      Files.deleteIfExists(temp);
    }
    return new Loaded(watermarkLsn, watermarkOffset, state, sha256(state));
  }

  /**
   * Loads the directory's checkpoint and checks it against a scan of the log, or returns
   * {@code null} when there is no checkpoint — or when there was one and it failed its own
   * integrity checks, in which case the file is renamed aside and recovery falls back to
   * from-scratch replay.
   *
   * @throws UnrecoverableLogException ({@link Corruption#CHECKPOINT_MISMATCH}) when the
   *     checkpoint is intact but names coverage the scanned log cannot prove: see the
   *     class comment for why this refuses rather than falls back
   */
  public static Loaded load(Path directory, WalRecovery.Scan scan) throws IOException {
    Path live = directory.resolve(FILE_NAME);
    if (!Files.exists(live)) {
      return null;
    }
    byte[] file;
    try {
      file = Files.readAllBytes(live);
    } catch (IOException unreadable) {
      reject(live, "it could not be read: " + unreadable);
      return null;
    }
    Loaded loaded = parse(live, file);
    if (loaded == null) {
      return null;
    }
    checkAgainstLog(loaded, scan);
    return loaded;
  }

  /** Deletes a temp file, which is garbage however it got there. */
  public static void cleanTemp(Path directory) throws IOException {
    Files.deleteIfExists(directory.resolve(TEMP_NAME));
  }

  /**
   * Parses and integrity-checks the bytes; returns {@code null} (after renaming the file
   * aside) when they are damaged, which is the discardable half of the rule.
   */
  private static Loaded parse(Path live, byte[] file) throws IOException {
    if (file.length < HEADER_BYTES + TRAILER_BYTES) {
      return reject(live, "it holds " + file.length
          + " bytes, fewer than an empty checkpoint's " + (HEADER_BYTES + TRAILER_BYTES));
    }
    if (WalFormat.intAt(file, 0) != SEGMENT_MAGIC) {
      return reject(live, "magic is 0x" + Integer.toHexString(WalFormat.intAt(file, 0))
          + ", not 0x" + Integer.toHexString(SEGMENT_MAGIC) + " — not a ledger-x checkpoint");
    }
    int version = file[4] & 0xFF;
    if (version != FORMAT_VERSION) {
      return reject(live, "format version is " + version + ", this build writes "
          + FORMAT_VERSION);
    }
    if (file[5] != 0) {
      return reject(live, "flags are 0x" + Integer.toHexString(file[5] & 0xFF)
          + " and this format has none");
    }
    int headerSize = WalFormat.shortAt(file, 6);
    int reserved = WalFormat.intAt(file, 8);
    if (headerSize != HEADER_BYTES || reserved != 0) {
      return reject(live, "header size " + headerSize + " and reserved 0x"
          + Integer.toHexString(reserved) + " are not what version " + FORMAT_VERSION
          + " promises");
    }
    if (WalFormat.intAt(file, 32) != WalFormat.crc32c(file, 32)) {
      return reject(live, "the header's crc does not match its bytes");
    }
    long watermarkLsn = WalFormat.longAt(file, 12);
    long watermarkOffset = WalFormat.longAt(file, 20);
    int stateLength = WalFormat.intAt(file, 28);
    if (watermarkLsn < 0L || watermarkOffset < 0L) {
      return reject(live, "the watermark is negative: lsn " + watermarkLsn + " at byte "
          + watermarkOffset);
    }
    if (stateLength < 0 || HEADER_BYTES + (long) stateLength + TRAILER_BYTES != file.length) {
      return reject(live, "stateLength " + stateLength + " does not end the file at "
          + (HEADER_BYTES + (long) stateLength + TRAILER_BYTES) + " of " + file.length
          + " bytes");
    }
    int crcAt = file.length - 4;
    if (WalFormat.intAt(file, crcAt) != WalFormat.crc32c(file, crcAt)) {
      return reject(live, "the file's crc does not match its bytes");
    }
    byte[] state = new byte[stateLength];
    System.arraycopy(file, HEADER_BYTES, state, 0, stateLength);
    byte[] storedDigest = new byte[DIGEST_BYTES];
    System.arraycopy(file, HEADER_BYTES + stateLength, storedDigest, 0, DIGEST_BYTES);
    byte[] computed = sha256(state);
    for (int i = 0; i < DIGEST_BYTES; i++) {
      if (storedDigest[i] != computed[i]) {
        return reject(live, "the state's sha-256 is not the hash of the state it rides with");
      }
    }
    return new Loaded(watermarkLsn, watermarkOffset, state, storedDigest);
  }

  /**
   * The half of the rule that is not discardable: an intact checkpoint must describe
   * coverage the log still has. LSNs are dense from 1 within a surviving prefix, so record
   * {@code watermarkLsn} is the {@code watermarkLsn}-th frame, and its last byte sits at
   * exactly the claimed offset — a checkpoint from another log, or one whose log lost
   * forced bytes, fails here and the open refuses.
   */
  private static void checkAgainstLog(Loaded loaded, WalRecovery.Scan scan)
      throws UnrecoverableLogException {
    long offset = loaded.watermarkOffset();
    long lsn = loaded.watermarkLsn();
    long cleanBytes = scan.report().cleanBytes();
    if (offset > cleanBytes) {
      throw new UnrecoverableLogException(
          Corruption.CHECKPOINT_MISMATCH, offset, lsn >= 1L ? dev.ledgerx.wal.Lsn.of(lsn) : null,
          "the checkpoint covers bytes through " + offset + " but the log's surviving prefix"
              + " ends at " + cleanBytes + " — the covered bytes were forced before this"
              + " checkpoint existed, so the log has lost acknowledged data. Deleting the"
              + " checkpoint replays from scratch, and is a decision to accept that loss,"
              + " not a repair",
          null);
    }
    long covered = lsn;
    if (covered > scan.records().size()) {
      throw new UnrecoverableLogException(
          Corruption.CHECKPOINT_MISMATCH, offset, dev.ledgerx.wal.Lsn.of(lsn),
          "the checkpoint covers lsn " + lsn + " but the log holds " + scan.records().size()
              + " frames — the checkpoint and the log are not from the same history",
          null);
    }
    long expectedEnd = covered == 0L
        ? WalFormat.SEGMENT_HEADER_BYTES
        : scan.endOffsetOfFrame((int) covered - 1);
    if (offset != expectedEnd) {
      throw new UnrecoverableLogException(
          Corruption.CHECKPOINT_MISMATCH, offset, dev.ledgerx.wal.Lsn.of(lsn),
          "the checkpoint claims lsn " + lsn + " ends at byte " + offset + ", and the log's"
              + " frame ends at byte " + expectedEnd + " — the checkpoint and the log are"
              + " not from the same history",
          null);
    }
  }

  /**
   * Renames a damaged checkpoint aside and reports it. Renaming, not deleting: a damaged
   * checkpoint is the only evidence a post-mortem gets about what scribbled on the disk.
   */
  private static Loaded reject(Path live, String why) throws IOException {
    Path rejected = live.resolveSibling(REJECTED_NAME);
    try {
      Files.move(live, rejected, StandardCopyOption.REPLACE_EXISTING);
    } catch (IOException moveFailed) {
      Files.deleteIfExists(live);
    }
    System.out.println("checkpoint: discarding " + live.getFileName() + " — " + why
        + " (kept as " + rejected.getFileName() + "; recovery proceeds from scratch)");
    return null;
  }

  private static byte[] sha256(byte[] bytes) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(bytes);
    } catch (NoSuchAlgorithmException missing) {
      throw new IllegalStateException("SHA-256 is required by the JCA specification", missing);
    }
  }
}
