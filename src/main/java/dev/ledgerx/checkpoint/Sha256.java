package dev.ledgerx.checkpoint;

import dev.ledgerx.substrate.DurableChannel;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * SHA-256, in the three shapes this package needs, and nowhere else.
 *
 * <p>SHA-256 rather than the WAL's CRC32C because the two are asked different questions. A CRC
 * answers "are these the bytes that were written"; it is cheap, it is in the write path, and a
 * collision is a curiosity. A digest here answers "is this the same <em>state</em>", across
 * processes, machines and builds, and it is the number a human copies out of a log line — so it
 * is the standard hash, it is 64 hex characters, and {@code MessageDigest} supplies it without a
 * dependency. The cost is real and is priced in ADR 0004 §7: SHA-256 runs at roughly a tenth of
 * the speed CRC32C does, which is affordable because a checkpoint is written per cadence, not
 * per commit, and it is never in an ack path.
 *
 * <p>The streamed form exists for the one job the other two cannot do: hashing a prefix of a file
 * without holding it in memory. That is the {@code walDigest} — a checkpoint's claim about the
 * exact bytes of the log it covers (ADR 0004 §5).
 */
public final class Sha256 {

  /** Chunk size for hashing a file prefix. Big enough to matter, small enough to be a loser. */
  private static final int CHUNK_BYTES = 64 * 1024;

  private Sha256() {}

  /** A fresh digest. SHA-256 is required of every JCA provider, so the catch is unreachable. */
  static MessageDigest newDigest() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException missing) {
      throw new IllegalStateException("SHA-256 is required by the JCA specification", missing);
    }
  }

  /** The digest of these bytes. */
  public static byte[] of(byte[] bytes) {
    return newDigest().digest(bytes);
  }

  /** The digest of a string, for the digests-of-names case. */
  static byte[] of(String text) {
    return of(text.getBytes(StandardCharsets.US_ASCII));
  }

  /** Lowercase hex, the form the checkpoint's trailer carries in text and the ADR quotes. */
  static String hex(byte[] digest) {
    StringBuilder hex = new StringBuilder(digest.length * 2);
    for (byte b : digest) {
      hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
    }
    return hex.toString();
  }

  /**
   * The SHA-256 of {@code bytes} bytes taken from the start of {@code file}. Streams, because a
   * log prefix is not going to be read into a byte array to be a checkpoint's footnote.
   *
   * <p>It reads through the same {@link DurableChannel} the WAL uses, so the substrate rule that
   * every file this project touches is opened through one place keeps holding.
   */
  static byte[] ofFilePrefix(Path file, long bytes) throws IOException {
    MessageDigest digest = newDigest();
    if (bytes == 0L) {
      return digest.digest();
    }
    byte[] chunk = new byte[CHUNK_BYTES];
    long position = 0L;
    try (DurableChannel in = DurableChannel.openForRead(file)) {
      while (position < bytes) {
        int want = (int) Math.min(CHUNK_BYTES, bytes - position);
        int got = in.readAt(ByteBuffer.wrap(chunk, 0, want), position);
        if (got <= 0) {
          throw new IOException(
              "the log ended after "
                  + position
                  + " of the "
                  + bytes
                  + " bytes a checkpoint claims to cover");
        }
        digest.update(chunk, 0, got);
        position += got;
      }
    }
    return digest.digest();
  }

  /** Whether two digests are the same bytes, spelled so a reader does not reach for equals. */
  static boolean same(byte[] left, byte[] right) {
    return MessageDigest.isEqual(left, right);
  }
}
