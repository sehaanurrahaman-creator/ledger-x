package dev.ledgerx.checkpoint;

import dev.ledgerx.substrate.DurableChannel;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Writing a checkpoint so that a crash at any instant leaves either the old snapshot or the new
 * one, and never a mixture.
 *
 * <p><strong>The protocol, in the only order that works.</strong>
 *
 * <ol>
 *   <li>Serialize the whole file in memory. There is no partial-state window inside the writer
 *       because there is no partial write: the bytes are complete before any of them are on disk.
 *   <li>Write them to a scratch file in the same directory — {@code checkpoint.<lsn>.tmp} — and
 *       {@code fsync} it. Same directory because {@code rename} across filesystems is not atomic
 *       (it is a copy), and a copy is exactly the half-written snapshot this protocol exists to
 *       prevent. {@code fsync} and not {@code fdatasync}: the rename is about to make this file's
 *       <em>name and size</em> visible, which is metadata.
 *   <li>{@code rename} it over the live name, atomically. POSIX {@code rename(2)} is atomic with
 *       respect to other readers of the directory, so a recovery that runs concurrently sees one
 *       of the two files whole.
 *   <li>{@code fsync} the directory. Without this the rename itself can be lost on power failure,
 *       which would silently roll the checkpoint back — a benign outcome, but one the writer
 *       should not leave to chance while claiming it performed the swap.
 * </ol>
 *
 * <p><strong>What a crash at each step leaves, which is the actual content of "atomic".</strong>
 * After (1) or during (2): an orphan scratch file and the previous checkpoint intact. After (3)
 * but before (4): the directory entry may or may not have reached the device, so recovery sees
 * either the old file or the new one — both complete, both self-checking. After (4): the new one.
 * There is no instant at which the live name points at a partial file, and no instant at which
 * there is no live file at all, which is why a checkpoint never needs a "generation" counter or a
 * manifest to be read safely.
 *
 * <p><strong>The rule inherited from ADR 0003 and enforced by the caller:</strong> a checkpoint may
 * only be written once the WAL bytes it covers have been forced. {@link
 * dev.ledgerx.journal.DurableLedger} is what guarantees that, by refusing to snapshot a state
 * further along than its last forced ack; this class cannot check it, because it is handed bytes
 * and told they are true.
 *
 * <p><strong>Retention.</strong> One live checkpoint, replaced in place — see {@link #write}.
 * Orphan scratch files are the only garbage this format produces, and
 * {@link #collectScratchFiles} removes them.
 */
public final class CheckpointWriter {

  /** The one live name. There is deliberately no numbering: see the retention rule in ADR 0004. */
  public static final String FILE_NAME = "checkpoint.ckpt";

  /** Scratch files start here, so an orphan is recognizable after a crash. */
  public static final String SCRATCH_PREFIX = "checkpoint.";

  /** Scratch files end here. */
  public static final String SCRATCH_SUFFIX = ".tmp";

  private CheckpointWriter() {}

  /** The path the live checkpoint lives at, whether or not one exists yet. */
  public static Path liveFile(Path directory) {
    return directory.resolve(FILE_NAME);
  }

  /** The scratch name for one attempt; deterministic, so a retry rewrites rather than piles up. */
  public static Path scratchFile(Path directory, long lastLsn) {
    return directory.resolve(SCRATCH_PREFIX + lastLsn + SCRATCH_SUFFIX);
  }

  /**
   * Writes {@code checkpoint} as the directory's live checkpoint, atomically.
   *
   * <p>One live file, replaced, rather than a numbered series. The reasoning is in ADR 0004 §5 and
   * it rests on one fact: a checkpoint is a cache, and the log is never truncated, so the worst
   * thing that can happen to a checkpoint is that it is unusable and recovery pays a full scan —
   * which is correct, merely slow. Keeping N snapshots would buy a faster recovery from the rare
   * "the newest one is corrupt" case and would cost a selection rule, a garbage-collection rule and
   * N times the crash window; that trade only changes when WAL rotation makes replay-from-scratch
   * expensive, and rotation is not built.
   *
   * @return the live checkpoint's path
   */
  public static Path write(Path directory, Checkpoint checkpoint) throws IOException {
    byte[] bytes = checkpoint.encode();
    Path scratch = scratchFile(directory, checkpoint.lastLsn());
    Path live = liveFile(directory);
    try {
      try (DurableChannel out = DurableChannel.openForRewrite(scratch)) {
        out.write(ByteBuffer.wrap(bytes));
        // force(true): the rename below publishes this file's name and size, and POSIX only
        // promises those reach the device on fsync.
        out.forceAll();
      }
      Files.move(
          scratch, live, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
      DurableChannel.syncDirectory(directory);
      return live;
    } catch (IOException | RuntimeException failed) {
      // A failed swap must leave the previous checkpoint untouched, and the scratch file is this
      // attempt's only footprint — so removing it is the whole cleanup.
      Files.deleteIfExists(scratch);
      throw failed;
    }
  }

  /**
   * Removes scratch files left behind by a crash mid-write, and makes the removals durable.
   *
   * <p>This is the whole of checkpoint garbage collection, and it is safe because a scratch file is
   * never read by anything: recovery looks at {@link #FILE_NAME} and nowhere else.
   *
   * @return how many files were removed
   */
  public static int collectScratchFiles(Path directory) throws IOException {
    List<Path> orphans = new ArrayList<>();
    try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory)) {
      for (Path entry : entries) {
        String name = entry.getFileName().toString();
        if (name.startsWith(SCRATCH_PREFIX) && name.endsWith(SCRATCH_SUFFIX)) {
          orphans.add(entry);
        }
      }
    }
    for (Path orphan : orphans) {
      Files.deleteIfExists(orphan);
    }
    if (!orphans.isEmpty()) {
      DurableChannel.syncDirectory(directory);
    }
    return orphans.size();
  }

  /**
   * Deletes the live checkpoint. Recovery then folds the log from byte 0, which is what makes a
   * checkpoint disposable: this method cannot lose money, only time.
   *
   * @return whether there was one to delete
   */
  public static boolean delete(Path directory) throws IOException {
    boolean deleted = Files.deleteIfExists(liveFile(directory));
    if (deleted) {
      DurableChannel.syncDirectory(directory);
    }
    return deleted;
  }
}
