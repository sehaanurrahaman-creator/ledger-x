# ADR 0004 — Checkpoints and byte-identical replay: a hash that defines "identical", one atomic file, and a crash at every byte

- **Status:** Accepted — 2026-09-17
- **Ticket:** [How is state proven identical after a crash? Checkpoints and byte-identical replay](https://github.com/sehaanurrahaman-creator/ledger-x/issues/6),
  a child of the [Wayfinder map](https://github.com/sehaanurrahaman-creator/ledger-x/issues/1),
  unblocked by [ADR 0003](./0003-wal-fsync.md) — this file consumes the WAL format, the ack rule
  and the marker that ADR 0003 decided.
- **Decides:** the canonical serialization of ledger state and the SHA-256 over it — which is
  the *definition* of "byte-identical" the charter asks for; the determinism rules (what is
  banned from state, and why); the checkpoint file format; the atomic swap protocol; the
  retention rule; the order recovery runs in (validate the checkpoint **before** repairing the
  log); the split between a *damaged* checkpoint (discardable) and a *disagreeing* one
  (fatal); and the domain's restore surface.
- **Does not decide:** segment rotation and truncating the log behind a checkpoint (the WAL
  keeps its whole history, and this file argues that is why retention is one line); when to
  checkpoint automatically — the trigger policy is the benchmark/ops ticket's; the
  idempotency table, which joins the canonical state when its own ticket lands, with a stated
  rule for how; and the latency numbers (*How fast is it, honestly?*), which get a
  checkpoint-cost column to measure.

## 1. Decision

| Question the ticket left open | Decision |
| --- | --- |
| What "byte-identical" means | SHA-256 over the **canonical state encoding** (`StateHash`): accounts in opening order, each with its balance, pinned by the journal length. Same bytes, same state; no weaker notion of "equivalent" is recognized. |
| What is banned from state | Wall-clock time, random values, map-iteration order, floating point, locale-dependent rendering, object identity — and LSNs, with an argument of its own (§2). The log already carried most of this rule; the hash makes it *checkable*. |
| Checkpoint format | One file, `checkpoint.bin`: 36-byte header (magic `"LCHK"`, version, watermark LSN and byte offset, state length, header CRC), the canonical state bytes, a SHA-256 of those bytes, and a CRC32C over the whole file (§3). |
| Swap protocol | Write `checkpoint.tmp`; fsync it; **atomic rename** onto the live name; fsync the directory. A crash at any step leaves either the old checkpoint or the new one, never a half file (§4). |
| Retention | Exactly one live checkpoint per directory, superseded in place; the temp is the only garbage there ever is, deleted at open. The log is **not** truncated behind the checkpoint, so replay-from-scratch never depends on it and deleting checkpoints is always safe (§4). |
| Recovery order | Scan the log (read-only) → validate the checkpoint against that scan → *then* open the log for repair. A checkpoint that names coverage the log cannot prove is refused with the evidence untouched (§5). |
| Damaged vs disagreeing | A checkpoint that fails **its own** integrity checks is renamed to `checkpoint.rejected` and recovery proceeds from scratch — a damaged optimization must not become an outage. A checkpoint that is intact but **disagrees with the log** is `CHECKPOINT_MISMATCH` and refuses to open — silently replaying from scratch would paper over lost acknowledgements (§5). |
| The proof | A harness that treats **every byte offset of a history** as a crash point: for each, recover four ways and demand the same canonical bytes. 12 histories × ~2,000 offsets in the CI-sized run, zero mismatches (§7). |

## 2. The state hash: what "byte-identical" is defined to mean

The charter's sharpest testable property is deterministic replay — redoing the log after a
crash yields byte-identical state. "Byte-identical" is not self-defining: bytes of *what*,
in *which* order, compared *when*? The answer has to be a function, written down before it
can be proved, and the proof is then a comparison of outputs of that function. `StateHash`
is the function:

```
u8  version (1)
u64 eventCount            the journal length the state includes
u32 accountCount
per account, in opening order:
  u8 idLength | id (7-bit ASCII) | u8 kind ordinal
u32 balanceCount          equal to accountCount; written so a lie is checkable
per balance, same order:
  i64 minorUnits          signed, debit-positive
```

Big-endian, fixed-width fields and lengths, no delimiters and no escaping — the same rules as
the WAL's payload codec, and resting on the same ADR 0002 fact: an account id's alphabet
excludes every byte a length prefix makes unnecessary to quote, so the encoding is injective
without an escaping scheme, and two implementations of "the same" state cannot disagree by
one byte. The hash is `SHA-256` over those bytes.

**What is in, and why exactly that.** The account table and the balances are the
materialized state — the whole of what a checkpoint carries and the whole of what replay
produces. The **event count** is in because balances alone do not pin a history: a ledger
that posted 5 and a counter-posting of 5 has the same balances as one that never posted, and
a hash that called those states identical would make a lost transaction invisible. The
contract asserts exactly this near-miss: a cancelling pair of postings moves no money,
changes no account, and must still change the hash. When the idempotency table arrives it
appends its own section — a count plus entries in a canonical key order — and the version
byte moves; a hash change is a format change, and old checkpoints become unreadable rather
than silently re-interpreted, which is the trade ADR 0003 made with the type table.

**What is out, and why, is the determinism rule.** This is the half the ticket calls
"forcing nondeterminism out of state", and each ban has a named failure it prevents:

| Banned from state | The failure it prevents |
| --- | --- |
| Wall-clock time | Two folds of one log would disagree; the same history would have two states. Nothing in `src/main` reads a clock into state — `System.nanoTime` appears only in the committer's batch window, which is scheduling, never state — and the contract asserts a checkpoint of one state is **byte-identical however it was produced**, which a timestamp would break. |
| Random values | Same failure. Ids are caller-chosen references (ADR 0002); the ledger mints nothing. |
| Map-iteration order | A `HashMap`'s order is a function of hash codes and insertion history — leakage, not state. The encoding walks accounts in **opening order**, which is a pure function of the event sequence; the contract asserts that swapping the opening order changes the hash, i.e. the order is *signal*, and that two ledgers built by the same steps encode identically. |
| LSNs and byte offsets | The subtle one, and the marker is the argument: a crash inserts a `RECOVERY_MARKER` into the log, shifting every later LSN by one while the ledger state stays the same. An LSN in the hash would make *crash, recover, continue* land on a different final hash than a clean run — breaking the composition property this ticket proves at every boundary. The journal **length** is state; the log's internal **position units** are not. |
| Floating point | Lint-enforced in the domain since ADR 0002; impossible here by construction, since money is a `long`. |
| Locale-dependent rendering, object identity | The encoding is binary and structural; nothing calls `toString`, `hashCode`, or `String.format` on the way in. |

Two things the hash deliberately is **not**. It is not the test suite's older
`JournalDigest` rendering — that stays test-only, exactly as ADR 0003 §10 predicted ("the
durable state hash is yours to canonicalise, since `EventCodec`'s rendering is only
`JournalDigest`-shaped test code"); the durable definition lives in `src/main` because
checkpoint files carry it and recovery reads it. And it is not computed after every commit
in production: hashing is O(state), and the write path's per-commit cost is the fsync menu's
to price. The *proof protocol* hashes after every commit — that is what an experiment is —
while the production path hashes at checkpoint time and after recovery, the two moments a
comparison needs.

## 3. The checkpoint file

```
  +0  u32 magic      "LCHK"
  +4  u8  format version (1)
  +5  u8  flags (0)
  +6  u16 header size (36)
  +8  u32 reserved (0)
 +12  u64 watermarkLsn       the last LSN the state includes (0 = an empty log)
 +20  u64 watermarkOffset    the log's byte length through that record, header included
 +28  u32 stateLength
 +32  u32 crc32C over [0,32)
 +36  state bytes (stateLength)          ← the §2 encoding, verbatim
      u8[32] sha-256 of the state bytes
      u32 crc32C over [0, file length - 4)
```

Three integrity layers, each with one job. The **state hash** is the *identity*: it is the
same function the replay proof compares, so a checkpoint's state and a replayed state are
comparable by construction — the file carries its own witness. The **whole-file CRC** is the
*damage* detector: any scribble anywhere in the file is caught before a byte of it is
believed, which is what lets the discard rule below be safe. The **header CRC** exists so a
reader can reject a foreign file after 32 bytes instead of after all of them. Nothing here is
trusted because it says so; every field is either covered by a checksum or cross-checked
against the log.

The state section being exactly the hash's preimage is a deliberate collapse of two
artifacts into one. A design that stored a *rendering* of state plus a hash *of different
bytes* would have two encodings to keep deterministically equal — the same disease as a
second copy of the LSN, and for the same reason.

The watermark has two halves, and both are load-bearing. The **LSN** says where the tail
begins; the **byte offset** says what the log must still show. LSNs are dense from 1 within a
surviving prefix (ADR 0003 §2), so record *n* is the *n*-th frame and ends at a byte the
scan can name — which turns "does this checkpoint describe this log?" from a hope into a
frame-end comparison.

## 4. The swap, the retention rule, and why the log is not truncated

The swap is five steps, in the only order that survives a crash at any of them:

1. **Force the log** — `force(true)` through the last written byte, routed through the
   committer as a queue barrier so the watermark it returns is the committer's own number.
   This is ADR 0001's table (`force(true)` at checkpoint) enforcing ADR 0003 §10's rule:
   *the bytes a watermark names are durable before the snapshot claiming them exists, never
   the reverse.* A watermark written ahead of its own durability would let recovery skip a
   tail that was never lost — it would be lost.
2. **Serialize the state** — under the ledger's commit lock, so no commit can append and no
   apply can land between the force and the encode. The watermark and the bytes are the same
   moment; that is what makes the watermark *true* rather than recent.
3. **Write `checkpoint.tmp`, fsync it** — `force(true)`, because a new file's size is
   metadata, and a rename of a file whose bytes are not durable would publish a checkpoint
   nobody can read back.
4. **Atomic rename onto `checkpoint.bin`** — and if the filesystem will not promise
   atomicity, the swap refuses: a non-atomic checkpoint swap is the bug this protocol exists
   to not have.
5. **fsync the directory** — `fsync(2)`'s own sentence: the rename is only as durable as the
   directory entry it changed.

A crash at step 3 leaves a temp file — garbage by definition, deleted at open and deleted
again before every write. A crash at step 4 leaves the old checkpoint or the new one; there
is no instant at which the live name is half a checkpoint. That is the whole argument for
writing a temp instead of the live name, and it is why the contract plants both garbage and
*foreign but valid* temp files and asserts they are never obeyed.

**The retention rule is one line, because the log is not truncated:** exactly one live
checkpoint per directory, superseded in place by the atomic rename; the temp is the only
garbage there ever is. Old checkpoints do not accumulate, so nothing needs collecting — and
because the WAL keeps its whole history, **replay-from-scratch never depends on the
checkpoint at all**. That is the strongest retention property a checkpoint can have: deleting
every checkpoint file is always safe, and the contract asserts recovery after deletion is
byte-identical. ADR 0003 §10's ordering note ends "then truncate or rotate the log — in that
order, never the reverse"; this ticket takes the ordering and declines the truncation.
Truncating behind the checkpoint would make the checkpoint load-bearing — break it and you
cannot rebuild — and would need numbered segments, a manifest, and keep-newest-K GC to be
crash-safe, which is the rotation ticket's whole substance. When rotation lands, numbered
checkpoints and a keep-newest-K rule supersede this one; nothing here blocks them, because
the format is per-directory and the swap is per-file.

## 5. Recovery: checkpoint plus tail, in a stated order

Recovery is the fold it always was, with a base: load the checkpoint (validated), fold every
record **after** its watermark on top of its restored state, audit. That the tail starts at
record `watermarkLsn + 1` is an LSN fact, not a convention — dense numbering makes record
*n* the *n*-th frame — and the coverage check makes it a proof:

- the watermark offset must be **within the log's surviving bytes** (an offset past the end
  is a checkpoint describing a log that is gone), and
- the frame the watermark LSN names must **end at exactly the claimed offset** (a frame-end
  mismatch means the checkpoint and the log are not from the same history).

Either failure is `Corruption.CHECKPOINT_MISMATCH`, and the open refuses. The reasoning is
ADR 0003 §4's asymmetry applied to a new artifact pair: the covered bytes were *forced*
before the checkpoint existed (§4's step 1), so bytes the checkpoint vouches for but the log
has lost are lost acknowledgements — data loss, not a torn tail. Silently replaying from
scratch would reconstruct a consistent ledger *that is missing acknowledged money*, and the
first anyone would hear of it is a reconciliation dispute. Refusal makes the loss visible;
deleting the checkpoint is how an operator chooses from-scratch replay anyway — a decision,
recorded in the refusal message itself, not a surprise.

**Damage is the opposite verdict, on purpose.** A checkpoint that fails its *own* integrity
checks — bad magic, bad CRC, bad digest, truncated, structurally impossible — is renamed to
`checkpoint.rejected` and recovery proceeds from scratch. The checkpoint is an optimization;
the from-scratch path is *proven* to reconstruct the identical state (§7), so a damaged
optimization must never become an outage. The rename rather than a delete is deliberate: a
damaged checkpoint is the only evidence a post-mortem gets about what scribbled on the disk.
The two rules split on one question — *is the disagreement between two durable artifacts, or
is one artifact internally broken?* — which is the same question ADR 0003's cuttable-versus-
fatal table asks, pointed at a different file.

**The order is the finding this ticket's own test wrote.** The first draft opened the log
for repair first (`Wal.open` cuts a torn tail, appends a marker) and validated the checkpoint
afterwards. The contract's refusal check then failed with "the refusal edited the log": a
checkpoint ahead of a torn log would have its evidence repaired away — the tear cut, a
marker appended — *before* the refusal said data loss. So the order is now: scan read-only,
validate the checkpoint against that scan, and only then open for repair. A refusal leaves
the file byte-identical; the repair happens on the next open, after the operator has decided.
The repair itself is unchanged ADR 0003 — and a validated checkpoint stays valid across it,
because the cut can only land at or above the watermark the coverage check just proved.

**The domain's restore surface is one static factory.** `InMemoryLedger.restored(accounts,
balances, eventCount)` builds a ledger whose journal *begins* at the watermark: its balances
and accounts are the restored ones, events appended afterwards are the tail replay's,
`size()` counts restore-plus-tail so the journal position is continuous, `audit()` folds the
tail on top of the restored balances and counts restored accounts as log-opened, and posting
validates against the restored balances, which are ordinary balances for every purpose. The
constructor is private and every field stays final, so the domain's no-mutation-surface
property — which failed the first draft for exactly this — holds over the new state too. The
alternative, building the ledger from outside by setting fields, would have made the
checkpoint layer the one place exempt from the audit's meaning, and an exemption is where a
ledger starts lying.

What a restored ledger's `events()` returns is part of the decision: **the tail, not the
history.** The in-memory list is a view of the durable log, and the durable log still holds
everything; a ledger that restored at watermark 6 and appended 6 events holds 6 events in
memory and 12 in the journal, and `size()` says 12. The contract asserts exactly this
balance — position continuous, in-memory list tail-only — because it is the observable that
would catch a checkpoint that was decorative (replaying the whole history behind a banner
saying it did not).

## 6. What the checkpoint buys, and what it costs

Honest accounting, since the benchmark ticket will replace this with a table. A checkpoint
costs O(state) bytes written plus one `force(true)` on the log, one on the temp, one
directory fsync — a cold path, once per checkpoint, against an O(log) recovery scan saved
per open. The prototype does not auto-checkpoint; the trigger policy (every *n* records? by
bytes? by age?) needs the latency numbers this map has not measured yet, and a wrong
default would put an O(state) pause inside someone's p99. Until then `writeCheckpoint()`
is an explicit call, which is also what makes the harness able to place checkpoints exactly.

Recovery with a checkpoint scans the log twice — the read-only validation scan, then the
repairing open's own scan. That is the price of refusing before repairing, and recovery is
the coldest path there is; if it ever matters, the scan can be shared, but not by putting
the repair back in front of the refusal.

## 7. The proof: a crash at every byte

The ticket's resolution names a crash-at-every-LSN-boundary harness. This build takes it
one notch stricter: **every byte offset**, not only frame boundaries. A crash does not land
on record boundaries — it lands mid-frame, mid-payload, mid-LSN — and a harness that
swept only the clean points would miss the tears that recovery actually cuts. The disk
state at the instant the log was *o* bytes long is reconstructed exactly: the log truncated
to *o*, plus the checkpoint that was live then (the newest whose watermark does not exceed
*o* — checkpoints are byte-identical files, so re-planting the recorded bytes *is*
replaying history).

Four verdicts per offset, all of which must hold:

1. **checkpoint + tail** — the recovered ledger's canonical bytes equal the clean run's
   bytes after the last record that fits in *o*;
2. **full replay from scratch** — the same truncated log, checkpoint deleted, folded whole,
   folds to the same bytes: the identity the ticket names, and the one that makes the
   discard rule of §5 safe;
3. **crash, recover, continue** — at every record boundary (and sampled mid-frame cuts),
   posting the rest of the history through the recovered ledger and reopening lands on the
   clean run's *final* bytes: recovery is not only correct at the moment it happens, it is
   invisible in everything that comes after — this is the verdict that would fail if an
   LSN had leaked into the hash, or if the marker had left a trace in state;
4. **torn heads refuse** — offsets 1–15 cut the segment header, which no crash can damage;
   the open must refuse with the log untouched.

Plus a fifth, run per step in the clean pass: the live ledger's canonical bytes equal a fold
of the same steps into a fresh in-memory ledger — the identity that makes "the state the run
had" well-defined before any crash is simulated.

The kill-9 harness is still there and still the end-to-end end (`./build.sh crash`): real
process death, real page-cache semantics, the ack rule under a SIGKILL. What the forked
harness cannot do is be exhaustive — a kill lands at one instant per JVM launch — and what
the byte harness cannot model is a controller that reorders or lies about a force, which
byte truncation cannot produce. The two are complements, and the ADR states the gap in each
rather than letting either claim the whole fault surface.

## 8. Evidence

**13 checks in `dev.ledgerx.checkpoint.CheckpointContract`, run by `./build.sh`** — the two
byte-level layouts written down rather than computed by the code under test (canonical state
and checkpoint file, against hex dumped in the failure message); determinism and its three
near-misses (journal depth, opening order, kind); restore-is-inverse over 8 random histories
with post-restore posting and audit; checkpoint byte-identity across two runs and two
directories; the watermark forced under all three policies, including "no-fsync advanced no
watermark until the checkpoint's force"; refusal of a checkpoint ahead of its log at three
truncation points plus two patched watermarks (moved offset, over-LSN), log untouched each
time; 8 kinds of checkpoint damage (bit flips in state, digest, header CRC, file CRC;
truncations to 10, 40, `len−1`, header+trailer−1) each discarded, evidence kept, recovery
byte-identical from scratch; garbage and foreign-but-valid temps never obeyed and always
cleaned; deletion of the checkpoint leaving recovery unchanged; the tail-only fold proven
observable (12 steps, watermark 6, 6 events in memory, position 12); a tear above the
checkpoint cut, marked, recovered twice, and re-checkpointed over the marker's LSN; and the
empty ledger's checkpoint at watermark (0, 16) restored, then used stale while the whole log
folds over it.

**12 trials in `dev.ledgerx.checkpoint.ReplayHarness` (`./build.sh replay`)** — 12 seeded
histories of 40 accepted steps, cycling the fsync menu (group commit, per-commit, no-fsync),
a checkpoint every 7 steps, and every byte offset of every resulting log treated as a crash:

| trial | policy | log bytes | offsets swept | tears cut | head refusals | continues | mismatches |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | group commit | 2,107 | 2,108 | 2,051 | 15 | 132 | **0** |
| 2 | per-commit | 1,990 | 1,991 | 1,934 | 15 | 127 | **0** |
| 3 | no-fsync | 2,061 | 2,062 | 2,005 | 15 | 127 | **0** |
| 4–12 | cycling | 1,930–2,023 | 24,031 total | 23,347 total | 15 each | 1,514 total | **0** |

Every offset passed all four verdicts: checkpoint + tail equal to the clean prefix's exact
bytes, full replay from scratch equal to it, and — at 1,514 boundaries and sampled cuts —
crash-recover-continue landing on the clean run's final bytes, re-verified through a second
recovery. 49–59 s for the CI-sized campaign across two runs, and the runs are
seed-reproducible to the tear count — 23,347 tears and 1,514 continues both — which is §2's
determinism rule showing up in the harness itself: a seeded campaign whose numbers drifted
between runs would be measuring the drift. The cheapest exhaustive claim on the map so far.
(`LEDGER_X_REPLAY_TRIALS` / `_OPS` / `_SEED` resize it; failures print the trial, seed and
byte offset, and keep the directory.)

**Everything already green stays green**: the substrate contract (5/5), the durable-write
contract (19/19 — untouched frames, unchanged recovery rules), the domain property suite
(9/9 — including its reflection check, which rejected the first draft's restore fields for
not being final), and the demo. The kill-9 harness ran 1,000 cycles across the five-policy
menu unchanged in its verdict (§7's complement harness; its numbers live in ADR 0003 §8).

**Two findings this ticket's own tests wrote, both fixed in the same commit that found
them.** The recovery-order bug of §5 — a refusal that repaired first — was found by the
contract's "the refusal edited the log" assertion, which exists because a post-mortem
deserves the log exactly as it lies. And the domain's immutability property refused the
first restore surface (mutable restore fields), which is the kind of check that earns its
keep by being annoying at exactly the right moment.

## 9. Consequences accepted

- **The hash is O(state), and checkpoints are O(state) writes.** Priced in §6; the
  alternative — hashing incrementally — is a research ticket of its own and would still
  need the canonical bytes at checkpoint time.
- **The restored ledger's in-memory event list is the tail.** Callers wanting the full
  history read the log, which still has it. `JournalDigest`-style whole-journal assertions
  in test code must not be pointed at a restored ledger and expected to see history.
- **`InMemoryLedger` grew one static factory and one private constructor.** The first draft
  grew three non-final fields instead and was rejected by the domain's own property suite;
  what shipped keeps every field final and the restore state fixed at construction.
- **A damaged checkpoint is renamed, not deleted** — `checkpoint.rejected` accumulates at
  most one file, overwritten. Disk litter in exchange for post-mortem evidence; the right
  trade for a ledger.
- **A refusal can be survived only by a decision.** `CHECKPOINT_MISMATCH` names the loss and
  the way out (delete the checkpoint, replay from scratch, accept what is missing). The
  prototype puts that sentence in the exception message rather than in a runbook.
- **The scan runs twice on a checkpointed open.** §6; recovery is cold.
- **No auto-checkpoint.** Explicit calls only, until the benchmark ticket has numbers that
  can set a default honestly.

## 10. What this changes for other tickets

- *What are ledger-x's idempotency semantics?* — the idempotency table joins the canonical
  state as its own section (count + entries in canonical key order) with a version bump;
  the hash definition is ready for it, and the §2 ban list is the spec it must satisfy. The
  key's record still rides in the same group as the posting (ADR 0003 §10), unchanged.
- *Segment rotation / retention* — inherits: numbered checkpoints and keep-newest-K
  supersede §4's one-file rule when the log splits into segments; the watermark's
  (LSN, byte-offset) pair becomes (segment, LSN, offset) and the coverage check's frame-end
  comparison becomes a per-segment check. Nothing here blocks that; the swap protocol and
  the hash do not change.
- *What breaks it?* (the chaos ticket) — the fault set grows by one file: kill during a
  checkpoint swap (covered here exhaustively at the byte level for the *log*, and by the
  swap analysis for the checkpoint), scribble inside a checkpoint (must be discarded),
  a checkpoint from another directory (must be refused by the frame-end check). The
  combination "corrupt the checkpoint while a writer commits" is this ticket's leftover and
  is a scheduling question for the chaos harness, not a new rule.
- *What must TLC prove?* — the recovery lemma now has two halves to name: the fold
  determinism (same events ⇒ same state, which §2's ban list makes true by construction)
  and the coverage rule (checkpoint ⊑ log ⇒ recovered = fold(log)). Both are small enough
  to exhaust with a handful of records, a watermark and a cut.
- *How fast is it, honestly?* — add a checkpoint column: bytes written per checkpoint
  (state size + 72), forces per checkpoint (three: log, temp, directory), and the recovery
  time with and without a checkpoint at a given log length. The prototype's `writeCheckpoint`
  counters are all `Wal.Stats` already reports.
- *The public API surface* — `writeCheckpoint()` is the first operation that is not
  "post a transaction"; if the ledger becomes a service, checkpoint becomes an admin
  endpoint with the same refusal semantics, and the exception message is already the
  operator's runbook entry.

## 11. Fog graduated

The map's *Not yet specified* loses one line: **the replay state-hash scheme** is decided —
it is §2, it is code, and it is checked at every byte offset of every CI run. Sharpened
rather than opened: the idempotency table's place in the canonical state is now a rule that
ticket inherits instead of a question it owns; the chaos fault set's storage half is now
fully named (log head, log tail, checkpoint, swap — each with its required verdict); and the
TLC ticket's recovery property is nameable in one line each for fold determinism and
coverage. Still open, unchanged: the benchmark workload's concurrency shape, the CI budget,
the public API surface, and the cross-shard argument.
