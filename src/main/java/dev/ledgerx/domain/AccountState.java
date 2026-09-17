package dev.ledgerx.domain;

import java.util.Comparator;
import java.util.Objects;

/**
 * One account's materialized state: its id, its kind, and the balance its history adds up to.
 *
 * <p>This is the row a checkpoint carries and the row a state hash covers — the whole of what
 * "state" means to ledger-x, per ADR 0004. It exists as a type because a checkpoint has to
 * describe a ledger it has never seen: recovery builds an {@link InMemoryLedger} from a list of
 * these and then folds the log's tail on top, so the baseline and a live account have to be the
 * same shape or the two paths would not be comparable.
 *
 * <p><strong>The order below is the only order state is ever emitted in, and that is the
 * point.</strong> Sorting by id rather than by insertion is not tidiness: account-opening order
 * depends on where a
 * recovery started, because a ledger restored from a checkpoint opens the checkpointed accounts
 * first and the tail's accounts after, while a from-scratch fold opens them in the order the log
 * did. Two paths through the same history would then serialize the same money in two different
 * orders and hash differently — which is exactly the "map-iteration-order leakage" ADR 0004 bans.
 * A total order over ids is a function of the state alone, so it is the same on every path.
 *
 * <p>The comparison is over the id's characters as unsigned code units. {@link AccountId}'s
 * alphabet is 7-bit ASCII — letters, digits, {@code .}, {@code _} and {@code -} — so character
 * order and UTF-8 byte order coincide here, and this comparator is therefore exactly
 * "ascending by id bytes" without allocating a byte array per comparison. Widening that alphabet
 * to non-ASCII is a format change and would have to restate this ordering in bytes.
 *
 * @param id the account
 * @param kind what it is, which is metadata (ADR 0002 §4) but is state, so it is hashed
 * @param balance the signed balance in minor units, on the debit-positive axis
 */
public record AccountState(AccountId id, AccountKind kind, Money balance) {

  /** Ascending by account id: the canonical order of a ledger's state. */
  public static final Comparator<AccountState> CANONICAL_ORDER =
      Comparator.comparing((AccountState row) -> row.id(), AccountState::compareIds);

  public AccountState {
    Objects.requireNonNull(id, "account id");
    Objects.requireNonNull(kind, "account kind");
    Objects.requireNonNull(balance, "account balance");
  }

  public static AccountState of(AccountId id, AccountKind kind, long minorUnits) {
    return new AccountState(id, kind, Money.ofMinor(minorUnits));
  }

  /**
   * A total order on account ids, by character as an unsigned code unit, shorter id first when
   * one is a prefix of the other. Total and history-independent, which is what makes it usable
   * as a canonical order at all.
   */
  public static int compareIds(AccountId left, AccountId right) {
    String a = left.value();
    String b = right.value();
    int shared = Math.min(a.length(), b.length());
    for (int i = 0; i < shared; i++) {
      int difference = Character.compare(a.charAt(i), b.charAt(i));
      if (difference != 0) {
        return difference;
      }
    }
    return Integer.compare(a.length(), b.length());
  }

  @Override
  public String toString() {
    return id + " " + kind + " " + balance.minorUnits();
  }
}
