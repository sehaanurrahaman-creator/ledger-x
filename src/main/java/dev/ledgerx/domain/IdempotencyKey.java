package dev.ledgerx.domain;

import java.util.Objects;

/**
 * The client-chosen key of one intended operation: the naming half of the idempotency identity
 * {@code (merchant_id, idempotency_key)}.
 *
 * <p><strong>The client owns the key, and the ledger owns what it means.</strong> A key names an
 * <em>intent</em> — "this specific payout, attempted however many times" — not a session and
 * not a
 * call site, which is the distinction Stripe's own guidance draws. Because the client mints it,
 * the ledger cannot assume entropy, uniqueness across clients, or honesty; it can only promise
 * that within one merchant's scope, one key can be bound at most once, forever (ADR 0005 §5), and
 * that everything after the binding is one of replay, refusal or conflict — never a second
 * posting.
 *
 * <p><strong>255 characters, the same ceiling Stripe documents.</strong> Not because ledger-x
 * must imitate the number, but because it is the one part of the key contract clients already
 * write code against, and diverging downward would reject keys the ecosystem emits. The WAL's
 * payload ceiling bounds the true cost; this ceiling bounds the claim.
 *
 * <p>The alphabet is {@link AccountId}'s, for the same encoding reason — see {@link MerchantId},
 * which makes the same argument and shares the rule.
 *
 * @param value the key exactly as the client presented it
 */
public record IdempotencyKey(String value) {

  /** The documented ceiling, kept at Stripe's so integrations do not have to guess. */
  public static final int MAX_LENGTH = 255;

  public IdempotencyKey {
    Objects.requireNonNull(value, "idempotency key");
    if (value.isEmpty()) {
      throw new IllegalArgumentException("idempotency key must not be empty");
    }
    if (value.length() > MAX_LENGTH) {
      throw new IllegalArgumentException(
          "idempotency key longer than " + MAX_LENGTH + " characters: " + value.length());
    }
    for (int i = 0; i < value.length(); i++) {
      if (!isAllowed(value.charAt(i))) {
        throw new IllegalArgumentException(
            "idempotency key must use only letters, digits, '.', '_' and '-' (found 0x"
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

  public static IdempotencyKey of(String value) {
    return new IdempotencyKey(value);
  }

  @Override
  public String toString() {
    return value;
  }
}
