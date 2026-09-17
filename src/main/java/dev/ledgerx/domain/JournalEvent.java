package dev.ledgerx.domain;

/**
 * One thing that happened to the ledger, in the order it happened.
 *
 * <p>The log holds three kinds of event, all in <em>one</em> list, which is a decision
 * rather than a convenience. Account openings move no money, so they could have lived in a
 * second list beside the journal — but then there would be two sequence spaces, and replay
 * would need a merge rule to interleave them. One list has one sequence space, and the
 * sequence number of an event is its position, which is exactly what a write-ahead log's
 * record offset gives you for free. ADR 0002 §8 records the trade; ADR 0005 §4 records why
 * the third kind could not be two records instead of one.
 *
 * <p>Sealed, with three record implementations: the set of things that can happen to a ledger
 * is small, closed, and encoded into bytes. A closed hierarchy means a fold over the log can
 * be checked for exhaustiveness by the compiler instead of by an {@code else throw} that only
 * fires in production.
 */
public sealed interface JournalEvent {

  /**
   * An account came into existence. Immutable from here on: there is no re-kind event and no
   * close event, which is why replaying every {@code AccountOpened} before every
   * {@code Posted} produces the same state as replaying the log in order.
   */
  record AccountOpened(Account account) implements JournalEvent {}

  /**
   * A balanced transaction was appended. The entries inside it will never be edited, moved
   * or removed; the only thing that can happen after this event is another event.
   */
  record Posted(Transaction transaction) implements JournalEvent {}

  /**
   * A balanced transaction was appended <em>under an idempotency key</em>: one event, one
   * record, one unit of commit — the posting and the key's binding are the same bytes, so a
   * crash cannot land between them (ADR 0005 §4).
   *
   * <p>The event carries what the record carries, minus the record's frame: the scope and the
   * key, the fingerprint of the body the key was bound to, the server's capture instant, and the
   * transaction. It deliberately does <em>not</em> carry the LSN of its own record — position is
   * the frame's to say, exactly as with {@link Posted} — so the domain's binding row gets its
   * {@code responseLsn} from whichever caller owns the log: the writer, after the ack; the fold,
   * from the record it is applying.
   *
   * <p>The capture instant is data here, not a clock read: the domain never asks what time it
   * is, it only remembers what time it was told, which is the whole of the rule that keeps
   * replay deterministic while expiry still works (ADR 0005 §5).
   *
   * @param merchant the scope the key is unique within
   * @param key the client's key, bound by this event and never bindable again in this scope
   * @param fingerprint the body the key was bound to, committed so a conflict is detectable for
   *     as long as the binding exists
   * @param capturedAtMillis the server wall clock when the request was accepted, committed as
   *     bytes and never recomputed
   * @param transaction the posting, which is also the stored response a replay returns
   */
  record PostedIdempotently(
      MerchantId merchant,
      IdempotencyKey key,
      RequestFingerprint fingerprint,
      long capturedAtMillis,
      Transaction transaction)
      implements JournalEvent {}
}
