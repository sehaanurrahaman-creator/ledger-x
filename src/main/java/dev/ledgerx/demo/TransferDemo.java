package dev.ledgerx.demo;

import dev.ledgerx.domain.Account;
import dev.ledgerx.domain.AccountId;
import dev.ledgerx.domain.AccountKind;
import dev.ledgerx.domain.Entry;
import dev.ledgerx.domain.InMemoryLedger;
import dev.ledgerx.domain.JournalEvent;
import dev.ledgerx.domain.Money;
import dev.ledgerx.domain.RejectionReason;
import dev.ledgerx.domain.RejectedTransactionException;
import dev.ledgerx.domain.Transaction;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * {@code make demo}: a two-account transfer, posted to an in-memory ledger, with the balances
 * and the whole journal printed afterwards.
 *
 * <p>It is a demonstration, not a test — but it demonstrates by asserting. Every claim it
 * prints (Σ balances = 0, the index equals a fold of the log, a refused transaction changed
 * nothing) is checked before it is printed, and the program exits non-zero if a check fails,
 * so a demo that lies cannot get through CI.
 *
 * <p>Nothing here reads a clock, a file, a socket or a random number generator: the same
 * program prints the same bytes on every run. That determinism is not an accident of the demo
 * being small — it is the property the replay ticket needs of the real thing, and it is why
 * the domain model has no timestamp in it.
 */
public final class TransferDemo {

  private TransferDemo() {}

  public static void main(String[] args) {
    InMemoryLedger ledger = new InMemoryLedger();

    heading("ledger-x demo — a two-account transfer, in memory");
    System.out.println(
        "One append-only event log, balances derived from it, Σ balances = 0 after every");
    System.out.println(
        "step. Nothing here is durable: ADR 0002 decides what the model is, and the WAL,");
    System.out.println("checkpoint and concurrency tickets decide what happens to it on disk.");

    section("open the accounts");
    AccountId capital = open(ledger, "capital", AccountKind.EQUITY);
    AccountId settlement = open(ledger, "bank-settlement", AccountKind.ASSET);
    AccountId escrow = open(ledger, "bank-escrow", AccountKind.ASSET);
    AccountId payable = open(ledger, "merchant-payable", AccountKind.LIABILITY);

    section("the owner funds the platform: 1000.00 out of capital into the settlement account");
    post(ledger, Transaction.transfer(capital, settlement, Money.ofMajor(1_000L)));

    section("the ticket's two-account transfer: 123.45 from bank-settlement to bank-escrow");
    System.out.println("   before: " + balancesInOneLine(ledger));
    post(ledger, Transaction.transfer(settlement, escrow, Money.ofMinor(12_345L)));
    System.out.println("   after:  " + balancesInOneLine(ledger));
    System.out.println(
        "   one transaction, two entries, one thing to commit: there is nothing for a");
    System.out.println(
        "   coordinator to do between the two accounts, which is the whole domain-level");
    System.out.println("   answer to standing design-review question 4.");

    section("a card payment of 500.00 arrives for the merchant, so the platform owes it");
    System.out.println(
        "   written out long, to show that transfer() above is sugar for exactly this:");
    post(
        ledger,
        new Transaction(
            List.of(
                Entry.debit(settlement, Money.ofMinor(50_000L)),
                Entry.credit(payable, Money.ofMinor(50_000L)))));

    section("the grammar is n entries, not two: settle 300.00 of it from two accounts");
    post(
        ledger,
        new Transaction(
            List.of(
                Entry.debit(payable, Money.ofMinor(30_000L)),
                Entry.credit(settlement, Money.ofMinor(20_000L)),
                Entry.credit(escrow, Money.ofMinor(10_000L)))));

    section("a refund of 50.00 out of an escrow that holds 23.45: the ledger records it");
    System.out.println(
        "   an asset balance goes negative and the posting is accepted, because the log");
    System.out.println(
        "   records what happened, and a funds check is a policy for a layer above it");
    System.out.println("   ADR 0002 §5. The anomaly is visible below, and is drift to");
    System.out.println("   reconcile, not a reason to refuse an append.");
    post(
        ledger,
        new Transaction(
            List.of(
                Entry.debit(payable, Money.ofMinor(5_000L)),
                Entry.credit(escrow, Money.ofMinor(5_000L)))));

    section("a transaction that fails validation appends nothing");
    refuse(
        ledger,
        new Transaction(
            List.of(
                Entry.debit(escrow, Money.ofMinor(1_000L)),
                Entry.credit(settlement, Money.ofMinor(999L)))),
        RejectionReason.UNBALANCED);
    refuse(
        ledger,
        new Transaction(
            List.of(
                Entry.debit(AccountId.of("typo-payable"), Money.ofMinor(1_000L)),
                Entry.credit(settlement, Money.ofMinor(1_000L)))),
        RejectionReason.UNKNOWN_ACCOUNT);

    section("the ledger, in append order — sequence numbers are positions, not fields");
    printJournal(ledger);

    section("balances, on the one axis: positive is debit-positive");
    printBalances(ledger);

    section("audit");
    ledger.audit();
    System.out.println("   Σ balances            " + render(ledger.totalBalance()));
    System.out.println("   balance index == fold of the event log: yes (audit() passed)");
    System.out.println(
        "   every committed transaction: >= "
            + Transaction.MINIMUM_ENTRIES
            + " entries, debits == credits, all amounts positive");
    System.out.println(
        "   events " + ledger.size() + ", none of them ever edited, none of them removable");
    System.out.println();
    System.out.println("demo ok");
  }

  // --- the four operations, each printing what it did ----------------------------------

  private static AccountId open(InMemoryLedger ledger, String id, AccountKind kind) {
    Account opened = ledger.openAccount(AccountId.of(id), kind);
    System.out.println(
        "   seq "
            + pad(String.valueOf(ledger.size() - 1), 3)
            + "  opened  "
            + pad(id, 18)
            + pad(kind.name(), 10)
            + "normal side "
            + kind.normalSide());
    return opened.id();
  }

  private static void post(InMemoryLedger ledger, Transaction transaction) {
    Money before = ledger.totalBalance();
    Transaction posted = ledger.post(transaction);
    Money after = ledger.totalBalance();
    System.out.println("   seq " + pad(String.valueOf(ledger.size() - 1), 3) + "  posted");
    for (Entry entry : posted.entries()) {
      System.out.println("           " + describe(entry));
    }
    System.out.println(
        "           debits "
            + render(posted.totalDebits())
            + "  credits "
            + render(posted.totalCredits())
            + "  -> balanced");
    if (!after.isZero()) {
      throw new AssertionError(
          "Σ balances moved to " + render(after) + " — the model is broken");
    }
    if (!before.isZero()) {
      throw new AssertionError("Σ balances was already " + render(before));
    }
  }

  /** Posts something that must be refused, and proves the ledger did not move. */
  private static void refuse(
      InMemoryLedger ledger, Transaction candidate, RejectionReason expected) {
    int eventsBefore = ledger.size();
    Map<AccountId, Money> balancesBefore = ledger.balances();
    try {
      ledger.post(candidate);
    } catch (RejectedTransactionException rejected) {
      System.out.println("   refused, as it must be: " + rejected.reason());
      for (Entry entry : candidate.entries()) {
        System.out.println("           " + describe(entry));
      }
      if (rejected.reason() != expected) {
        throw new AssertionError(
            "refused for " + rejected.reason() + ", the demo expected " + expected);
      }
      if (ledger.size() != eventsBefore) {
        throw new AssertionError(
            "a refused transaction appended " + (ledger.size() - eventsBefore) + " events");
      }
      if (!ledger.balances().equals(balancesBefore)) {
        throw new AssertionError("a refused transaction moved a balance");
      }
      System.out.println(
          "           events still " + ledger.size() + ", balances unchanged:");
      System.out.println("           " + balancesInOneLine(ledger));
      return;
    }
    throw new AssertionError("the ledger accepted a candidate the demo expected to be refused");
  }

  // --- printing ------------------------------------------------------------------------

  private static String describe(Entry entry) {
    return pad(entry.side().name(), 7)
        + pad(entry.account().value(), 18)
        + pad(render(entry.amount()), 14)
        + (entry.amount().minorUnits())
        + " minor units";
  }

  private static void printJournal(InMemoryLedger ledger) {
    int sequence = 0;
    for (JournalEvent event : ledger.events()) {
      if (event instanceof JournalEvent.AccountOpened opened) {
        System.out.println(
            "   "
                + pad(String.valueOf(sequence), 3)
                + "  opened   "
                + pad(opened.account().id().value(), 18)
                + opened.account().kind());
      } else if (event instanceof JournalEvent.Posted posted) {
        Transaction transaction = posted.transaction();
        System.out.println(
            "   "
                + pad(String.valueOf(sequence), 3)
                + "  posted   "
                + transaction.size()
                + " entries, debits "
                + render(transaction.totalDebits())
                + " = credits "
                + render(transaction.totalCredits()));
        for (Entry entry : transaction.entries()) {
          System.out.println("              " + describe(entry));
        }
      }
      sequence++;
    }
  }

  private static void printBalances(InMemoryLedger ledger) {
    System.out.println(
        "   "
            + pad("account", 18)
            + pad("kind", 10)
            + pad("balance", 14)
            + pad("minor units", 20)
            + "side");
    for (Account account : ledger.accounts()) {
      Money balance = ledger.balanceOf(account.id());
      AccountKind kind = account.kind();
      System.out.println(
          "   "
              + pad(account.id().value(), 18)
              + pad(kind.name(), 10)
              + pad(render(balance), 14)
              + pad(String.valueOf(balance.minorUnits()), 20)
              + (kind.isNaturalBalance(balance)
                  ? "natural for " + kind
                  : "UNNATURAL for " + kind + " — an anomaly to reconcile, not an error"));
    }
    System.out.println(
        "   "
            + pad("Σ balances", 18)
            + pad("", 10)
            + pad(render(ledger.totalBalance()), 14)
            + pad(String.valueOf(ledger.totalBalance().minorUnits()), 20)
            + "the invariant");
  }

  private static String balancesInOneLine(InMemoryLedger ledger) {
    List<String> parts = new ArrayList<>();
    for (Map.Entry<AccountId, Money> row : ledger.balances().entrySet()) {
      parts.add(row.getKey().value() + "=" + row.getValue().minorUnits());
    }
    return String.join(" ", parts) + " (Σ = " + ledger.totalBalance().minorUnits() + ")";
  }

  private static String render(Money amount) {
    return amount.toMajorString();
  }

  private static String pad(String text, int width) {
    if (text.length() >= width) {
      return text + " ";
    }
    return text + " ".repeat(width - text.length());
  }

  private static void heading(String text) {
    System.out.println();
    System.out.println(text);
    System.out.println("=".repeat(text.length()));
    System.out.println();
  }

  private static void section(String text) {
    System.out.println();
    System.out.println("-- " + text);
  }
}
