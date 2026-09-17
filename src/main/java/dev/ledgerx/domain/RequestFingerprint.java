package dev.ledgerx.domain;

import java.util.Arrays;
import java.util.Objects;

/**
 * What a key was bound to: a SHA-256 over the request body, exactly as encoded.
 *
 * <p><strong>Identity and validation are different questions, and only one of them is the
 * key.</strong>
 * The pair {@code (merchant_id, idempotency_key)} answers "which intent is this"; the fingerprint
 * answers "is this the same request that intent was bound to". Stripe makes the same split — the
 * key identifies, the endpoint-and-parameters <em>validate</em> — and it is what lets a replay be
 * distinguished from a conflict without making either part of the key itself: if the body were
 * part of the identity, a client bug could never be detected, only forgiven.
 *
 * <p>The fingerprint is stored, not derived at lookup time. Three reasons, in the order they
 * matter: a comparison of two 32-byte values cannot drift the way a re-derivation can if the
 * canonical encoding ever evolves; the record's bytes then carry the claim "this is the body this
 * key was bound to" explicitly, the way a checkpoint's header carries its watermark; and the
 * binding outlives the response (ADR 0005 §5), so the conflict check must work long after the
 * entries that would have let it be recomputed are anybody's guess.
 *
 * <p><strong>The domain stores fingerprints; it does not compute them.</strong> The canonical
 * request body is the posting's encoded entry list, and the encoding lives with the codec that
 * owns every other encoding ({@code EventCodec}), which keeps one serialization in one place. A
 * fingerprint constructor that digested bytes would put half of the codec in the domain, and two
 * encoders that disagree by a byte would turn every replay into a conflict.
 *
 * @param bytes 32 bytes of SHA-256, never null, never mutated
 */
public record RequestFingerprint(byte[] bytes) {

  /** SHA-256's width. */
  public static final int BYTES = 32;

  public RequestFingerprint {
    Objects.requireNonNull(bytes, "fingerprint bytes");
    if (bytes.length != BYTES) {
      throw new IllegalArgumentException(
          "a request fingerprint is " + BYTES + " bytes, not " + bytes.length);
    }
    bytes = bytes.clone();
  }

  /** The fingerprint of a request whose canonical body hashes to {@code digest}. */
  public static RequestFingerprint of(byte[] digest) {
    return new RequestFingerprint(digest);
  }

  @Override
  public byte[] bytes() {
    return bytes.clone();
  }

  /** The payload bytes without copying, for a reader that only compares. Never store the result. */
  public byte[] bytesUnsafe() {
    return bytes;
  }

  /** Lowercase hex, 64 digits — the form an error message quotes so an operator can diff it. */
  public String hex() {
    StringBuilder text = new StringBuilder(BYTES * 2);
    for (byte b : bytes) {
      text.append(Character.forDigit((b >> 4) & 0xF, 16));
      text.append(Character.forDigit(b & 0xF, 16));
    }
    return text.toString();
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof RequestFingerprint fingerprint
        && Arrays.equals(bytes, fingerprint.bytes);
  }

  @Override
  public int hashCode() {
    return Arrays.hashCode(bytes);
  }

  @Override
  public String toString() {
    return hex().substring(0, 16) + "…";
  }
}
