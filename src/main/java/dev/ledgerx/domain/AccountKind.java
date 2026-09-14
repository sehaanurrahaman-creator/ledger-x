package dev.ledgerx.domain;

/**
 * What an account *is*, for reporting: the direction its balance naturally runs in.
 *
 * <p>Three kinds, the permanent balance-sheet ones. Revenue and expense are deliberately
 * absent — they are equity sub-kinds whose whole purpose is to be closed into equity at the
 * end of an accounting period, and nothing downstream of this ticket asks for a period. If
 * a ticket ever does, adding a constant here is additive: no invariant in the model reads
 * an account's kind, so no invariant changes when one appears.
 *
 * <p>That last sentence is the important one and ADR 0002 §4 says it plainly: <strong>kind
 * is metadata.</strong> No posting rule consults it. It is not a permission, it is not a
 * constraint, and it cannot cause a transaction to be rejected — which is why the property
 * suite can randomize kinds freely without changing a single expected outcome.
 */
public enum AccountKind {
  /** Something the platform owns or is owed. Balances naturally debit-positive. */
  ASSET(Side.DEBIT),

  /** Something the platform owes — a merchant's payable is the canonical case. */
  LIABILITY(Side.CREDIT),

  /** The owners' claim, and the counterparty of any opening balance. */
  EQUITY(Side.CREDIT);

  private final Side normalSide;

  AccountKind(Side normalSide) {
    this.normalSide = normalSide;
  }

  /** The side that increases this kind of account in normal operation. */
  public Side normalSide() {
    return normalSide;
  }

  /**
   * Whether a balance sits on the kind's natural side. A negative-balance *asset* is an
   * anomaly worth reporting — and, per ADR 0002 §5, exactly the kind of thing the
   * reconciliation ticket looks for. It is never a reason to refuse a posting.
   */
  public boolean isNaturalBalance(Money balance) {
    return normalSide == Side.DEBIT ? !balance.isNegative() : !balance.isPositive();
  }
}
