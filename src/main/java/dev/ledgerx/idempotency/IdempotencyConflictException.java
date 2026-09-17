package dev.ledgerx.idempotency;

import dev.ledgerx.domain.IdempotencyKey;
import dev.ledgerx.domain.MerchantId;
import dev.ledgerx.domain.RequestFingerprint;

/**
 * The same key was presented with a different body — the 409, and the one answer this ledger
 * never
 * compromises on.
 *
 * <p><strong>Why 409 and never a 200-replay: because a mismatch is not a retry, it is a client
 * bug, and a replay would hide it.</strong> A 200 hands back the old receipt, the caller marks the
 * operation done, the money the client <em>meant</em> to move never moves, and nothing anywhere
 * errors — the ledger's own invariants cannot catch it, because the ledger did exactly what it
 * was
 * told, twice-told wrongly. The full narrative, with a worked example, is
 * <a href="../../../../idempotency.md">the idempotency contract</a>; the rule it argues is this
 * exception. When ledger-x grows an HTTP surface, this maps to {@code 409 Conflict} — not
 * Stripe's
 * 400, because the request is well-formed and the key is the resource being contested, and not a
 * 200, because silence is the failure mode the rule exists to prevent.
 *
 * <p><strong>The rule is unconditional.</strong> A conflict is a conflict at any age: after the
 * response has expired, after a recovery, after a checkpoint — for as long as the binding exists,
 * and the binding exists for as long as the log does (ADR 0005 §5). The fingerprint stored in the
 * record is permanent, so the hole Stripe's 24-hour prune reopens — a key forgotten and then
 * re-executed under a new body — is a hole this design does not have.
 *
 * <p>Unchecked, like {@link dev.ledgerx.domain.RejectedTransactionException}: the guarantee when
 * it is thrown is the same — nothing changed, nothing was appended, no balance moved — and the
 * caller above it has an error path for a 4xx already.
 */
public final class IdempotencyConflictException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final MerchantId merchant;
  private final IdempotencyKey key;
  private final RequestFingerprint boundTo;
  private final RequestFingerprint presented;

  public IdempotencyConflictException(
      MerchantId merchant,
      IdempotencyKey key,
      RequestFingerprint boundTo,
      RequestFingerprint presented) {
    super(
        "the key " + key + " is bound in the scope of " + merchant + " to a different request"
            + " body (bound to " + boundTo.hex() + ", presented " + presented.hex() + ");"
            + " this is a client bug, not a retry — use a new key for a new intent");
    this.merchant = merchant;
    this.key = key;
    this.boundTo = boundTo;
    this.presented = presented;
  }

  /** The scope the contested key lives in. */
  public MerchantId merchant() {
    return merchant;
  }

  /** The key two different bodies have now claimed. */
  public IdempotencyKey key() {
    return key;
  }

  /** The body the key is bound to — the one whose receipt a 200-replay would have returned. */
  public RequestFingerprint boundTo() {
    return boundTo;
  }

  /** The body this request presented. */
  public RequestFingerprint presented() {
    return presented;
  }
}
