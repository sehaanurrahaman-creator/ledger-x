package dev.ledgerx.domain;

import dev.ledgerx.testing.PropertyRunner;
import dev.ledgerx.testing.RandomSource;
import dev.ledgerx.testing.Shrink;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The double-entry invariants, proved in memory: what the domain ticket asks for and the
 * evidence ADR 0002 cites.
 *
 * <p>Nine checks. Four are the ones the ticket names — Σ balances = 0 after every step, each
 * transaction's debits equal its credits, entries never mutate, invalid transactions change
 * nothing — and they are not four separate loops over the same campaign: they are asserted
 * after <em>every operation</em> of every random case, by {@link OpApplier}, against an
 * independent {@link ModelLedger} oracle. The other five exist because a property suite can
 * pass without proving anything, and each one closes a way that could happen here:
 *
 * <ul>
 *   <li>the campaign is not vacuous — every acceptance and rejection path was walked;
 *   <li>the rejection order the ADR documents is the order the code evaluates;
 *   <li>overflow is rejected rather than wrapped;
 *   <li>{@link InMemoryLedger#audit()} actually fails on a corrupted ledger, so the checks
 *       that depend on it are not decoration — this one reaches into private state by
 *       reflection on purpose;
 *   <li>the harness itself is reproducible, because a seed that does not reproduce makes every
 *       other claim in this file unverifiable.
 * </ul>
 *
 * <p>Run by {@code ./build.sh}; exits non-zero if any check fails. Reproduce one case with
 * {@code -Dledgerx.property.seed=<the seed in the failure>}, and scale the campaign with
 * {@code -Dledgerx.property.trials=} and {@code -Dledgerx.property.operations=}.
 */
public final class DomainModelProperties {

  private static final int DEFAULT_TRIALS = 60;
  private static final int DEFAULT_OPERATIONS = 200;

  /** Fixed, so a green build is the same build tomorrow: the seeds are part of the record. */
  private static final long DEFAULT_BASE_SEED = 20260914L;

  /** Cumulative across the campaign; exact on a green run, see {@link #campaign}. */
  private static final OpApplier.Coverage campaignCoverage = new OpApplier.Coverage();

  /** The types whose fields must all be final for "entries never mutate" to mean anything. */
  private static final Class<?>[] DOMAIN_TYPES = {
    Money.class,
    AccountId.class,
    Account.class,
    Entry.class,
    Transaction.class,
    Side.class,
    AccountKind.class,
    RejectionReason.class,
    RejectedTransactionException.class,
    JournalEvent.class,
    JournalEvent.AccountOpened.class,
    JournalEvent.Posted.class,
    InMemoryLedger.class
  };

  /** Every delimiter the canonical rendering in {@link JournalDigest} uses. */
  private static final String FORBIDDEN_IN_IDS = " ,;:[](){}\n\t\r\0/\\|=\"'";

  private DomainModelProperties() {}

  public static void main(String[] args) {
    int trials = integerProperty("ledgerx.property.trials", DEFAULT_TRIALS);
    int operations = integerProperty("ledgerx.property.operations", DEFAULT_OPERATIONS);
    long baseSeed = longProperty("ledgerx.property.seed", DEFAULT_BASE_SEED);

    System.out.println("ledger-x domain model properties on " + Runtime.version());
    System.out.println(
        "  campaign: "
            + trials
            + " cases x "
            + operations
            + " operations, seed base "
            + baseSeed
            + ", curriculum "
            + OpGenerator.curriculumLength()
            + " operations per case");

    PropertyRunner runner = new PropertyRunner(trials, baseSeed, campaignCoverage::summary);

    campaign(runner, operations);
    coverage(runner, baseSeed, operations);
    replay(runner, operations);
    rejectionOrder(runner);
    overflow(runner);
    negativeBalances(runner);
    noMutationSurface(runner);
    auditIsNotVacuous(runner);
    harnessIsReproducible(runner, operations);

    System.out.println();
    System.out.println(runner.verdict());
    if (!runner.passed()) {
      System.exit(1);
    }
  }

  // --- the campaign --------------------------------------------------------------------

  /**
   * The ticket's four invariants, after every operation of every random case: Σ balances = 0,
   * debits equal credits, nothing already appended changed, and a refused transaction changed
   * nothing at all.
   */
  private static void campaign(PropertyRunner runner, int operations) {
    runner.check(
        "random sequences of valid and invalid operations never break the invariants",
        rnd -> OpGenerator.sequence(rnd, operations),
        DomainModelProperties::smallerSequence,
        ops -> {
          OpApplier applier = new OpApplier();
          applier.applyAll(ops);
          campaignCoverage.merge(applier.coverage());
          return campaignCoverage.summary();
        });
  }

  /** A campaign that never refused anything would pass every invariant without testing them. */
  private static void coverage(PropertyRunner runner, long baseSeed, int operations) {
    runner.checkOnce(
        "the generated mix walks every acceptance and rejection path",
        () -> {
          OpApplier applier = new OpApplier();
          applier.applyAll(OpGenerator.sequence(new RandomSource(baseSeed), operations));
          applier.coverage().assertComplete();
          return applier.coverage().summary();
        });
  }

  /** The in-memory ancestor of byte-identical replay: fold the log, get the same ledger. */
  private static void replay(PropertyRunner runner, int operations) {
    runner.check(
        "replaying the event log into a fresh ledger reproduces the state exactly",
        rnd -> OpGenerator.sequence(rnd, operations),
        DomainModelProperties::smallerSequence,
        ops -> {
          OpApplier applier = new OpApplier();
          applier.applyAll(ops);
          InMemoryLedger original = applier.ledger();
          InMemoryLedger replayed = replay(original);
          replayed.audit();

          JournalDigest digests = new JournalDigest();
          String before = digests.stateDigest(original);
          String after = digests.stateDigest(replayed);
              require(
              before.equals(after),
              "replay produced a different state digest:\n  original "
                  + before
                  + "\n  replayed "
                  + after);
          require(
              replayed.balances().equals(original.balances()),
              "replay produced different balances: "
                  + original.balances()
                  + " vs "
                  + replayed.balances());
          return original.size() + " events replayed identically";
        });
  }

  private static InMemoryLedger replay(InMemoryLedger source) {
    InMemoryLedger replayed = new InMemoryLedger();
    for (JournalEvent event : source.events()) {
      if (event instanceof JournalEvent.AccountOpened opened) {
        replayed.openAccount(opened.account().id(), opened.account().kind());
      } else if (event instanceof JournalEvent.Posted posted) {
        replayed.post(posted.transaction());
      }
    }
    return replayed;
  }

  private static List<List<LedgerOp>> smallerSequence(List<LedgerOp> failing) {
    List<List<LedgerOp>> candidates = new ArrayList<>(Shrink.halves(failing));
    candidates.addAll(Shrink.withoutOne(failing));
    candidates.addAll(Shrink.oneShrunk(failing, OpGenerator::smaller));
    return candidates;
  }

  // --- targeted checks -----------------------------------------------------------------

  /**
   * A candidate can break two rules at once, and which reason comes back is part of the
   * contract: the idempotency ticket will map reasons onto responses, so the choice must not
   * depend on which check happened to be written first. Each case below violates two rules and
   * must be refused for the earlier one in {@link RejectionReason}'s declared order.
   */
  private static void rejectionOrder(PropertyRunner runner) {
    runner.checkOnce(
        "a candidate breaking two rules is rejected for the earlier one, in the declared order",
        () -> {
          List<RejectionReason> declared = List.of(RejectionReason.values());
          List<RejectionReason> documented =
              List.of(
                  RejectionReason.TOO_FEW_ENTRIES,
                  RejectionReason.UNKNOWN_ACCOUNT,
                  RejectionReason.OVERFLOWING_TOTALS,
                  RejectionReason.UNBALANCED,
                  RejectionReason.OVERFLOWING_BALANCE);
          require(
              declared.equals(documented),
              "RejectionReason is declared " + declared + " but ADR 0002 documents " + documented);

          InMemoryLedger ledger = new InMemoryLedger();
          AccountId asset =
              ledger.openAccount(AccountId.of("order-asset"), AccountKind.ASSET).id();
          AccountId liability =
              ledger.openAccount(AccountId.of("order-liability"), AccountKind.LIABILITY).id();
          AccountId ghost = AccountId.of("order-ghost");
          Money max = Money.ofMinor(Long.MAX_VALUE);
          ledger.post(
              new Transaction(List.of(Entry.debit(asset, max), Entry.credit(liability, max))));

          List<String> observed = new ArrayList<>();
          // grammar before reference
          observed.add(
              expect(
                  ledger,
                  new Transaction(List.of(Entry.debit(ghost, Money.ofMinor(10L)))),
                  RejectionReason.TOO_FEW_ENTRIES));
          // reference before arithmetic
          observed.add(
              expect(
                  ledger,
                  new Transaction(
                      List.of(
                          Entry.debit(ghost, max),
                          Entry.debit(ghost, max),
                          Entry.credit(asset, Money.ofMinor(1L)))),
                  RejectionReason.UNKNOWN_ACCOUNT));
          // reference before the money rule
          observed.add(
              expect(
                  ledger,
                  new Transaction(
                      List.of(
                          Entry.debit(ghost, Money.ofMinor(10L)),
                          Entry.credit(asset, Money.ofMinor(5L)))),
                  RejectionReason.UNKNOWN_ACCOUNT));
          // arithmetic before the money rule: totals that wrapped would have compared equal
          observed.add(
              expect(
                  ledger,
                  new Transaction(
                      List.of(
                          Entry.debit(asset, max),
                          Entry.debit(asset, max),
                          Entry.credit(liability, Money.ofMinor(1L)))),
                  RejectionReason.OVERFLOWING_TOTALS));
          // the money rule before the representation limit
          observed.add(
              expect(
                  ledger,
                  new Transaction(
                      List.of(
                          Entry.debit(asset, Money.ofMinor(5L)),
                          Entry.credit(liability, Money.ofMinor(3L)))),
                  RejectionReason.UNBALANCED));
          // and the limit on its own
          observed.add(
              expect(
                  ledger,
                  new Transaction(
                      List.of(
                          Entry.debit(asset, Money.ofMinor(1L)),
                          Entry.credit(liability, Money.ofMinor(1L)))),
                  RejectionReason.OVERFLOWING_BALANCE));

          ledger.audit();
          return String.join(", ", observed);
        });
  }

  private static String expect(
      InMemoryLedger ledger, Transaction candidate, RejectionReason expected) {
    try {
      ledger.post(candidate);
    } catch (RejectedTransactionException rejected) {
      require(
          rejected.reason() == expected,
          "expected " + expected + " for " + candidate + ", got " + rejected.reason());
      return expected.name();
    }
    throw new AssertionError(
        "the ledger accepted a candidate it should have refused: " + candidate);
  }

  /** The whole point of overflow-checked money: a wrapped balance would break Σ = 0 silently. */
  private static void overflow(PropertyRunner runner) {
    runner.checkOnce(
        "an overflowing total or balance is rejected and nothing wraps",
        () -> {
          InMemoryLedger ledger = new InMemoryLedger();
          AccountId high = ledger.openAccount(AccountId.of("range-high"), AccountKind.ASSET).id();
          AccountId low = ledger.openAccount(AccountId.of("range-low"), AccountKind.LIABILITY).id();
          Money max = Money.ofMinor(Long.MAX_VALUE);

          ledger.post(new Transaction(List.of(Entry.debit(high, max), Entry.credit(low, max))));
          require(
              ledger.balanceOf(high).minorUnits() == Long.MAX_VALUE,
              "at the limit, the balance is " + ledger.balanceOf(high).minorUnits());
          require(
              ledger.balanceOf(low).minorUnits() == -Long.MAX_VALUE,
              "at the limit, the balance is " + ledger.balanceOf(low).minorUnits());
          require(ledger.totalBalance().isZero(), "Σ balances is " + ledger.totalBalance());

          Transaction oneMore =
              new Transaction(
                  List.of(
                      Entry.debit(high, Money.ofMinor(1L)),
                      Entry.credit(low, Money.ofMinor(1L))));
          expect(ledger, oneMore, RejectionReason.OVERFLOWING_BALANCE);
          require(
              ledger.balanceOf(high).minorUnits() == Long.MAX_VALUE,
              "a rejected posting moved the balance to " + ledger.balanceOf(high).minorUnits());
          require(ledger.size() == 3, "a rejected posting appended an event");

          Transaction wrappedTotals =
              new Transaction(
                  List.of(
                      Entry.debit(high, max),
                      Entry.debit(high, max),
                      Entry.credit(low, Money.ofMinor(7L)),
                      Entry.credit(low, Money.ofMinor(7L))));
          expect(ledger, wrappedTotals, RejectionReason.OVERFLOWING_TOTALS);

          ledger.audit();
          return "balance held at "
              + ledger.balanceOf(high).minorUnits()
              + " / "
              + ledger.balanceOf(low).minorUnits()
              + ", Σ balances = "
              + ledger.totalBalance().minorUnits()
              + ", events = "
              + ledger.size();
        });
  }

  /**
   * ADR 0002 §5: the ledger records, it does not judge. An overdrawn asset and an
   * unnatural-side liability are both legal postings, and Σ balances stays zero through them.
   */
  private static void negativeBalances(PropertyRunner runner) {
    runner.checkOnce(
        "a balance may go negative on every kind, and Σ balances stays zero",
        () -> {
          InMemoryLedger ledger = new InMemoryLedger();
          AccountId bank = ledger.openAccount(AccountId.of("bank"), AccountKind.ASSET).id();
          AccountId payable =
              ledger.openAccount(AccountId.of("merchant-payable"), AccountKind.LIABILITY).id();
          AccountId capital = ledger.openAccount(AccountId.of("capital"), AccountKind.EQUITY).id();

          ledger.transfer(capital, bank, Money.ofMinor(10_000L));
          ledger.transfer(bank, payable, Money.ofMinor(25_000L));

          Money bankBalance = ledger.balanceOf(bank);
          Money payableBalance = ledger.balanceOf(payable);
          require(bankBalance.isNegative(), "the overdrawn asset holds " + bankBalance);
          require(
              !AccountKind.ASSET.isNaturalBalance(bankBalance),
              "a negative asset should read as unnatural");
          require(payableBalance.isPositive(), "the payable holds " + payableBalance);
          require(
              !AccountKind.LIABILITY.isNaturalBalance(payableBalance),
              "a debit-positive liability should read as unnatural");
          require(
              AccountKind.EQUITY.isNaturalBalance(ledger.balanceOf(capital)),
              "equity of " + ledger.balanceOf(capital) + " should read as natural");

          ledger.audit();
          return "bank="
              + bankBalance.minorUnits()
              + " payable="
              + payableBalance.minorUnits()
              + " capital="
              + ledger.balanceOf(capital).minorUnits()
              + ", Σ balances = "
              + ledger.totalBalance().minorUnits();
        });
  }

  /**
   * "Entries never mutate" needs three different kinds of evidence: the types offer no way to
   * change them, the ledger hands out no way to change what it stored, and the identifiers
   * cannot forge the canonical form the immutability check reads.
   */
  private static void noMutationSurface(PropertyRunner runner) {
    runner.checkOnce(
        "no domain type, and no view of the ledger, offers a way to mutate what was appended",
        () -> {
          int fields = 0;
          for (Class<?> type : DOMAIN_TYPES) {
            for (Field field : type.getDeclaredFields()) {
              if (field.isSynthetic()) {
                continue;
              }
              fields++;
              require(
                  Modifier.isFinal(field.getModifiers()),
                  type.getSimpleName() + "." + field.getName() + " is not final");
            }
            for (Method method : type.getDeclaredMethods()) {
              if (method.isSynthetic() || !Modifier.isPublic(method.getModifiers())) {
                continue;
              }
              require(
                  !method.getName().startsWith("set"),
                  type.getSimpleName() + " exposes " + method.getName());
            }
            if (!type.isEnum() && !type.isInterface()) {
              boolean mutableByDesign =
                  type == InMemoryLedger.class || type == RejectedTransactionException.class;
              require(
                  type.isRecord() || mutableByDesign,
                  type.getSimpleName()
                      + " is neither a record nor one of the two mutable-by-design classes");
            }
          }

          InMemoryLedger ledger = new InMemoryLedger();
          AccountId from = ledger.openAccount(AccountId.of("copy-from"), AccountKind.ASSET).id();
          AccountId to = ledger.openAccount(AccountId.of("copy-to"), AccountKind.LIABILITY).id();

          List<Entry> handedOver =
              new ArrayList<>(
                  List.of(
                      Entry.debit(to, Money.ofMinor(700L)),
                      Entry.credit(from, Money.ofMinor(700L))));
          ledger.post(new Transaction(handedOver));
          handedOver.clear();
          handedOver.add(Entry.debit(to, Money.ofMinor(1L)));
          Transaction stored = ledger.transactions().get(0);
          require(stored.size() == 2, "the caller's list reached the journal: " + stored);
          require(
              stored.totalDebits().minorUnits() == 700L,
              "the stored transaction's debits are " + stored.totalDebits());

          require(refuses(ledger.events()), "events() is modifiable");
          require(refuses(ledger.transactions()), "transactions() is modifiable");
          require(refuses(ledger.accounts()), "accounts() is modifiable");
          require(refusesMap(ledger.balances()), "balances() is modifiable");
          require(refusesMap(ledger.foldBalances()), "foldBalances() is modifiable");

          int refused = 0;
          for (int i = 0; i < FORBIDDEN_IN_IDS.length(); i++) {
            char delimiter = FORBIDDEN_IN_IDS.charAt(i);
            try {
              AccountId.of("a" + delimiter + "b");
              throw new AssertionError(
                  "AccountId accepted 0x"
                      + Integer.toHexString(delimiter)
                      + ", which the canonical form uses as a delimiter");
            } catch (IllegalArgumentException rejected) {
              refused++;
            }
          }

          ledger.audit();
          return fields
              + " fields, all final; "
              + refused
              + " delimiters refused by AccountId; views unmodifiable; caller's list cannot"
              + " reach the journal";
        });
  }

  /**
   * The checks above all lean on {@link InMemoryLedger#audit()}, so audit() has to fail when
   * the ledger is wrong. This reaches into private state by reflection to make it wrong on
   * purpose — a test that cannot corrupt the thing it is testing cannot show the test works.
   */
  private static void auditIsNotVacuous(PropertyRunner runner) {
    runner.checkOnce(
        "audit() detects a corrupted index, a phantom event and a vanished one",
        () -> {
          InMemoryLedger ledger = new InMemoryLedger();
          AccountId asset =
              ledger.openAccount(AccountId.of("audit-asset"), AccountKind.ASSET).id();
          AccountId liability =
              ledger.openAccount(AccountId.of("audit-liability"), AccountKind.LIABILITY).id();
          ledger.transfer(asset, liability, Money.ofMinor(1_000L));
          ledger.audit();

          Object index = privateState(ledger, "balances");
          long saved = (Long) mapGet(index, asset);
          mapPut(index, asset, saved + 1L);
          String corrupted = auditFailure(ledger);
          require(
              corrupted != null && corrupted.contains("balance index disagrees"),
              "a corrupted balance index passed audit(): " + corrupted);
          mapPut(index, asset, saved);
          ledger.audit();

          Object events = privateState(ledger, "events");
          Transaction phantom =
              new Transaction(
                  List.of(
                      Entry.debit(asset, Money.ofMinor(1L)),
                      Entry.credit(liability, Money.ofMinor(2L))));
          listAdd(events, new JournalEvent.Posted(phantom));
          String unbalanced = auditFailure(ledger);
          require(
              unbalanced != null && unbalanced.contains("unbalanced"),
              "a phantom unbalanced event passed audit(): " + unbalanced);
          listRemoveLast(events);

          listAdd(
              events,
              new JournalEvent.Posted(
                  Transaction.transfer(
                      asset, AccountId.of("audit-never-opened"), Money.ofMinor(1L))));
          String unknown = auditFailure(ledger);
          require(
              unknown != null && unknown.contains("never opened"),
              "an event naming an unopened account passed audit(): " + unknown);
          listRemoveLast(events);

          Object opening = listRemoveAt(events, 0);
          String vanished = auditFailure(ledger);
          require(
              vanished != null && vanished.contains("account index disagrees"),
              "a vanished opening passed audit(): " + vanished);
          listAddAt(events, 0, opening);

          ledger.audit();
          return "corrupted index, phantom event, unopened account and vanished opening all"
              + " detected; a clean ledger audits silently";
        });
  }

  /** A seed that does not reproduce would make every failure message in this file a lie. */
  private static void harnessIsReproducible(PropertyRunner runner, int operations) {
    runner.checkOnce(
        "the generator reproduces a case from its seed, and differs on another seed",
        () -> {
          long seed = DEFAULT_BASE_SEED + 7L;
          List<LedgerOp> first = OpGenerator.sequence(new RandomSource(seed), operations);
          List<LedgerOp> second = OpGenerator.sequence(new RandomSource(seed), operations);
          List<LedgerOp> other = OpGenerator.sequence(new RandomSource(seed + 1L), operations);
          require(first.equals(second), "the same seed produced two different cases");
          require(!first.equals(other), "two seeds produced the same case");
          require(first.size() == operations, "asked for " + operations + ", got " + first.size());
          return operations + " operations reproduce from seed " + seed;
        });
  }

  // --- helpers -------------------------------------------------------------------------

  private static void require(boolean condition, String failure) {
    if (!condition) {
      throw new AssertionError(failure);
    }
  }

  private static boolean refuses(List<?> view) {
    try {
      view.clear();
      return false;
    } catch (UnsupportedOperationException immutable) {
      return true;
    }
  }

  private static boolean refusesMap(Map<?, ?> view) {
    try {
      view.clear();
      return false;
    } catch (UnsupportedOperationException immutable) {
      return true;
    }
  }

  private static String auditFailure(InMemoryLedger ledger) {
    try {
      ledger.audit();
    } catch (IllegalStateException detected) {
      return detected.getMessage();
    }
    return null;
  }

  /**
   * Reads one of the ledger's private fields, to corrupt it on purpose.
   *
   * <p>Everything below goes through {@link Method#invoke} rather than a cast to the field's
   * real type, and the reason is a compiler, not a style: an unchecked cast needs
   * {@code @SuppressWarnings("unchecked")}, which javac honours and the ECJ build that
   * {@code scripts/bootstrap-toolchain.sh} uses does not when {@code -err:+unchecked} promotes
   * the warning. Code that only compiles under one of the two compilers this repository builds
   * with is code that cannot be checked locally, so there is none of it here.
   */
  private static Object privateState(InMemoryLedger ledger, String name) {
    try {
      Field field = InMemoryLedger.class.getDeclaredField(name);
      field.setAccessible(true);
      return field.get(ledger);
    } catch (ReflectiveOperationException blocked) {
      throw new AssertionError(
          "cannot reach InMemoryLedger." + name + " to prove audit() is not vacuous", blocked);
    }
  }

  private static Object invoke(
      Object target, String name, Class<?>[] parameterTypes, Object... arguments) {
    try {
      Method method = target.getClass().getMethod(name, parameterTypes);
      return method.invoke(target, arguments);
    } catch (ReflectiveOperationException blocked) {
      throw new AssertionError(
          "cannot invoke " + name + " on " + target.getClass().getName(), blocked);
    }
  }

  private static Object mapGet(Object map, Object key) {
    return invoke(map, "get", new Class<?>[] {Object.class}, key);
  }

  private static void mapPut(Object map, Object key, Object value) {
    invoke(map, "put", new Class<?>[] {Object.class, Object.class}, key, value);
  }

  private static void listAdd(Object list, Object value) {
    invoke(list, "add", new Class<?>[] {Object.class}, value);
  }

  private static void listAddAt(Object list, int index, Object value) {
    invoke(list, "add", new Class<?>[] {int.class, Object.class}, index, value);
  }

  private static Object listRemoveAt(Object list, int index) {
    return invoke(list, "remove", new Class<?>[] {int.class}, index);
  }

  private static void listRemoveLast(Object list) {
    int size = (Integer) invoke(list, "size", new Class<?>[0]);
    listRemoveAt(list, size - 1);
  }

  private static int integerProperty(String name, int fallback) {
    String value = System.getProperty(name);
    if (value == null || value.isBlank()) {
      return fallback;
    }
    return Integer.parseInt(value);
  }

  private static long longProperty(String name, long fallback) {
    String value = System.getProperty(name);
    if (value == null || value.isBlank()) {
      return fallback;
    }
    return Long.parseLong(value);
  }
}
