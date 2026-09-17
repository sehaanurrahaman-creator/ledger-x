package dev.ledgerx.checkpoint;

import dev.ledgerx.substrate.DurableChannel;
import dev.ledgerx.wal.WalRecovery;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * The checkpoints directory: how a checkpoint is swapped in, which one recovery may use, and which
 * ones may be thrown away. ADR 0004 §5 and §6, as code.
 *
 * <p><strong>Write-temp, force, rename, sync the directory — in that order, and never the
 * reverse.</strong> That order is what makes "a checkpoint file exists" and "the checkpoint file is
 * complete" the same statement. A reader listing the directory sees a temp file and an older
 * checkpoint; it can
 * never see a checkpoint name pointing at bytes that have not been forced, because the name only
 * appears after the force, and the name is the only thing the reader trusts.
 *
 * <p><strong>The resume point is the reader's job to check, not the writer's to promise.</strong>
 * {@link #load} refuses a checkpoint whose covered bytes the log does not have
 * ({@link CheckpointRefusal#COVERAGE_GONE}) and one whose covered bytes a different history wrote
 * ({@link CheckpointRefusal#WAL_DIGEST_MISMATCH}). The first is the rule ADR 0003 §10 handed over;
 * the second is the reason this ticket's format carries a log digest at all, and it exists because
 * a watermark is reusable: a torn tail that gets cut can be re-appended with the same LSNs
 * (ADR 0003 §2).
 *
 * <p><strong>Refusal is always a fallback, never an outage.</strong> A checkpoint is a cache of a
 * fold; the log holds everything it holds. So the loader walks the candidates newest first and
 * takes the first that stands up, recording every refusal for the report — and if none does, the
 * caller folds the log from the start and loses nothing but time. That is also what makes it safe
 * to be strict: a reader that would rather refuse than be wrong costs a slower recovery, and the
 * alternative — believing a file because it parses — costs money.
 */
public final class CheckpointStore {

  /** The directory a ledger's checkpoints live in, beside its WAL segment. */
  public static final String DIRECTORY_NAME = "checkpoints";

  private final Path directory;

  /**
   * @param ledgerDirectory the directory the ledger's WAL lives in; checkpoints go in a
   *     {@value #DIRECTORY_NAME} subdirectory of it, which keeps garbage collection from ever
   *     having to wonder whether a file is its business
   */
  public CheckpointStore(Path ledgerDirectory) {
    Objects.requireNonNull(ledgerDirectory, "ledger directory");
    this.directory = ledgerDirectory.resolve(DIRECTORY_NAME);
  }

  public Path directory() {
    return directory;
  }

  /** Whether this directory holds any checkpoint at all. */
  public boolean any() throws IOException {
    return !newestFirst().isEmpty();
  }

  /**
   * The checkpoint files, newest first.
   *
   * <p>Sorted by the watermark each <em>name</em> claims, descending, then by path — explicitly,
   * because the order a directory listing returns is not a promise any filesystem makes (ADR 0004
   * §4), and a recovery that picked a checkpoint by listing order would be nondeterministic in
   * exactly the way this ticket exists to remove. The zero-padded name makes lexicographic order
   * equal numeric order, which is why the sort can be by name — but it is written as a sort by
   * parsed watermark so that a change to the padding cannot silently reorder recovery.
   *
   * <p>Temp files are not in the list at all: a half-written swap is skipped by name, before a byte
   * of it is trusted.
   */
  public List<Path> newestFirst() throws IOException {
    if (!Files.isDirectory(directory)) {
      return List.of();
    }
    List<Path> files = new ArrayList<>();
    try (Stream<Path> listing = Files.list(directory)) {
      listing
          .filter(Files::isRegularFile)
          .filter(path -> CheckpointFormat.isCheckpointName(fileName(path)))
          .forEach(files::add);
    }
    files.sort(
        Comparator.comparingLong((Path path) -> CheckpointFormat.watermarkOf(fileName(path)))
            .reversed()
            .thenComparing(Path::toString));
    return files;
  }

  /** Temp files a crash may have left behind: garbage by construction, deleted by the next swap. */
  public List<Path> staleTemps() throws IOException {
    if (!Files.isDirectory(directory)) {
      return List.of();
    }
    List<Path> temps = new ArrayList<>();
    try (Stream<Path> listing = Files.list(directory)) {
      listing
          .filter(Files::isRegularFile)
          .filter(path -> CheckpointFormat.isTempName(fileName(path)))
          .forEach(temps::add);
    }
    temps.sort(Comparator.comparing(Path::toString));
    return temps;
  }

  /**
   * The newest checkpoint that stands up against this log, with every refusal that came first.
   *
   * @param walFile the log the checkpoint must be bound to
   * @param scan recovery's scan of that log, which is what knows where a frame ends and which
   *     frames the clean prefix holds
   */
  public Load load(Path walFile, WalRecovery.Scan scan) throws IOException {
    Objects.requireNonNull(walFile, "wal file");
    Objects.requireNonNull(scan, "scan");
    List<Refusal> refused = new ArrayList<>();
    for (Path file : newestFirst()) {
      try {
        return new Load(verify(file, walFile, scan), file, List.copyOf(refused));
      } catch (CorruptCheckpointException refuse) {
        refused.add(new Refusal(file, refuse.refusal(), refuse.getMessage()));
      } catch (IllegalArgumentException noSuchFrame) {
        refused.add(
            new Refusal(file, CheckpointRefusal.COVERAGE_GONE, noSuchFrame.getMessage()));
      } catch (IOException unreadable) {
        refused.add(
            new Refusal(
                file, CheckpointRefusal.UNREADABLE, String.valueOf(unreadable.getMessage())));
      }
    }
    return new Load(null, null, List.copyOf(refused));
  }

  /** Reads and checks one file against one log. Throws rather than returning a refusal. */
  private Checkpoint verify(Path file, Path walFile, WalRecovery.Scan scan)
      throws IOException {
    Checkpoint checkpoint = Checkpoint.decode(Files.readAllBytes(file));
    long claimed = CheckpointFormat.watermarkOf(fileName(file));
    if (claimed != checkpoint.lastLsn()) {
      throw new CorruptCheckpointException(
          CheckpointRefusal.NAME_MISMATCH,
          "the name claims lsn " + claimed + " and the header says " + checkpoint.lastLsn());
    }
    long end = scan.endOf(checkpoint.lastLsn());
    if (end != checkpoint.coveredBytes()) {
      throw new CorruptCheckpointException(
          CheckpointRefusal.COVERAGE_GONE,
          "the state is at lsn "
              + checkpoint.lastLsn()
              + ", which ends at byte "
              + end
              + " of the log, and the checkpoint covers "
              + checkpoint.coveredBytes());
    }
    byte[] actual = Sha256.ofFilePrefix(walFile, checkpoint.coveredBytes());
    if (!Sha256.same(actual, checkpoint.walDigestUnsafe())) {
      throw new CorruptCheckpointException(
          CheckpointRefusal.WAL_DIGEST_MISMATCH,
          "the checkpoint was taken over a different "
              + checkpoint.coveredBytes()
              + " bytes of log than this one: it says "
              + Sha256.hex(checkpoint.walDigestUnsafe())
              + ", the file hashes to "
              + Sha256.hex(actual));
    }
    return checkpoint;
  }

  /**
   * Performs the swap, then retention, reporting each stage to {@code listener}.
   *
   * <p>The temp file is deleted before it is opened, because a stale temp from a crashed swap would
   * otherwise be appended to — and the resulting file would be renamed into place under a
   * checkpoint's name while being two checkpoints long. That is the one way this protocol could
   * produce a file that is corrupt <em>and</em> named as valid, and the fix is one line: a temp is
   * garbage the moment its swap ends, and no swap ever resumes one.
   *
   * @param retain how many checkpoint files to keep; the newest are kept, always including this one
   */
  public Checkpoint write(Checkpoint checkpoint, int retain, CheckpointListener listener)
      throws IOException {
    Objects.requireNonNull(checkpoint, "checkpoint");
    if (retain < 1) {
      throw new IllegalArgumentException("retention of " + retain + " keeps nothing");
    }
    CheckpointListener stages = listener == null ? CheckpointListener.NONE : listener;
    Files.createDirectories(directory);
    String name = CheckpointFormat.nameFor(checkpoint.lastLsn());
    Path temp = directory.resolve(name + CheckpointFormat.TEMP_SUFFIX);
    Path target = directory.resolve(name);
    byte[] bytes = checkpoint.encode();

    Files.deleteIfExists(temp);
    try (DurableChannel out = DurableChannel.openForAppend(temp)) {
      out.write(ByteBuffer.wrap(bytes));
      stages.reached(CheckpointStage.TEMP_WRITTEN);
      // forceAll, not forceData: a checkpoint is a whole file whose length is part of its claim, so
      // both its data and its size must be durable before its name is. This is the same argument
      // ADR 0003 §4 makes for forcing after a truncation, and the opposite of the WAL's own case,
      // where a grown file's length is recoverable from its tail.
      out.forceAll();
      stages.reached(CheckpointStage.TEMP_FORCED);
    }

    // Same directory, so rename(2): readers see the old name or the new one, never a mixture.
    Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE);
    stages.reached(CheckpointStage.RENAMED);

    // The rename is a directory entry, and a directory entry is durable only once the directory
    // itself is forced — the rule ADR 0003 §4 already applies to creating a segment.
    DurableChannel.syncDirectory(directory);
    stages.reached(CheckpointStage.DIRECTORY_SYNCED);

    collect(retain, target);
    stages.reached(CheckpointStage.COLLECTED);
    return checkpoint;
  }

  /**
   * Retention: keep the newest {@code retain} checkpoints and {@code keep} itself, delete the rest
   * and every stale temp. Returns what it deleted, so a caller can report it rather than guess.
   *
   * <p>Nothing here has to be atomic, and that is a property of what is being deleted rather than a
   * shortcut: every file this method removes is a cache of a fold that the log can reproduce, so a
   * crash that resurrects one leaves a directory with one extra valid checkpoint in it. There is no
   * ordering to get wrong, which is why there is no fsync after the deletes.
   */
  public List<Path> collect(int retain, Path keep) throws IOException {
    List<Path> deleted = new ArrayList<>();
    List<Path> files = newestFirst();
    for (int i = 0; i < files.size(); i++) {
      Path file = files.get(i);
      if (i < retain || file.equals(keep)) {
        continue;
      }
      Files.deleteIfExists(file);
      deleted.add(file);
    }
    for (Path stale : staleTemps()) {
      Files.deleteIfExists(stale);
      deleted.add(stale);
    }
    return deleted;
  }

  /** What recovery found in the directory, and what it had to refuse on the way. */
  public record Load(Checkpoint checkpoint, Path file, List<Refusal> refused) {

    /** Whether a checkpoint was usable at all. When false, the caller folds the log from byte 0. */
    public boolean used() {
      return checkpoint != null;
    }

    /** How many files were refused before the usable one, or in total when none was. */
    public int refusals() {
      return refused.size();
    }

    @Override
    public String toString() {
      return used()
          ? "checkpoint " + file.getFileName() + " (" + refusals() + " refused first)"
          : "no usable checkpoint (" + refusals() + " refused), folding the log from the start";
    }
  }

  /** One refused file and why, for a report that says what was wrong rather than that it was. */
  public record Refusal(Path file, CheckpointRefusal why, String detail) {

    @Override
    public String toString() {
      return file.getFileName() + ": " + why + " — " + detail;
    }
  }

  private static String fileName(Path path) {
    return path.getFileName().toString();
  }

}
