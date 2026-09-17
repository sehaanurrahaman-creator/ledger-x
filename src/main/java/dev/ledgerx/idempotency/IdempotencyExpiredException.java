package dev.ledgerx.idempotency;

import dev.ledgerx.domain.IdempotencyKey;
import dev.ledgerx.domain.MerchantId;

/**
 * The key is still bound, but the stored response is past its retention window — so the request
 * is
 * <em>refused</em>: not replayed (the response is no longer being served) and never re-executed
 * (that would be the second posting the whole design exists to prevent).
 *
 * <p>This is the boundary behaviour the idempotency ticket weighed against Stripe's documented
 * alternative — Stripe prunes a key after 24 hours and <em>executes a fresh request</em> if the
 * key is reused, silently converting a very late retry into a duplicate. ledger-x refuses instead,
 * for the window the operator configures and by default forever: the refusal is loud, it is the
 * same loudness at any age, and the client's remedy is explicit — start a new intent under a new
 * key, because this one has already had exactly one posting (ADR 0005 §5). When ledger-x grows an
 * HTTP surface this maps to {@code 410 Gone}: the resource the key names is deliberately no longer
 * available, and will not become available again.
 *
 * <p><strong>What this exception is not.</strong> It is not a clock-skew hazard: a clock that
 * steps backwards extends replay (a negative age is treated as zero), and a clock that steps
 * forwards can only turn a would-be replay into this refusal — a refusal posts nothing, so skew
 * can shrink the set of replays served but can never widen the set of postings made. The
 * document that plays this out is <a href="../../../../idempotency.md">the idempotency
 * contract</a>.
 */
public final class IdempotencyExpiredException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  // Transient by decision, as everywhere else a refusal carries value types: nothing
  // serializes an exception across a boundary this project has not decided on yet, and the
  // instants are primitives, which need no such help.
  private final transient MerchantId merchant;
  private final transient IdempotencyKey key;
  private final long capturedAtMillis;
  private final long retentionMillis;

  public IdempotencyExpiredException(
      MerchantId merchant, IdempotencyKey key, long capturedAtMillis, long retentionMillis) {
    super(
        "the stored response for the key " + key + " in the scope of " + merchant + " was"
            + " captured at " + capturedAtMillis + " and retention is " + retentionMillis
            + " ms, so it is refused rather than replayed or re-executed; start a new intent"
            + " under a new key");
    this.merchant = merchant;
    this.key = key;
    this.capturedAtMillis = capturedAtMillis;
    this.retentionMillis = retentionMillis;
  }

  /** The scope the expired key lives in. */
  public MerchantId merchant() {
    return merchant;
  }

  /** The key whose response has aged out — still bound, still conflict-checked, never
   * replayed.  */
  public IdempotencyKey key() {
    return key;
  }

  /** When the original request was accepted, as committed in its record. */
  public long capturedAtMillis() {
    return capturedAtMillis;
  }

  /** The retention that was configured when the refusal was made. */
  public long retentionMillis() {
    return retentionMillis;
  }
}
