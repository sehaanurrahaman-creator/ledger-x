package dev.ledgerx.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Renders a ledger as text and hashes it — the test suite's eyes.
 *
 * <p>"Entries never mutate" cannot be checked by comparing an entry to itself: if a field
 * changed in place, both sides of the comparison changed with it. So every event is rendered
 * to a canonical string the first time it is seen, and re-rendered after every operation;
 * a mutated entry shows up as a different string at a position that already has one. The
 * same rendering, hashed over the whole ledger, is what "a rejected transaction changed
 * nothing" is actually compared with.
 *
 * <p><strong>This is test-only canonicalization, and it is not the replay state hash.</strong>
 * How a durable state hash is canonicalized — field order, encoding, whether balances are
 * included at all — is the checkpoint ticket's decision, and a scheme invented here would
 * quietly become the answer by being the one that exists. What this class does establish is
 * that a canonical form is *possible* without escaping, because {@link AccountId} refuses
 * the delimiters used below; {@code DomainModelProperties} asserts that directly.
 */
public final class JournalDigest {

  /** One digest per suite, reused: {@code getInstance} is the expensive part of hashing. */
  private final MessageDigest sha256;

  private final List<String> rendered = new ArrayList<>();

  public JournalDigest() {
    try {
      this.sha256 = MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException missing) {
      throw new IllegalStateException("SHA-256 is required by the JCA specification", missing);
    }
  }

  /** How many events this watcher has already rendered and recorded. */
  public int eventsSeen() {
    return rendered.size();
  }

  /**
   * Re-renders every event in the ledger and asserts that the ones already seen are
   * byte-identical to what was recorded. Then records any new ones.
   *
   * @return the number of events appended since the last call
   * @throws AssertionError if an earlier event changed, with the position and both renderings
   */
  public int observe(InMemoryLedger ledger) {
    List<JournalEvent> events = ledger.events();
    for (int i = 0; i < rendered.size(); i++) {
      if (i >= events.size()) {
        throw new AssertionError(
            "the journal shrank: " + rendered.size() + " events recorded, " + events.size()
                + " present — append-only was violated");
      }
      String now = canonical(events.get(i));
      if (!now.equals(rendered.get(i))) {
        throw new AssertionError(
            "event " + i + " mutated after it was appended\n  was: " + rendered.get(i)
                + "\n  now: " + now);
      }
    }
    int appended = 0;
    for (int i = rendered.size(); i < events.size(); i++) {
      rendered.add(canonical(events.get(i)));
      appended++;
    }
    return appended;
  }

  /** SHA-256 of the whole ledger state: accounts, every event, and every balance. */
  public String stateDigest(InMemoryLedger ledger) {
    StringBuilder text = new StringBuilder();
    for (Account account : ledger.accounts()) {
      text.append(canonical(account)).append('\n');
    }
    for (JournalEvent event : ledger.events()) {
      text.append(canonical(event)).append('\n');
    }
    for (Map.Entry<AccountId, Money> row : ledger.balances().entrySet()) {
      text.append(row.getKey().value())
          .append('=')
          .append(row.getValue().minorUnits())
          .append('\n');
    }
    return sha256Hex(text.toString());
  }

  private String sha256Hex(String text) {
    sha256.reset();
    byte[] digest = sha256.digest(text.getBytes(StandardCharsets.UTF_8));
    StringBuilder hex = new StringBuilder(digest.length * 2);
    for (byte b : digest) {
      hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
    }
    return hex.toString();
  }

  // --- canonical rendering -------------------------------------------------------------
  // Delimiters are space, comma, semicolon and brackets — precisely the characters an
  // AccountId cannot contain, so the rendering is injective without an escaping scheme.

  public static String canonical(Account account) {
    return "account(" + account.id().value() + "," + account.kind() + ")";
  }

  public static String canonical(Entry entry) {
    return entry.side()
        + " "
        + entry.account().value()
        + " "
        + entry.amount().minorUnits();
  }

  public static String canonical(Transaction transaction) {
    StringBuilder text = new StringBuilder("tx[");
    List<Entry> entries = transaction.entries();
    for (int i = 0; i < entries.size(); i++) {
      if (i > 0) {
        text.append(';');
      }
      text.append(canonical(entries.get(i)));
    }
    return text.append(']').toString();
  }

  public static String canonical(JournalEvent event) {
    if (event instanceof JournalEvent.AccountOpened opened) {
      return "opened:" + canonical(opened.account());
    }
    if (event instanceof JournalEvent.Posted posted) {
      return "posted:" + canonical(posted.transaction());
    }
    if (event instanceof JournalEvent.PostedIdempotently keyed) {
      // The key material is part of what must never mutate after the append, so it is rendered
      // like the entries: identity, fingerprint, instant, then the transaction.
      return "keyed:" + keyed.merchant() + "/" + keyed.key() + "/"
          + keyed.fingerprint().hex() + "/" + keyed.capturedAtMillis() + ":"
          + canonical(keyed.transaction());
    }
    throw new AssertionError("an event kind this suite does not know how to render: " + event);
  }
}
