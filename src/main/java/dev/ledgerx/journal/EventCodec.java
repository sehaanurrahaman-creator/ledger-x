package dev.ledgerx.journal;

import dev.ledgerx.checkpoint.Sha256;
import dev.ledgerx.domain.Account;
import dev.ledgerx.domain.AccountId;
import dev.ledgerx.domain.AccountKind;
import dev.ledgerx.domain.Entry;
import dev.ledgerx.domain.IdempotencyKey;
import dev.ledgerx.domain.JournalEvent;
import dev.ledgerx.domain.MerchantId;
import dev.ledgerx.domain.Money;
import dev.ledgerx.domain.RequestFingerprint;
import dev.ledgerx.domain.Side;
import dev.ledgerx.domain.Transaction;
import dev.ledgerx.wal.Corruption;
import dev.ledgerx.wal.RecordType;
import dev.ledgerx.wal.UnrecoverableLogException;
import dev.ledgerx.wal.WalFormat;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The ledger's event kinds as bytes: ADR 0002's event log, encoded — three kinds since ADR 0005.
 *
 * <p>The encoding is fixed-width fields and lengths, no delimiters and no escaping, because
 * {@link AccountId}'s alphabet already excludes every character a textual encoding would need to
 * quote — which is the property ADR 0002 established and this class spends its layout on. A
 * textual form would have been shorter to write and would have made one record parse two ways,
 * which is the specific thing that turns "replay" into "probably replay".
 *
 * <pre>{@code
 * ACCOUNT_OPENED  u8 kind | u8 idLength | id bytes (7-bit ASCII)
 * POSTED     u16 entryCount | entry*   where entry = u8 idLength | id | u8 side | i64 minUnits
 * }</pre>
 *
 * <p>What is <em>not</em> in a payload, and why, is the interesting half of the format:
 *
 * <ul>
 *   <li><strong>No LSN.</strong> The frame carries it. A second copy inside the payload would be a
 *       second source of truth about position, and ADR 0002 already refused a transaction id for
 *       exactly that reason.
 *   <li><strong>No transaction id and no timestamp.</strong> Inherited, not decided here: the
 *       domain
 *       has neither, and a clock read inside a record would make replay non-deterministic —
 *       standing design-review question 5 answered for the storage layer as "the log never reads
 *       one, so there is nothing for two nodes' clocks to disagree about". The one exception is
 *       the idempotency record's <em>capture instant</em> (ADR 0005 §5): a value the writer
 *       committed, not a time this codec reads — replay reproduces the bytes exactly, and the
 *       determinism rule survives because nothing in the fold ever consults a clock.
 *   <li><strong>No currency code.</strong> One currency and a constant exponent (ADR 0002 §2),
 *       so the
 *       field would be identical in every record. Naming it is the same change as widening money to
 *       128 bits: a format change, deferred with the reason recorded rather than forgotten.
 *   <li><strong>The idempotency key is a record type, not a field inside a transaction.</strong>
 *       ADR 0002 deferred it to the idempotency ticket, and the shape it arrives in is the one
 *       this javadoc always predicted: the key and the entries it guards are <em>one record</em>,
 *       because a key must be durable in the same unit of commit as the posting — one append,
 *       one force, one ack — and a crash between two records reopens the double-post window
 *       (ADR 0005 §4).
 *   <li><strong>No checksum of the decoded event.</strong> The frame's CRC32C covers the payload
 *       already; a second digest would be a field a corrupt writer could leave consistent.
 * </ul>
 *
 * <p>Every decoder here is bounded by the frame it was handed: a payload that runs out mid-entry is
 * a malformed record, never a read past a buffer.
 */
public final class EventCodec {

  /** Bytes an entry costs beyond its account id: length, side, amount. */
  public static final int ENTRY_FIXED_BYTES = 10;

  /**
   * The idempotency record's fixed bytes beyond the names and the entry list: the two length
   * bytes and the capture instant and the fingerprint. The entry count is not here — it is part
   * of the entry-list bytes a keyed posting shares byte for byte with a plain one, so counting
   * it here as well would allocate two bytes the decoder would rightly refuse to consume.
   */
  public static final int IDEMPOTENT_FIXED_BYTES =
      1 + 1 + 8 + RequestFingerprint.BYTES;

  /**
   * The operation tag a request fingerprint starts with: {@code 'P'} for a posting. The tag is
   * what keeps fingerprints honest across operation kinds, the day a key can be used for
   * something other than a posting — a body that matches by bytes but means a different
   * operation must not compare equal.
   */
  public static final int REQUEST_TAG_POSTING = 'P';

  private EventCodec() {}

  /** The record type a ledger event serializes as. */
  public static RecordType typeOf(JournalEvent event) {
    if (event instanceof JournalEvent.AccountOpened) {
      return RecordType.ACCOUNT_OPENED;
    }
    if (event instanceof JournalEvent.PostedIdempotently) {
      return RecordType.IDEMPOTENT_POSTING;
    }
    return RecordType.POSTED;
  }

  /** The payload bytes for one event, ready for {@code Wal.append}. */
  public static byte[] encode(JournalEvent event) {
    if (event instanceof JournalEvent.AccountOpened opened) {
      return encodeOpenAccount(opened.account());
    }
    if (event instanceof JournalEvent.Posted posted) {
      return encodePosted(posted.transaction());
    }
    if (event instanceof JournalEvent.PostedIdempotently keyed) {
      return encodePostedIdempotently(keyed);
    }
    throw new IllegalArgumentException("an event kind this codec does not know: " + event);
  }

  /**
   * The request fingerprint of a posting: SHA-256 over {@code u8 REQUEST_TAG_POSTING} followed by
   * the canonical entry list — the same bytes a {@code POSTED} payload carries — so "the same
   * body" means "the same entries, in the order the client sent them", and nothing else.
   *
   * <p>This is the only place a fingerprint is computed, on purpose. Two computations that could
   * disagree — one for the binding, one for the check — would turn every replay into a
   * conflict,
   * so the derivation lives beside the encoding it digests, and everything else in the repository
   * compares {@link RequestFingerprint}s without knowing how they were made.
   */
  public static RequestFingerprint fingerprintOf(Transaction transaction) {
    byte[] body = encodePosted(transaction);
    byte[] tagged = new byte[1 + body.length];
    tagged[0] = (byte) REQUEST_TAG_POSTING;
    System.arraycopy(body, 0, tagged, 1, body.length);
    return RequestFingerprint.of(Sha256.of(tagged));
  }

  private static byte[] encodeOpenAccount(Account account) {
    byte[] id = ascii(account.id().value());
    byte[] payload = new byte[2 + id.length];
    payload[0] = (byte) account.kind().ordinal();
    payload[1] = (byte) id.length;
    System.arraycopy(id, 0, payload, 2, id.length);
    return payload;
  }

  private static byte[] encodePosted(Transaction transaction) {
    List<Entry> entries = transaction.entries();
    int size = 2;
    byte[][] ids = new byte[entries.size()][];
    for (int i = 0; i < entries.size(); i++) {
      ids[i] = ascii(entries.get(i).account().value());
      size += 1 + ids[i].length + 1 + 8;
    }
    byte[] payload = new byte[size];
    WalFormat.putShort(payload, 0, entries.size());
    int at = 2;
    for (int i = 0; i < entries.size(); i++) {
      Entry entry = entries.get(i);
      payload[at++] = (byte) ids[i].length;
      System.arraycopy(ids[i], 0, payload, at, ids[i].length);
      at += ids[i].length;
      payload[at++] = (byte) (entry.side() == Side.DEBIT ? 0 : 1);
      WalFormat.putLong(payload, at, entry.amount().minorUnits());
      at += 8;
    }
    return payload;
  }

  /**
   * The keyed posting: the scope, the key, the capture instant, the fingerprint of the body, and
   * the entries — one payload, so one frame, so one commit (ADR 0005 §4).
   *
   * <p>The entry list is byte-identical to a {@code POSTED} payload's, which is not a coincidence
   * to tolerate but a rule to keep: {@link #fingerprintOf} digests exactly those bytes, so a keyed
   * and a plain encoding of one transaction must agree for the fingerprint of a request to be
   * stable across how it was filed.
   */
  private static byte[] encodePostedIdempotently(JournalEvent.PostedIdempotently event) {
    byte[] merchant = text(event.merchant().value(), MerchantId.MAX_LENGTH, "merchant id");
    byte[] key = text(event.key().value(), IdempotencyKey.MAX_LENGTH, "idempotency key");
    byte[] entries = encodePosted(event.transaction());
    byte[] payload =
        new byte[IDEMPOTENT_FIXED_BYTES + merchant.length + key.length + entries.length];
    int at = 0;
    payload[at++] = (byte) merchant.length;
    System.arraycopy(merchant, 0, payload, at, merchant.length);
    at += merchant.length;
    payload[at++] = (byte) key.length;
    System.arraycopy(key, 0, payload, at, key.length);
    at += key.length;
    WalFormat.putLong(payload, at, event.capturedAtMillis());
    at += 8;
    System.arraycopy(event.fingerprint().bytesUnsafe(), 0, payload, at,
        RequestFingerprint.BYTES);
    at += RequestFingerprint.BYTES;
    System.arraycopy(entries, 0, payload, at, entries.length);
    return payload;
  }

  /** ASCII bytes of a name this format carries, bounded by its type's documented ceiling. */
  private static byte[] text(String value, int maxLength, String what) {
    byte[] bytes = value.getBytes(StandardCharsets.US_ASCII);
    if (bytes.length < 1 || bytes.length > maxLength) {
      throw new IllegalArgumentException(
          "a " + what + " of " + bytes.length + " bytes is outside 1.." + maxLength);
    }
    return bytes;
  }

  /**
   * Decodes one record's payload, insisting it is the event its frame type promises.
   *
   * @throws UnrecoverableLogException if the bytes do not describe what the type says they do,
   *     which
   *     is a {@link Corruption#PAYLOAD_MALFORMED} finding and therefore fatal to the open rather
   *     than truncatable
   */
  public static JournalEvent decode(
      RecordType type, byte[] payload, int offset, int length, long lsn) throws IOException {
    int at = offset;
    int end = offset + length;
    if (type == RecordType.ACCOUNT_OPENED) {
      if (length < 2) {
        throw malformed(lsn, "an account opening needs a kind and an id length");
      }
      int kindOrdinal = payload[at++] & 0xFF;
      AccountKind[] kinds = AccountKind.values();
      if (kindOrdinal >= kinds.length) {
        throw malformed(lsn, "account kind ordinal " + kindOrdinal + " of " + kinds.length);
      }
      int idLength = payload[at++] & 0xFF;
      if (at + idLength != end) {
        throw malformed(
            lsn, "an id of " + idLength + " bytes does not end the payload where the frame does");
      }
      return new JournalEvent.AccountOpened(
          new Account(accountId(payload, at, idLength, lsn), kinds[kindOrdinal]));
    }
    if (type == RecordType.IDEMPOTENT_POSTING) {
      if (length < IDEMPOTENT_FIXED_BYTES) {
        throw malformed(
            lsn, "a keyed posting needs at least " + IDEMPOTENT_FIXED_BYTES + " bytes");
      }
      int merchantLength = payload[at++] & 0xFF;
      if (at + merchantLength > end) {
        throw malformed(lsn, "the merchant runs past the end of the payload");
      }
      MerchantId merchant =
          MerchantId.of(textOf(payload, at, merchantLength, lsn, MerchantId.MAX_LENGTH));
      at += merchantLength;
      int keyLength = payload[at++] & 0xFF;
      if (at + keyLength + 8 + RequestFingerprint.BYTES + 2 > end) {
        throw malformed(lsn, "the key, the instant or the fingerprint runs past the payload");
      }
      IdempotencyKey key =
          IdempotencyKey.of(textOf(payload, at, keyLength, lsn, IdempotencyKey.MAX_LENGTH));
      at += keyLength;
      long capturedAtMillis = WalFormat.longAt(payload, at);
      at += 8;
      RequestFingerprint fingerprint =
          RequestFingerprint.of(
              Arrays.copyOfRange(payload, at, at + RequestFingerprint.BYTES));
      at += RequestFingerprint.BYTES;
      int count = WalFormat.shortAt(payload, at);
      at += 2;
      List<Entry> entries = decodeEntries(payload, at, count, end, lsn);
      return new JournalEvent.PostedIdempotently(
          merchant, key, fingerprint, capturedAtMillis, new Transaction(entries));
    }
    if (type != RecordType.POSTED) {
      throw malformed(lsn, type + " is not a ledger event");
    }
    if (length < 2) {
      throw malformed(lsn, "a posting needs an entry count");
    }
    int count = WalFormat.shortAt(payload, at);
    at += 2;
    List<Entry> entries = decodeEntries(payload, at, count, end, lsn);
    return new JournalEvent.Posted(new Transaction(entries));
  }

  /**
   * The entry list both posting records share, decoded once: bounded by the frame, exhausted by
   * the count, and consumed exactly to the end. One decoder for both types is what makes the
   * fingerprint's promise — a keyed and a plain encoding of one transaction are the same bytes
   * —
   * checkable rather than hoped for.
   */
  private static List<Entry> decodeEntries(
      byte[] payload, int at, int count, int end, long lsn) throws IOException {
    List<Entry> entries = new ArrayList<>(count);
    for (int i = 0; i < count; i++) {
      if (at >= end) {
        throw malformed(lsn, "entry " + i + " of " + count + " is not there");
      }
      int idLength = payload[at++] & 0xFF;
      if (at + idLength + 9 > end) {
        throw malformed(lsn, "entry " + i + " runs past the end of the payload");
      }
      AccountId account = accountId(payload, at, idLength, lsn);
      at += idLength;
      int side = payload[at++] & 0xFF;
      if (side > 1) {
        throw malformed(lsn, "side is " + side + ", and there are exactly two");
      }
      long minorUnits = WalFormat.longAt(payload, at);
      at += 8;
      entries.add(
          new Entry(
              account, side == 0 ? Side.DEBIT : Side.CREDIT, Money.ofMinor(minorUnits)));
    }
    if (at != end) {
      throw malformed(lsn, (end - at) + " bytes are left over after " + count + " entries");
    }
    return entries;
  }

  /** A length-bounded ASCII name, validated by its type — the decode side of {@link #text}. */
  private static String textOf(
      byte[] payload, int at, int length, long lsn, int maxLength) throws IOException {
    if (length < 1 || length > maxLength) {
      throw malformed(lsn, "a name of " + length + " bytes is outside 1.." + maxLength);
    }
    for (int i = 0; i < length; i++) {
      if ((payload[at + i] & 0xFF) > 0x7F) {
        throw malformed(lsn, "a name is 7-bit ASCII and byte " + i + " is not");
      }
    }
    return new String(payload, at, length, StandardCharsets.US_ASCII);
  }

  /** Decodes the payload of a record the caller has already framed. */
  public static JournalEvent decode(RecordType type, byte[] payload, long lsn) throws IOException {
    return decode(type, payload, 0, payload.length, lsn);
  }

  private static AccountId accountId(byte[] payload, int at, int length, long lsn)
      throws IOException {
    if (length < 1 || length > AccountId.MAX_LENGTH) {
      throw malformed(lsn, "an account id of " + length + " bytes is outside 1.."
          + AccountId.MAX_LENGTH);
    }
    for (int i = 0; i < length; i++) {
      if ((payload[at + i] & 0xFF) > 0x7F) {
        throw malformed(lsn, "an account id is 7-bit ASCII and byte " + i + " is not");
      }
    }
    try {
      return AccountId.of(new String(payload, at, length, StandardCharsets.US_ASCII));
    } catch (IllegalArgumentException outsideTheAlphabet) {
      throw malformed(
          lsn, "an account id outside the alphabet: " + outsideTheAlphabet.getMessage());
    }
  }

  private static byte[] ascii(String text) {
    byte[] bytes = text.getBytes(StandardCharsets.US_ASCII);
    if (bytes.length > AccountId.MAX_LENGTH) {
      throw new IllegalArgumentException("an account id longer than " + AccountId.MAX_LENGTH);
    }
    return bytes;
  }

  private static UnrecoverableLogException malformed(long lsn, String detail) {
    return new UnrecoverableLogException(
        Corruption.PAYLOAD_MALFORMED, -1L, null, "lsn " + lsn + ": " + detail, null);
  }
}
