# ADR 0002 — The domain model: n-entry transactions, three account kinds, integer minor units, one event log

- **Status:** Accepted — 2026-09-14
- **Ticket:** [What is a transaction? The double-entry domain model, proven in memory](https://github.com/sehaanurrahaman-creator/ledger-x/issues/4),
  a child of the [Wayfinder map](https://github.com/sehaanurrahaman-creator/ledger-x/issues/1), unblocked by
  [ADR 0001](./0001-substrate.md)
- **Decides:** what accounts, entries and transactions *are*; whether balances may go negative; the transaction
  grammar; the money representation; what atomic rejection means; the shape of the in-memory journal; and which
  property-testing toolkit proves all of it.
- **Does not decide:** the WAL record format, durable record ids, the fsync policy and torn-tail rules (ADR 0001's
  successors, ticket *What does a durable write look like?*); checkpoints and the durable state-hash scheme (*How is
  state proven identical after a crash?*); idempotency keys, expiry and merchant scoping (*What are ledger-x's
  idempotency semantics?*); locking, per-account ordering and every question of concurrency (*How do a payout and a
  refund to the same account never interleave?*); the payout state machine; reconciliation; and whether ledger-x is a
  library or a service. Each of those reads a decision made here; none of them is made here.

## 1. Decision

| Question the ticket left open | Decision |
| --- | --- |
| Money representation | **Signed integer minor units in a `long`.** One currency, minor unit = 1/100 of the major unit, exponent a constant rather than per-account metadata. Every operation overflow-checked. |
| Account kinds | **Three — `ASSET`, `LIABILITY`, `EQUITY`** — each with a normal side. Kind is **metadata**: no posting rule reads it and no invariant depends on it. |
| May balances go negative? | **Yes, on every kind.** The ledger records; it does not judge. A funds check is a policy for a layer above, and an unnatural balance is an anomaly to reconcile, not a reason to refuse an append. |
| Transaction grammar | **n entries, not two.** At least two entries, Σ debits = Σ credits, every account already opened, no overflow of a total or of a resulting balance. The two-account transfer is a factory method over that grammar. |
| Atomic rejection | **Validate, then apply the deltas validation checked.** A refused transaction changes nothing — no event, no balance, no account — and says which rule it broke, in a fixed order. |
| Journal shape | **One sealed event log with two event kinds** (`AccountOpened`, `Posted`), append-only, immutable, sequence number = position. |
| Property-testing toolkit | **Hand-rolled, no dependency manager.** ADR 0001's trigger for one is JMH or JUnit and this ticket needs neither. Seeded SplitMix64, generators, delta-debugging shrinks, and an independent `BigInteger` model as the oracle. |

Everything below is the reasoning, including where the alternatives were stronger.

## 2. Money: integer minor units in a `long`, one currency, overflow-checked

The map already ruled out the interesting half of this — *Out of scope* says "Multi-currency / FX — one currency,
integer minor units" — so the decision left is what "integer minor units" means in code, and what happens at the
edges of the type.

`Money` is a record over one `long`. `Money.MINOR_UNIT_EXPONENT` is 2, a constant: with one currency the exponent is
a property of the system, not of a row, and putting it in the type instead of in per-account metadata removes a field
that could disagree with itself. Naming the currency — and therefore deciding whether the exponent is 2, 0 or 3 — is
a record-format question and stays with the WAL ticket. Modern Treasury, whose ledger API is the closest public
analogue to what this project is building, carries `currency` and `currency_exponent` inside every balance object
([`ledger_account` object](https://docs.moderntreasury.com/platform/reference/ledger-account-object)); ledger-x does
not, and the cost of that is recorded under *Consequences accepted*.

**Every arithmetic operation is overflow-checked.** `Math`'s own class javadoc on jdk21u states the contract this
relies on: "In cases where the size is `int` or `long` and overflow errors need to be detected, the methods whose
names end with `Exact` throw an `ArithmeticException` when the results overflow"
([`java/lang/Math.java`, openjdk/jdk21u](https://github.com/openjdk/jdk21u/blob/master/src/java.base/share/classes/java/lang/Math.java)),
and `addExact(long, long)` is documented as "Returns the sum of its arguments, throwing an exception if the result
overflows a long". So `Money.plus`, `minus`, `negated` and `times` are `addExact`, `subtractExact`, `negateExact` and
`multiplyExact`, and an amount that does not fit becomes a **rejection at the ledger**, never a wrapped balance. This
is not a nicety: a wrapped balance breaks Σ balances = 0 *silently*, which is the one failure mode this project
exists to make impossible. `negateExact` matters for a specific edge — its javadoc notes "The overflow only occurs for
the minimum value" — so `-Long.MIN_VALUE` throws instead of returning itself, and the property suite drives balances
to exactly that neighbourhood on purpose.

| Option | Verdict | One-line reason |
| --- | --- | --- |
| **`long` minor units, overflow-checked** | **Chosen** | Exact, allocation-free on the hot path, and one field to serialize; overflow becomes a rejection instead of a lie. |
| `BigDecimal` | Rejected | Exact too, but an object per amount with a scale that has to agree with the exponent, and an allocation per arithmetic step — a cost the GC/allocation-budget ticket would inherit on the WAL path for no gain, since there is one currency and one scale. |
| `double` | Rejected | `BigDecimal`'s javadoc puts the reason better than any argument here could: "One might assume that writing `new BigDecimal(0.1)` in Java creates a `BigDecimal` which is exactly equal to 0.1 … but it is actually equal to 0.1000000000000000055511151231257827021181583404541015625" ([`java/math/BigDecimal.java`, openjdk/jdk21u](https://github.com/openjdk/jdk21u/blob/master/src/java.base/share/classes/java/math/BigDecimal.java)). A rounding error in a ledger is not a rounding error, it is a false statement about somebody's money. |
| Two `int`s (major, minor) | Rejected | Two fields that must agree, a carry rule to get right on every operation, and no additional range. |
| `long` plus a per-entry currency code | Rejected here, not forever | One currency, so the field would be a constant in every record — but see *Consequences accepted*: adding one later is a record-format change. |

**What the range buys and costs.** A signed `long` of minor units spans ±92,233,720,368,547,758.07 in major units.
That is more than any single-merchant ledger will hold and less than a global settlement total, so the honest
statement is: enough for the prototype and for the metrics ticket's workload, not enough to be a platform-wide
balance forever. The model's answer to running out is a rejection with reason `OVERFLOWING_TOTALS` or
`OVERFLOWING_BALANCE` — loud, deterministic and tested — rather than a wraparound. Widening to 128 bits is a
record-format decision and belongs to the WAL ticket.

**The decision is enforced mechanically, not in review.** `scripts/lint.sh` rule 7 fails the build on a `float` or
`double` anywhere in `src/main/java/dev/ledgerx/domain/`, matching code shapes (declarations, casts, conversions,
`f`/`d` literals) and skipping comment lines so that the javadoc arguing *about* doubles does not trip it. Because a
rule that never fires is indistinguishable from a rule that is never violated, the lint runs both ADR patterns
against lines that must match and lines that must not, every invocation — and it was verified both ways while writing
this ADR: injecting `public double asDouble() { return (double) minorUnits / 100.0d; }` into `Money.java` fails the
lint naming lines 78 and 79, and removing it passes.

## 3. Entries: the side is data, and the amount is always positive

An `Entry` is `(AccountId, Side, Money)` with `Side ∈ {DEBIT, CREDIT}` and a **strictly positive** amount. An
account's balance is Σ debits − Σ credits on one signed axis, debit-positive, so Σ over all accounts is 0 exactly
when every transaction balances.

The alternative — one signed amount per entry, no `Side` — is simpler and was seriously considered. It loses on three
counts:

1. **It merges two checks into one.** With signed amounts, "this transaction's debits equal its credits" and "Σ
   balances = 0" are the same arithmetic statement, so a bug that breaks one breaks both and the property suite
   reports one failure where it could report two independent ones. With a side and a positive amount they are
   different statements about different objects: one is a property of a transaction, the other of the whole ledger.
2. **Zero becomes ambiguous.** A signed entry of 0 is neither a debit nor a credit, moves nothing, and is
   indistinguishable from no entry in every total — so "balanced" can be achieved by padding. `Entry`'s constructor
   refuses it, which is strictly stronger than a validator a future caller might forget to run.
3. **Reporting needs the branch anyway.** "How much was ever debited to this account" is a filter over sides, and
   with signed amounts it is a filter plus an absolute value plus a sign convention that has to be documented twice.

`Entry.reversal()` exists because reversal is the only way anything is ever undone here. Modern Treasury states the
same rule for posted transactions: "In order to undo the effect of a posted Ledger Transaction, you will need to
write a second reversing Ledger Transaction"
([Ledger Transactions Overview](https://docs.moderntreasury.com/ledgers/docs/ledger-transactions-overview)).

## 4. Accounts: three kinds, and none of them constrains anything

`AccountKind` is `ASSET`, `LIABILITY`, `EQUITY`, each carrying the side that normally increases it — debit for an
asset, credit for a liability and for equity. That is the standard normal-balance table, and it matches what a
production ledger API exposes: Modern Treasury's `ledger_account` has a `normal_balance` of `credit` or `debit` with
the explanation "If an account is credit normal, then a 'negative' balance would be one where the debit balance
exceeds the credit balance. For example, liabilities accounts are credit normal whereas assets accounts are debit
normal" ([`ledger_account` object](https://docs.moderntreasury.com/platform/reference/ledger-account-object)).

| Option | Verdict | One-line reason |
| --- | --- | --- |
| **Three balance-sheet kinds, kind as metadata** | **Chosen** | Enough to say which direction is natural for reporting and reconciliation; nothing in the invariants depends on it, so adding a kind later is additive. |
| One kind, no typing | Rejected | Σ balances = 0 survives it, but "this asset balance is negative" stops being expressible — and that is exactly the anomaly the reconciliation ticket exists to find. |
| Five kinds (add `REVENUE`, `EXPENSE`) | Rejected for now | Revenue and expense are equity sub-kinds whose entire purpose is to be *closed* into equity at the end of a period, and no downstream ticket asks for a period. Adding them would import an accounting-calendar question this project has no answer to. |
| Kind as a posting constraint (an asset may not go negative, a liability may not be debited beyond its balance, …) | **Rejected, deliberately** | It would make the append-only log a policy engine, and see the next section: it would also break replay. |

**The one sentence that matters about kinds: no posting rule reads them.** Kind cannot cause a rejection, cannot
change a balance and cannot affect Σ = 0. It answers "which way does this balance naturally run", for the demo, for
reconciliation and for a human reading a statement. That is why the property suite can randomize kinds across a
campaign without changing a single expected outcome — and why adding `REVENUE` in a year is a one-line change rather
than a re-derivation of the invariants.

`AccountId` is a caller-chosen string, validated once. ledger-x does not mint ids: an id refers to something outside
the ledger (a merchant's payable, a settlement account at a correspondent), and an id the ledger invented would have
to be mapped back anyway. What it does own is the **alphabet** — letters, digits, `.`, `_`, `-`, at most 64
characters — and the reason is encoding rather than taste: an id ends up inside a WAL record and inside any canonical
form used to hash state, and an alphabet that excludes whitespace, control characters and serialization punctuation
can be written to bytes without an escaping scheme. Escaping schemes are where two implementations of "the same
string" start to disagree, and a replay that disagrees by one byte is not a replay. The property suite asserts the
other half of that claim: all 20 delimiters its canonical rendering uses are characters `AccountId` refuses.

`AccountId` deliberately carries **no merchant or tenant scope**, and that is fog rather than an oversight — see
*Fog graduated*.

## 5. Negativity: balances may go negative on every kind

The ticket asks directly whether balances may go negative. **They may, on any account of any kind, and the ledger
accepts the posting.** Three reasons, in the order they decided it:

1. **Σ balances = 0 is orthogonal to sign.** The invariant constrains the *sum*, never an individual balance. Nothing
   about double-entry requires a non-negative asset; what it requires is that every movement has a counterparty.
2. **A funds check needs facts this layer does not have.** "May this payout go out" depends on ordering against a
   concurrent refund, on what the bank has already been asked to do, and on retry semantics after a crash — the
   concurrency ticket's and the payout ticket's subject matter. Putting a balance test inside the append path would
   make the ledger's acceptance depend on state that a later ticket has to define, and would put the check in the one
   place that cannot be retried.
3. **Refusing an entry breaks replay.** The log must be able to re-derive whatever happened. A validation rule that
   depends on *current* balances is fine at append time and poison at replay time, unless replay is guaranteed to see
   the same balances in the same order — which is a concurrency-and-recovery claim nobody has made yet. An
   append-only log whose acceptance depends on mutable state is a log that may not replay.

So: an overdrawn asset is **drift to detect, not a posting to refuse**. `AccountKind.isNaturalBalance(Money)` exists
to say which balances are anomalies, `make demo` prints one, and the reconciliation ticket owns what happens when a
real bank disagrees with it.

**Where the funds check does go**, stated so it is not lost: above the ledger, in the payout protocol, as a
pre-condition of *intent* rather than of *append*. The shape that suggests itself — a policy object consulted before
building a transaction, leaving the ledger free to record whatever it is handed — is deliberately **not** built here,
because its correct behaviour under concurrency is exactly what the payout and concurrency tickets have to decide.

**The honest divergence.** Production ledger APIs do offer balance-aware behaviour: Modern Treasury exposes
`pending_balance`, `posted_balance` and `available_balance` per account
([`ledger_account` object](https://docs.moderntreasury.com/platform/reference/ledger-account-object)), and
`available_balance` is what a caller checks before spending. ledger-x has one balance per account and no availability
notion, because a pending/available split presupposes a two-phase posting lifecycle that the immutability rule in the
charter rules out: entries are append-only, so there is no "pending entry" that later becomes "posted". That
divergence is real, it is a consequence of the charter rather than an oversight, and it belongs in `STRIPE-DIFFS.md`
alongside the idempotency divergences the Stripe research ticket already recorded.

## 6. The transaction grammar: n entries

A transaction is a list of entries that must all be appended or none. The rules, in the order the ledger evaluates
them:

1. at least **two** entries (`Transaction.MINIMUM_ENTRIES`);
2. every account it names **already exists**;
3. its debit total and credit total each **fit in a `long`**;
4. **Σ debits = Σ credits**;
5. every touched account's **resulting balance fits in a `long`**.

| Question | Decision | Why |
| --- | --- | --- |
| Is the two-account transfer the only shape? | **No.** n entries, n ≥ 2 | A split settlement, a payout funded from two accounts and a fee-taking payment are all one transaction each; refusing them would push callers into multiple transactions that are no longer atomic — the exact property the grammar exists to provide. Modern Treasury's transactions "allow you to specify groups of Entries that either must all succeed or all fail. The total balance of all Entries on a single Ledger Transaction must have equal debits and credits" ([Ledger Transactions Overview](https://docs.moderntreasury.com/ledgers/docs/ledger-transactions-overview)). |
| Is `transfer(from, to, amount)` still there? | Yes, as a **factory** over the grammar | `Transaction.transfer` and `InMemoryLedger.transfer` build a two-entry transaction: debit the destination, credit the source. Convenience, not a special case. |
| May one account appear twice in a transaction? | **Yes** | Two debits to the same account against one credit is a normal split. The ledger sums per-account nets; the campaign posts thousands of them (`same-account-twice` in the coverage line). |
| May a transaction net to nothing? | **Yes** | Debiting and crediting the same account for the same amount is legal, pointless, and harmless: Σ = 0 still holds. The ledger records, it does not judge — the same principle as the negativity rule. |
| Is there a maximum entry count? | **Not in the model** | A ceiling is a record-size question: it belongs with the WAL record format, which owns how many entries fit in one record and what happens when a transaction does not. |
| Does a transaction carry an **id**? | **No** | Its sequence number is its position in the event log. A stored id would be a second source of truth that could disagree with the first, and the log is the truth. When records need durable ids the WAL ticket will derive them from log position. |
| Does it carry a **timestamp**? | **No** | Nothing in the model reads a clock. A ledger-assigned wall-clock time would make replay non-deterministic — the same log would produce different state on a second run — and byte-identical replay is the destination. If time is wanted it arrives as caller-supplied data inside the record, which is a record-format decision. |
| Does it carry an **idempotency key**? | **No** | Key scoping, expiry, GC reuse and the 409-vs-200 question are the idempotency ticket's, and a key baked into the domain type now would pre-empt them. |
| Is a transaction mutable before posting? | **No** | `Transaction` is a record over `List.copyOf` of its entries. A candidate is immutable too, so the object that is validated is bit-for-bit the object that is appended — which is what makes `post` return the same instance and the property suite able to assert it. |

A transaction is constructed **without** grammar validation, and that is deliberate: half the grammar is a fact about
the ledger (which accounts exist) rather than about the entries, and rejection is a path the property suite has to be
able to walk with a reason code. `Transaction.isBalanced()` is a pure predicate the ledger and the tests both use;
`Entry`'s positivity rule *is* enforced at construction, because it needs no ledger state and an impossible object is
better than a validated one.

**This decision answers the domain half of standing design-review question 4** ("How do you make a two-account
transfer atomic without 2PC across shards?"). At the model level there is nothing to coordinate: a transfer is one
transaction with two entries, one unit of commit. What is left for the cross-shard ticket is a *storage* question —
how one record stays atomic when the accounts it names live on different shards — not a protocol question about two
accounts agreeing. `make demo` prints exactly that transaction, and says so.

## 7. Atomic rejection: validate, then apply what validation checked

`InMemoryLedger.post` is two steps. `validate` runs every rule against immutable state and returns the per-account
deltas it range-checked; `apply` appends the event and adds exactly those deltas, one addition per touched account.
**Atomicity here is not rollback — it is the absence of a fallible step after the last check.** Once the accounts are
known, the totals fit and every resulting balance has been range-checked, the remaining arithmetic cannot throw, so
there is no halfway state to unwind and no compensation path to get wrong.

**The property suite found a real bug in this on its first run**, and it is recorded here because the fix changed the
shape of the code. The first version validated the *net* effect per account but applied entries *one at a time*. A
transaction that credited an account 232,390, debited it 282,945 and credited it 50,555 nets to zero — validation
passed — but the first entry alone drove an already near-`Long.MIN_VALUE` balance out of range, and it threw *after*
the event had been appended: a partial mutation, which is the one thing the atomic-rejection rule exists to forbid.
The shrunk case was four operations long. The fix is structural rather than a patch: `validate` now returns the
deltas and `apply` consumes them, so the two cannot disagree about what was checked. Applying nets rather than entries
also makes acceptance independent of the order entries happen to be listed in, which is what a caller would assume
anyway.

**The rejection reason order is part of the contract.** A candidate can break two rules at once — unbalanced *and*
naming an account that was never opened — and which reason comes back must not depend on hash iteration order or on
which check was written first, because the idempotency ticket will map reasons onto HTTP statuses and a
nondeterministic reason becomes a nondeterministic response body, which then fails to match the stored one on replay.
`RejectionReason`'s declaration order *is* the evaluation order — grammar, reference, arithmetic, money rule,
representation limit — with arithmetic before the money rule because totals that wrapped would compare equal and pass
it. The suite pins the order two ways: it asserts the enum's declared order equals the order documented here, and it
posts six candidates that each break two rules and asserts the earlier reason wins.

**Rejection throws an unchecked `RejectedTransactionException` carrying its reason and the refused candidate.** A
result type was considered and rejected: it puts the failure case in the type of the success path, where most callers
have nothing to say about it, and every layer above already has an error path to map onto (`IOException` at the WAL, a
4xx at the API). Unchecked because a rejection is not a condition every posting site can meaningfully be forced to
handle; the exception's `attempted()` returns the candidate so a rejection can be logged or answered with the thing
that caused it.

## 8. One event log, two event kinds

The log holds `AccountOpened` and `Posted` in **one** `sealed interface JournalEvent`. Account openings move no money
and could have lived in a second list beside the journal — but then there would be two sequence spaces, and replay
would need a merge rule to interleave them. One list has one sequence space, and the sequence number of an event is
its position, which is what a write-ahead log's record offset gives for free.

Because accounts are immutable — no rename, no re-kind, no close-that-forgets — replaying every `AccountOpened`
before every `Posted` is equivalent to replaying the log in order, and the suite checks the stronger statement
directly: fold the events into a fresh ledger and the state digest is identical. Sealed, because the set of things
that can happen to a ledger is small, closed and about to be encoded into bytes; a closed hierarchy lets the compiler
check a fold for exhaustiveness instead of an `else throw` that only fires in production.

**Immutability is enforced at three different levels**, because "entries never mutate" is the charter's claim and one
level would be an assertion rather than a proof:

1. *By type* — every domain type is a record or an enum, every declared field is final (the suite reflects over all
   13 types and 31 fields to confirm), no public method is named `set*`, and `Transaction`'s constructor takes
   `List.copyOf`, so the caller's list is not merely hidden but no longer referenced: clearing it after posting cannot
   reach the journal, and the suite does exactly that and checks.
2. *By view* — `events()`, `transactions()`, `accounts()`, `balances()` and `foldBalances()` all hand out copies that
   throw on mutation, asserted by attempting it.
3. *By observation* — every event is rendered to a canonical string the first time it is seen and re-rendered after
   every operation, so a field changed in place shows up as a different string at a position that already has one.
   Comparing an entry to itself cannot detect this: if a field changed, both sides of the comparison changed with it.

Two representations of the same truth is a fourth, related choice: balances live in an index maintained at append time,
`foldBalances()` recomputes them from the log by brute force, and `audit()` insists the two agree, that Σ balances is
exactly zero, and that every committed transaction still has at least two entries, names only opened accounts, holds
only positive amounts and still balances. The fold and the sum use `BigInteger` — not because the model needs it, but
because an auditor must not overflow while auditing: a set of balances that sums to zero can still leave `long` range
on the way (two accounts at `Long.MAX_VALUE` and two at its negation), and an auditor that throws `ArithmeticException`
reports a crash where it should report a discrepancy.

`audit()` is proved non-vacuous by corrupting the ledger on purpose through reflection — a balance index off by one, a
phantom unbalanced event, an event naming an account that was never opened, a vanished opening — and asserting each
one is caught. A test that cannot break the thing it tests cannot show the test works. (Those reflections go through
`Method.invoke` rather than a cast to the field's real type, because an unchecked cast needs
`@SuppressWarnings("unchecked")`, which javac honours and the ECJ fallback compiler does not once `-err:+unchecked`
promotes the warning; see *Property-based testing*.)

**The ledger is not thread-safe, and that is a decision rather than an omission.** Concurrency is another ticket's,
with a hard constraint this class must not quietly violate: per-account ordering without a global pessimistic lock. A
lock here would either be that global lock or a lie about isolation the concurrency ticket then has to un-find. Until
it decides, an `InMemoryLedger` is confined to the thread that created it and the property suite is single-threaded by
construction.

## 9. Property-based testing without a dependency manager

The ticket says "property-based tests (toolkit per ADR-0001)", and ADR 0001 says: zero third-party dependencies, with
an explicit trigger — "the first ticket that needs JMH or JUnit (the metrics ticket) introduces a dependency-managing
build". This ticket needs property-based tests, which in Java usually means jqwik, which runs as a JUnit Platform
engine. **The trigger did not fire, and the harness is hand-rolled**, for two reasons, one of principle and one
measured:

- ADR 0001 named JMH or JUnit as the trigger and wrote it down "so the shape of the build is a decision somebody made
  rather than an accident of what was handy". Honouring a trigger means not firing it early: a build that resolves
  nothing cannot break on a registry outage, and this repository's whole argument is that its claims are reproducible.
- **It could not have been verified here anyway.** Measured 2026-09-14 from this sandbox: `repo1.maven.org` and
  `api.adoptium.net` both fail the TLS handshake (`curl: (35) SSL_ERROR_SYSCALL`), while `pypi.org` and
  `registry.npmjs.org` answer. A jqwik harness would have compiled and run in CI and nowhere else, which is the
  weakest possible position for the tests that are supposed to be the evidence.

What the harness is: ~350 lines across `dev.ledgerx.testing` (SplitMix64 `RandomSource`, list and number `Shrink`
steps, a `PropertyRunner` that shrinks and reports) plus the domain-side generators, oracle and applier. SplitMix64 is
implemented rather than taken from `java.util.Random` because a failure message that says "reproduce with seed 1234"
is only telling the truth if seed 1234 still produces that case after a JDK upgrade, and the JDK's generators are not
contractually frozen across releases. Bounded draws are modulo a 63-bit sample, so they carry a bias below
`bound / 2^63` — under 2⁻⁴³ for every bound used — and that is documented in the class rather than hidden behind a
hand-rolled Lemire multiply-shift, which is exactly where a subtle bug likes to live in the code that is supposed to
be obviously right.

| What jqwik would have given | What this has instead |
| --- | --- |
| 1,000 tries by default, configurable per property | 60 cases × 200 operations by default, `LEDGER_X_TRIALS` / `LEDGER_X_OPERATIONS` to scale, and `make campaign` for ~10× |
| *Integrated shrinking*, with `ShrinkingMode.BOUNDED` timing out after 10 s ([jqwik user guide](https://jqwik.net/docs/1.6.3/user-guide.html)) | Delta debugging over the operation list — halves, drop-one, then shrink one operation in place (amounts towards zero, slots towards 0, credit towards debit) — capped at 4,000 steps. Finds a **local** minimum, not the smallest case, and says so in the source. |
| Both the original and the shrunk sample in the failure report, plus the seed | The seed, the shrunk case, the failure and the top four stack frames. The original sample is not printed — the seed reproduces it. |
| An edge-case database mixed into generation (`edge-cases#mode = MIXIN`) | A hand-written **curriculum**: 19 fixed operations at the head of every case that walk every acceptance and rejection class. Randomness is good at finding what nobody thought of and bad at guaranteeing the obvious paths were taken. |
| A `.jqwik-database` of previous runs, so falsified properties run first | Nothing. A failing seed is printed and replayed by hand. |
| JUnit Platform reporting, IDE integration, `@Property` discovery | A `main` class per suite, like `SubstrateContract` before it, with a `PASS n/n` verdict line that CI publishes as a run annotation. |

**The oracle is the part that makes this more than a smoke test.** Random operations alone cannot tell you a ledger is
right, only that it is self-consistent: a ledger that computed every balance as zero would keep Σ balances = 0 forever
and pass every invariant in isolation. So `ModelLedger` is a second, deliberately slow and deliberately obvious ledger
— `BigInteger` balances, straight-line validation transcribed from the rules above, no shared helper and no shared
state — and after every operation the suite asserts the two agree on *which transactions are acceptable, why the
unacceptable ones are unacceptable, and what every balance is afterwards*. Where they disagree, the model is assumed
right until proven otherwise; that assumption paid off once already, in the partial-mutation bug above.

**And the campaign is guarded against being vacuous.** A generator that quietly stopped producing invalid operations
would leave every invariant trivially true, so a dedicated check asserts the mix walked every path: at least one
acceptance, one construction refusal, one rejection for each of the five reasons, a transaction of at least four
entries, a negative balance, a same-account-twice posting and a self-cancelling one. That check fails loudly with the
coverage counters in the message.

## 10. What the suite asserts, and what it measured

Nine checks, run by `./build.sh`, ~3 s at default size on this sandbox's fallback toolchain:

| Check | What it proves |
| --- | --- |
| random sequences never break the invariants | Σ balances = 0 after every operation; each committed transaction's debits equal its credits; every earlier event renders identically; a refused operation changed nothing; index == fold; verdicts match the model |
| the mix walks every path | the campaign above is not vacuous |
| replaying the log reproduces the state | the in-memory ancestor of byte-identical replay: fold a fresh ledger from the events, get an identical state digest |
| two-rule candidates are rejected for the earlier rule | the `RejectionReason` order is the evaluation order, and the enum's declaration order matches this document |
| overflow is rejected, nothing wraps | balances held at ±`Long.MAX_VALUE`, one more minor unit refused, Σ still exactly 0, no event appended |
| negative balances are legal on every kind | an overdrawn asset and a debit-positive liability are both accepted, flagged unnatural, and Σ still 0 |
| no mutation surface | 13 types, 31 fields, all final; no `set*`; views unmodifiable; a cleared caller list cannot reach the journal; 20 canonical delimiters refused by `AccountId` |
| `audit()` is not vacuous | a corrupted index, a phantom unbalanced event, an event naming an unopened account and a vanished opening are all detected |
| the harness reproduces a case from its seed | the same seed yields an equal operation list, a different seed does not |

Measured on 2026-09-14 with the fallback toolchain (Temurin 21.0.8 runtime, ECJ-compiled classes):

| Campaign | Operations in the random campaign | Result | Wall clock |
| --- | --- | --- | --- |
| `./build.sh` default — 60 cases × 200 operations | 12,000 | `PASS 9/9`, 5,838 accepted, 3,706 rejected, 844 refused at construction, widest transaction 12 entries; reasons `TOO_FEW_ENTRIES=492 UNKNOWN_ACCOUNT=785 OVERFLOWING_TOTALS=457 UNBALANCED=1137 OVERFLOWING_BALANCE=835` | ~3 s |
| `make campaign` — 150 × 400 | 60,000 | `PASS 9/9`, 30,344 accepted, 17,946 rejected, 4,153 refused at construction; reasons `2291 / 4082 / 2133 / 5843 / 3597` | ~22 s |
| 400 × 600, run by hand | 240,000 | `PASS 9/9`, 123,780 accepted, 69,886 rejected, 16,465 refused at construction; reasons `8707 / 16186 / 8297 / 23888 / 12808` | ~141 s |
| CI, `ubuntu-latest`, run `34890770933` | 60,000 | `PASS 9/9`, 307 cases — **counters identical to the local `make campaign`**, digit for digit | 23 s |

That last row is the determinism claim verified off-box rather than asserted: a different machine, a different JVM
build, a different compiler and a different `awk`, and the campaign produced the same 307 cases, the same 30,344
accepted, 17,946 rejected and 4,153 refused at construction, and the same five reason counts. That is what a
hand-written SplitMix64 buys over `java.util.Random`, and it is why a failing seed printed by CI can be replayed
locally with `LEDGER_X_SEED`.

Cost grows superlinearly with operations per case, because every operation re-audits and re-renders the whole journal:
that is the price of checking invariants after *every* step instead of at the end, and it is the right trade for a
prototype whose only job is to be correct. Those three timings are also the first real data points for the map's
CI-budget fog.

## 11. Verification

**CI, on this branch — the check of record.** Run [`34890770933`](https://github.com/sehaanurrahaman-creator/ledger-x/actions/runs/34890770933)
(`pull_request`, head `59947f5`, `ubuntu-latest`, Temurin 21): **success**, 37 s end to end — `Build and test` 10 s,
`Demo` 3 s, `Extended property campaign` 23 s. The annotations the build publishes, read back through the API:

- `PASS 5/5 substrate checks`, `substrate contract measured platformThreads=10 platform threads`
- `PASS 9/9 property checks — 127 random cases` (the default campaign)
- `PASS 9/9 property checks — 307 random cases, 30344 accepted, 17946 rejected, 4153 refused at construction`
  (`make campaign`, 60,000 operations)

Two earlier runs on this branch failed, and both failures are recorded here rather than quietly fixed, because each
one is a divergence between the toolchain this repository can verify with locally and the one that decides:

1. **Run `34887589236` — lint, 11 s.** `selftest: the floating-point rule matched 0 of the 7 violations it must
   match`. POSIX escape-processes the value of an `awk -v` assignment, so under **gawk** `\(` arrived as `(` and
   `\.` as `.`: the rule's regexes stopped being the regexes that were written and the rule stopped matching
   anything. **mawk** and **busybox awk** pass `-v` through untouched, so every local run was green and only CI was
   red — reproduced here rather than guessed at, by handing the same fixtures the pattern as gawk's `-v` delivers it:
   it does not even compile (`Value(` loses its escape, the paren goes unbalanced), where through `ENVIRON` it matches
   7 of 7. Patterns now travel by environment variable, which is literal on all three implementations, and the
   selftest gained a tripwire written to *discriminate*: its fixture line has no parentheses, so an intact
   `\(double\)` matches nothing while an escape-stripped `(double)` matches the bare word and names `-v` in the
   failure. The selftest is what made this a red build instead of a lint that silently checked nothing.
2. **Run `34889657513` — the same failure**, which is what produced the change that made it readable: a failing step
   now publishes its own first or last twelve lines as `::error` check annotations, because the runner's log lives
   behind a blob URL (`results-receiver.actions.githubusercontent.com`) that is not reachable from every environment,
   and annotations are the one CI channel readable back through the REST API. Without that change this failure was
   undiagnosable from the sandbox; with it, the cause arrived as ten lines of JSON.

Locally, the same commands, with the fallback toolchain described below:

- `./build.sh` end to end: lint over 36 files including the two self-tested ADR rules, 13 main and 11 test sources
  compiled, `PASS 5/5 substrate checks` (`platformThreads=8`), `PASS 9/9 property checks`.
- `make demo`: nine events, Σ balances 0.00, the escrow account at −26.55 flagged `UNNATURAL for ASSET`, two refusals
  proved to have changed nothing, `demo ok`.
- `make campaign`: `PASS 9/9` at 60,000 operations, counters identical to CI's.
- lint rule 7, both directions: appending `public double asDouble()` to `Money.java` fails the lint naming line 115 —
  the one line it was injected at — and removing it passes; re-verified after the `-v` fix, under mawk and under
  busybox awk.

**The local compiler is still not the check of record, and CI has now said so in both directions.** No JDK is
installable in this sandbox, so local runs go through `scripts/bootstrap-toolchain.sh`: a Temurin 21 *runtime* from a
digest-pinned PyPI wheel and the Eclipse JDT batch compiler from npm, with `java`/`javac` shims so that `./build.sh`
itself ran rather than a parallel script. `-Xlint:all -Werror` under javac was therefore unverified locally — and CI's
javac accepted all 24 sources with no warnings, so the ECJ-compiled tree was not hiding a javac-only lint. That is a
result, not an assumption: javac's `-Xlint:all` on JDK 21 includes checks ECJ does not make (`this-escape`, `serial`,
`lossy-conversions`), and none of them fired.

**One divergence between the two compilers, found and designed around.** ECJ ignores `@SuppressWarnings("unchecked")`
once `-err:+unchecked` promotes the warning, so a cast that javac accepts does not compile locally. Nothing in `src/`
depends on a suppression: the reflection in the audit check goes through `Method.invoke` instead. Anyone reaching for
an unchecked cast should know it will red-build locally and green-build in CI, which is the worst of both.

CI evidence for this branch is read back from the API by `scripts/ci-evidence.sh` rather than hand-counted, because
hand-counting went wrong once already (ADR 0001's closeout records it).

## 12. Consequences accepted

- **One currency, no code stored.** `Money` has no currency field, so a second currency is a record-format change and
  not a type change — and every balance object in the system would need one. Accepted because the map puts
  multi-currency out of scope; recorded so the cost is not discovered by whoever wants FX.
- **A `long` ceiling of ±92,233,720,368,547,758.07.** Loud rejection at the edge, widening is a WAL-format decision.
- **No funds check anywhere in the domain.** An overdraft is accepted and must be caught above the ledger or by
  reconciliation. Two tickets now depend on that being true rather than on it being forgotten.
- **No pending state.** Entries are immutable on append, so there is no half-posted transaction to cancel; a payout in
  flight needs an intent log outside the ledger. That is the payout ticket's, and it inherits the constraint rather
  than choosing it.
- **Rejection is an unchecked exception**, so a caller that ignores it gets a stack trace rather than a compile error.
- **~350 lines of test harness this repository owns**, with no edge-case database, no IDE integration and a shrinker
  that finds local minima. ADR 0001's dependency trigger stays armed for the metrics ticket.
- **The audit costs O(n) per call**, and the suite calls it after every operation, so a campaign costs O(n²). Fine for
  a prototype; the metrics ticket will not be able to leave it on the hot path.
- **Not thread-safe.** Confinement is documented, not enforced; the concurrency ticket owns the answer and inherits a
  class with no lock in it, which is the point.

## 13. What this changes for other tickets

- *What does a durable write look like?* — inherits two event kinds to encode, a transaction with no id and no
  timestamp (so both come from the record, if at all), an `AccountId` alphabet that needs no escaping, and an entry
  count with no ceiling — which means it owns the ceiling. `Money`'s fixed exponent is one byte at most, and the
  overflow rejections define what a record must never silently do.
- *How is state proven identical after a crash?* — replay is a fold over one event list, and the in-memory replay
  property here is its ancestor: same events in, same state digest out. The canonical rendering in
  `JournalDigest` is **test-only** and is not the durable state-hash scheme; what it does establish is that a
  canonical form is possible without escaping, because of the `AccountId` alphabet. The index-versus-fold auditor is
  the shape a post-recovery check can take.
- *What are ledger-x's idempotency semantics?* — `RejectionReason` is a closed enum in a fixed order, ready to map onto
  statuses; a rejection is deterministic, so a replayed request can be answered with the stored reason. What is
  **not** decided, and now visibly: whether `merchant_id` scopes accounts as well as keys (see *Fog graduated*).
- *How do a payout and a refund to the same account never interleave?* — inherits a ledger with no lock, no funds
  check and no thread-safety, i.e. a clean sheet with one constraint: whatever ordering it builds has to keep
  `validate`-then-`apply` atomic, and ADR 0001's `ReentrantLock`-not-`synchronized` rule still applies.
- *How does a payout become exactly-once across the bank boundary?* — the pending-state consequence lands here: the
  intent log has to live outside the append-only journal, because the journal cannot hold a transaction that might not
  happen.
- *What is drift, and how is it found?* — gets its vocabulary: `AccountKind.isNaturalBalance` names the anomaly, and
  the decision that the ledger accepts it is what makes the reconciliation job necessary rather than optional.
- *How fast is it, honestly?* — the benchmark workload fog is half-cleared: accounts have kinds, transactions have a
  width distribution the generator already produces (2–12 entries), amounts have a range, and the mix of
  accepted/refused operations is measurable. What still hangs on the concurrency ticket is hotness and thread shape.
- *What must TLC prove?* — `ModelLedger` is an executable reference implementation of the same rules the spec will
  state, in the same language, so the spec's invariants and the oracle's can be compared line by line rather than
  argued about.
- *How does a two-account transfer stay atomic across shards without 2PC?* — the domain half is answered: one
  transaction, one unit of commit, nothing to coordinate at model level. What is left is storage.
- *What did Stripe teach us, and what breaks at 10M merchants?* — two divergences to argue in `STRIPE-DIFFS.md`: no
  `available_balance`/pending-posted split, and no merchant scope on an account id.

## 14. Fog graduated

One new decision, and it is genuinely new rather than a restatement: **does an account belong to a merchant, and where
does that scope live?** `AccountId` is unscoped, the idempotency ticket's key is `(merchant_id, idempotency_key)`, and
those two facts cannot both stay as they are — either the scope becomes part of the account's identity, or it stays
outside and something has to enforce that a transaction never mixes merchants' accounts. That is a domain-model
consequence, so it graduates as a child of the map, blocked by this ticket and blocking the idempotency ticket. The
payload is in [`0002-domain-model.closeout.md`](./0002-domain-model.closeout.md) §5, because the GitHub credentials in
this session expired before it could be filed (see that file's §0).

Nothing else graduates; five findings feed existing tickets and are written into *What this changes* above: the
entry-count ceiling and the record encoding go to the WAL ticket, the canonicalization question to the checkpoint
ticket, the missing pending state to the payout ticket, the natural-balance vocabulary to the reconciliation ticket,
and the workload shape to the metrics ticket. Checked against the map's *Not yet specified*, the **benchmark workload**
item is now half-cleared exactly as ADR 0001 left the chaos fault set half-cleared — the domain half is settled, the
concurrency half is not — and the **public API surface** item is no more decided than before, though it now has a
domain to expose.
