package dev.ledgerx.domain;

/**
 * Why a candidate transaction was refused, in the order the ledger evaluates them.
 *
 * <p><strong>The order is part of the contract, not an implementation detail.</strong> A
 * candidate can break two rules at once — an unbalanced transaction naming an account that
 * was never opened — and which reason comes back must not depend on hash iteration order or
 * on which check happened to be written first. The idempotency ticket will map these onto
 * HTTP statuses, so a nondeterministic reason would become a nondeterministic response body,
 * and a replayed request would stop matching the stored one.
 *
 * <p>The order below is grammar, then reference, then arithmetic, then the money rule, then
 * the representation limit. Totals have to be computed before they can be compared, which is
 * why {@link #OVERFLOWING_TOTALS} precedes {@link #UNBALANCED} rather than following it.
 *
 * <p>The property suite pins this order with transactions that violate two rules at once, so
 * reordering the enum without reordering the checks fails the build.
 */
public enum RejectionReason {
  /** Fewer than {@link Transaction#MINIMUM_ENTRIES} entries: nothing that small can balance. */
  TOO_FEW_ENTRIES,

  /** An entry names an account the ledger never opened. */
  UNKNOWN_ACCOUNT,

  /** The debit or credit total does not fit in a {@code long} of minor units. */
  OVERFLOWING_TOTALS,

  /** Σ debits ≠ Σ credits — the one rule that makes it double-entry. */
  UNBALANCED,

  /** Balanced and well-formed, but an account's resulting balance would not fit. */
  OVERFLOWING_BALANCE
}
