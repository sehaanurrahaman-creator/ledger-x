package dev.ledgerx.domain;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * A candidate posting: the entries that would move together, or not at all.
 *
 * <p><strong>A transaction is the unit of atomicity, and its grammar is n entries, not
 * two.</strong> A transaction must carry at least two entries and its debits must equal its
 * credits; every account it names must already exist; and no total or resulting balance may
 * overflow the {@code long} minor-unit representation. The two-account transfer is the
 * common case and gets a factory ({@link #transfer}), but it is not the grammar — a
 * four-entry split settlement is one transaction, and a transaction that debits the same
 * account twice is legal too.
 *
 * <p>Deliberately absent from the type:
 *
 * <ul>
 *   <li><strong>An id.</strong> A transaction's sequence number is its position in the
 *       event log. Storing one as well would create a second source of truth that could
 *       disagree with the first, and the log is the truth. When records need durable ids the
 *       WAL ticket will assign them from log position.
 *   <li><strong>A timestamp.</strong> Nothing here reads a clock. A wall-clock time assigned
 *       by the ledger would make replay non-deterministic — the same log would produce
 *       different state on a second run — and byte-identical replay is the destination. If
 *       time is wanted it arrives as caller-supplied data inside the record, which is a
 *       record-format decision.
 *   <li><strong>An idempotency key.</strong> Same reasoning, different ticket: key scoping,
 *       expiry and the 409-vs-200 question all sit above this type.
 * </ul>
 *
 * <p>Constructing a transaction does <em>not</em> check the grammar, and that is on purpose.
 * Half the grammar is a fact about the ledger, not about the entries — whether the accounts
 * exist — and rejection is a path the property suite has to be able to walk, with a reason
 * code, atomically. {@link InMemoryLedger#post} is where a candidate becomes a posting or
 * becomes nothing.
 *
 * @param entries the movements, in posting order; never null, never mutated after
 *     construction
 */
public record Transaction(List<Entry> entries) {

  /** The smallest transaction that can balance: one debit and one credit. */
  public static final int MINIMUM_ENTRIES = 2;

  public Transaction {
    entries = copyOfNonNull(entries);
  }

  private static List<Entry> copyOfNonNull(List<Entry> source) {
    Objects.requireNonNull(source, "transaction entries");
    List<Entry> copy = new ArrayList<>(source.size());
    for (Entry entry : source) {
      Objects.requireNonNull(entry, "transaction entry");
      copy.add(entry);
    }
    // List.copyOf, not Collections.unmodifiableList: the caller's list is not merely hidden,
    // it is not referenced any more. Mutating it afterwards cannot reach the transaction.
    return List.copyOf(copy);
  }

  public static Transaction of(Entry first, Entry second, Entry... rest) {
    List<Entry> all = new ArrayList<>(2 + rest.length);
    all.add(first);
    all.add(second);
    for (Entry entry : rest) {
      all.add(entry);
    }
    return new Transaction(all);
  }

  public static Transaction of(List<Entry> entries) {
    return new Transaction(entries);
  }

  /**
   * The common case: move {@code amount} out of {@code from} and into {@code to}. On the
   * debit-positive axis that is a debit to the destination and a credit to the source, so an
   * asset transfer raises the destination's balance and lowers the source's.
   *
   * <p>This is one transaction with two entries — which is the whole answer to standing
   * design-review question 4 at the domain level. There is no coordination problem between
   * the two accounts because there is only one thing to commit; whether one record can stay
   * atomic across shards is the question that ticket argues, and it is a storage question
   * now, not a protocol one.
   */
  public static Transaction transfer(AccountId from, AccountId to, Money amount) {
    return new Transaction(List.of(Entry.debit(to, amount), Entry.credit(from, amount)));
  }

  public int size() {
    return entries.size();
  }

  /** Σ of the debit entries. Throws {@link ArithmeticException} rather than wrapping. */
  public Money totalDebits() {
    return total(Side.DEBIT);
  }

  /** Σ of the credit entries. Throws {@link ArithmeticException} rather than wrapping. */
  public Money totalCredits() {
    return total(Side.CREDIT);
  }

  private Money total(Side wanted) {
    Money sum = Money.ZERO;
    for (Entry entry : entries) {
      if (entry.side() == wanted) {
        sum = sum.plus(entry.amount());
      }
    }
    return sum;
  }

  /**
   * Whether the debits equal the credits. A transaction whose totals overflow is reported as
   * unbalanced rather than propagating the overflow, because "is this balanced" is a total
   * question with a boolean answer; callers that need to know *which* rule failed use the
   * totals directly, as the ledger does when it picks a rejection reason.
   */
  public boolean isBalanced() {
    try {
      return totalDebits().equals(totalCredits());
    } catch (ArithmeticException overflow) {
      return false;
    }
  }

  /**
   * What this transaction would do to one account's balance on the debit-positive axis, or
   * {@link Money#ZERO} if it does not touch that account.
   */
  public Money netEffectOn(AccountId account) {
    Money net = Money.ZERO;
    for (Entry entry : entries) {
      if (entry.account().equals(account)) {
        net = net.plus(entry.signedAmount());
      }
    }
    return net;
  }

  /** The distinct accounts this transaction touches, in first-appearance order. */
  public Set<AccountId> accounts() {
    Set<AccountId> touched = new LinkedHashSet<>();
    for (Entry entry : entries) {
      touched.add(entry.account());
    }
    return touched;
  }

  /**
   * The transaction that undoes this one: every entry's opposite, in the same order. A
   * refund is a reversal, never an edit — the original entries stay exactly where they are.
   */
  public Transaction reversed() {
    List<Entry> opposite = new ArrayList<>(entries.size());
    for (Entry entry : entries) {
      opposite.add(entry.reversal());
    }
    return new Transaction(opposite);
  }

  @Override
  public String toString() {
    StringBuilder text = new StringBuilder("Transaction[");
    for (int i = 0; i < entries.size(); i++) {
      Entry entry = entries.get(i);
      if (i > 0) {
        text.append(", ");
      }
      text.append(entry.side())
          .append(' ')
          .append(entry.amount().toMajorString())
          .append(" -> ")
          .append(entry.account());
    }
    return text.append(']').toString();
  }
}
