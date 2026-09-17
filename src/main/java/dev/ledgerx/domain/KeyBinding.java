package dev.ledgerx.domain;

import java.util.Objects;

/**
 * One key's durable identity: what was bound, by whom, and where the answer lives.
 *
 * <p>This is the row that joins three things that must never disagree — the idempotency index in
 * memory, the canonical state section of a checkpoint (ADR 0005 §6), and the WAL record that made
 * the binding — so it carries exactly the fields all three can supply from the log, and nothing
 * else:
 *
 * <ul>
 *   <li><strong>merchant and key</strong> — the identity, as the client presented it;
 *   <li><strong>fingerprint</strong> — the body the key was bound to, so a conflict is detectable
 *       for as long as the binding exists, which is as long as the log does (ADR 0005 §5);
 * <li><strong>responseLsn</strong> — the LSN of the record that carried the posting, supplied by
 *       the caller on every path for the same reason {@code LedgerState}'s watermark is: the
 *       domain does not know about LSNs, so the layer that owns the log says where the record is.
 *       Recovery uses it to re-materialize the stored response from the log rather than copy
 *       entries into the checkpoint twice.
 * </ul>
 *
 * <p><strong>What is deliberately not here: the capture instant and the response.</strong> The
 * instant of capture is timing, and timing is policy metadata — carried in the record and in the
 * checkpoint's timing section, never inside the hashed state, so that a state hash says what was
 * bound and never when (ADR 0004 §4's rule, inherited). The response is the transaction, which the
 * log already holds; a checkpoint that duplicated it would carry two copies of one truth.
 *
 * @param merchant the scope
 * @param key the key, unique within the scope
 * @param fingerprint the body the key was bound to
 * @param responseLsn the LSN of the {@code IDEMPOTENT_POSTING} record that made this binding
 */
public record KeyBinding(
    MerchantId merchant, IdempotencyKey key, RequestFingerprint fingerprint, long responseLsn) {

  public KeyBinding {
    Objects.requireNonNull(merchant, "binding merchant");
    Objects.requireNonNull(key, "binding key");
    Objects.requireNonNull(fingerprint, "binding fingerprint");
    if (responseLsn < 1L) {
      throw new IllegalArgumentException(
          "a binding names the record that made it, and lsn " + responseLsn + " is before the"
              + " first record");
    }
  }

  @Override
  public String toString() {
    return "(" + merchant + ", " + key + ") -> " + fingerprint + " @ lsn " + responseLsn;
  }
}
