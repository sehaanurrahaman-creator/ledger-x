package dev.ledgerx.domain;

import java.util.Objects;

/**
 * One movement of money against one account, in one direction: the atom of the ledger.
 *
 * <p>An entry is immutable and append-only. There is no setter, no correcting entry, no
 * delete — the only way to undo an entry is to append its opposite in a new transaction,
 * which is what the charter means and what the property suite checks by re-hashing every
 * entry it has ever seen after every single operation.
 *
 * <p>The amount is strictly positive and the {@link Side} carries the direction. An entry
 * for zero minor units cannot exist: it would move nothing, it would be indistinguishable
 * from no entry at all in a total, and it would let a "balanced" transaction balance by
 * padding. Refusing it here, in the type, is strictly stronger than refusing it in a
 * validator that a future caller might forget to run.
 *
 * @param account the account this entry moves
 * @param side the direction it moves it in
 * @param amount how much, strictly positive, in integer minor units
 */
public record Entry(AccountId account, Side side, Money amount) {

  public Entry {
    Objects.requireNonNull(account, "entry account");
    Objects.requireNonNull(side, "entry side");
    Objects.requireNonNull(amount, "entry amount");
    if (!amount.isPositive()) {
      throw new IllegalArgumentException(
          "an entry's amount must be positive — the side carries the direction, so "
              + side
              + " "
              + amount.toMajorString()
              + " against "
              + account
              + " is not an entry");
    }
  }

  public static Entry debit(AccountId account, Money amount) {
    return new Entry(account, Side.DEBIT, amount);
  }

  public static Entry credit(AccountId account, Money amount) {
    return new Entry(account, Side.CREDIT, amount);
  }

  /**
   * This entry's contribution to its account's balance on the debit-positive axis: the
   * amount for a debit, its negation for a credit. Summed over every entry of every
   * transaction, this is Σ balances.
   */
  public Money signedAmount() {
    return side.sign() > 0 ? amount : amount.negated();
  }

  /** The entry that would undo this one, if a transaction ever needs to reverse it. */
  public Entry reversal() {
    return new Entry(account, side.opposite(), amount);
  }
}
