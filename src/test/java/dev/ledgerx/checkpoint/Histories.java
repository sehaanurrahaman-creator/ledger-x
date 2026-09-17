package dev.ledgerx.checkpoint;

import dev.ledgerx.domain.AccountId;
import dev.ledgerx.domain.AccountKind;
import dev.ledgerx.domain.Entry;
import dev.ledgerx.domain.InMemoryLedger;
import dev.ledgerx.domain.Money;
import dev.ledgerx.domain.Transaction;
import dev.ledgerx.journal.DurableLedger;
import dev.ledgerx.testing.RandomSource;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * A random ledger history in which every step is accepted: the workload the checkpoint
 * contract and the replay harness both run.
 *
 * <p>Every posting is balanced by construction over accounts the history has already opened,
 * with positive amounts small enough that no balance can approach a boundary the domain
 * would refuse. A checkpoint harness that had to reason about rejections would be proving
 * two things at once and measuring neither; rejections belong to the domain suite, which has
 * an oracle for them. The one randomness this file is allowed is <em>which</em> valid history
 * runs — the seed is printed with every failure, and {@link RandomSource} is frozen across
 * JDK releases, so a red run replays exactly.
 */
final class Histories {

  /** One step: open an account, or post a transaction. Exactly one side is non-null. */
  record Step(String account, AccountKind kind, Transaction post) {

    static Step open(String name, AccountKind kind) {
      return new Step(name, kind, null);
    }

    static Step post(Transaction transaction) {
      return new Step(null, null, transaction);
    }

    boolean isOpen() {
      return account != null;
    }
  }

  /** Ordinary amounts run up to a million minor units — ten thousand in major units. */
  private static final long AMOUNT_CEILING = 1_000_000L;

  private Histories() {}

  /**
   * A history of {@code count} accepted steps: four opening accounts, then random openings
   * and postings — two-leg transfers mostly, three-leg splits sometimes.
   */
  static List<Step> random(RandomSource rnd, int count) {
    List<Step> steps = new ArrayList<>(Math.max(count, 4));
    List<String> known = new ArrayList<>();
    for (String name : new String[] {"alpha", "beta", "gamma", "delta"}) {
      steps.add(Step.open(name, rnd.pick(AccountKind.values())));
      known.add(name);
    }
    int minted = 0;
    while (steps.size() < count) {
      if (rnd.chance(12)) {
        String name = "acct-" + (minted++);
        steps.add(Step.open(name, rnd.pick(AccountKind.values())));
        known.add(name);
        continue;
      }
      steps.add(Step.post(randomPosting(rnd, known)));
    }
    return steps;
  }

  /** A balanced posting over known accounts: never refused, never out of range. */
  private static Transaction randomPosting(RandomSource rnd, List<String> known) {
    int from = rnd.nextInt(known.size());
    int to = rnd.nextInt(known.size());
    if (to == from) {
      to = (to + 1) % known.size();
    }
    long amount = rnd.nextLong(1L, AMOUNT_CEILING);
    if (known.size() >= 3 && rnd.chance(25)) {
      int third = rnd.nextInt(known.size());
      if (third == from || third == to) {
        third = (third + 1) % known.size();
      }
      if (third != from && third != to && amount >= 2L) {
        long share = rnd.nextLong(1L, amount);
        return new Transaction(
            List.of(
                Entry.debit(account(known.get(to)), Money.ofMinor(amount)),
                Entry.credit(account(known.get(from)), Money.ofMinor(share)),
                Entry.credit(account(known.get(third)), Money.ofMinor(amount - share))));
      }
    }
    return Transaction.transfer(account(known.get(from)), account(known.get(to)),
        Money.ofMinor(amount));
  }

  private static AccountId account(String name) {
    return new AccountId(name);
  }

  /** Applies steps {@code from} (inclusive) through {@code to} (exclusive) to a durable ledger. */
  static void apply(DurableLedger ledger, List<Step> steps, int from, int to) throws IOException {
    for (int i = from; i < to; i++) {
      Step step = steps.get(i);
      if (step.isOpen()) {
        ledger.openAccount(account(step.account()), step.kind());
      } else {
        ledger.post(step.post());
      }
    }
  }

  /** Applies steps {@code from} (inclusive) through {@code to} (exclusive) in memory. */
  static void apply(InMemoryLedger ledger, List<Step> steps, int from, int to) {
    for (int i = from; i < to; i++) {
      Step step = steps.get(i);
      if (step.isOpen()) {
        ledger.openAccount(account(step.account()), step.kind());
      } else {
        ledger.post(step.post());
      }
    }
  }
}
