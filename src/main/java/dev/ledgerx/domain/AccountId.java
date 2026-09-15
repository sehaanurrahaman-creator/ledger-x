package dev.ledgerx.domain;

import java.util.Objects;

/**
 * The identity of an account: a caller-chosen string, validated once here and never
 * rewritten.
 *
 * <p>ledger-x does not mint account ids. An id is a reference to something that exists
 * outside the ledger — a merchant's payable, a settlement account at a correspondent — and an
 * id the ledger invented would have to be mapped back to the outside thing anyway. What the
 * ledger does own is the <strong>alphabet</strong>, and the reason is encoding rather than
 * taste: an id ends up inside a WAL record and inside any canonical form used to hash state,
 * and an alphabet that excludes whitespace, control characters and the punctuation a textual
 * encoding needs for delimiters is one that can be serialized without an escaping scheme.
 * Escaping schemes are where two implementations of "the same" string start to disagree, and
 * a replay that disagrees by one byte is not a replay.
 *
 * <p>The property suite asserts the other half of that claim: every delimiter its canonical
 * rendering uses is a character this type refuses.
 *
 * <p>Deliberately absent: any merchant or tenant scope. Whether an account belongs to a
 * merchant, and where that scope lives if it does, is not decided here — ADR 0002 records it
 * as fog, because the idempotency ticket's {@code (merchant_id, idempotency_key)} cannot be
 * fixed while it is open.
 *
 * @param value the id as chosen by the caller
 */
public record AccountId(String value) {

  /** Long enough for any external reference, short enough to bound a record's header. */
  public static final int MAX_LENGTH = 64;

  public AccountId {
    Objects.requireNonNull(value, "account id");
    if (value.isEmpty()) {
      throw new IllegalArgumentException("account id must not be empty");
    }
    if (value.length() > MAX_LENGTH) {
      throw new IllegalArgumentException(
          "account id longer than " + MAX_LENGTH + " characters: " + value.length());
    }
    for (int i = 0; i < value.length(); i++) {
      if (!isAllowed(value.charAt(i))) {
        throw new IllegalArgumentException(
            "account id must use only letters, digits, '.', '_' and '-' (found 0x"
                + Integer.toHexString(value.charAt(i))
                + " at index "
                + i
                + "): "
                + value);
      }
    }
  }

  /**
   * The alphabet: unreserved characters, in the sense that no serialization this project is
   * likely to grow needs one of them to mean something else.
   */
  private static boolean isAllowed(char c) {
    return (c >= 'a' && c <= 'z')
        || (c >= 'A' && c <= 'Z')
        || (c >= '0' && c <= '9')
        || c == '.'
        || c == '_'
        || c == '-';
  }

  public static AccountId of(String value) {
    return new AccountId(value);
  }

  @Override
  public String toString() {
    return value;
  }
}
