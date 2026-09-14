package dev.ledgerx.domain;

/**
 * One thing that happened to the ledger, in the order it happened.
 *
 * <p>The log holds two kinds of event and both are in <em>one</em> list, which is a decision
 * rather than a convenience. Account openings move no money, so they could have lived in a
 * second list beside the journal — but then there would be two sequence spaces, and replay
 * would need a merge rule to interleave them. One list has one sequence space, and the
 * sequence number of an event is its position, which is exactly what a write-ahead log's
 * record offset gives you for free. ADR 0002 §8 records the trade.
 *
 * <p>Sealed, with two record implementations: the set of things that can happen to a ledger
 * is small, closed, and about to be encoded into bytes. A closed hierarchy means a fold over
 * the log can be checked for exhaustiveness by the compiler instead of by an
 * {@code else throw} that only fires in production.
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
}
