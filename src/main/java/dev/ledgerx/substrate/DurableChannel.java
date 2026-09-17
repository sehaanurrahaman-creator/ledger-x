package dev.ledgerx.substrate;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * The durability primitives that ADR 0001 commits ledger-x to, and nothing else.
 *
 * <p>There is deliberately no ledger here: no accounts, no entries, no balances, no replay. This
 * class exists so that the boundaries in ADR 0001's commit table are the only way anything reaches
 * storage, and so that the substrate contract test can prove they behave as the ADR claims. Every
 * file the write-ahead log touches is opened through these methods and no others, which is what
 * makes "the WAL owns its own fsync" a checkable statement rather than a review habit.
 *
 * <p>On Linux the {@code force} variants are exactly one syscall each:
 * {@code FileChannel.force(false)} is {@code fdatasync(fd)} and {@code FileChannel.force(true)} is
 * {@code fsync(fd)} — see {@code sun.nio.ch.UnixFileDispatcherImpl.force0} in the JDK.
 *
 * <p>ADR 0003 added the two operations recovery needs and no others: a positioned read, because a
 * scan must not disturb the append handle, and a truncate, because cutting a torn tail is the
 * decided repair. {@link #truncateTo} is the only method here that destroys data, which is why it
 * takes a length and not a direction. ADR 0004 added one more, {@link #openForRewrite}, for the
 * scratch file a checkpoint is written to before it is renamed into place.
 */
public final class DurableChannel implements AutoCloseable {

  private final FileChannel channel;

  private DurableChannel(FileChannel channel) {
    this.channel = channel;
  }

  /** Opens (creating if needed) an append-only WAL file. Appends never rewrite earlier bytes. */
  public static DurableChannel openForAppend(Path file) throws IOException {
    FileChannel opened =
        FileChannel.open(
            file,
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE,
            StandardOpenOption.APPEND);
    return new DurableChannel(opened);
  }

  /** Opens a file read-only, which is what a recovery scan uses. Never creates. */
  public static DurableChannel openForRead(Path file) throws IOException {
    return new DurableChannel(FileChannel.open(file, StandardOpenOption.READ));
  }

  /**
   * Opens a file writable but <em>not</em> in append mode, which is what a truncate needs: an
   * append-mode channel is defined to write at EOF, and the repair is about making the file end
   * earlier.
   */
  public static DurableChannel openForTruncate(Path file) throws IOException {
    return new DurableChannel(FileChannel.open(file, StandardOpenOption.WRITE));
  }

  /**
   * Opens a scratch file for a complete rewrite, creating it and discarding whatever it held.
   *
   * <p>ADR 0004 added this one, and it exists for a checkpoint's temporary file: a snapshot is
   * written whole, forced, and only then renamed into place, so the handle it needs is
   * write-and-replace rather than append. Nothing else in the repository may rewrite a file's
   * earlier bytes, and keeping the exception inside this class is what keeps that a checkable
   * statement.
   */
  public static DurableChannel openForRewrite(Path file) throws IOException {
    return new DurableChannel(
        FileChannel.open(
            file,
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE,
            StandardOpenOption.TRUNCATE_EXISTING));
  }

  /** Writes every remaining byte. Partial writes are retried until the buffer is drained. */
  public void write(ByteBuffer bytes) throws IOException {
    while (bytes.hasRemaining()) {
      channel.write(bytes);
    }
  }

  /** {@code fdatasync}: the data is durable; the file's metadata (size, mtime) may not be. */
  public void forceData() throws IOException {
    channel.force(false);
  }

  /** {@code fsync}: data and metadata durable. Used at open, close, truncation and checkpoint. */
  public void forceAll() throws IOException {
    channel.force(true);
  }

  /**
   * Fills {@code dst} from {@code position}, stopping at EOF, and returns how many bytes came
   * back, or 0 at or past the end. A short read is not an error: it is the shape of a torn tail,
   * and the caller decides what it means. Looping on this method would spin, because 0 is an answer
   * rather than a request to retry.
   */
  public int readAt(ByteBuffer dst, long position) throws IOException {
    int read = 0;
    while (dst.hasRemaining()) {
      int got = channel.read(dst, position + read);
      if (got < 0) {
        break;
      }
      read += got;
    }
    return read;
  }

  /**
   * Makes the file {@code bytes} long, discarding whatever was past it. This is the torn-tail
   * repair; it is durable only once a {@link #forceAll()} follows, because a truncation is a change
   * to the file's size and POSIX promises nothing about a shrunk size reaching the device on
   * {@code fdatasync}.
   */
  public void truncateTo(long bytes) throws IOException {
    channel.truncate(bytes);
  }

  public long size() throws IOException {
    return channel.size();
  }

  @Override
  public void close() throws IOException {
    channel.close();
  }

  /**
   * {@code fsync} of the directory itself, which is what makes a create or rename inside it
   * durable. Opening a directory read-only and forcing it is the portable-on-Linux idiom; a
   * filesystem that refuses it fails loudly here rather than silently losing the directory entry
   * after a crash.
   */
  public static void syncDirectory(Path directory) throws IOException {
    try (FileChannel dir = FileChannel.open(directory, StandardOpenOption.READ)) {
      dir.force(true);
    }
  }
}
