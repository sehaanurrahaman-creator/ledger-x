package dev.ledgerx.domain;

/**
 * A candidate transaction that the ledger refused — and therefore never touched.
 *
 * <p>Unchecked, on purpose. A rejection is not a condition a caller can meaningfully be
 * forced to handle at every posting site, and every layer above this one already has an
 * error path it maps onto: the WAL layer has {@code IOException}, the idempotency layer will
 * have a 4xx, and a checked exception here would only add a {@code try} around code whose
 * success path has no branch to fill. The alternative — returning a result type — was
 * considered and rejected in ADR 0002 §7: it puts the failure case in the type of the
 * success path, where nine callers out of ten have nothing to say about it.
 *
 * <p>What is guaranteed when this is thrown: <strong>nothing changed.</strong> No event was
 * appended, no balance moved, no account appeared. That is not a promise in a javadoc that
 * somebody should trust; it is a property the test suite checks on every rejected operation
 * by digesting the whole ledger before and after.
 */
public final class RejectedTransactionException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final RejectionReason reason;
  private final transient Transaction attempted;

  public RejectedTransactionException(
      RejectionReason reason, Transaction attempted, String detail) {
    super(reason + ": " + detail);
    this.reason = reason;
    this.attempted = attempted;
  }

  /** Which rule the candidate broke, in the documented evaluation order. */
  public RejectionReason reason() {
    return reason;
  }

  /**
   * The refused candidate, unchanged. Held so that a rejection can be logged or answered
   * with the thing that caused it; {@code transient} because an exception should not drag a
   * transaction graph into a serialized form that this project has not decided yet.
   */
  public Transaction attempted() {
    return attempted;
  }
}
