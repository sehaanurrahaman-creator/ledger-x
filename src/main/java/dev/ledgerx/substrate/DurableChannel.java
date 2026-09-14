package dev.ledgerx.substrate;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * The durability primitives that ADR 0001 commits ledger-x to, and nothing else.
 *
 * <p>There is deliberately no ledger here: no accounts, no entries, no balances, no
 * replay. This class exists so that the four boundaries in the ADR's commit table are the
 * only way the WAL will ever reach storage, and so that the substrate contract test can
 * prove they behave as the ADR claims.
 *
 * <p>On Linux the {@code force} variants are exactly one syscall each:
 * {@code FileChannel.force(false)} is {@code fdatasync(fd)} and
 * {@code FileChannel.force(true)} is {@code fsync(fd)} — see
 * {@code sun.nio.ch.UnixFileDispatcherImpl.force0} in the JDK.
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

  /** {@code fsync}: data and metadata durable. Used at open, close, rotation and checkpoint. */
  public void forceAll() throws IOException {
    channel.force(true);
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
   * filesystem that refuses it fails loudly here rather than silently losing the directory
   * entry after a crash.
   */
  public static void syncDirectory(Path directory) throws IOException {
    try (FileChannel dir = FileChannel.open(directory, StandardOpenOption.READ)) {
      dir.force(true);
    }
  }
}
