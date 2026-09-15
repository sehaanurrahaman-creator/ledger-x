package dev.ledgerx.domain;

/**
 * One entry of a generated posting, before it becomes a real {@link Entry}.
 *
 * <p>Three fields, each of which can be a rule violation on purpose: {@code slot} can name
 * an account that does not exist, {@code minorUnits} can be zero or negative (which makes the
 * entry unconstructible, since {@link Entry} refuses it), and it can be large enough that the
 * transaction's totals or an account's resulting balance overflow a {@code long}.
 *
 * @param slot index into the accounts opened so far, or {@link #UNKNOWN_SLOT} for an id the
 *     ledger has never seen
 * @param side the direction to post in
 * @param minorUnits the amount, which may be non-positive to exercise construction refusal
 */
public record OpEntry(int slot, Side side, long minorUnits) {

  /** Names an account that was never opened, so {@code UNKNOWN_ACCOUNT} can be exercised. */
  public static final int UNKNOWN_SLOT = -1;

  public boolean isUnknownAccount() {
    return slot == UNKNOWN_SLOT;
  }

  public boolean isConstructible() {
    return minorUnits > 0L;
  }
}
