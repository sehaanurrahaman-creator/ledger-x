package dev.ledgerx.domain;

import dev.ledgerx.domain.ModelLedger.Prediction;
import dev.ledgerx.domain.ModelLedger.ResolvedEntry;
import dev.ledgerx.domain.ModelLedger.Verdict;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Applies one generated operation to the ledger and to the model, then asserts everything the
 * domain ticket says must hold.
 *
 * <p>This is where "random sequences never break the invariants" becomes code. Per operation
 * it checks, in this order:
 *
 * <ol>
 *   <li>the ledger's verdict matches the model's — accepted, rejected with the same reason, or
 *       unconstructible for the same cause;
 *   <li>an accepted posting appended exactly one event, and a refused one appended none;
 *   <li>a refused posting left the state digest, the event count, the account list and every
 *       balance byte-identical to what they were before it;
 *   <li>every event already in the journal still renders exactly as it did when it was
 *       appended — the append-only and immutability claim, checked rather than trusted;
 *   <li>Σ balances is exactly zero, the balance index equals a brute-force fold of the log,
 *       and every committed transaction still balances ({@link InMemoryLedger#audit()});
 *   <li>the index agrees with the model's {@link BigInteger} balances, account for account, so
 *       a ledger that was merely self-consistent would still be caught.
 * </ol>
 *
 * <p>A case builds a fresh applier, so the body is re-runnable and the runner can shrink by
 * re-executing it.
 */
public final class OpApplier {

  private final InMemoryLedger ledger = new InMemoryLedger();
  private final ModelLedger model = new ModelLedger();
  private final JournalDigest digests = new JournalDigest();
  private final Coverage coverage = new Coverage();

  public InMemoryLedger ledger() {
    return ledger;
  }

  public ModelLedger model() {
    return model;
  }

  public Coverage coverage() {
    return coverage;
  }

  public void apply(LedgerOp op) {
    if (op instanceof LedgerOp.OpenAccount open) {
      applyOpen(open);
    } else if (op instanceof LedgerOp.Post post) {
      applyPost(post);
    } else {
      throw new AssertionError("an operation this suite does not know: " + op);
    }
    assertConsistent(op);
  }

  public void applyAll(List<LedgerOp> ops) {
    for (LedgerOp op : ops) {
      apply(op);
    }
  }

  private void applyOpen(LedgerOp.OpenAccount op) {
    AccountId id = AccountId.of("acct-" + model.accountCount());
    Account opened = ledger.openAccount(id, op.kind());
    model.open(id, op.kind());
    coverage.opened++;

    if (!opened.id().equals(id) || opened.kind() != op.kind()) {
      throw new AssertionError(
          "openAccount returned " + opened + ", asked for " + id + "/" + op.kind());
    }
    if (!ledger.balanceOf(id).isZero()) {
      throw new AssertionError("a freshly opened account holds " + ledger.balanceOf(id));
    }
    int appended = digests.observe(ledger);
    if (appended != 1) {
      throw new AssertionError("opening an account appended " + appended + " events, expected 1");
    }
  }

  private void applyPost(LedgerOp.Post op) {
    if (needsAnOpenAccount(op) && model.accountCount() == 0) {
      // A shrunk case can lose the operations that opened accounts. Skipping is honest here:
      // there is nothing to post against, and inventing a failure would send the shrinker
      // chasing an artifact of shrinking instead of the bug it started from.
      coverage.skipped++;
      return;
    }

    List<ResolvedEntry> resolved = model.resolve(op);
    Prediction expected = model.predict(resolved);

    String digestBefore = digests.stateDigest(ledger);
    long eventsBefore = ledger.size();
    Map<AccountId, Money> balancesBefore = ledger.balances();
    List<Account> accountsBefore = ledger.accounts();

    Transaction candidate;
    try {
      candidate = build(resolved);
    } catch (IllegalArgumentException refused) {
      if (expected.verdict() != Verdict.UNCONSTRUCTIBLE) {
        throw new AssertionError(
            "an entry could not be built, but the model predicted "
                + expected.verdict()
                + " ("
                + expected.why()
                + ") for "
                + resolved,
            refused);
      }
      coverage.unconstructible++;
      digests.observe(ledger);
      assertUntouched(
          digestBefore, eventsBefore, balancesBefore, accountsBefore, "an unconstructible entry");
      return;
    }

    if (expected.verdict() == Verdict.UNCONSTRUCTIBLE) {
      throw new AssertionError(
          "the model expected no entry to be constructible ("
              + expected.why()
              + "), but this one was: "
              + candidate);
    }

    try {
      Transaction appended = ledger.post(candidate);
      if (expected.verdict() != Verdict.ACCEPTED) {
        throw new AssertionError(
            "the ledger accepted a posting the model predicted would be "
                + expected.verdict()
                + " ("
                + expected.why()
                + "): "
                + candidate);
      }
      if (appended != candidate) {
        throw new AssertionError(
            "post returned a different object than the candidate, so the journal no longer"
                + " holds what the caller handed over");
      }
      model.commit(appended);
      coverage.recordAccepted(appended, ledger);
      int events = digests.observe(ledger);
      if (events != 1) {
        throw new AssertionError("one posting appended " + events + " events, expected 1");
      }
    } catch (RejectedTransactionException rejected) {
      if (expected.verdict() != Verdict.REJECTED) {
        throw new AssertionError(
            "the ledger rejected a posting the model predicted would be accepted: "
                + candidate
                + " — "
                + rejected.getMessage(),
            rejected);
      }
      if (rejected.reason() != expected.reason()) {
        throw new AssertionError(
            "rejected for "
                + rejected.reason()
                + ", the model predicted "
                + expected.reason()
                + " ("
                + expected.why()
                + ") for "
                + candidate,
            rejected);
      }
      coverage.recordRejected(rejected.reason(), candidate);
      int events = digests.observe(ledger);
      if (events != 0) {
        throw new AssertionError(
            "a rejected posting appended " + events + " events: " + rejected.getMessage());
      }
      assertUntouched(
          digestBefore,
          eventsBefore,
          balancesBefore,
          accountsBefore,
          "a posting rejected for " + rejected.reason());
    }
  }

  private static boolean needsAnOpenAccount(LedgerOp.Post op) {
    for (OpEntry entry : op.entries()) {
      if (!entry.isUnknownAccount()) {
        return true;
      }
    }
    return false;
  }

  private static Transaction build(List<ResolvedEntry> resolved) {
    List<Entry> entries = new ArrayList<>(resolved.size());
    for (ResolvedEntry row : resolved) {
      entries.add(new Entry(row.account(), row.side(), Money.ofMinor(row.minorUnits())));
    }
    return new Transaction(entries);
  }

  /**
   * The atomic-rejection check: nothing about the ledger may differ from the snapshot taken
   * before the refused operation.
   */
  private void assertUntouched(
      String digestBefore,
      long eventsBefore,
      Map<AccountId, Money> balancesBefore,
      List<Account> accountsBefore,
      String what) {
    if (ledger.size() != eventsBefore) {
      throw new AssertionError(
          what + " changed the event count: " + eventsBefore + " -> " + ledger.size());
    }
    if (!ledger.accounts().equals(accountsBefore)) {
      throw new AssertionError(what + " changed the accounts: " + ledger.accounts());
    }
    if (!ledger.balances().equals(balancesBefore)) {
      throw new AssertionError(
          what + " moved a balance: " + balancesBefore + " -> " + ledger.balances());
    }
    String digestAfter = digests.stateDigest(ledger);
    if (!digestAfter.equals(digestBefore)) {
      throw new AssertionError(
          what + " changed the ledger state: " + digestBefore + " -> " + digestAfter);
    }
  }

  /** The invariants that must hold after every operation, whatever it did. */
  private void assertConsistent(LedgerOp op) {
    ledger.audit();

    Money total = ledger.totalBalance();
    if (!total.isZero()) {
      throw new AssertionError(
          "Σ balances = " + total.minorUnits() + " minor units after " + op + ", must be 0");
    }

    if (ledger.size() != model.events()) {
      throw new AssertionError(
          "the ledger holds " + ledger.size() + " events, the model " + model.events());
    }
    if (ledger.transactions().size() != model.posted()) {
      throw new AssertionError(
          "the ledger holds "
              + ledger.transactions().size()
              + " transactions, the model "
              + model.posted());
    }

    List<Account> accounts = ledger.accounts();
    if (accounts.size() != model.accountCount()) {
      throw new AssertionError(
          "the ledger has " + accounts.size() + " accounts, the model " + model.accountCount());
    }
    for (Account account : accounts) {
      if (model.kinds().get(account.id()) != account.kind()) {
        throw new AssertionError(
            account.id()
                + " is "
                + account.kind()
                + " in the ledger and "
                + model.kinds().get(account.id())
                + " in the model");
      }
    }

    Map<AccountId, Money> index = ledger.balances();
    Map<AccountId, BigInteger> expected = model.balances();
    if (index.size() != expected.size()) {
      throw new AssertionError(
          "the balance index has " + index.size() + " rows, the model " + expected.size());
    }
    for (Map.Entry<AccountId, BigInteger> row : expected.entrySet()) {
      Money actual = index.get(row.getKey());
      if (actual == null) {
        throw new AssertionError("the balance index has no row for " + row.getKey());
      }
      if (BigInteger.valueOf(actual.minorUnits()).compareTo(row.getValue()) != 0) {
        throw new AssertionError(
            row.getKey() + " holds " + actual.minorUnits() + ", the model says " + row.getValue());
      }
    }

    for (Transaction committed : ledger.transactions()) {
      if (!committed.totalDebits().equals(committed.totalCredits())) {
        throw new AssertionError("a committed transaction does not balance: " + committed);
      }
      if (committed.size() < Transaction.MINIMUM_ENTRIES) {
        throw new AssertionError("a committed transaction has " + committed.size() + " entries");
      }
    }
  }

  /**
   * What the campaign actually exercised, so that a green run cannot be a run that never
   * tried anything. A generator that stopped producing invalid operations would leave every
   * invariant trivially true; {@link #assertComplete} is what makes that a failure.
   */
  public static final class Coverage {

    private final Map<RejectionReason, Integer> reasons = new EnumMap<>(RejectionReason.class);

    private int opened;
    private int accepted;
    private int rejected;
    private int unconstructible;
    private int skipped;
    private int widest = 2;
    private long largestAmount;
    private int negativeBalances;
    private int duplicateAccountPostings;
    private int selfCancellingPostings;

    void recordAccepted(Transaction transaction, InMemoryLedger ledger) {
      accepted++;
      widest = Math.max(widest, transaction.size());
      if (transaction.accounts().size() < transaction.size()) {
        duplicateAccountPostings++;
      }
      boolean movesNothing = true;
      for (AccountId id : transaction.accounts()) {
        if (!transaction.netEffectOn(id).isZero()) {
          movesNothing = false;
        }
      }
      if (movesNothing) {
        selfCancellingPostings++;
      }
      for (Entry entry : transaction.entries()) {
        largestAmount = Math.max(largestAmount, entry.amount().minorUnits());
      }
      for (Money balance : ledger.balances().values()) {
        if (balance.isNegative()) {
          negativeBalances++;
        }
      }
    }

    void recordRejected(RejectionReason reason, Transaction transaction) {
      rejected++;
      reasons.merge(reason, 1, Integer::sum);
      widest = Math.max(widest, transaction.size());
    }

    /** Folds another case's counts into these, for a campaign-wide summary. */
    public void merge(Coverage other) {
      opened += other.opened;
      accepted += other.accepted;
      rejected += other.rejected;
      unconstructible += other.unconstructible;
      skipped += other.skipped;
      widest = Math.max(widest, other.widest);
      largestAmount = Math.max(largestAmount, other.largestAmount);
      negativeBalances += other.negativeBalances;
      duplicateAccountPostings += other.duplicateAccountPostings;
      selfCancellingPostings += other.selfCancellingPostings;
      for (Map.Entry<RejectionReason, Integer> row : other.reasons.entrySet()) {
        reasons.merge(row.getKey(), row.getValue(), Integer::sum);
      }
    }

    /**
     * Fails a campaign that never walked some path it was supposed to walk. This is the check
     * that keeps the property suite honest about its own coverage.
     */
    public void assertComplete() {
      List<String> missing = new ArrayList<>();
      if (opened == 0) {
        missing.add("no account was ever opened");
      }
      if (accepted == 0) {
        missing.add("no posting was ever accepted");
      }
      if (unconstructible == 0) {
        missing.add("no entry was ever refused at construction");
      }
      for (RejectionReason reason : RejectionReason.values()) {
        if (reasons.getOrDefault(reason, 0) == 0) {
          missing.add("nothing was ever rejected for " + reason);
        }
      }
      if (widest < 4) {
        missing.add("no transaction wider than " + widest + " entries was ever posted");
      }
      if (negativeBalances == 0) {
        missing.add("no account ever held a negative balance");
      }
      if (duplicateAccountPostings == 0) {
        missing.add("no transaction ever touched the same account twice");
      }
      if (selfCancellingPostings == 0) {
        missing.add("no self-cancelling transaction was ever accepted");
      }
      if (!missing.isEmpty()) {
        throw new AssertionError(
            "the campaign was vacuous — " + String.join("; ", missing) + ". " + summary());
      }
    }

    public String summary() {
      StringBuilder text =
          new StringBuilder()
              .append(accepted)
              .append(" accepted, ")
              .append(rejected)
              .append(" rejected, ")
              .append(unconstructible)
              .append(" refused at construction, ")
              .append(opened)
              .append(" accounts opened");
      if (skipped > 0) {
        text.append(", ").append(skipped).append(" skipped");
      }
      text.append("; widest ")
          .append(widest)
          .append(" entries, largest amount ")
          .append(largestAmount)
          .append(", negative balances ")
          .append(negativeBalances)
          .append(", same-account-twice ")
          .append(duplicateAccountPostings)
          .append(", self-cancelling ")
          .append(selfCancellingPostings)
          .append("; reasons ");
      for (RejectionReason reason : RejectionReason.values()) {
        text.append(reason).append('=').append(reasons.getOrDefault(reason, 0)).append(' ');
      }
      return text.toString().trim();
    }
  }
}
