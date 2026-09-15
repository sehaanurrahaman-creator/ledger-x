package dev.ledgerx.domain;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The whole ledger, in memory: one append-only event log, an index of balances derived from
 * it, and the validation that decides what may be appended.
 *
 * <p>This is the prototype the domain ticket asks for, and it is deliberately the only thing
 * it is. There is no durability here, no WAL, no fsync, no clock and no lock. What it exists
 * to prove is that the grammar and the invariants are right before anything is built on top
 * of them: every downstream ticket — the record format, idempotency, replay, concurrency —
 * reads a decision made here.
 *
 * <p><strong>Not thread-safe, and that is a decision.</strong> Concurrency is another
 * ticket's, and it has a hard constraint this class must not quietly violate: per-account
 * ordering without a global pessimistic lock. Putting a lock in here would either be the
 * global lock the charter forbids or a lie about isolation that the concurrency ticket then
 * has to un-find. Until that ticket decides, an {@code InMemoryLedger} is confined to the
 * thread that created it, and the property suite that proves the invariants is
 * single-threaded by construction.
 *
 * <p><strong>Two representations of the same truth, on purpose.</strong> Balances live in an
 * index maintained at append time, and {@link #foldBalances()} recomputes them from the
 * event log by brute force. {@link #audit()} insists the two agree. An index that can
 * disagree with the log and nobody notices is how a ledger ends up with money that is not
 * there; an index that is checked against a fold on every operation cannot drift silently.
 */
public final class InMemoryLedger {

  /** The append-only log. Position is the sequence number; nothing is ever removed. */
  private final List<JournalEvent> events = new ArrayList<>();

  /** Index: accounts by id, in opening order. Derived from the log, checked against it. */
  private final Map<AccountId, Account> accounts = new LinkedHashMap<>();

  /** Index: balance in signed minor units, debit-positive. Derived from the log. */
  private final Map<AccountId, Long> balances = new LinkedHashMap<>();

  /**
   * Opens an account. Appends an event and moves no money, so it cannot disturb Σ balances.
   *
   * @throws IllegalArgumentException if the id is already open — reopening an account under a
   *     different kind would be an edit, and this ledger has no edits
   */
  public Account openAccount(AccountId id, AccountKind kind) {
    Account account = new Account(id, kind);
    if (accounts.containsKey(id)) {
      throw new IllegalArgumentException("account already open: " + id + " (" + kind + ")");
    }
    accounts.put(id, account);
    balances.put(id, 0L);
    events.add(new JournalEvent.AccountOpened(account));
    return account;
  }

  public boolean isKnown(AccountId id) {
    return accounts.containsKey(id);
  }

  /** @throws IllegalArgumentException if the account was never opened */
  public Account account(AccountId id) {
    Account account = accounts.get(id);
    if (account == null) {
      throw new IllegalArgumentException("no such account: " + id);
    }
    return account;
  }

  /**
   * Appends {@code candidate}, or throws and appends nothing.
   *
   * <p>Atomicity here is not rollback — there is nothing to roll back. Every fallible check
   * runs in {@link #validate} against immutable state, and what remains in {@link #apply}
   * cannot throw: the accounts are known, the totals fit in a {@code long}, and each
   * resulting balance was range-checked before the first mutation. A transaction that fails
   * validation therefore leaves no trace, because it never reached the part of the method
   * that leaves traces.
   *
   * @return the appended transaction — the same object, since it is immutable
   * @throws RejectedTransactionException if the candidate breaks the grammar, with the
   *     reason in {@link RejectionReason}'s documented order
   */
  public Transaction post(Transaction candidate) {
    Objects.requireNonNull(candidate, "candidate transaction");
    apply(candidate, validate(candidate));
    return candidate;
  }

  /**
   * The two-account transfer, posted: one transaction, two entries, one thing to commit.
   *
   * @throws RejectedTransactionException exactly as {@link #post} does
   */
  public Transaction transfer(AccountId from, AccountId to, Money amount) {
    return post(Transaction.transfer(from, to, amount));
  }

  /**
   * Every check, against immutable state, in the order {@link RejectionReason} documents —
   * and it returns the per-account deltas it range-checked, which is the only set of numbers
   * {@link #apply} is allowed to use.
   *
   * <p>Passing the checked deltas across rather than letting apply recompute them is not
   * tidiness. The first run of the property suite found a transaction whose net effect on an
   * account was zero but whose entries, applied one at a time, drove that account's balance
   * past {@code Long.MIN_VALUE} on the way: validation had checked the net, application had
   * used the entries, and the event was already in the journal when the overflow threw. A
   * partial mutation, which is the one thing the atomic-rejection rule exists to forbid. One
   * computation, checked and then used, cannot disagree with itself.
   */
  private Map<AccountId, Long> validate(Transaction candidate) {
    // 1. Grammar: a transaction this small cannot balance, and a one-entry transaction is a
    //    money creation event.
    if (candidate.size() < Transaction.MINIMUM_ENTRIES) {
      String noun = candidate.size() == 1 ? "entry" : "entries";
      throw reject(
          RejectionReason.TOO_FEW_ENTRIES,
          candidate,
          candidate.size() + " " + noun + ", at least " + Transaction.MINIMUM_ENTRIES
              + " required");
    }

    // 2. Reference: every account must already exist. Creating accounts implicitly on first
    //    mention would turn a typo into a new account with a balance, which is the one kind of
    //    silent error a ledger cannot recover from after the fact.
    for (Entry entry : candidate.entries()) {
      if (!accounts.containsKey(entry.account())) {
        throw reject(
            RejectionReason.UNKNOWN_ACCOUNT, candidate, "no such account: " + entry.account());
      }
    }

    // 3. Arithmetic before the money rule, because totals that wrapped would compare equal
    //    and pass it.
    Money debits;
    Money credits;
    try {
      debits = candidate.totalDebits();
      credits = candidate.totalCredits();
    } catch (ArithmeticException overflow) {
      throw reject(
          RejectionReason.OVERFLOWING_TOTALS,
          candidate,
          "debit or credit total exceeds " + Long.MAX_VALUE + " minor units");
    }

    // 4. The money rule.
    if (!debits.equals(credits)) {
      throw reject(
          RejectionReason.UNBALANCED,
          candidate,
          "debits " + debits.toMajorString() + " != credits " + credits.toMajorString());
    }

    // 5. The representation limit, checked per touched account so that a balanced transaction
    //    still cannot push an existing balance out of range. The check is on the *net* effect:
    //    whether a transaction may be appended must not depend on the order its entries happen
    //    to be listed in.
    Map<AccountId, Long> deltas = new LinkedHashMap<>();
    for (AccountId id : candidate.accounts()) {
      long current = balances.get(id);
      long delta = candidate.netEffectOn(id).minorUnits();
      try {
        Math.addExact(current, delta);
      } catch (ArithmeticException overflow) {
        throw reject(
            RejectionReason.OVERFLOWING_BALANCE,
            candidate,
            id + " holds " + current + " minor units and would move by " + delta);
      }
      deltas.put(id, delta);
    }
    return deltas;
  }

  /**
   * Appends the event and applies exactly the deltas {@link #validate} range-checked, one
   * addition per touched account. Nothing here can throw, which is what makes the posting
   * atomic without a rollback path.
   */
  private void apply(Transaction accepted, Map<AccountId, Long> checkedDeltas) {
    // The journal is appended first because the journal is the truth and the balances are an
    // index of it; were this method able to fail halfway — it cannot — an event without its
    // index update is what audit() would report, which is the recoverable direction.
    events.add(new JournalEvent.Posted(accepted));
    for (Map.Entry<AccountId, Long> row : checkedDeltas.entrySet()) {
      balances.put(row.getKey(), Math.addExact(balances.get(row.getKey()), row.getValue()));
    }
  }

  private static RejectedTransactionException reject(
      RejectionReason reason, Transaction candidate, String detail) {
    return new RejectedTransactionException(reason, candidate, detail);
  }

  /**
   * One account's balance on the debit-positive axis. Negative is a legal answer: it means
   * the account's credits exceed its debits, which for an asset is an anomaly worth
   * investigating and for a liability is Tuesday.
   *
   * @throws IllegalArgumentException if the account was never opened — returning zero would
   *     conflate "empty account" with "no such account", and the second is a caller bug
   */
  public Money balanceOf(AccountId id) {
    Long balance = balances.get(id);
    if (balance == null) {
      throw new IllegalArgumentException("no such account: " + id);
    }
    return Money.ofMinor(balance);
  }

  /**
   * Σ balances, which is 0 for any ledger that has only ever appended balanced events.
   *
   * <p>Summed in {@link BigInteger}, not in {@code long}: the running total of a set of
   * balances that sums to zero can still leave the range on the way (two accounts at
   * {@code Long.MAX_VALUE} and two at its negation), and an auditor that overflows while
   * auditing reports a crash instead of a discrepancy.
   *
   * @throws IllegalStateException if Σ balances does not fit in a {@code long}, which cannot
   *     happen to a ledger that only ever appended balanced transactions and means the state
   *     is broken beyond representation if it does
   */
  public Money totalBalance() {
    return exact(sumExactly(balances.values()), "Σ balances");
  }

  private static BigInteger sumExactly(Iterable<Long> values) {
    BigInteger total = BigInteger.ZERO;
    for (long value : values) {
      total = total.add(BigInteger.valueOf(value));
    }
    return total;
  }

  private static Money exact(BigInteger value, String what) {
    try {
      return Money.ofMinor(value.longValueExact());
    } catch (ArithmeticException unrepresentable) {
      throw new IllegalStateException(
          what + " = " + value + " does not fit in a long; the ledger state is broken",
          unrepresentable);
    }
  }

  /** Snapshot of the balance index, in account-opening order. Not a live view. */
  public Map<AccountId, Money> balances() {
    Map<AccountId, Money> snapshot = new LinkedHashMap<>();
    for (Map.Entry<AccountId, Long> row : balances.entrySet()) {
      snapshot.put(row.getKey(), Money.ofMinor(row.getValue()));
    }
    return Collections.unmodifiableMap(snapshot);
  }

  /**
   * Recomputes every balance from the event log, ignoring the index. Slow, obvious, and the
   * reference the index is judged against.
   */
  public Map<AccountId, Money> foldBalances() {
    Map<AccountId, BigInteger> folded = new LinkedHashMap<>();
    for (JournalEvent event : events) {
      if (event instanceof JournalEvent.AccountOpened opened) {
        folded.putIfAbsent(opened.account().id(), BigInteger.ZERO);
      } else if (event instanceof JournalEvent.Posted posted) {
        for (Entry entry : posted.transaction().entries()) {
          BigInteger signed = BigInteger.valueOf(entry.amount().minorUnits());
          if (entry.side() == Side.CREDIT) {
            signed = signed.negate();
          }
          folded.merge(entry.account(), signed, BigInteger::add);
        }
      }
    }
    Map<AccountId, Money> result = new LinkedHashMap<>();
    for (Map.Entry<AccountId, BigInteger> row : folded.entrySet()) {
      result.put(row.getKey(), exact(row.getValue(), "the folded balance of " + row.getKey()));
    }
    return Collections.unmodifiableMap(result);
  }

  /** Every event so far, in order. Snapshot; mutating it throws. */
  public List<JournalEvent> events() {
    return List.copyOf(events);
  }

  /** The posted transactions only, in order. Snapshot; mutating it throws. */
  public List<Transaction> transactions() {
    List<Transaction> posted = new ArrayList<>();
    for (JournalEvent event : events) {
      if (event instanceof JournalEvent.Posted append) {
        posted.add(append.transaction());
      }
    }
    return List.copyOf(posted);
  }

  /** Snapshot of the opened accounts, in opening order. */
  public List<Account> accounts() {
    return List.copyOf(accounts.values());
  }

  /** The number of events appended, which is also the next sequence number. */
  public int size() {
    return events.size();
  }

  /**
   * Checks the ledger against its own log and throws if anything disagrees.
   *
   * <p>What it checks, in order: the account index equals the accounts the log opens; the
   * balance index equals a brute-force fold of the log; Σ balances is exactly 0; and every
   * posted transaction still has at least two entries, names only known accounts, holds only
   * positive amounts, and still balances. The last one is not redundant with validation —
   * validation runs at append time on the candidate, and this runs later on what was actually
   * stored, so it is the check that would catch an entry mutated in place after the fact.
   *
   * @throws IllegalStateException naming the first disagreement found
   */
  public void audit() {
    Map<AccountId, Account> openedByLog = new LinkedHashMap<>();
    int sequence = 0;
    for (JournalEvent event : events) {
      if (event instanceof JournalEvent.AccountOpened open) {
        AccountId id = open.account().id();
        if (openedByLog.putIfAbsent(id, open.account()) != null) {
          throw new IllegalStateException("event " + sequence + " opens " + id + " twice");
        }
      } else if (event instanceof JournalEvent.Posted posted) {
        auditTransaction(sequence, posted.transaction());
      }
      sequence++;
    }

    if (!openedByLog.equals(accounts)) {
      throw new IllegalStateException(
          "account index disagrees with the log: index="
              + accounts.keySet()
              + " log="
              + openedByLog.keySet());
    }

    Map<AccountId, Money> folded = foldBalances();
    Map<AccountId, Money> indexed = balances();
    if (!folded.equals(indexed)) {
      throw new IllegalStateException(
          "balance index disagrees with a fold of the log: index="
              + indexed
              + " fold="
              + folded);
    }

    Money total = totalBalance();
    if (!total.isZero()) {
      throw new IllegalStateException(
          "Σ balances = " + total.toMajorString() + ", must be exactly zero");
    }
  }

  private void auditTransaction(int sequence, Transaction transaction) {
    if (transaction.size() < Transaction.MINIMUM_ENTRIES) {
      throw new IllegalStateException(
          "event " + sequence + " holds " + transaction.size() + " entries");
    }
    for (Entry entry : transaction.entries()) {
      if (!entry.amount().isPositive()) {
        throw new IllegalStateException(
            "event " + sequence + " holds a non-positive amount: " + entry);
      }
      if (!accounts.containsKey(entry.account())) {
        throw new IllegalStateException(
            "event " + sequence + " names an account that was never opened: " + entry.account());
      }
    }
    if (!transaction.isBalanced()) {
      throw new IllegalStateException("event " + sequence + " is unbalanced: " + transaction);
    }
  }
}
