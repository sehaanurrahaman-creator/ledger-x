package dev.ledgerx.checkpoint;

import dev.ledgerx.domain.Account;
import dev.ledgerx.domain.AccountId;
import dev.ledgerx.domain.AccountKind;
import dev.ledgerx.domain.InMemoryLedger;
import dev.ledgerx.domain.Money;
import dev.ledgerx.wal.Corruption;
import dev.ledgerx.wal.UnrecoverableLogException;
import dev.ledgerx.wal.WalFormat;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The canonical serialization of ledger state, and its SHA-256: this file <em>is</em> the
 * definition of "byte-identical" that the charter asks for, written down as bytes rather than
 * as intent.
 *
 * <p>Two states are byte-identical when {@link #encode} produces the same bytes for both, and
 * the hash is {@code SHA-256} over those bytes. The encoding is fixed-width fields and
 * lengths, big-endian, no delimiters and no escaping — the same rules as the WAL's payload
 * codec, and for the same reason: an id's alphabet already excludes every byte a length
 * prefix makes unnecessary to quote, so two implementations of "the same" state cannot
 * disagree by a byte. The layout, version 1:
 *
 * <pre>{@code
 * u8  version (1)
 * u64 eventCount            the journal length the state includes
 * u32 accountCount
 * per account, in opening order:
 *   u8 idLength | id (7-bit ASCII) | u8 kind ordinal
 * u32 balanceCount          equal to accountCount; written so a lie is checkable
 * per balance, same order:
 *   i64 minorUnits          signed, debit-positive
 * }</pre>
 *
 * <p><strong>What is in, and why exactly that.</strong> The account table and the balances
 * are the materialized state — the whole of what a checkpoint carries and the whole of what
 * replay produces. The event count is in because balances alone do not pin a history: a
 * ledger that posted 5 and a counter-posting of 5 has the same balances as one that never
 * posted, and a hash that called those two states identical would call a lost transaction
 * invisible. The event count is the journal position, which is as much state as the money
 * is.
 *
 * <p><strong>What is out, and why, is the determinism rule.</strong> No LSNs and no byte
 * offsets: a crash inserts a recovery marker into the log, which shifts every later LSN by
 * one while the ledger state stays the same — an LSN in the hash would make "crash, recover,
 * continue" land on a different final hash than a clean run, which is exactly the identity
 * this ticket proves. No wall-clock time: nothing in {@code src/main} may put a clock read
 * into state, because two folds of one log would then disagree. No random: ids are
 * caller-chosen references, and nothing mints one. No map-iteration order: the encoding
 * walks accounts in <em>opening</em> order, which is a pure function of the event sequence —
 * a {@ java.util.HashMap}'s order would be a function of hash codes and insertion history,
 * which is leakage. No floating point: lint-enforced in the domain, and impossible here by
 * construction, since money is a {@code long} of minor units.
 *
 * <p><strong>The hash is production code, not test code.</strong> Checkpoint files carry it
 * as the state section's own digest, and the replay harness compares it after every commit
 * and after every recovery. The test suite's older {@code JournalDigest} rendering stays
 * test-only; this class is the durable definition, and ADR 0004 §2 is its spec.
 *
 * <p>When the idempotency table arrives it appends its own section here — a count and
 * entries in a canonical key order — and the version byte moves. A hash change is a format
 * change: old checkpoints become unreadable rather than silently re-interpreted, which is
 * the same trade ADR 0003 made with the type table.
 */
public final class StateHash {

  /** The only encoding version this code writes, and the only one it reads. */
  public static final int FORMAT_VERSION = 1;

  /** Fixed part of the encoding: version, event count, two counts. */
  private static final int FIXED_BYTES = 1 + 8 + 4 + 4;

  private StateHash() {}

  /**
   * The canonical bytes for {@code ledger}'s state: accounts in opening order, each with its
   * balance, pinned by the journal length. Two calls on one ledger return equal arrays, and
   * the arrays do not alias the ledger's internals.
   */
  public static byte[] encode(InMemoryLedger ledger) {
    List<Account> accounts = ledger.accounts();
    Map<AccountId, Money> balances = ledger.balances();
    int size = FIXED_BYTES + accounts.size() * (2 + 8);
    for (Account account : accounts) {
      size += account.id().value().length();
    }
    byte[] out = new byte[size];
    int at = 0;
    out[at++] = (byte) FORMAT_VERSION;
    WalFormat.putLong(out, at, ledger.size());
    at += 8;
    WalFormat.putInt(out, at, accounts.size());
    at += 4;
    for (Account account : accounts) {
      String id = account.id().value();
      byte[] idBytes = id.getBytes(StandardCharsets.US_ASCII);
      if (idBytes.length < 1 || idBytes.length > AccountId.MAX_LENGTH) {
        throw new IllegalStateException("an account id outside 1.." + AccountId.MAX_LENGTH);
      }
      out[at++] = (byte) idBytes.length;
      System.arraycopy(idBytes, 0, out, at, idBytes.length);
      at += idBytes.length;
      out[at++] = (byte) account.kind().ordinal();
    }
    WalFormat.putInt(out, at, accounts.size());
    at += 4;
    for (Account account : accounts) {
      Money balance = balances.get(account.id());
      if (balance == null) {
        throw new IllegalStateException("no balance for " + account.id() + "; the index is broken");
      }
      WalFormat.putLong(out, at, balance.minorUnits());
      at += 8;
    }
    if (at != size) {
      throw new IllegalStateException("the encoder wrote " + at + " of " + size + " bytes");
    }
    return out;
  }

  /** SHA-256 over {@link #encode}'s bytes — the state hash the ADR defines. */
  public static byte[] hash(InMemoryLedger ledger) {
    return hash(encode(ledger));
  }

  /** SHA-256 over canonical bytes a caller already holds. */
  public static byte[] hash(byte[] canonical) {
    MessageDigest digest = sha256();
    return digest.digest(canonical);
  }

  /**
   * Restores a ledger from canonical bytes: the inverse of {@link #encode}, in the only
   * sense that matters — {@code encode(decodeLedger(b))} equals {@code b}, which the
   * contract asserts over random histories.
   *
   * <p>The restored ledger's journal <em>begins</em> at the encoded event count: its
   * balances are the encoded ones, and events appended after the restore are the tail
   * replay's. That is what a checkpoint is — materialized state plus a position — and
   * {@link InMemoryLedger#restored} is the domain's whole surface for it.
   *
   * @throws UnrecoverableLogException if the bytes do not decode, which after the
   *     checkpoint's own CRC and hash checks means a collision or a bug, and either way is
   *     not state this build will serve
   */
  public static InMemoryLedger decodeLedger(byte[] canonical) throws IOException {
    int at = 0;
    int version = canonical[at++] & 0xFF;
    if (version != FORMAT_VERSION) {
      throw malformed("state encoding version is " + version + ", this build reads "
          + FORMAT_VERSION);
    }
    long eventCount = WalFormat.longAt(canonical, at);
    at += 8;
    if (eventCount < 0L) {
      throw malformed("the encoded event count is negative: " + eventCount);
    }
    int accountCount = WalFormat.intAt(canonical, at);
    at += 4;
    if (accountCount < 0 || accountCount > canonical.length) {
      throw malformed("an account count of " + accountCount + " cannot fit in "
          + canonical.length + " bytes");
    }
    List<Account> accounts = new ArrayList<>(accountCount);
    for (int i = 0; i < accountCount; i++) {
      if (at + 2 > canonical.length) {
        throw malformed("account " + i + " of " + accountCount + " is not there");
      }
      int idLength = canonical[at++] & 0xFF;
      if (idLength < 1 || idLength > AccountId.MAX_LENGTH) {
        throw malformed("account " + i + " has an id of " + idLength + " bytes, outside 1.."
            + AccountId.MAX_LENGTH);
      }
      if (at + idLength + 1 > canonical.length) {
        throw malformed("account " + i + "'s id runs past the end of the state");
      }
      for (int b = 0; b < idLength; b++) {
        if ((canonical[at + b] & 0xFF) > 0x7F) {
          throw malformed("an account id is 7-bit ASCII and byte " + b + " is not");
        }
      }
      AccountId id;
      try {
        id = AccountId.of(new String(canonical, at, idLength, StandardCharsets.US_ASCII));
      } catch (IllegalArgumentException outsideTheAlphabet) {
        throw malformed("an account id outside the alphabet: " + outsideTheAlphabet.getMessage());
      }
      at += idLength;
      int kindOrdinal = canonical[at++] & 0xFF;
      AccountKind[] kinds = AccountKind.values();
      if (kindOrdinal >= kinds.length) {
        throw malformed("account kind ordinal " + kindOrdinal + " of " + kinds.length);
      }
      accounts.add(new Account(id, kinds[kindOrdinal]));
    }
    if (at + 4 > canonical.length) {
      throw malformed("the balance count is not there");
    }
    int balanceCount = WalFormat.intAt(canonical, at);
    at += 4;
    if (balanceCount != accountCount) {
      throw malformed(balanceCount + " balances for " + accountCount
          + " accounts; the counts must agree");
    }
    if (at + 8L * balanceCount != canonical.length) {
      throw malformed((canonical.length - at) + " bytes of balances where "
          + (8L * balanceCount) + " belong");
    }
    LinkedHashMap<AccountId, Long> balances = new LinkedHashMap<>();
    for (Account account : accounts) {
      balances.put(account.id(), WalFormat.longAt(canonical, at));
      at += 8;
    }
    try {
      return InMemoryLedger.restored(accounts, balances, eventCount);
    } catch (IllegalArgumentException inconsistent) {
      throw malformed("the encoded state is not a ledger: " + inconsistent.getMessage());
    }
  }

  /** Lowercase hex, for logs and for failure messages that must survive a screenshot. */
  public static String hex(byte[] bytes) {
    StringBuilder text = new StringBuilder(bytes.length * 2);
    for (byte b : bytes) {
      text.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
    }
    return text.toString();
  }

  private static UnrecoverableLogException malformed(String detail) {
    return new UnrecoverableLogException(
        Corruption.PAYLOAD_MALFORMED, -1L, null, "checkpoint state: " + detail, null);
  }

  private static MessageDigest sha256() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException missing) {
      throw new IllegalStateException("SHA-256 is required by the JCA specification", missing);
    }
  }
}
