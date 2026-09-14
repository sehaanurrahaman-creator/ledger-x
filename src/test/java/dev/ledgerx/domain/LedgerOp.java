package dev.ledgerx.domain;

import java.util.List;

/**
 * One operation in a randomly generated sequence: the alphabet the property suite writes its
 * cases in.
 *
 * <p>Operations are data, not closures, and that is what makes them shrinkable. A case is a
 * {@code List<LedgerOp>} that can be printed, cut in half, have an element dropped, be
 * re-executed from a seed and be replayed after shrinking — none of which is possible if an
 * operation is a lambda that captured the ledger it was generated against. Accounts are
 * therefore named by <em>slot</em> (the index into the accounts opened so far, taken modulo
 * the count at apply time) rather than by id, so an operation stays meaningful in a sequence
 * where earlier openings were shrunk away.
 */
public sealed interface LedgerOp {

  /** Opens the next account id, which the applier mints as {@code acct-<n>}. */
  record OpenAccount(AccountKind kind) implements LedgerOp {}

  /**
   * Attempts a posting. Whether it should succeed is not stored here: the model ledger
   * decides that independently of the production code, and the applier compares the two
   * verdicts. An operation that carried its own expected outcome would be an operation that
   * could only ever confirm the generator's opinion of itself.
   */
  record Post(List<OpEntry> entries) implements LedgerOp {

    public Post {
      entries = List.copyOf(entries);
    }

    public int size() {
      return entries.size();
    }
  }
}
