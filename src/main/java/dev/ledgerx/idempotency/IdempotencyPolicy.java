package dev.ledgerx.idempotency;

import java.time.Clock;
import java.time.Duration;
import java.util.Objects;

/**
 * The two settings idempotency has: which clock to consult, and how long a stored response is
 * replayable. ADR 0005 §5, as one value.
 *
 * <p><strong>The clock is injected, and that is the whole of the clock-skew answer's
 * mechanism.</strong> The ledger reads no clock of its own: the writer consults
 * {@code policy.clock()} once per keyed commit, under the commit lock, and the value it reads is
 * committed as the record's capture instant — replay never consults anything. A test can
 * therefore
 * make two runs of one history write identical bytes by handing both the same fixed clock, and
 * production hands the ledger {@link Clock#systemUTC()} and accepts that two runs of one history
 * differ in exactly the timing bytes, which the state hash deliberately does not cover. The clock
 * answers one question only — "how much time has passed since this key was captured?" — and
 * ordering is never its to answer: which posting happened first is the LSN's to say.
 *
 * <p><strong>Retention is a floor, not a switch.</strong> {@link #DEFAULT_RETENTION} is 24 hours,
 * the number Stripe documents as the <em>minimum</em> a key must be remembered, kept as ledger-x's
 * default because it is the one retention figure clients already plan around. A key that has been
 * held longer than {@code retention} stops replaying its stored response and starts
 * <em>refusing</em> — never re-executing — and the binding itself (the identity and the
 * fingerprint) is retained for as long as the log is, so a mismatched body is a conflict at any
 * age. Setting retention below a client's retry horizon converts safe replays into safe refusals;
 * it cannot convert anything into a second posting.
 *
 * @param clock the server clock the writer consults at commit and at lookup, never the client's
 * @param retention how long after capture a stored response is replayed rather than refused
 */
public record IdempotencyPolicy(Clock clock, Duration retention) {

  /** Stripe's documented floor, adopted as ledger-x's default: keys replay for 24 hours. */
  public static final Duration DEFAULT_RETENTION = Duration.ofHours(24);

  /** The production default: the system UTC clock, 24-hour retention. */
  public static final IdempotencyPolicy SYSTEM = new IdempotencyPolicy(Clock.systemUTC(),
      DEFAULT_RETENTION);

  public IdempotencyPolicy {
    Objects.requireNonNull(clock, "clock");
    Objects.requireNonNull(retention, "retention");
    if (retention.isNegative()) {
      throw new IllegalArgumentException(
          "a retention of " + retention + " would expire a response before it was stored;"
              + " zero is the degenerate floor and means never replay");
    }
  }

  /** A policy with an explicit clock and retention — how a test or an operator says "my time". */
  public static IdempotencyPolicy of(Clock clock, Duration retention) {
    return new IdempotencyPolicy(clock, retention);
  }

  /** The production default: the system UTC clock, 24-hour retention. */
  public static IdempotencyPolicy system() {
    return SYSTEM;
  }

  /** Retention in milliseconds, the unit the capture instant is stored in. */
  public long retentionMillis() {
    return retention.toMillis();
  }
}
