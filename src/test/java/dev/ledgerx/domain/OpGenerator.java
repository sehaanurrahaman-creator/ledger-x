package dev.ledgerx.domain;

import dev.ledgerx.testing.RandomSource;
import dev.ledgerx.testing.Shrink;
import java.util.ArrayList;
import java.util.List;

/**
 * Generates random operation sequences, and shrinks the ones that fail.
 *
 * <p>Two ideas do the work here. The first is a <strong>mix</strong> written as a repeated
 * enum array rather than as cumulative weights: the proportion of invalid operations is
 * visible by counting lines, and there is no weight arithmetic to get silently wrong. Roughly
 * a third of generated postings are invalid on purpose — a campaign that only ever posted
 * valid transactions would prove the ledger accepts what it should and nothing about what it
 * refuses.
 *
 * <p>The second is a <strong>curriculum</strong>: a fixed prefix of hand-written operations
 * that hits every acceptance and rejection class at least once, in every case. Randomness is
 * good at finding what nobody thought of and bad at guaranteeing that the obvious paths were
 * taken; without the curriculum, a coverage assertion would pass or fail on luck, and a suite
 * that is red on some seeds and green on others is worse than no suite.
 */
public final class OpGenerator {

  /** The shapes a generated posting can take. */
  public enum Shape {
    /** 1–3 debits and 1–3 credits, ordinary amounts, exactly balanced. */
    BALANCED,
    /** The common case: one debit and one credit of the same amount. */
    TRANSFER,
    /** 4–6 legs a side: a split settlement, still one transaction. */
    MANY_LEGS,
    /** Debit and credit the same account for the same amount: legal, and moves nothing. */
    SELF_CANCELLING,
    /** The same account debited twice against one credit: legal, and tests per-account nets. */
    DUPLICATE_ACCOUNT,
    /** A balanced transaction with one amount nudged, so it is off by a little. */
    UNBALANCED,
    /** Balanced, but one entry names an account that was never opened. */
    UNKNOWN_ACCOUNT,
    /** Zero or one entry: too small to balance. */
    TOO_FEW_ENTRIES,
    /** An amount of zero, or negative, or {@code Long.MIN_VALUE}. */
    NON_POSITIVE_AMOUNT,
    /** Amounts whose debit total does not fit in a {@code long}. */
    OVERFLOWING_TOTALS,
    /** Balanced at {@code Long.MAX_VALUE}, so a second posting of it overflows a balance. */
    EXTREME
  }

  /** The mix, one entry per part in twenty-six. Repetition is the weighting. */
  private static final Shape[] MIX = {
    Shape.BALANCED, Shape.BALANCED, Shape.BALANCED, Shape.BALANCED, Shape.BALANCED,
    Shape.BALANCED, Shape.TRANSFER, Shape.TRANSFER, Shape.TRANSFER, Shape.MANY_LEGS,
    Shape.MANY_LEGS, Shape.SELF_CANCELLING, Shape.DUPLICATE_ACCOUNT, Shape.DUPLICATE_ACCOUNT,
    Shape.UNBALANCED, Shape.UNBALANCED, Shape.UNBALANCED, Shape.UNKNOWN_ACCOUNT,
    Shape.UNKNOWN_ACCOUNT, Shape.TOO_FEW_ENTRIES, Shape.NON_POSITIVE_AMOUNT,
    Shape.NON_POSITIVE_AMOUNT, Shape.OVERFLOWING_TOTALS, Shape.EXTREME, Shape.EXTREME,
    Shape.BALANCED
  };

  /** Ordinary amounts run up to a million minor units — ten thousand in major units. */
  private static final long ORDINARY_CEILING = 1_000_000L;

  /** How often a random operation opens an account instead of posting. */
  private static final int OPEN_ACCOUNT_PERCENT = 12;

  private OpGenerator() {}

  /** A case: the curriculum, then random operations up to {@code operations} in total. */
  public static List<LedgerOp> sequence(RandomSource rnd, int operations) {
    List<LedgerOp> ops = new ArrayList<>(Math.max(operations, CURRICULUM_LENGTH));
    for (LedgerOp op : curriculum()) {
      ops.add(op);
      if (ops.size() >= operations) {
        return ops;
      }
    }
    while (ops.size() < operations) {
      ops.add(randomOp(rnd));
    }
    return ops;
  }

  public static int curriculumLength() {
    return CURRICULUM_LENGTH;
  }

  public static LedgerOp randomOp(RandomSource rnd) {
    if (rnd.chance(OPEN_ACCOUNT_PERCENT)) {
      return new LedgerOp.OpenAccount(rnd.pick(AccountKind.values()));
    }
    return new LedgerOp.Post(randomEntries(rnd, rnd.pick(MIX)));
  }

  /** Builds the entries for one shape. Amounts and slots are drawn from {@code rnd}. */
  public static List<OpEntry> randomEntries(RandomSource rnd, Shape shape) {
    return switch (shape) {
      case BALANCED -> balanced(rnd, rnd.nextInt(1, 4), rnd.nextInt(1, 4), ORDINARY_CEILING);
      case TRANSFER -> {
        long amount = rnd.nextLong(1L, ORDINARY_CEILING);
        yield List.of(debit(rnd, amount), credit(rnd, amount));
      }
      case MANY_LEGS -> balanced(rnd, rnd.nextInt(4, 7), rnd.nextInt(4, 7), ORDINARY_CEILING);
      case SELF_CANCELLING -> {
        long amount = rnd.nextLong(1L, ORDINARY_CEILING);
        int slot = rnd.nextInt(8);
        yield List.of(
            new OpEntry(slot, Side.DEBIT, amount), new OpEntry(slot, Side.CREDIT, amount));
      }
      case DUPLICATE_ACCOUNT -> {
        long first = rnd.nextLong(1L, ORDINARY_CEILING);
        long second = rnd.nextLong(1L, ORDINARY_CEILING);
        int slot = rnd.nextInt(8);
        List<OpEntry> entries = new ArrayList<>();
        entries.add(new OpEntry(slot, Side.DEBIT, first));
        entries.add(new OpEntry(slot, Side.DEBIT, second));
        entries.addAll(split(rnd, first + second, rnd.nextInt(1, 3), Side.CREDIT));
        shuffle(rnd, entries);
        yield entries;
      }
      case UNBALANCED -> {
        List<OpEntry> entries =
            new ArrayList<>(balanced(rnd, rnd.nextInt(1, 4), rnd.nextInt(1, 4),
                ORDINARY_CEILING));
        int victim = rnd.nextInt(entries.size());
        OpEntry original = entries.get(victim);
        long nudge = rnd.nextLong(1L, 100L) * (rnd.nextBoolean() ? -1L : 1L);
        entries.set(victim, new OpEntry(original.slot(), original.side(),
            original.minorUnits() + nudge));
        yield List.copyOf(entries);
      }
      case UNKNOWN_ACCOUNT -> {
        long amount = rnd.nextLong(1L, ORDINARY_CEILING);
        List<OpEntry> entries = new ArrayList<>();
        entries.add(new OpEntry(OpEntry.UNKNOWN_SLOT, Side.DEBIT, amount));
        entries.add(credit(rnd, amount));
        shuffle(rnd, entries);
        yield entries;
      }
      case TOO_FEW_ENTRIES -> {
        if (rnd.nextBoolean()) {
          yield List.of();
        }
        yield List.of(debit(rnd, rnd.nextLong(1L, ORDINARY_CEILING)));
      }
      case NON_POSITIVE_AMOUNT -> {
        long bad = rnd.pick(new Long[] {0L, -1L, -1_000L, Long.MIN_VALUE});
        long good = rnd.nextLong(1L, ORDINARY_CEILING);
        List<OpEntry> entries = new ArrayList<>();
        entries.add(new OpEntry(rnd.nextInt(8), Side.DEBIT, bad));
        entries.add(new OpEntry(rnd.nextInt(8), Side.CREDIT, good));
        shuffle(rnd, entries);
        yield entries;
      }
      case OVERFLOWING_TOTALS -> {
        List<OpEntry> entries = new ArrayList<>();
        entries.add(new OpEntry(rnd.nextInt(8), Side.DEBIT, Long.MAX_VALUE));
        entries.add(new OpEntry(rnd.nextInt(8), Side.DEBIT, Long.MAX_VALUE));
        entries.add(new OpEntry(rnd.nextInt(8), Side.CREDIT, 7L));
        entries.add(new OpEntry(rnd.nextInt(8), Side.CREDIT, 7L));
        yield entries;
      }
      case EXTREME -> {
        long amount = rnd.pick(new Long[] {Long.MAX_VALUE, Long.MAX_VALUE - 1L, 1L << 62});
        yield List.of(
            new OpEntry(rnd.nextInt(4), Side.DEBIT, amount),
            new OpEntry(rnd.nextInt(4), Side.CREDIT, amount));
      }
    };
  }

  /** {@code debitLegs} debits and {@code creditLegs} credits summing to the same total. */
  private static List<OpEntry> balanced(
      RandomSource rnd, int debitLegs, int creditLegs, long ceiling) {
    List<OpEntry> debits = split(rnd, rnd.nextLong(debitLegs, debitLegs * ceiling), debitLegs,
        Side.DEBIT);
    long total = 0L;
    for (OpEntry entry : debits) {
      total += entry.minorUnits();
    }
    List<OpEntry> entries = new ArrayList<>(debits);
    entries.addAll(split(rnd, total, creditLegs, Side.CREDIT));
    shuffle(rnd, entries);
    return entries;
  }

  /**
   * Splits {@code total} into exactly {@code legs} positive parts. Every part is at least 1,
   * so a split never silently produces the zero amount that {@link Entry} refuses — a
   * generator that could produce an invalid "valid" transaction would spend the whole
   * campaign testing the wrong thing.
   */
  private static List<OpEntry> split(RandomSource rnd, long total, int legs, Side side) {
    if (total < legs) {
      throw new IllegalArgumentException("cannot split " + total + " into " + legs + " parts");
    }
    List<OpEntry> parts = new ArrayList<>(legs);
    long remaining = total;
    for (int leg = 0; leg < legs; leg++) {
      int legsLeft = legs - leg;
      long part;
      if (legsLeft == 1) {
        part = remaining;
      } else if (remaining == legsLeft) {
        part = 1L;
      } else {
        part = 1L + rnd.nextLong(remaining - legsLeft);
      }
      parts.add(new OpEntry(rnd.nextInt(8), side, part));
      remaining -= part;
    }
    return parts;
  }

  private static OpEntry debit(RandomSource rnd, long amount) {
    return new OpEntry(rnd.nextInt(8), Side.DEBIT, amount);
  }

  private static OpEntry credit(RandomSource rnd, long amount) {
    return new OpEntry(rnd.nextInt(8), Side.CREDIT, amount);
  }

  private static void shuffle(RandomSource rnd, List<OpEntry> entries) {
    for (int i = entries.size() - 1; i > 0; i--) {
      int swap = rnd.nextInt(i + 1);
      OpEntry moved = entries.get(i);
      entries.set(i, entries.get(swap));
      entries.set(swap, moved);
    }
  }

  // --- shrinking -----------------------------------------------------------------------

  /** Smaller versions of an operation: fewer entries, smaller amounts, lower slots. */
  public static List<LedgerOp> smaller(LedgerOp op) {
    if (op instanceof LedgerOp.OpenAccount open) {
      AccountKind[] kinds = AccountKind.values();
      if (open.kind().ordinal() == 0) {
        return List.of();
      }
      return List.of(new LedgerOp.OpenAccount(kinds[open.kind().ordinal() - 1]));
    }
    if (op instanceof LedgerOp.Post post) {
      List<LedgerOp> candidates = new ArrayList<>();
      List<OpEntry> entries = post.entries();
      for (List<OpEntry> shorter : Shrink.halves(entries)) {
        candidates.add(new LedgerOp.Post(shorter));
      }
      for (List<OpEntry> shorter : Shrink.withoutOne(entries)) {
        candidates.add(new LedgerOp.Post(shorter));
      }
      for (int i = 0; i < entries.size(); i++) {
        for (OpEntry smaller : smallerEntry(entries.get(i))) {
          List<OpEntry> variant = new ArrayList<>(entries);
          variant.set(i, smaller);
          candidates.add(new LedgerOp.Post(variant));
        }
      }
      return candidates;
    }
    throw new AssertionError("an operation this suite does not know how to shrink: " + op);
  }

  private static List<OpEntry> smallerEntry(OpEntry entry) {
    List<OpEntry> candidates = new ArrayList<>();
    for (long amount : Shrink.towardsZero(entry.minorUnits())) {
      candidates.add(new OpEntry(entry.slot(), entry.side(), amount));
    }
    for (long slot : Shrink.towardsZero(entry.slot())) {
      candidates.add(new OpEntry((int) slot, entry.side(), entry.minorUnits()));
    }
    if (entry.side() == Side.CREDIT) {
      candidates.add(new OpEntry(entry.slot(), Side.DEBIT, entry.minorUnits()));
    }
    if (entry.isUnknownAccount()) {
      candidates.add(new OpEntry(0, entry.side(), entry.minorUnits()));
    }
    return candidates;
  }

  // --- the curriculum ------------------------------------------------------------------

  private static final int CURRICULUM_LENGTH = 19;

  /**
   * Nineteen operations that between them walk every path in the grammar, written out long so
   * that reading them is reading the rules. Slots 0–2 are ordinary accounts and slots 3 and 4
   * are opened for the range cases and stay at zero until those cases run, so the arithmetic
   * in the comments below is arithmetic a reader can check.
   */
  public static List<LedgerOp> curriculum() {
    List<LedgerOp> ops = new ArrayList<>(CURRICULUM_LENGTH);

    // 0-2: the three kinds, one account each.
    ops.add(new LedgerOp.OpenAccount(AccountKind.ASSET));
    ops.add(new LedgerOp.OpenAccount(AccountKind.LIABILITY));
    ops.add(new LedgerOp.OpenAccount(AccountKind.EQUITY));
    // 3-4: two accounts reserved for the top of the long range.
    ops.add(new LedgerOp.OpenAccount(AccountKind.ASSET));
    ops.add(new LedgerOp.OpenAccount(AccountKind.LIABILITY));

    // 5: a two-account transfer of 5000 minor units — accepted.
    ops.add(post(new OpEntry(0, Side.DEBIT, 5_000L), new OpEntry(1, Side.CREDIT, 5_000L)));

    // 6 and 7: too few entries — one entry, then none.
    ops.add(post(new OpEntry(0, Side.DEBIT, 100L)));
    ops.add(new LedgerOp.Post(List.of()));

    // 8: off by one minor unit — the near miss that matters.
    ops.add(post(new OpEntry(0, Side.DEBIT, 100L), new OpEntry(1, Side.CREDIT, 99L)));

    // 9: an account that was never opened.
    ops.add(
        post(
            new OpEntry(OpEntry.UNKNOWN_SLOT, Side.DEBIT, 10L),
            new OpEntry(1, Side.CREDIT, 10L)));

    // 10 and 11: amounts that cannot become an entry at all.
    ops.add(post(new OpEntry(0, Side.DEBIT, 0L), new OpEntry(1, Side.CREDIT, 0L)));
    ops.add(post(new OpEntry(0, Side.DEBIT, Long.MIN_VALUE), new OpEntry(1, Side.CREDIT, 5L)));

    // 12: an n-entry transaction — eight legs, four a side, all of them balanced.
    ops.add(
        post(
            new OpEntry(0, Side.DEBIT, 11L),
            new OpEntry(1, Side.DEBIT, 12L),
            new OpEntry(2, Side.DEBIT, 13L),
            new OpEntry(0, Side.DEBIT, 14L),
            new OpEntry(1, Side.CREDIT, 20L),
            new OpEntry(2, Side.CREDIT, 10L),
            new OpEntry(0, Side.CREDIT, 5L),
            new OpEntry(2, Side.CREDIT, 15L)));

    // 13: debit and credit the same account — legal, and nets to nothing.
    ops.add(post(new OpEntry(2, Side.DEBIT, 10L), new OpEntry(2, Side.CREDIT, 10L)));

    // 14: one account debited twice against a single credit.
    ops.add(
        post(
            new OpEntry(2, Side.DEBIT, 3L),
            new OpEntry(2, Side.DEBIT, 4L),
            new OpEntry(0, Side.CREDIT, 7L)));

    // 15: the reversal of operation 5 — the only way anything is ever undone here.
    ops.add(post(new OpEntry(1, Side.DEBIT, 5_000L), new OpEntry(0, Side.CREDIT, 5_000L)));

    // 16: totals that do not fit in a long, checked before the money rule.
    ops.add(
        post(
            new OpEntry(3, Side.DEBIT, Long.MAX_VALUE),
            new OpEntry(3, Side.DEBIT, Long.MAX_VALUE),
            new OpEntry(2, Side.CREDIT, 7L),
            new OpEntry(2, Side.CREDIT, 7L)));

    // 17: accepted at the very top of the range — slot 3 holds MAX_VALUE, slot 4 holds its
    //     negation, and Σ balances is still exactly zero.
    ops.add(
        post(
            new OpEntry(3, Side.DEBIT, Long.MAX_VALUE),
            new OpEntry(4, Side.CREDIT, Long.MAX_VALUE)));

    // 18: the same posting again, so slot 3's balance would not fit — rejected for the limit,
    //     not wrapped into a negative one.
    ops.add(
        post(
            new OpEntry(3, Side.DEBIT, Long.MAX_VALUE),
            new OpEntry(4, Side.CREDIT, Long.MAX_VALUE)));

    if (ops.size() != CURRICULUM_LENGTH) {
      throw new AssertionError(
          "the curriculum has " + ops.size() + " operations, declared " + CURRICULUM_LENGTH);
    }
    return ops;
  }

  private static LedgerOp.Post post(OpEntry... entries) {
    return new LedgerOp.Post(List.of(entries));
  }
}
