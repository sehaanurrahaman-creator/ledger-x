# ADR 0005 — Idempotency: (merchant, key) → response, in one record, with a 409 where Stripe returns a replay

- **Status:** Accepted — 2026-09-17
- **Ticket:** [What are ledger-x's idempotency semantics?](https://github.com/sehaanurrahaman-creator/ledger-x/issues/7),
  a child of the [Wayfinder map](https://github.com/sehaanurrahaman-creator/ledger-x/issues/1), unblocked by
  [ADR 0003](./0003-wal-fsync.md), fed by the Stripe research in `docs/research/`
- **Decides:** the idempotency contract — `(merchant_id, idempotency_key)` as the scope; the request fingerprint;
  **409 Conflict on same-key-different-body** (a deliberate divergence from Stripe, §3); the single-record commit
  that makes a posting and its key's binding one atomic unit (§4); the response table and its three homes —
  memory, log, checkpoint (§5–§6); expiry as a 410 refusal, never a re-arming (§7, answering standing
  design-review question 3); clock skew as a one-directional risk, floored at zero (§7, answering standing
  question 5); and the evidence: a fifteen-check contract with a hundred-thread duplicate storm, a model-oracle
  property suite, and the boundary harness re-armed over a keyed history (§8).
- **Does not decide:** the HTTP surface that will carry the 409/410 as status codes (*Public API surface*, map
  fog this ticket sharpens but does not clear); key-issuing guidance for clients beyond what
  [docs/idempotency.md](../idempotency.md) states; per-merchant rate limits or quotas; the TLA⁺ exactly-once
  spec (the next ticket's, via Jaunt); WAL segment rotation; and the ops/sec cost of SHA-256 per keyed posting,
  which §8 prices as arithmetic rather than measures at scale.

## 0. What the ticket asked, and where it is answered

| Deliverable | Where |
| --- | --- |
| The exact contract of an idempotency key | §1 and [docs/idempotency.md](../idempotency.md) — scope, fingerprint, precedence, receipts |
| Same key, different body | §3 — 409 Conflict, at any age, before any other consideration; the 200-replay danger played out |
| Expiry and GC: can a GC'd key be reused, and what then | §7 — there is no GC; an expired key is refused (410) and *still* conflict-checked forever |
| Clock skew vs the expiry window | §7 — age is floored at zero: skew can extend replay or end it, never re-execute |
| Idempotency records commit atomically with their transactions | §4 — one event, one record, one append, one fsync, one ack |
| Replay returns the stored response, flagged as a replay | §1 and §4 — `IdempotentReceipt(replayed = true)` with the original LSN and capture instant |
| Duplicate-storm test: 100 concurrent retries → one posting, 99 replays | §8 — check 8 of the contract, on virtual threads |
| Property test: no sequence of retries/crashes/recoveries posts a key twice | §8 — `IdempotencyProperties`, a model oracle over 40 × 120 random operations |
| The 200-replay danger, concretely, in writing | §3 and [docs/idempotency.md](../idempotency.md) |
| Standing design-review questions 3 and 5 | §7 |

## 1. Decision

| Question | Decision |
| --- | --- |
| The identity of a request | `(merchant_id, idempotency_key)`. The key alone is nothing: two merchants may use the same key for different intents without ever meeting. Both are 1–64 and 1–255 bytes of 7-bit ASCII. |
| What "the same body" means | The entries as sent, in the order sent: `SHA-256('P' ‖ encodePosted(transaction))`. Not the transaction object, not a semantic equality — the bytes. A reordered entry list is a different body (§3). |
| The record | One event, `PostedIdempotently(merchant, key, fingerprint, capturedAtMillis, transaction)`, serialized as record type `0x04`. The binding and the posting are the same bytes (§4). |
| Precedence, the whole table | A bound key answers from the table before the candidate's merits are looked at: fingerprint differs → **409**; fingerprint matches and age < retention → **replay**; fingerprint matches and age ≥ retention → **410**. An unbound key's candidate is domain-validated first, and only a valid one binds. |
| The replay | The stored response — the same transaction — returned flagged `replayed = true`, quoting the original record's LSN and the original capture instant. A replay appends nothing and reads no clock but the comparison (§7). |
| Expiry | A state, not an event: `capturedAt + retention < now`. Default retention 24h. An expired key is refused forever, never re-executed, and — because the binding is permanent — still conflict-checked (§7). |
| The clock | Injected: `IdempotencyPolicy(Clock, Duration)`. The wall clock is read in exactly one file, `IdempotencyPolicy.java`, enforced by lint rule 8. Tests freeze it; production takes `SYSTEM` (UTC, 24h). |
| Where the table lives | Three homes, one truth: the live index in memory (`InMemoryLedger`), the records in the log (the table's history *is* the log), and the checkpoint's state section plus timing section (§5–§6). Recovery re-materializes from either, and the response is re-read from the log at the binding's LSN. |
| Concurrency | All keyed posts take the same `ReentrantLock` as every other commit (ADR 0001's pinning rule). One hundred concurrent retries of one key: one binds, ninety-nine replay, none re-post (§8). |
| Failure mode | A crash between append-ack and apply is repaired by the next open: the log holds the posting, the fold re-binds, and the caller that saw no ack retries into a replay. A log that binds one key twice refuses to open — `DOMAIN_REJECTED`, the exactly-once theorem's one way to be false (§4). |

## 2. The record, byte by byte

```
IDEMPOTENT_POSTING (0x04) payload
+--------+---------------------+--------+--------------------+------------------+--------+---------+
| u8 mLen| merchant (mLen)     | u8 kLen| key (kLen)         | i64 capturedAtMs | u8[32] | u16 n   | entries…
+--------+---------------------+--------+--------------------+------------------+--------+---------+
                                              fingerprint (SHA-256 of the body)     entry count
```

The entry list is byte-identical to a `POSTED` payload's entry list — the same encoder, the same
bytes — and that is not a coincidence to tolerate but a rule to keep: `fingerprintOf` digests
exactly those bytes, so a keyed and a plain encoding of one transaction agree, and "the same
body" means the same thing whichever way the request was filed. `IDEMPOTENT_FIXED_BYTES = 42`
(the two length bytes, the instant, the fingerprint); the count belongs to the entry list, and
counting it here as well would allocate two bytes the decoder rightly refuses to consume — a bug
this ADR's own contract check caught in the first build, which is the argument for having one.

The segment version moved from 1 to 2, because the version byte is a promise about the record
vocabulary (ADR 0003 §2): this build writes 2, still reads 1, and refuses at the header anything
above 2. A version-1 segment carrying a `0x04` frame is refused as `UNKNOWN_RECORD_TYPE`
vocabulary damage however cleanly it parses — an old build cannot have written it, so it is
damage wearing a header, and folding it would mean trusting a promise the header never made.
The WAL contract pins all three sentences with hand-crafted v1 and v3 headers over real frames.

## 3. The 409, and the 200-replay it refuses to be

Stripe's documented behaviour, and the research ticket's summary of it: a reused key with a
different body is a **400**, and a reused key within 24 hours is a **200 replay** of the stored
response. The replay half is right and this design keeps it. The different-body half is where
ledger-x diverges, deliberately:

> A key reused with a different body is **409 Conflict**, at any age, and the response names both
> fingerprints — the one the key is bound to, and the one the request carried.

Three reasons, in the order they matter:

1. **A 200-replay hides divergence.** Play it out. A payout client generates a key per payout
   intent. A bug — a cached constant, a copied line — reuses Tuesday's key for Wednesday's
   payout, which happens to be 250.01 rather than 250.00. Under a replay-instead-of-refuse rule
   the platform answers 200, returns Tuesday's stored response, and credits the merchant 250.00.
   Wednesday's ledger entry never exists. Nobody's invariant fires: the books balance, the
   response is "successful", the amount is off by a cent. The divergence surfaces at
   reconciliation at best, in a dispute at worst. A 409 turns the same event into a refused
   request the client can log, alert on, and fix — the failure is loud, immediate, and carries
   both fingerprints so the diff is one glance.

2. **400 says the wrong thing.** "Bad request" means *your request is malformed* — but the body
   of a same-key-different-body request is typically perfectly valid. What is wrong is the key:
   it names an intent that already exists and means something else. 409 names exactly that
   conflict, and this ledger's error type (`IdempotencyConflictException`) carries
   `merchant()`, `key()`, `boundTo()` and `presented()` so the future HTTP layer can map it
   without guessing.

3. **The refusal is unconditional, including after expiry.** Stripe prunes its table after 24
   hours, after which an old key is fresh again — the hole §7 closes by refusing instead of
   re-arming. A key that ever meant one intent never means another; the conflict check consults
   the permanent fingerprint, not the retention window.

One deliberate sharp edge, stated rather than left to be discovered: the fingerprint covers the
entries *as sent*, in order. `debit(A,5), credit(B,5)` and `credit(B,5), debit(A,5)` are the
same transaction to the domain (Σ balances is order-blind) but **different bodies** to the key,
so the second is a 409. This is the least surprising rule available — "the bytes you sent" —
and the property suite pins that both orderings post cleanly under two different keys.

## 4. One record, one commit

The ticket's non-negotiable: *idempotency records commit atomically with their transactions —
same WAL record, one append, one fsync, one ack.* The design is the sentence:

```
postIdempotent(merchant, key, candidate)
  ├─ binding exists? ── fingerprint differs ──→ 409 IdempotencyConflictException      (nothing appends)
  │                    └─ age < retention ────→ replay the stored response            (nothing appends)
  │                    └─ age ≥ retention ────→ 410 IdempotencyExpiredException      (nothing appends)
  └─ unbound: check(candidate) ── rejected ───→ RejectedTransactionException         (nothing appends, nothing binds)
       └─ valid: append(PostedIdempotently) → await ack → apply → afterCommit
                  one append · one fsync · one ack — the posting and the binding are the same bytes
```

There is no second write, no index-update-before-or-after, no window between "the money moved"
and "the key remembers". The binding *is* the record: `merchant`, `key`, `fingerprint`,
`capturedAtMillis`, and the transaction — so a crash at any instant leaves the log saying
exactly one of three things: the record is there (the posting happened, and recovery re-binds
it), the record is torn (ADR 0003's rules cut it, and the retry posts fresh), or the record was
never written (nothing happened). A fourth state — the posting without the binding, or the
binding without the posting — cannot be constructed, because there is nothing to construct it
from. The contract's eleventh check hand-crafts the one log that would falsify this — one key
bound in two records — and requires the open to refuse it as `DOMAIN_REJECTED`: a duplicate
binding is not data to fold, it is the exactly-once theorem's one way to be false, and the only
correct response to a proof's counterexample is to stop.

The response is stored *in the record itself* — the transaction is the response. Replay does
not need a side table of serialized HTTP bodies (there is no HTTP layer yet); it re-reads the
record the binding names, at the LSN the binding records, through the log's dense index, and
the round trip is one array access and one decode. When the binding came from a checkpoint, the
same lookup re-materializes the response from the log the checkpoint points at — which is why a
checkpoint's binding rows carry `responseLsn` and never the response bytes themselves (§5).

## 5. The table's three homes

| Home | Holds | Written when | Read when |
| --- | --- | --- | --- |
| `InMemoryLedger` | the live index: scope → binding (fingerprint, responseLsn), capture instant, the applied transaction | at commit, under the commit lock | on every keyed request, before anything else |
| The log | the records themselves — the table's entire history | one per keyed posting, by the commit path | on replay (response at `responseLsn`), and by recovery (fold from byte 0) |
| The checkpoint | binding rows in the hashed state section; one capture instant per binding in the timing section | per `CheckpointPolicy` cadence | on recovery, when the newest checkpoint stands against the log |

There is no fourth home — no side file, no separate table, no cache to invalidate. The in-memory
index is authoritative *for this process*, and both durable homes are derived from the same
records, so the three cannot disagree about anything except what a crash left unfinished, which
is precisely what recovery resolves.

## 6. Checkpoint format v2, and what the timing section is for

The state section (ADR 0004 §2) gains a binding table after the accounts, inside the hash:

```
canonical state section (v2)
  accounts and balances, exactly as v1 …
  u32 bindingCount
  binding row, repeated: u8 mLen | merchant | u8 kLen | key | u8[32] fingerprint | i64 responseLsn
```

The capture instants, however, do **not** go in the hashed section — they go in a new timing
section after it, one `i64` per binding, covered by the file CRC but not by the state hash:

```
header (72 bytes, was 68+4 reserved → the reserved word at +36 is now u32 timingBytes)
state section (stateBytes) → state hash (u8[32]) → timing section (8 × bindingCount) → trailer (36)
file length = 72 + stateBytes + timingBytes + 36
```

Two decisions worth their paragraph:

- **The hash is blind to timing, on purpose.** ADR 0004's theorem is that a state hash is a
  function of the money state, and two runs of one history must agree on it. Capture instants
  are inputs, not state: the same history run against two different clocks must hash the same
  (the contract's thirteenth check asserts the logs differ and the hashes agree), and the
  boundary harness's five-way comparison — live hash, golden hash, recovered hash, fold-from-zero
  hash, checkpoints-deleted hash — stays meaningful now that records carry wall-clock instants.
- **The timing section is CRC'd but unhashed, so its pairing is checked directly.** The header's
  `timingBytes` must equal `8 × bindingCount`, or the file is refused with the new
  `TIMING_MISMATCH` — the one refusal in the taxonomy that exists because the bytes it guards
  are the one thing the state hash cannot see. The contract's thirteenth check builds that
  exact file — every other layer internally valid, one instant cut out — and requires the name.

Version 1 checkpoints remain readable: their state sections predate bindings, and
`LedgerState` refuses canonical version 1 with a message that says *skip, don't upgrade* — a
v1 checkpoint is folded exactly as before, and the binding table is re-derived by folding the
tail. A checkpoint claiming format version 2 with canonical version 1 is `STATE_MALFORMED`, the
same layer that refuses every other state-section lie.

## 7. Expiry, GC, and the clock (standing questions 3 and 5)

**Question 3 — can a GC'd key be reused, and what happens then?** Nothing is GC'd, and the
answer is the design: a key's *binding* — the scope and the fingerprint — is permanent for the
life of the log, because the binding is the record and the log is append-only. What expires is
the *stored response's availability*: past retention, a same-body retry is refused with
`IdempotencyExpiredException` (410, quoting the capture instant and the retention), not
replayed and not re-executed. So the full matrix:

| Request after expiry | Response | Why |
| --- | --- | --- |
| Same body as bound | 410 — the response is gone; retry with a **new key** | the posting may have downstream consumers who rely on the response window closing |
| Different body | 409 — the conflict outlives the response | the permanent fingerprint is the whole point of §3 |

The cost is stated rather than hidden: the table's rows live as long as the log does, one row
per key ever bound — a handful of bytes against an append-only file whose retention is a
deferred ticket (segment rotation, on the map). Stripe can afford to prune because its table is
a cache of responses, not a promise about identity; this ledger's table is both, and the 409
after expiry is the price of keeping the promise, paid gladly.

**Question 5 — clock skew vs the expiry window.** The capture instant is read **once**, from the
policy's clock, at the moment the record is committed — replay never consults anything but the
comparison. Age is `now − capturedAt`, and a clock that steps backwards makes age negative,
which reads as *live*: the floor is zero. The consequences, all of them:

- A backwards-skewed clock **extends** the replay window — replays are served that strict time
  would refuse. Harmless: a replay is a read.
- A forwards-skewed clock **shortens** the window — replays become 410s early. Loud, not
  silent: the client retries with a new key or investigates the clock.
- No skew, in either direction, at any magnitude, turns a retry into a **second posting**. The
  one dangerous direction — expiry as re-arming — is structurally absent, because expiry checks
  a permanent binding rather than deleting one.

The property suite exercises this as a campaign verb: its `Tick` op moves the clock both ways
by deltas that straddle the window, and the model oracle predicts replay or expiry from the
skewed clock exactly as the ledger must answer. And because capture is a *decision of the
caller's clock*, the whole mechanism is testable with a frozen one — which is what lint rule 8
protects: the wall clock is read in `IdempotencyPolicy.java` and nowhere else in `src/main`, so
every capture instant is an input the tests control, and `System.nanoTime()` (group commit's
monotonic deadline) is not the wall clock and is allowed.

## 8. The evidence, and what it caught

| Suite | What it holds | Result |
| --- | --- | --- |
| `IdempotencyContract` (new, 15 checks) | the receipt; the replay (zero bytes appended); the 409 at age 0 and age 48h; the record's exact bytes; per-merchant scoping; expiry refused twice with nothing appended; a backwards clock serving a live replay; **the duplicate storm — 100 virtual threads, one posting, 99 replays, zero double-charges, one LSN**; recovery folding the table back (hash identical, live replays, aged refusals, conflicts surviving); a checkpoint carrying bindings, expiry aging exactly, the response re-materialized; the double-binding log refused `DOMAIN_REJECTED`; retention zero (store once, refuse forever, never re-execute); the hash blind to timing; reordered entries conflicting; a rejected candidate leaving no binding | 15/15 |
| `IdempotencyProperties` (new, 3 checks) | the ticket's theorem as a model oracle: every op predicted by four branches of the ADR, the ledger must agree — outcome, LSN, capture instant, binding count, Σ balances — across random interleavings of retries, conflicts, expiries, reopens, checkpoints and skewed ticks; coverage refuses a path un-walked; the harness reproduces from its seed | 40 × 120 ops, `posted=1124 replayed=65 conflict=964 expired=126 rejected=769 reopened=331 checkpointed=235`, 3/3 |
| `WalContract` (+1 check, 20 total) | a hand-crafted v1 header over a real `POSTED` frame reads; the same header over a `0x04` frame is refused as vocabulary damage; a v3 header is refused before any record is read | 20/20 |
| `CheckpointContract` (12 → 13 checks) | the timing section pairs with the bindings (byte-level), order and presence pinned by the hash, `TIMING_MISMATCH` refused by name from a file valid in every other layer; the v1/v3 header refusals moved with the version bump | 13/13 |
| `LsnBoundaryHarness` (re-armed) | the child protocol interleaves the keyed act — every even op posts under a fresh key, every keyed posting schedules a retry and a conflict — and the parent folds `I`/`R`/`C` lines; stage kills arm on the golden run's own `CKPT` lines, so an armed crash always has a swap to die inside | 71/71 at 16 ops, 220/220 at 64 ops |
| `DomainModelProperties`, `SubstrateContract` | unchanged, still green — the new domain types joined the final-field rule (`DOMAIN_TYPES + 6`) and `JournalDigest` learned to render the new event, so the no-mutation property covers it | 9/9, 5/5 |
| Lint rule 8 (new) | the wall clock is read in exactly one file, self-tested with five violations and prose that must not match | green |

What the suites caught while being written — the argument for writing them first:

- **The double-counted count.** `IDEMPOTENT_FIXED_BYTES` was 44 and the entry-list bytes carry
  their own `u16` count: the encoder allocated two bytes the decoder refused, and every
  replay-time decode failed with *"2 bytes are left over after 2 entries"*. The in-run paths
  never decode, so twelve of fifteen contract checks passed on the broken build — only the
  recovery and checkpoint checks caught it. Which is why there are recovery and checkpoint
  checks.
- **The precedence was backwards in the oracle.** The first property campaign failed with a
  conflict where the model predicted a domain refusal, and the model was wrong: the ledger
  answers from the table before it validates the candidate, because "this key means a different
  intent" is the more useful refusal. The ADR now states the order, and the oracle encodes it.
- **The stage arms could name an instant nothing happens.** The boundary harness armed its
  stage kills at fixed fractions of the history; the keyed protocol moved the last checkpoint,
  and ten armed children ran to completion with nothing to die inside. The golden run now
  reports its checkpoints (`CKPT` lines) and the harness arms on those — an armed crash always
  has a swap to die inside, whatever the history's mix.

## 9. What this design knowingly does not do

- **No HTTP layer.** The 409/410 are exception types with structured accessors, not status
  codes; the public-API ticket decides the mapping and the headers (an `Idempotency-Key`
  header, a `X-Replayed: true`-style flag, both trivially derivable from `IdempotentReceipt`).
- **No TLA⁺.** The single-record commit is an argument, not a proof; the Jaunt ticket owes the
  machine-checked version, and this ADR's §4 is the prose it will formalize.
- **No metrics.** The charter's idempotency metrics (replay rate, conflict rate, expiry
  refusals) need a metrics substrate that does not exist yet; the receipts and exceptions carry
  everything a future counter needs.
- **No cross-ledger keys.** Scope is one ledger directory. A merchant on two ledgers needs two
  bindings, and the federation ticket — if the map ever grows one — owns that problem.
- **The response is the transaction, not the HTTP response.** When there is an HTTP surface,
  the stored response may want headers and a status of its own; the record's payload has room
  (a version-tagged response section), and that day's ADR decides it.

## 10. Consequences for the map

- **"Public API surface"** leaves fog for the next ticket with the 409/410-to-HTTP mapping
  decided here as an input.
- **"Per-account concurrency"** is untouched: keyed posts serialize on the same commit lock as
  plain ones, and the duplicate storm is the evidence the serialization is not a bottleneck
  shape.
- **"The TLA⁺ spec"** inherits §4 as its exactly-once claim.
- The checkpoint format is at v2 and the WAL at v2; both can still read their v1s, and the
  boundary harness's byte-prefix rule now covers keyed records end to end.
