package dev.ledgerx.domain;

import java.util.Objects;

/**
 * The merchant a request acts for: the scoping half of the idempotency identity
 * {@code (merchant_id, idempotency_key)} that the charter fixes.
 *
 * <p><strong>Scope is the whole point of this type.</strong> Two merchants may independently use
 * the same key string for unrelated operations, because the binding table is keyed by the pair and
 * never by the key alone — the same shape Stripe documents for accounts, where one key
 * "unambiguously identifies a single operation within your account". ledger-x has no accounts
 * whose money is the merchant's own; {@code merchant_id} is therefore an explicitly supplied scope
 * rather than something derived from the entries, and a caller that omits it has not narrowed the
 * scope, it has chosen a different one.
 *
 * <p><strong>The alphabet is {@link AccountId}'s, deliberately.</strong> An id that ends up inside
 * a WAL record and inside the canonical state section must serialize without an escaping scheme,
 * and the reasoning is ADR 0002's: an alphabet that excludes whitespace, control characters and
 * the punctuation a textual encoding needs is one that can be encoded as length-plus-bytes and
 * never parsed two ways. Sharing the alphabet also means one rule to lint and one rule to test,
 * where two nearly-identical alphabets would eventually disagree at the edges.
 *
 * @param value the merchant id as chosen by the caller
 */
public record MerchantId(String value) {

  /** Long enough for any external reference, short enough to bound a record's header. */
  public static final int MAX_LENGTH = 64;

  public MerchantId {
    Objects.requireNonNull(value, "merchant id");
    if (value.isEmpty()) {
      throw new IllegalArgumentException("merchant id must not be empty");
    }
    if (value.length() > MAX_LENGTH) {
      throw new IllegalArgumentException(
          "merchant id longer than " + MAX_LENGTH + " characters: " + value.length());
    }
    for (int i = 0; i < value.length(); i++) {
      if (!isAllowed(value.charAt(i))) {
        throw new IllegalArgumentException(
            "merchant id must use only letters, digits, '.', '_' and '-' (found 0x"
                + Integer.toHexString(value.charAt(i))
                + " at index "
                + i
                + "): "
                + value);
      }
    }
  }

  private static boolean isAllowed(char c) {
    return (c >= 'a' && c <= 'z')
        || (c >= 'A' && c <= 'Z')
        || (c >= '0' && c <= '9')
        || c == '.'
        || c == '_'
        || c == '-';
  }

  public static MerchantId of(String value) {
    return new MerchantId(value);
  }

  @Override
  public String toString() {
    return value;
  }
}
