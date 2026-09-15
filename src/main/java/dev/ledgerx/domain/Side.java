package dev.ledgerx.domain;

/**
 * The direction an entry posts in. Double-entry bookkeeping's two verbs, and the only two
 * there are.
 *
 * <p>The side is data, not a sign: an entry's amount is always positive and the side says
 * which way it moves the account. ADR 0002 records why, and what the alternative (signed
 * amounts) would have cost: with a signed amount, "the debits equal the credits" and
 * "Σ balances = 0" become the same arithmetic statement, so the property suite loses a
 * check that can fail independently of the other.
 */
public enum Side {
  /** Increases an asset, decreases a liability. The left column of a T-account. */
  DEBIT,

  /** Increases a liability, decreases an asset. The right column of a T-account. */
  CREDIT;

  public Side opposite() {
    return this == DEBIT ? CREDIT : DEBIT;
  }

  /**
   * The sign this side's amounts carry on ledger-x's one balance axis, which is
   * debit-positive: an account's balance is Σ debits − Σ credits, so that Σ over every
   * account is 0 for any balanced ledger.
   */
  public int sign() {
    return this == DEBIT ? 1 : -1;
  }
}
