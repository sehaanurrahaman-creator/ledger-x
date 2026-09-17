package dev.ledgerx.domain;

import java.util.Objects;

/**
 * What a lookup of {@code (merchant, key)} found: the binding, when it was made, and the response
 * it stored — everything the commit path needs to decide between a replay, a refusal and a
 * conflict, in one read.
 *
 * <p>It exists as a type because the three facts come from two different lifetimes: the binding
 * and its capture instant are durable (log bytes, restored by recovery), while the response is a
 * reference to a transaction the event log already holds — a cache, like the balance index, and
 * exempt from {@code audit()} for the same reason the index is checked against a fold rather than
 * the fold against it. A caller that gets a {@code BoundKey} back can act on all three facts
 * without a second lookup racing the first, which is the actual requirement: the decision and the
 * data it was made from must be one observation.
 *
 * @param binding the durable identity — merchant, key, fingerprint, response LSN
 * @param capturedAtMillis the server wall clock when the request was accepted, as committed
 * @param response the stored response: the transaction that was posted
 */
public record BoundKey(KeyBinding binding, long capturedAtMillis, Transaction response) {

  public BoundKey {
    Objects.requireNonNull(binding, "binding");
    Objects.requireNonNull(response, "stored response");
  }

  @Override
  public String toString() {
    return binding + ", captured at " + capturedAtMillis;
  }
}
