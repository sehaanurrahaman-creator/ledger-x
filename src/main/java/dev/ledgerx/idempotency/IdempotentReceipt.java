package dev.ledgerx.idempotency;

import dev.ledgerx.domain.IdempotencyKey;
import dev.ledgerx.domain.MerchantId;
import dev.ledgerx.domain.Transaction;
import java.util.Objects;

/**
 * The response a key maps to, and whether this call is the one that produced it: the value side of
 * the charter's {@code (merchant_id, idempotency_key) → response}.
 *
 * <p><strong>The {@code replayed} flag is the contract's honesty mechanism.</strong> A replay
 * returns the stored response — the same transaction that the first call committed — and the
 * flag
 * is what tells the caller that this call moved no money, so a client or an operator can tell
 * "we paid" from "we already paid" without diffing receipts. When ledger-x grows an HTTP surface,
 * the flag is the {@code Idempotent-Replayed: true} header, and the fields below are chosen so
 * that header needs nothing this record does not already carry.
 *
 * <p>The receipt is immutable data about a commit that already happened; there is no receipt for a
 * refused or conflicting request, because those outcomes are exceptions rather than partial
 * answers ({@link IdempotencyConflictException}, {@link IdempotencyExpiredException}).
 *
 * @param merchant the scope the key was bound in
 * @param key the key
 * @param transaction the posted transaction — the stored response on a replay, the fresh posting
 *     otherwise
 * @param replayed whether this call replayed a stored response rather than posting
 * @param originalLsn the LSN of the record that carries the posting — stable across replays, so
 *     it is the receipt's durable identity
 * @param capturedAtMillis the server's capture instant of the original request, as committed
 */
public record IdempotentReceipt(
    MerchantId merchant,
    IdempotencyKey key,
    Transaction transaction,
    boolean replayed,
    long originalLsn,
    long capturedAtMillis) {

  public IdempotentReceipt {
    Objects.requireNonNull(merchant, "merchant");
    Objects.requireNonNull(key, "idempotency key");
    Objects.requireNonNull(transaction, "transaction");
    if (originalLsn < 1L) {
      throw new IllegalArgumentException(
          "a receipt names the record that posted it, and lsn " + originalLsn
              + " is before the first record");
    }
  }

  @Override
  public String toString() {
    return "(" + merchant + ", " + key + ") " + (replayed ? "replayed" : "posted")
        + " @ lsn " + originalLsn;
  }
}
