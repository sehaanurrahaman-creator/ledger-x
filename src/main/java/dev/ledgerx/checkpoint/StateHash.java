package dev.ledgerx.checkpoint;

import dev.ledgerx.domain.AccountId;
import dev.ledgerx.domain.AccountKind;
import dev.ledgerx.domain.AccountState;
import dev.ledgerx.domain.InMemoryLedger;
import dev.ledgerx.domain.Money;
import dev.ledgerx.wal.WalFormat;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;

/**
 * The state hash ADR 0004 defines: a canonical serialization of the whole materialized state, and
 * SHA-256 over it.
 *
 * <p><strong>What "byte-identical state" means, made testable.</strong> Two ledgers are the same
 * state if and only if their canonical bytes are the same bytes. That turns the charter's claim —
 * replaying the log after a crash yields byte-identical state — into an equality between two
 * 32-byte digests, which is a thing a harness can assert on every prefix of every history rather
 * than a thing a reviewer has to agree with.
 *
 * <p><strong>The encoding, in full.</strong>
 *
 * <pre>{@code
 * +0   u8   canonical version (1)
 * +1   u8   domain tag 'S' (0x53)
 * +2   u16  reserved (0)
 * +4   u32  account count
 * +8   one row per account, ascending by id (AccountState.CANONICAL_ORDER):
 *        u8   id length (1..64)
 *        u8[] id bytes, 7-bit ASCII
 *        u8   AccountKind ordinal
 *        i64  balance, signed minor units, big-endian two's complement
 * }</pre>
 *
 * <p><strong>Why these choices, each a determinism rule rather than a preference:</strong>
 *
 * <ul>
 *   <li><strong>Sorted by id, never by insertion.</strong> Opening order is a fact about where a
 *       recovery started, not about the money: a ledger restored from a checkpoint opens the
 *       checkpointed accounts before the tail's, a from-scratch fold opens them in log order. A
 *       hash over insertion order would therefore differ between the two paths that must agree.
 *   <li><strong>Fixed-width integers, no text.</strong> A decimal rendering of a {@code long} is
 *       locale- and library-dependent in ways a two's-complement {@code i64} is not, and
 *       {@code Long.toString} is not something a second implementation is obliged to match.
 *   <li><strong>Lengths, not delimiters.</strong> {@link AccountId}'s alphabet excludes every
 *       character a textual encoding would need to escape, so a length prefix is injective with no
 *       escaping scheme — and an escaping scheme is exactly where two implementations of
 *       "the same" string start to disagree.
 *   <li><strong>A version byte and a domain tag.</strong> The version says which layout produced
 *       these bytes; the tag says what they are a hash <em>of</em>, so a future canonical form for
 *       something else — the idempotency table, say — cannot collide with this one by accident.
 *   <li><strong>Nothing positional.</strong> No LSN, no record count, no offset, no timestamp. ADR
 *       0003 §2 says a log's LSNs are unique only within a surviving prefix, so a cut and a regrow
 *       renumber the tail: a hash that mentioned position would change when the money did not.
 *       State is what is left when you forget how you got there.
 * </ul>
 *
 * <p><strong>What this does not cover, and why that is not a gap.</strong> The hash covers
 * materialized state — accounts, kinds, balances — and not the event list. It cannot cover the
 * event list: a checkpoint's whole purpose is to let recovery skip events, so a hash over every
 * event ever appended could only be reproduced by a checkpoint that stored every event ever
 * appended. The log's own bytes are proven separately, by ADR 0003's record format and its replay
 * check; what this ticket adds is the proof that the state those bytes <em>mean</em> comes back
 * identically. When the idempotency table exists it joins this hash as another section under a new
 * canonical version, because a key that survives a crash in the log but not in the state is the
 * double-payout the charter is about.
 */
public final class StateHash {

  /** Bumped whenever the layout below changes. It is inside the hashed bytes, so it cannot lie. */
  public static final byte CANONICAL_VERSION = 1;

  /** 'S' for state: the domain separator. */
  public static final byte DOMAIN_TAG = 0x53;

  /** Version, tag, reserved, count. */
  public static final int HEADER_BYTES = 8;

  /** SHA-256's output length. */
  public static final int DIGEST_BYTES = 32;

  /** The largest account count the header can name. */
  public static final long MAX_ACCOUNTS = 0xFFFF_FFFFL;

  private StateHash() {}

  /** The canonical bytes of a ledger's state: the digest's preimage and a checkpoint's body. */
  public static byte[] canonical(InMemoryLedger ledger) {
    return canonical(ledger.state());
  }

  /** The canonical bytes of a state given as rows, in canonical order or not. */
  public static byte[] canonical(List<AccountState> rows) {
    List<AccountState> ordered = new ArrayList<>(rows);
    ordered.sort(AccountState.CANONICAL_ORDER);
    int size = HEADER_BYTES;
    byte[][] ids = new byte[ordered.size()][];
    for (int i = 0; i < ordered.size(); i++) {
      ids[i] = ascii(ordered.get(i).id().value());
      size += 1 + ids[i].length + 1 + 8;
    }
    byte[] bytes = new byte[size];
    bytes[0] = CANONICAL_VERSION;
    bytes[1] = DOMAIN_TAG;
    WalFormat.putShort(bytes, 2, 0);
    WalFormat.putInt(bytes, 4, ordered.size());
    int at = HEADER_BYTES;
    for (int i = 0; i < ordered.size(); i++) {
      AccountState row = ordered.get(i);
      bytes[at++] = (byte) ids[i].length;
      System.arraycopy(ids[i], 0, bytes, at, ids[i].length);
      at += ids[i].length;
      bytes[at++] = (byte) row.kind().ordinal();
      WalFormat.putLong(bytes, at, row.balance().minorUnits());
      at += 8;
    }
    return bytes;
  }

  /**
   * Decodes canonical bytes back into rows.
   *
   * <p>Recovery does not need this — it stores the rows it hashed — but a checker does:
   * reading a checkpoint from disk and re-hashing what it decoded is the only way to prove the
   * encoding is
   * injective rather than merely self-consistent.
   *
   * @throws CheckpointFormatException if the bytes are not a canonical state this build writes
   */
  public static List<AccountState> decode(byte[] bytes) {
    if (bytes.length < HEADER_BYTES) {
      throw new CheckpointFormatException(
          bytes.length + " bytes is shorter than the " + HEADER_BYTES + "-byte canonical header");
    }
    if (bytes[0] != CANONICAL_VERSION) {
      throw new CheckpointFormatException(
          "canonical version " + bytes[0] + ", this build writes " + CANONICAL_VERSION);
    }
    if (bytes[1] != DOMAIN_TAG) {
      throw new CheckpointFormatException(
          "domain tag 0x" + Integer.toHexString(bytes[1] & 0xFF) + ", expected 0x"
              + Integer.toHexString(DOMAIN_TAG & 0xFF));
    }
    if (WalFormat.shortAt(bytes, 2) != 0) {
      throw new CheckpointFormatException("the canonical reserved field is not zero");
    }
    int count = WalFormat.intAt(bytes, 4);
    List<AccountState> rows = new ArrayList<>(count);
    int at = HEADER_BYTES;
    for (int i = 0; i < count; i++) {
      if (at >= bytes.length) {
        throw new CheckpointFormatException("row " + i + " of " + count + " is not there");
      }
      int idLength = WalFormat.unsignedByteAt(bytes, at++);
      if (idLength < 1 || idLength > AccountId.MAX_LENGTH) {
        throw new CheckpointFormatException(
            "row " + i + " claims an id of " + idLength + " bytes, outside 1.."
                + AccountId.MAX_LENGTH);
      }
      if (at + idLength + 9 > bytes.length) {
        throw new CheckpointFormatException("row " + i + " runs past the end of the state");
      }
      for (int b = 0; b < idLength; b++) {
        if ((bytes[at + b] & 0xFF) > 0x7F) {
          throw new CheckpointFormatException(
              "row " + i + " holds a non-ASCII id byte at " + b);
        }
      }
      String value = new String(bytes, at, idLength, StandardCharsets.US_ASCII);
      at += idLength;
      int kindOrdinal = WalFormat.unsignedByteAt(bytes, at++);
      AccountKind[] kinds = AccountKind.values();
      if (kindOrdinal >= kinds.length) {
        throw new CheckpointFormatException(
            "row " + i + " claims account kind ordinal " + kindOrdinal + " of " + kinds.length);
      }
      long balance = WalFormat.longAt(bytes, at);
      at += 8;
      rows.add(new AccountState(new AccountId(value), kinds[kindOrdinal], Money.ofMinor(balance)));
    }
    if (at != bytes.length) {
      throw new CheckpointFormatException(
          (bytes.length - at) + " bytes are left over after " + count + " rows");
    }
    return List.copyOf(rows);
  }

  /** SHA-256 of the canonical bytes: the state hash. */
  public static byte[] digest(InMemoryLedger ledger) {
    return digest(canonical(ledger));
  }

  /** SHA-256 of the canonical bytes: the state hash. */
  public static byte[] digest(List<AccountState> rows) {
    return digest(canonical(rows));
  }

  /**
   * SHA-256 over arbitrary bytes.
   *
   * <p>SHA-256 rather than CRC32C here, and the difference is the job: a CRC detects a torn write,
   * which is what the checkpoint file's own CRC32C is for, while this digest has to make an
   * accidental collision between two different states implausible enough to be worth asserting
   * equality on. 128 bits of collision resistance is the point where "the hashes match" becomes
   * evidence rather than luck. Every JDK is required to provide SHA-256 by the JCA specification,
   * so the {@code getInstance} below cannot fail on a conforming runtime.
   */
  public static byte[] digest(byte[] canonicalBytes) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(canonicalBytes);
    } catch (NoSuchAlgorithmException missing) {
      throw new IllegalStateException("SHA-256 is required by the JCA specification", missing);
    }
  }

  /** Lower-case hex, the form a log line and an ADR table can both quote. */
  public static String hex(byte[] bytes) {
    StringBuilder text = new StringBuilder(bytes.length * 2);
    for (byte b : bytes) {
      text.append(Character.forDigit((b >> 4) & 0xF, 16));
      text.append(Character.forDigit(b & 0xF, 16));
    }
    return text.toString();
  }

  /** A digest rendered for a failure message: 16 hex characters is enough to tell two apart. */
  public static String shortHex(byte[] bytes) {
    return hex(bytes).substring(0, 16);
  }

  private static byte[] ascii(String text) {
    byte[] bytes = text.getBytes(StandardCharsets.US_ASCII);
    if (bytes.length > AccountId.MAX_LENGTH) {
      throw new IllegalArgumentException("an account id longer than " + AccountId.MAX_LENGTH);
    }
    return bytes;
  }
}
