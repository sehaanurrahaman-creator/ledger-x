package dev.ledgerx.domain;

/**
 * An amount of money: a signed count of integer minor units, in the one currency ledger-x
 * has. ADR 0002 decides the representation and states it explicitly — {@code long} minor
 * units, minor unit = 1/100 of the major unit, no currency code stored anywhere.
 *
 * <p>Two rules make this type worth having instead of passing {@code long} around:
 *
 * <ul>
 *   <li>Every arithmetic operation is overflow-checked. A total that does not fit in a
 *       {@code long} throws {@link ArithmeticException} and becomes a rejection at the
 *       ledger; it never wraps. A wrapped balance would break Σ balances = 0 silently, and
 *       a silent break of the one invariant is the failure this project exists to prevent.
 *   <li>No floating point reaches money, ever. {@code double} cannot represent 0.1, and a
 *       rounding error in a ledger is not a rounding error, it is a lie about somebody's
 *       money. {@code scripts/lint.sh} fails the build on a {@code float} or {@code double}
 *       anywhere under {@code src/main/java/dev/ledgerx/domain/}.
 * </ul>
 *
 * <p>{@code Money} itself permits negative values, because a *balance* is signed. An
 * *entry's* amount may not be: {@link Entry} enforces positivity, since the direction lives
 * in the {@link Side}.
 *
 * @param minorUnits the amount in integer minor units; negative means credit-side on the
 *     debit-positive balance axis
 */
public record Money(long minorUnits) implements Comparable<Money> {

  /**
   * Digits between the minor and the major unit. A property of the currency, and ledger-x
   * has exactly one, so it is a constant rather than per-account or per-entry metadata.
   * Naming the currency — and therefore deciding whether this exponent is 2, 0 or 3 — is a
   * record-format decision and belongs to the WAL ticket, not here.
   */
  public static final int MINOR_UNIT_EXPONENT = 2;

  public static final Money ZERO = new Money(0L);

  public static Money ofMinor(long minorUnits) {
    return new Money(minorUnits);
  }

  /** {@code ofMajor(3)} is 300 minor units. Overflow-checked like everything else here. */
  public static Money ofMajor(long majorUnits) {
    return new Money(Math.multiplyExact(majorUnits, minorUnitScale()));
  }

  public static long minorUnitScale() {
    long scale = 1L;
    for (int digit = 0; digit < MINOR_UNIT_EXPONENT; digit++) {
      scale = Math.multiplyExact(scale, 10L);
    }
    return scale;
  }

  public Money plus(Money other) {
    return new Money(Math.addExact(minorUnits, other.minorUnits));
  }

  public Money minus(Money other) {
    return new Money(Math.subtractExact(minorUnits, other.minorUnits));
  }

  /** Throws rather than wrapping on {@code Long.MIN_VALUE}, which has no positive image. */
  public Money negated() {
    return new Money(Math.negateExact(minorUnits));
  }

  public Money times(long factor) {
    return new Money(Math.multiplyExact(minorUnits, factor));
  }

  public boolean isPositive() {
    return minorUnits > 0L;
  }

  public boolean isZero() {
    return minorUnits == 0L;
  }

  public boolean isNegative() {
    return minorUnits < 0L;
  }

  /**
   * The amount in major units with exactly {@link #MINOR_UNIT_EXPONENT} digits after the
   * point, formatted from the digits themselves. No {@code BigDecimal} and no
   * {@code double}: both would put a second representation of money in the codebase, and
   * the second one is where the disagreement would live.
   */
  public String toMajorString() {
    String digits = Long.toString(minorUnits);
    boolean negative = digits.charAt(0) == '-';
    if (negative) {
      digits = digits.substring(1);
    }
    while (digits.length() <= MINOR_UNIT_EXPONENT) {
      digits = "0" + digits;
    }
    int cut = digits.length() - MINOR_UNIT_EXPONENT;
    return (negative ? "-" : "") + digits.substring(0, cut) + "." + digits.substring(cut);
  }

  @Override
  public int compareTo(Money other) {
    return Long.compare(minorUnits, other.minorUnits);
  }

  @Override
  public String toString() {
    return toMajorString();
  }
}
