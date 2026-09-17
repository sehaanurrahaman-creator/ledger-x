package dev.ledgerx.domain;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The whole ledger, in memory: one append-only event log, an index of balances derived from
 * it, an index of idempotency bindings derived from it the same way, and the validation that
 * decides what may be appended.
 *
 * <p>This is the prototype the domain ticket asks for, and it is deliberately the only thing
 * it is. There is no durability here, no WAL, no fsync, no clock and no lock. What it exists
 * to prove is that the grammar and the invariants are right before anything is built on top
 * of them: every downstream ticket — the record format, idempotency, replay, concurrency —
 * reads a decision made here.
 *
 * <p><strong>The idempotency index joined the balances in ADR 0005, under the rules ADR 0004
 * §9 handed over.</strong> It is derived state — a fold of the log's keyed-posting events,
 * checked against them by {@link #audit()} — and it holds <em>instants as data, never clock
 * reads</em>: the capture instant of a binding is a value the caller supplies and the log
 * committed, which is what lets replay be deterministic while expiry still has a wall clock to
 * consult above this layer.
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
   * The balance every account had when <em>this</em> ledger's journal began: 0 for an account this
   * ledger opened itself, and a checkpoint's number for an account restored from one.
   *
   * <p>It exists because a checkpoint is a fold the ledger did not perform, and a ledger that
   * cannot say where its fold started cannot audit itself. With this map, a restored ledger is as
   * checkable as a fresh one: its index must equal this base plus a fold of the events it has
   * actually seen, which is exactly {@link #audit()}'s existing rule with one term added.
   */
  private final Map<AccountId, Long> baseBalances = new LinkedHashMap<>();

  /**
   * The accounts that came from a checkpoint rather than from an event in this ledger's journal.
   *
   * <p>A set of ids rather than a counter, because a counter can disagree with the maps it is
   * supposed to summarise and a set cannot be compared against them — and because every field of
   * this class is final, a rule the domain property suite enforces by reflection. That rule earned
   * its keep here: the alternative was a free-standing {@code int} that no other structure held to.
   */
  private final Set<AccountId> restored = new LinkedHashSet<>();

  /**
   * The idempotency index: one row per bound {@code (merchant, key)}, in binding order — which is
   * log order, because a {@code LinkedHashMap} built by a fold holds rows in the order their
   * records appear, and the order is the one the canonical state section requires (ADR 0005 §6).
   *
   * <p>Derived from the log and checked against it by {@link #audit()}, exactly like the balance
   * index: a binding the index holds that neither the log bound nor a checkpoint restored was
   * invented, and one the log binds that the index does not hold was lost. The one field that is
   * <em>not</em> held to the log is the stored response — a reference to a transaction the event
   * list already holds, a cache like any index, and re-materializable from the record the row's
   * {@code responseLsn} names.
   */
  private final LinkedHashMap<Scope, Row> keyBindings = new LinkedHashMap<>();

  /**
   * The bindings that came from a checkpoint rather than from an event in this ledger's journal —
   * the same partition the account index needs, for the same reason: a restored ledger's audit
   * must be able to say "the index is the restored base plus what this journal bound", not merely
   * count rows.
   */
  private final Set<Scope> restoredBindings = new LinkedHashSet<>();

  /** The identity a binding is filed under: the pair the charter scopes by, nothing else. */
  private record Scope(MerchantId merchant, IdempotencyKey key) {}

  /**
   * One row of the index: the durable binding, the committed capture instant, and the stored
   * response. Immutable, like everything else in the index — recovery builds rows whole, from a
   * checkpoint's identity, its timing section and the record the binding names, and never has to
   * repair one half of a row it already inserted.
   */
  private record Row(KeyBinding binding, long capturedAtMillis, Transaction response) {}

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
    baseBalances.put(id, 0L);
    events.add(new JournalEvent.AccountOpened(account));
    return account;
  }

  /**
   * Restores an account as a checkpoint recorded it: known, ordered, and holding a balance that no
   * event in this ledger's journal produced.
   *
   * <p>This is recovery's write path into the domain and the only method here that adds state
   * without appending an event — because the event exists, in the log, <em>before</em> the
   * checkpoint's watermark, and the checkpoint's whole job is to stand in for the part of the
   * journal the ledger is not going to hold. It is why ADR 0004 §9 records a domain change: before
   * it, "the log is the truth and the balances are an index of it" was a claim about a fold from
   * event one, and a snapshot makes the fold start in the middle.
   *
   * <p>It refuses a duplicate the same way {@link #openAccount} does, so a log that opens an
   * account a checkpoint already restored is a refusal at replay rather than a silent overwrite.
   *
   * @param account the account, with the kind it had
   * @param openingBalance its balance at the checkpoint's watermark
   */
  public void restore(Account account, Money openingBalance) {
    Objects.requireNonNull(account, "restored account");
    Objects.requireNonNull(openingBalance, "restored balance");
    if (accounts.containsKey(account.id())) {
      throw new IllegalArgumentException(
          "already open: " + account.id() + " (" + account.kind() + ")");
    }
    accounts.put(account.id(), account);
    balances.put(account.id(), openingBalance.minorUnits());
    baseBalances.put(account.id(), openingBalance.minorUnits());
    restored.add(account.id());
  }

  /** How many accounts were restored from a checkpoint rather than opened by an event here. */
  public int restoredAccounts() {
    return restored.size();
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
   * The check half of {@link #post}, and nothing else: every rule, against immutable state, with
   * no append and no mutation.
   *
   * <p>This is the split ADR 0003 §9 asked the domain for and handed to a later ticket, and
   * ADR 0005 §4 finally needs it: a keyed posting must sequence its append <em>between</em> the
   * check and the apply, because the binding row names the LSN of the record that carries it — a
   * number that does not exist until the append is acknowledged. A caller that checks first,
   * appends second and applies third can give the row a true position; a caller that had to apply
   * first would have to predict one.
   *
   * @throws RejectedTransactionException exactly as {@link #post} does, with nothing changed
   */
  public void check(Transaction candidate) {
    Objects.requireNonNull(candidate, "candidate transaction");
    validate(candidate);
  }

  /**
   * Appends {@code candidate} <em>and binds the key to it</em>: one event, one row, one thing
   * that either happened or did not.
   *
   * <p>This is the fold-side twin of {@code DurableLedger.postIdempotent}. The caller owns the
   * log, so the caller supplies the position: on the write path it is the ack's LSN, on the
   * replay path the record's own LSN, and the row is the same either way. Validation is the
   * same {@link #validate} as {@link #post} runs — re-checked here rather than trusted from the
   * write path, because a fold must be able to refuse a posting the domain rejects no matter who
   * wrote it (ADR 0003 §4's {@code DOMAIN_REJECTED} rule, inherited).
   *
   * <p>Refusing a duplicate binding with {@link IllegalArgumentException} rather than returning a
   * verdict is the same choice {@link #openAccount} makes, and for the same reason: no writer that
   * consults the index before binding can produce a second binding for one pair, so a log that
   * holds one was not written by this build, and recovery's job is to say so, loudly
   * ({@code Replay} turns this into a refusal to open).
   *
   * @param merchant the scope the key is unique within
   * @param key the client's key
   * @param fingerprint the body the key is being bound to, as computed by the caller's codec
   * @param capturedAtMillis the server's capture instant, committed verbatim
   * @param responseLsn the LSN of the record carrying this posting, supplied by the log's owner
   * @return the appended transaction — the same object, which is also the stored response
   * @throws RejectedTransactionException if the candidate breaks the grammar, with nothing
   *     appended, no row inserted, and no balance moved
   * @throws IllegalArgumentException if {@code (merchant, key)} is already bound
   */
  public Transaction postIdempotently(
      MerchantId merchant,
      IdempotencyKey key,
      RequestFingerprint fingerprint,
      long capturedAtMillis,
      long responseLsn,
      Transaction candidate) {
    Objects.requireNonNull(merchant, "merchant");
    Objects.requireNonNull(key, "idempotency key");
    Objects.requireNonNull(fingerprint, "request fingerprint");
    Objects.requireNonNull(candidate, "candidate transaction");
    Scope scope = new Scope(merchant, key);
    if (keyBindings.containsKey(scope)) {
      throw new IllegalArgumentException(
          "the key " + key + " is already bound in the scope of " + merchant);
    }
    Map<AccountId, Long> checkedDeltas = validate(candidate);
    events.add(
        new JournalEvent.PostedIdempotently(
            merchant, key, fingerprint, capturedAtMillis, candidate));
    applyDeltas(checkedDeltas);
    keyBindings.put(
        scope,
        new Row(new KeyBinding(merchant, key, fingerprint, responseLsn), capturedAtMillis,
            candidate));
    return candidate;
  }

  /**
   * Restores a binding as a checkpoint recorded it: known, ordered, holding an identity whose
   * response the caller re-read from the log the checkpoint points at.
   *
   * <p>Recovery's write path into the idempotency index, and the twin of {@link #restore}: the
   * event exists — in the log, before the checkpoint's watermark — and the checkpoint's whole
   * job
   * is to stand in for the part of the journal this ledger is not going to hold. The caller
   * supplies the capture instant from the checkpoint's timing section and the response from the
   * record the binding names, because both live outside the hashed state section that is this
   * method's primary input (ADR 0005 §6).
   *
   * @throws IllegalArgumentException if the pair is already bound — a log that re-binds a key a
   *     checkpoint already restored is a refusal at replay, not a silent overwrite
   */
  public void restoreBinding(KeyBinding binding, long capturedAtMillis, Transaction response) {
    Objects.requireNonNull(binding, "restored binding");
    Objects.requireNonNull(response, "restored response");
    Scope scope = new Scope(binding.merchant(), binding.key());
    if (keyBindings.containsKey(scope)) {
      throw new IllegalArgumentException("already bound: " + binding);
    }
    keyBindings.put(scope, new Row(binding, capturedAtMillis, response));
    restoredBindings.add(scope);
  }

  /**
   * What a lookup of {@code (merchant, key)} found, or {@code null} when nothing is bound.
   *
   * <p>One read returns every fact the decision needs — identity, capture instant, stored
   * response — because the decision and the data it was made from must be one observation: a
   * second lookup could race a commit and answer about a different instant than the first.
   */
  public BoundKey boundKey(MerchantId merchant, IdempotencyKey key) {
    Objects.requireNonNull(merchant, "merchant");
    Objects.requireNonNull(key, "idempotency key");
    Row row = keyBindings.get(new Scope(merchant, key));
    return row == null
        ? null
        : new BoundKey(row.binding(), row.capturedAtMillis(), row.response());
  }

  /** The bindings in log order — the order their records appear — for the canonical state. */
  public List<KeyBinding> bindings() {
    List<KeyBinding> snapshot = new ArrayList<>(keyBindings.size());
    for (Row row : keyBindings.values()) {
      snapshot.add(row.binding());
    }
    return List.copyOf(snapshot);
  }

  /** How many keys are bound, restored rows included. */
  public int bindingCount() {
    return keyBindings.size();
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
    applyDeltas(checkedDeltas);
  }

  /**
   * The balance half of an apply, shared by the plain and the keyed posting: one checked
   * addition per touched account, in the account order the candidate named them in.
   */
  private void applyDeltas(Map<AccountId, Long> checkedDeltas) {
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
   * Recomputes every balance from the base plus the event log, ignoring the index. Slow, obvious,
   * and the reference the index is judged against.
   *
   * <p>The base is the only difference between this and a fold of the journal: an account restored
   * from a checkpoint starts at the balance the checkpoint recorded, and the events that moved it
   * before the checkpoint are not in this ledger's journal to be folded. For a ledger that was
   * never restored, the base is all zeros and this is exactly the fold ADR 0002 described.
   */
  public Map<AccountId, Money> foldBalances() {
    Map<AccountId, BigInteger> folded = new LinkedHashMap<>();
    for (Map.Entry<AccountId, Long> row : baseBalances.entrySet()) {
      folded.put(row.getKey(), BigInteger.valueOf(row.getValue()));
    }
    for (JournalEvent event : events) {
      if (event instanceof JournalEvent.AccountOpened opened) {
        folded.putIfAbsent(opened.account().id(), BigInteger.ZERO);
      } else if (event instanceof JournalEvent.Posted posted) {
        foldEntries(folded, posted.transaction());
      } else if (event instanceof JournalEvent.PostedIdempotently keyed) {
        foldEntries(folded, keyed.transaction());
      }
    }
    Map<AccountId, Money> result = new LinkedHashMap<>();
    for (Map.Entry<AccountId, BigInteger> row : folded.entrySet()) {
      result.put(row.getKey(), exact(row.getValue(), "the folded balance of " + row.getKey()));
    }
    return Collections.unmodifiableMap(result);
  }

  /** Folds one transaction's entries into a running {@code BigInteger} total, debit-positive. */
  private static void foldEntries(Map<AccountId, BigInteger> folded, Transaction transaction) {
    for (Entry entry : transaction.entries()) {
      BigInteger signed = BigInteger.valueOf(entry.amount().minorUnits());
      if (entry.side() == Side.CREDIT) {
        signed = signed.negate();
      }
      folded.merge(entry.account(), signed, BigInteger::add);
    }
  }

  /** Every event so far, in order. Snapshot; mutating it throws. */
  public List<JournalEvent> events() {
    return List.copyOf(events);
  }

  /**
   * The posted transactions only, in order — keyed postings included, because a keyed posting is
   * a posting: the money moved the same way, and a caller summing the journal must not have to
   * know which kind of event moved it.
   */
  public List<Transaction> transactions() {
    List<Transaction> posted = new ArrayList<>();
    for (JournalEvent event : events) {
      if (event instanceof JournalEvent.Posted append) {
        posted.add(append.transaction());
      } else if (event instanceof JournalEvent.PostedIdempotently keyed) {
        posted.add(keyed.transaction());
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
    // Fingerprint rather than KeyBinding: an event does not know the LSN of its own record, so
    // the identity the index is held to is (merchant, key, fingerprint) — the position is the
    // log owner's to have supplied, and the restore path checks it separately.
    Map<Scope, RequestFingerprint> boundByLog = new LinkedHashMap<>();
    int sequence = 0;
    for (JournalEvent event : events) {
      if (event instanceof JournalEvent.AccountOpened open) {
        AccountId id = open.account().id();
        if (openedByLog.putIfAbsent(id, open.account()) != null) {
          throw new IllegalStateException("event " + sequence + " opens " + id + " twice");
        }
      } else if (event instanceof JournalEvent.Posted posted) {
        auditTransaction(sequence, posted.transaction());
      } else if (event instanceof JournalEvent.PostedIdempotently keyed) {
        auditTransaction(sequence, keyed.transaction());
        Scope scope = new Scope(keyed.merchant(), keyed.key());
        if (boundByLog.putIfAbsent(scope, keyed.fingerprint()) != null) {
          throw new IllegalStateException(
              "event " + sequence + " binds " + keyed.key() + " twice in the scope of "
                  + keyed.merchant());
        }
      }
      sequence++;
    }

    // The account index is the restored base plus what the log opened, and nothing else: an account
    // the index holds that neither opened and never restored was invented, and an account the log
    // opens that the index does not hold was lost. The last clause is ADR 0002's check; the first
    // two are what a restore has to be held to, and between them they say the index is partitioned
    // by two disjoint sets rather than merely summed by two numbers.
    if (accounts.size() != openedByLog.size() + restored.size()) {
      throw new IllegalStateException(
          "account index disagrees with the log: the index holds "
              + accounts.size()
              + " accounts, the log opens "
              + openedByLog.size()
              + " and "
              + restored.size()
              + " were restored from a checkpoint");
    }
    if (!baseBalances.keySet().equals(accounts.keySet())) {
      throw new IllegalStateException(
          "account index disagrees with the balance base: index="
              + accounts.keySet()
              + " base="
              + baseBalances.keySet());
    }
    for (Map.Entry<AccountId, Account> row : openedByLog.entrySet()) {
      if (restored.contains(row.getKey())) {
        throw new IllegalStateException(
            "account index disagrees with the log: " + row.getKey()
                + " came from a checkpoint and is opened again by the log");
      }
      Account indexed = accounts.get(row.getKey());
      if (!row.getValue().equals(indexed)) {
        throw new IllegalStateException(
            "account index disagrees with the log on " + row.getKey() + ": index=" + indexed
                + " log=" + row.getValue());
      }
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

    // The binding index is the restored base plus what the log bound, and nothing else — the
    // same partition the account index is held to, with the same three failures: a row nobody
    // wrote was invented, a binding the log made was lost, and a binding that came from a
    // checkpoint is re-made by the log. The identity compared is (merchant, key, fingerprint):
    // a row that names a different body for its key than the event does is exactly the bug the
    // 409 exists to catch, found by the auditor instead of by a client.
    if (keyBindings.size() != boundByLog.size() + restoredBindings.size()) {
      throw new IllegalStateException(
          "binding index disagrees with the log: the index holds "
              + keyBindings.size()
              + " bindings, the log binds "
              + boundByLog.size()
              + " and "
              + restoredBindings.size()
              + " were restored from a checkpoint");
    }
    for (Map.Entry<Scope, RequestFingerprint> bound : boundByLog.entrySet()) {
      if (restoredBindings.contains(bound.getKey())) {
        throw new IllegalStateException(
            "binding index disagrees with the log: "
                + bound.getKey().key()
                + " came from a checkpoint and is bound again by the log");
      }
      Row row = keyBindings.get(bound.getKey());
      if (!row.binding().fingerprint().equals(bound.getValue())) {
        throw new IllegalStateException(
            "binding index disagrees with the log on "
                + bound.getKey().key()
                + ": index="
                + row.binding().fingerprint()
                + " log="
                + bound.getValue());
      }
    }
    for (Scope fromCheckpoint : restoredBindings) {
      if (boundByLog.containsKey(fromCheckpoint)) {
        throw new IllegalStateException(
            "binding index disagrees with the log: "
                + fromCheckpoint.key()
                + " is both restored and bound by this journal");
      }
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
