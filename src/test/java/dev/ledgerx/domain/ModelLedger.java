package dev.ledgerx.domain;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The oracle: a second, deliberately slow and deliberately obvious ledger that the real one
 * is compared against after every operation.
 *
 * <p>Random operations alone cannot tell you whether a ledger is right — they can only tell
 * you whether it is self-consistent. A ledger that computed every balance as zero would keep
 * Σ balances = 0 forever and pass every invariant check in isolation. So the property suite
 * checks something stronger: that {@link InMemoryLedger} and this model agree on <em>which
 * transactions are acceptable, why the unacceptable ones are unacceptable, and what every
 * balance is afterwards</em>.
 *
 * <p>The model differs from the implementation in the three ways that make it trustworthy:
 * balances are {@link BigInteger}, so overflow is predicted rather than suffered; validation
 * is a straight-line transcription of ADR 0002 §7's documented order, with no shared helper
 * and no shared state; and it is written to be read once and believed, at whatever cost in
 * speed. Where the two disagree, the model is assumed right until proven otherwise — and
 * proving otherwise has twice meant finding a real bug in the ledger rather than in the
 * model.
 */
public final class ModelLedger {

  /** What the model says will happen to a candidate posting. */
  public enum Verdict {
    /** The ledger appends it and every balance moves. */
    ACCEPTED,

    /** It cannot even be built: {@link Entry} refuses a non-positive amount. */
    UNCONSTRUCTIBLE,

    /** It builds, and {@link InMemoryLedger#post} refuses it with a reason. */
    REJECTED
  }

  /**
   * The model's verdict on one candidate.
   *
   * @param verdict what should happen
   * @param reason the rule broken, or {@code null} when nothing is
   * @param why a human-readable line, for a failure message that says more than "mismatch"
   */
  public record Prediction(Verdict verdict, RejectionReason reason, String why) {

    static Prediction accepted() {
      return new Prediction(Verdict.ACCEPTED, null, "balanced and in range");
    }

    static Prediction unconstructible(String why) {
      return new Prediction(Verdict.UNCONSTRUCTIBLE, null, why);
    }

    static Prediction rejected(RejectionReason reason, String why) {
      return new Prediction(Verdict.REJECTED, reason, why);
    }
  }

  /** An {@link OpEntry} with its slot resolved to the account it names. */
  public record ResolvedEntry(AccountId account, Side side, long minorUnits) {}

  private static final BigInteger LONG_MAX = BigInteger.valueOf(Long.MAX_VALUE);
  private static final BigInteger LONG_MIN = BigInteger.valueOf(Long.MIN_VALUE);

  private final List<AccountId> accounts = new ArrayList<>();
  private final Map<AccountId, AccountKind> kinds = new LinkedHashMap<>();
  private final Map<AccountId, BigInteger> balances = new LinkedHashMap<>();
  private int ghosts;
  private int events;
  private int posted;

  public int accountCount() {
    return accounts.size();
  }

  public int events() {
    return events;
  }

  public int posted() {
    return posted;
  }

  public List<AccountId> accounts() {
    return List.copyOf(accounts);
  }

  public Map<AccountId, BigInteger> balances() {
    return Map.copyOf(balances);
  }

  public Map<AccountId, AccountKind> kinds() {
    return Map.copyOf(kinds);
  }

  public BigInteger balanceOf(AccountId id) {
    return balances.get(id);
  }

  /** The account a slot names, wrapping — a shrunk case may reference a slot that is gone. */
  public AccountId accountAt(int slot) {
    if (accounts.isEmpty()) {
      throw new IllegalStateException("no accounts are open, so no slot names one");
    }
    return accounts.get(Math.floorMod(slot, accounts.size()));
  }

  /** An id that is guaranteed never to have been opened. */
  public AccountId ghostAccount() {
    return AccountId.of("ghost-" + ghosts++);
  }

  /** Turns generated slots into real account ids, minting ghosts where the op asked for one. */
  public List<ResolvedEntry> resolve(LedgerOp.Post post) {
    List<ResolvedEntry> resolved = new ArrayList<>(post.size());
    for (OpEntry entry : post.entries()) {
      AccountId account =
          entry.isUnknownAccount() ? ghostAccount() : accountAt(entry.slot());
      resolved.add(new ResolvedEntry(account, entry.side(), entry.minorUnits()));
    }
    return resolved;
  }

  /**
   * Predicts the outcome of posting {@code resolved}, in the order ADR 0002 §7 fixes:
   * grammar, reference, arithmetic, the money rule, then the representation limit.
   */
  public Prediction predict(List<ResolvedEntry> resolved) {
    for (ResolvedEntry entry : resolved) {
      if (entry.minorUnits() <= 0L) {
        return Prediction.unconstructible(
            "entry amount " + entry.minorUnits() + " is not positive, so Entry refuses it");
      }
    }

    if (resolved.size() < Transaction.MINIMUM_ENTRIES) {
      return Prediction.rejected(
          RejectionReason.TOO_FEW_ENTRIES,
          resolved.size() + " entries, at least " + Transaction.MINIMUM_ENTRIES + " required");
    }

    for (ResolvedEntry entry : resolved) {
      if (!balances.containsKey(entry.account())) {
        return Prediction.rejected(
            RejectionReason.UNKNOWN_ACCOUNT, "never opened: " + entry.account());
      }
    }

    BigInteger debits = BigInteger.ZERO;
    BigInteger credits = BigInteger.ZERO;
    for (ResolvedEntry entry : resolved) {
      BigInteger amount = BigInteger.valueOf(entry.minorUnits());
      if (entry.side() == Side.DEBIT) {
        debits = debits.add(amount);
      } else {
        credits = credits.add(amount);
      }
    }
    if (debits.compareTo(LONG_MAX) > 0 || credits.compareTo(LONG_MAX) > 0) {
      return Prediction.rejected(
          RejectionReason.OVERFLOWING_TOTALS,
          "debits " + debits + " / credits " + credits + " exceed " + LONG_MAX);
    }
    if (!debits.equals(credits)) {
      return Prediction.rejected(
          RejectionReason.UNBALANCED, "debits " + debits + " != credits " + credits);
    }

    Map<AccountId, BigInteger> nets = new LinkedHashMap<>();
    for (ResolvedEntry entry : resolved) {
      BigInteger signed =
          entry.side() == Side.DEBIT
              ? BigInteger.valueOf(entry.minorUnits())
              : BigInteger.valueOf(entry.minorUnits()).negate();
      nets.merge(entry.account(), signed, BigInteger::add);
    }
    for (Map.Entry<AccountId, BigInteger> row : nets.entrySet()) {
      BigInteger next = balances.get(row.getKey()).add(row.getValue());
      if (next.compareTo(LONG_MAX) > 0 || next.compareTo(LONG_MIN) < 0) {
        return Prediction.rejected(
            RejectionReason.OVERFLOWING_BALANCE,
            row.getKey()
                + " holds "
                + balances.get(row.getKey())
                + " and would move by "
                + row.getValue());
      }
    }

    return Prediction.accepted();
  }

  /** Records an opening, after the production ledger has performed it. */
  public void open(AccountId id, AccountKind kind) {
    if (balances.containsKey(id)) {
      throw new AssertionError("the model already has " + id);
    }
    accounts.add(id);
    kinds.put(id, kind);
    balances.put(id, BigInteger.ZERO);
    events++;
  }

  /** Records an accepted posting, after the production ledger has appended it. */
  public void commit(Transaction accepted) {
    for (Entry entry : accepted.entries()) {
      BigInteger delta = BigInteger.valueOf(entry.amount().minorUnits());
      if (entry.side() == Side.CREDIT) {
        delta = delta.negate();
      }
      balances.merge(entry.account(), delta, BigInteger::add);
    }
    events++;
    posted++;
  }

  /** The distinct accounts a posting would touch, for the duplicate-account coverage count. */
  public static Set<AccountId> touchedBy(List<ResolvedEntry> resolved) {
    Set<AccountId> touched = new LinkedHashSet<>();
    for (ResolvedEntry entry : resolved) {
      touched.add(entry.account());
    }
    return touched;
  }
}
