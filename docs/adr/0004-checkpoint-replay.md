# ADR 0004 — Byte-identical state: a checkpoint per watermark, a hash of the state, and a crash at every boundary

- **Status:** Accepted — 2026-09-17
- **Ticket:** [How is state proven identical after a crash? Checkpoints and byte-identical replay](https://github.com/sehaanurrahaman-creator/ledger-x/issues/6),
  a child of the [Wayfinder map](https://github.com/sehaanurrahaman-creator/ledger-x/issues/1), unblocked by
  [ADR 0003](./0003-wal-fsync.md)
- **Decides:** the checkpoint file format; the atomic swap and its ordering; the retention rule; **the
  state-hash scheme** — the canonical serialization of materialized state and the digest of it; the
  determinism rules (what is banned from state, and why); how recovery composes a checkpoint with the
  log's tail; the refusal taxonomy and why every refusal is a fallback; and the
  crash-at-every-LSN-boundary harness that makes the equality a number in a report.
- **Does not decide:** idempotency keys and the shape of the table that will join the state
  (*What are ledger-x's idempotency semantics?* — this ADR hands it the ordering rule it must obey);
  segment rotation and the retention of WAL bytes (*What does a durable write look like?* deferred it,
  and this ADR supplies the precondition without implementing it); per-account ordering and
  backpressure; the payout protocol; the TLA⁺ spec; and the ops/sec and p50/p99 numbers, which §7
  prices the arithmetic for rather than measuring at scale.

## 0. What the ticket asked, and where it is answered

| Deliverable | Where |
| --- | --- |
| Checkpoint format | §2 — 72 + stateBytes + 36 bytes, named for its watermark |
| Atomic swap protocol | §5 — temp, `forceAll`, `rename(2)`, `fsync(dirfd)`, collect — the order ADR 0003 §10 handed over |
| Retention rule | §6 — keep the newest `retain`, default two; deletion needs no atomicity because a checkpoint is a cache |
| A state-hash function, defined precisely | §3 — SHA-256 over one canonical serialization; §4 — what is banned from the state so two runs agree |
| Crash-at-every-LSN-boundary harness | §8 — every prefix of a seeded history, three configurations, and a kill inside each swap stage; `PASS 81/81` |
| Determinism rules and why | §4 |

## 1. Decision

| Question | Decision |
| --- | --- |
| What a checkpoint is | A file whose name is its watermark: `<ledger>/checkpoints/checkpoint-<19-digit lsn>.lckp`. One file per watermark, written as a unit, never appended to. |
| Layout | 72-byte header, the canonical state section, a 36-byte trailer: `u8[32]` state hash, then `u32 crc32C` over everything before it. |
| What "state" is | Accounts in opening order, each with its balance, plus the LSN of the last journal record the state includes. Not the event list — the log is the history (§3). The idempotency table joins this list when ticket #7 fixes its shape, under §4's rules. |
| State hash | SHA-256 over the canonical state bytes, printed as 64 lowercase hex digits. One function, one serialization, no options. |
| Binding to the log | `walDigest` = SHA-256 of exactly `coveredBytes` of log; `coveredBytes` = the end offset of the frame carrying `lastLsn`. Three claims the loader re-derives from the log itself. |
| Swap | Delete a stale temp → write the temp → `forceAll` → `ATOMIC_MOVE` onto the checkpoint name → `fsync(dirfd)` → collect. Never a different order. |
| Retention | Keep the newest `retain` files (default 2: one to use, one to fall back to); every stale temp is deleted by the next swap. Retention runs *after* the name is durable. |
| Recovery | List newest first by **parsed watermark**, verify each candidate, use the first that stands up: `state.restore()` then fold the tail. If none does, fold the log from byte 0. |
| Refusals | A closed list of ten (`CheckpointRefusal`); each one means *skip this file*, never *stop* or *guess*. |
| Cadence | `CheckpointPolicy(everyCommits, retain)`; the ledger's default is 1,024 commits and two files; `MANUAL` takes one only when asked. The *hash* exists after every commit; the *file* is per cadence. |
| The proof | For any prefix of any history, five hashes must agree — the crashed run's own, the uninterrupted run's at the same prefix, the recovery's, a fold of the log from byte 0, and a recovery with every checkpoint deleted — and the crashed log must be a byte prefix of the uninterrupted run's. `./build.sh boundary` asserts all of it at every prefix. |

Everything below is the reasoning, including the honest cost measurements (§7), the two bugs the
harnesses caught while this was being written (§8), and the parts this design knowingly does not
do (§10–§11).

## 2. The file, byte by byte

```
checkpoints/checkpoint-0000000000000000042.lckp
+--------------------------------------------------------------+
| header, 72 bytes                                             |
+--------------------------------------------------------------+
| canonical state section, stateBytes bytes                    |
+--------------------------------------------------------------+
| trailer: u8[32] stateHash | u32 crc32C over everything before |
+--------------------------------------------------------------+

header (@0)                                 state section (@72)
  +0  u32 magic "LCKP"                        u8  canonical version (1)
  +4  u8  format version (1)                  u64 lastLsn
  +5  u8  flags (0)                           u32 accountCount
  +6  u16 header size (72)                    accountCount x {
  +8  u32 reserved (0)                          u8  kindNameLength | kindName (ASCII)
  +12 u32 reserved (0)                          u8  idLength       | id (7-bit ASCII)
  +16 u64 lastLsn                               i64 balanceMinorUnits
  +24 u64 coveredBytes                        }
  +32 u32 stateBytes
  +36 u32 reserved (0)
  +40 u8[32] walDigest
  = 72
```

**The numbers are big-endian and the primitives are `WalFormat`'s.** Not for elegance: one repository
with two formats that disagree about what a length is would eventually read a checkpoint as a record
or a record as a checkpoint, and the way to make that impossible is to make both formats call the same
`putLong`. Nothing outside `CheckpointFormat` hard-codes an offset, which is why the diagram exists
in three places — the class's javadoc, this ADR, and the contract's byte-level check — and they have
to agree.

**The watermark appears three times: the file name, the header, and the state section.** The name is
the one part of a file a rename can change without touching a byte, so it is a claim like any other
and is checked against the header (`NAME_MISMATCH`). The state section carries it because the section
is hashed, and the hash is what the run compares; the header carries it so a selector can find the
resume point without decoding the state. Three copies of one number is not redundancy for its own
sake: it is three independent chances to notice that a file was edited, copied or renamed.

**Why the trailer is `stateHash` then `crc32C`, and not the other way.** The CRC covers every byte
before it, so a scanner can find where verification starts without understanding anything it has
read — the same convention as a WAL frame's trailing CRC (ADR 0003 §2). The state hash sits in front
of it so the two live in different layers: the CRC answers "did these bytes survive the medium", the
hash answers "is this the state it says it is". A file that is internally consistent but is not the
state of *this* log is caught by the third layer, the header's `walDigest`, against the log itself
(§5).

**`stateBytes` is bounded twice.** By the file's own length — `72 + stateBytes + 36` must equal it,
or `LENGTH_MISMATCH` — and by `MAX_STATE_BYTES`, 256 MiB, so that a corrupt length field cannot turn
recovery into a 4 GiB allocation before the reader notices the file is 300 bytes long. A reader that
trusts a length before checking it is how a corrupt file becomes an out-of-memory error.

**Why the state section's name lengths are one byte.** `AccountId` is a short, kebab-ish alphabet and
`AccountKind` has three names; the cap is a real assumption, checked at encode time, and it keeps the
section's per-account overhead at exactly 10 bytes plus the names. If a future id needs 256 characters
this is a canonical-version bump, not a silent truncation.

**Temp files are skipped by name, before any byte is trusted.** A half-written swap is written to
`<name>.tmp`, which does not match the reader's pattern; the reader lists by pattern, so a torn temp
cannot be selected even if it happens to parse. Deletion uses the same pattern too — a `.tmp` file
whose name starts with `checkpoint-` — which is what makes a stale temp garbage the next swap can
remove without a liveness question.

**Nineteen digits.** `Long.MAX_VALUE` has 19 decimal digits, so zero-padding to 19 makes
lexicographic order of the names equal numeric order of the watermarks. Recovery nevertheless sorts
by *parsed* watermark rather than by name: a change to the padding must not be able to silently
reorder which checkpoint is believed.

## 3. The state hash: one canonical serialization

```
u8  canonical version (1)
u64 lastLsn                    the LSN of the last journal record whose effect the state includes
u32 accountCount
accountCount x {
  u8  kindNameLength | kindName (ASCII, AccountKind.name())
  u8  idLength       | id (7-bit ASCII, AccountId's alphabet)
  i64 balanceMinorUnits, signed, debit-positive
}
```

`stateHash()` is SHA-256 over exactly these bytes, and nothing else. `stateHashBytes()` is the same
digest undecoded, for the trailer. Two states hash the same when and only when their canonical bytes
are equal, which is the entire requirement — and the reason the serializer is one method with no
parameters, no maps in its path, and nothing that reads a clock, a locale or an identity hash.

**Accounts are emitted in opening order — the order their `ACCOUNT_OPENED` records appear in the
log.** That is the order a `LinkedHashMap` built by a fold holds them in, the order a checkpoint
restored from a previous checkpoint preserves, and the only order in this system that is itself
replayable. The contract asserts the property directly: two ledgers with identical histories hash
identically, and the same balances opened in the other order hash differently. A canonical form that
sorted accounts by id would be deterministic too — and would diverge from the fold's own order,
which is the thing the hash is supposed to be able to compare.

**Each account carries its own balance, so the state is one list, not two collections that could
disagree.** There is no cross-reference between an account section and a balance section, no join key
to get wrong, and no iteration over a map. The alternative — accounts here, balances there — is a
second serialization that can be reordered independently, which is precisely the class of bug §4
bans.

**`lastLsn` is inside the hash, deliberately.** Two ledgers with the same balances at different points
in their lives are not the same state. The position is what makes "checkpoint + tail" comparable to
"replay from scratch" as one equality rather than two checks that can each pass while disagreeing:
the recovered state's `lastLsn` must be the same number the run reported, so a recovery that landed
on the right balances at the wrong point in the log fails the comparison rather than quietly
matching.

**Why the kind is a name here and a number in the WAL.** ADR 0003's payload writes
`AccountKind.ordinal()` because a log's reader is versioned with its writer and re-ordering the enum
is already a format change there. A state hash is a different animal: it is the number a human copies
out of a log line and compares between two builds, so it says `LIABILITY` rather than `1` and it
survives a constant being moved. The two encodings never have to agree, because a checkpoint's bytes
are never read as a WAL record and vice versa — and the contract reads both formats' bytes to pin
that.

**What is not in it, and what covers that instead.** The event list is not in the state hash: the log
*is* the history, a checkpoint that carried the whole history would be a copy of the log rather than
a cache of its fold, and hashing every event after every commit would make the hash O(history) while
saying nothing the log does not already say. The ticket's "byte-identical" is therefore a
**conjunction**, and all of its parts are checked:

1. **the state** — this hash, compared across a run, a recovery, and a fold from byte 0;
2. **the history's bytes** — `WalContract.replayIsDeterministic` re-encodes every payload after a
   replay and compares the byte arrays, and the domain's property suite replays event lists into
   fresh ledgers and requires the rendered events to be identical;
3. **the binding** — a checkpoint's `walDigest` is verified against the log's exact bytes before the
   state is used, so equal state hashes can only be compared over identical underlying bytes; and
4. **the harness's prefix rule** — the crashed run's log must be a byte prefix of the uninterrupted
   run's log, which is what rules out "same hash, different history".

So "byte-identical" is defined as: *the state hash of the recovered ledger equals the state hash the
run itself reported at that prefix, the recovered ledger is built from a byte prefix of the run's
log, and a full replay of that log produces the same state hash again*. §8 is how that is swept.

**When the idempotency table lands (ticket #7), it joins the state section as one more list, emitted
in the log's order of insertion, with no wall-clock value inside the hash** — an expiry deadline must
be derived from the log's own records or excluded from the hashed form, or the hash moves without a
commit and rule 1 of §4 is broken. That constraint is filed in §9 and §11 rather than left to be
rediscovered.

## 4. Determinism rules: what is banned from state, and why

A hash is a proof only if both sides can compute it from the same rules. Every rule below exists
because some construct would let two correct-looking runs produce different bytes for the same
history — and a difference in the hash of the *state* is indistinguishable from lost money, which is
why the bans are absolute rather than discouraged.

| Banned from the state path | Rule | Why |
| --- | --- | --- |
| Wall clock: `Instant.now`, `System.currentTimeMillis`, `nanoTime`, `Date` | No clock is read while state is built or hashed | Two replays of one history happen at different instants; a timestamp in state makes the hash depend on *when* you looked, which is the definition of a nondeterministic state. ADR 0002 already bans timestamps from events; a checkpoint must not smuggle one in. |
| Randomness: `UUID.randomUUID`, `SecureRandom`, `Math.random` | No random identity in state, at all | An identity that differs per run cannot be replayed, and cannot be hashed into anything stable. Where an id is needed it must come from the log's own sequence (the LSN). |
| Map and set iteration: `HashMap`, `HashSet`, `Stream.parallel`, a collector without a fixed order | State is serialized from a `List` in the log's opening order; nothing iterates a hash container on this path | Iteration order is not a promise, can differ between JVMs, and can differ *within a run* after a rehash. A `HashMap`'s order is a data structure's convenience, not a decision this system made. |
| Identity hash and default `toString`: `System.identityHashCode`, printing an object that does not override `toString` | Nothing on the path prints or hashes an identity | Identity hash codes vary per run by design; a hash that includes one is a hash of the process, not the money. |
| Default locale and default charset: `toUpperCase()`, `String.format`, `getBytes()` | All text on this path is ASCII and all encodings are named explicitly (`StandardCharsets.US_ASCII`), and the JVM's file.encoding is not consulted | A Turkish locale once changed `toUpperCase` for the world; a default charset changes between platforms. Both make the same state serialize to different bytes on different machines. |
| Floating point: `double`, `float`, `BigDecimal` | Money is a signed `long` of integer minor units; the lint forbids floating point under the domain package | FP association and rounding are a rounding error about somebody's money, and a bit pattern that can vary between compilation strategies has no business inside an identity. ADR 0002 made this decision; this ADR inherits it and the lint enforces it. |
| Environment identity: `java.version`, hostname, user name, time zone, absolute paths | Not in the state, not in the hash | A hash that includes the machine is not comparable across machines, and comparing two builds is the point of the number. |
| Filesystem listing order | Recovery sorts candidates by parsed watermark, descending, explicitly | No filesystem promises the order of `readdir`; a recovery that picked by listing order would be nondeterministic in exactly the way this ticket exists to remove. |
| An uncommitted or half-applied commit | A checkpoint is built under the commit lock, from state that only ever reflects applied, acked commits | Not "banned" so much as excluded by construction: the state has no way to observe a transaction that is not in the log. |

**"Hashed after every commit" is a property of the function, not of the file.** `stateHash()` is
defined at every instant, and the boundary harness compares the run's hash after *every* commit (and
at every prefix a crash can reach). The checkpoint *file* is written on a cadence, because the file is
a cache and the hash does not need one — the harness runs a configuration with a checkpoint on every
commit precisely so both halves of that sentence are exercised.

**What enforces the rules rather than merely stating them.** The canonical serializer is one method
whose only inputs are a `List<Account>` and balances in the same order, so a map cannot reach it
without a signature change; the contract pins the bytes and requires identical histories to hash
identically while a re-ordered history does not; the lint bans floating point under the domain; the
domain has no clock by ADR 0002; and the harness runs from a fixed seed so "identical history" is a
reproducible statement rather than a claim.

## 5. The swap, and the refusals that stand between a file and the state

**ADR 0003 §10 handed this ticket three rules; each is discharged by a specific line.**

| Rule from ADR 0003 | How it is discharged here |
| --- | --- |
| A snapshot may be written only *after* the WAL bytes it covers are forced | `DurableLedger.writeCheckpoint()` calls `wal.forceData()` before building the state, and the whole thing runs under the commit lock, so the covered bytes are exactly the acked prefix — and under `NO_FSYNC` this is the only force those bytes will ever get. |
| Recovery must refuse a snapshot whose claimed offset the log does not have | `CheckpointStore.verify` compares the header's `coveredBytes` with `WalRecovery.Scan.endOf(lastLsn)`, the offset the log itself says the watermark's frame ends at; a log that no longer holds that frame yields `COVERAGE_GONE`, and the loader moves to the next candidate. |
| Snapshot, then directory, then truncate or rotate — never the reverse | The swap's order below is the middle of that sentence; nothing in this ticket truncates the log, and rotation remains unstarted with the order recorded for whoever starts it. |

**The order, and what each step buys:**

1. **Delete the temp before opening it.** A stale temp from a crashed swap would otherwise be appended
   to, and the result would be renamed into place under a checkpoint's name while being two
   checkpoints long. That is the one way this protocol could produce a file that is corrupt *and*
   named as valid; a temp is garbage the moment its swap ends, and no swap ever resumes one.
2. **Write the bytes** (matching the header and trailer of §2).
3. **`forceAll`, not `forceData`.** A checkpoint is a whole file whose length is part of its claim,
   so its size must be durable before its name is — the same argument ADR 0003 §4 makes after a
   truncation, and the opposite of a WAL segment's case, where a grown file's length is recoverable
   from its tail.
4. **`rename(2)`** within the same directory: a reader sees the old name or the new one, never a
   mixture, and the checkpoint's name never points at bytes that have not been forced.
5. **`fsync(dirfd)`.** A rename is a directory entry, and a directory entry is durable only once the
   directory itself is forced — ADR 0003 §4 already applies this to creating a segment, and
   `fsync(2)`'s own documentation says an explicit directory fsync is needed for the entry to reach
   the disk.
6. **Collect.** Retention deletes what it deletes *after* the new name is durable, which is the only
   ordering constraint in the whole protocol: a checkpoint may never be deleted before its
   replacement exists (§6).

**The refusals, and why there are exactly these ten.** `CheckpointRefusal` is closed, and the split
inside it mirrors ADR 0003's torn-tail split: everything a crash can physically produce is here
(a tear — `CRC_MISMATCH`, `LENGTH_MISMATCH`; garbage — `BAD_MAGIC`, `BAD_HEADER`) and so is
everything that requires a complete, checksum-valid file to be wrong (`STATE_DIGEST_MISMATCH`,
`STATE_MALFORMED`, `NAME_MISMATCH`, `COVERAGE_GONE`, `WAL_DIGEST_MISMATCH`, `UNREADABLE`) — because
unlike the WAL, a checkpoint has nothing behind it to lose. A tear in the *log* is cuttable because
everything past it is unacked; a torn checkpoint is skippable because it is a cache. The loader
therefore never faces ADR 0003's hardest question — cut or refuse — at all: it refuses to *use*, and
folds.

**The digest of the log, and why a watermark is not an identity.** ADR 0003 §2 allows dense LSNs to
be *reused* after a torn tail is cut: record 4 can be appended again after a tear at record 4's
bytes. So a checkpoint that names `lastLsn = 42` can name two different histories, and no amount of
integer comparison can tell them apart. `walDigest` — SHA-256 over exactly `coveredBytes` of the log
— can, and the contract demonstrates it: two histories with different amounts, ending at the same
LSN and covering the same number of bytes, are told apart only by the digest, and the second
history's checkpoint, copied over the first, is refused as `WAL_DIGEST_MISMATCH`.

**Why three integrity layers rather than one.** Each answers a different question, and no two of
them can be substituted for each other:

- `crc32C` — "did these bytes survive the medium?" Cheap, in the write path, and a collision is not
  a security property anyone needs here.
- `stateHash` — "is this the state the file claims?" The number a run, a recovery and a fold
  compare, and the one an operator quotes.
- `walDigest` — "is this *our* log's history?" The layer that survives LSN reuse, a log restored
  from a different directory, and a checkpoint copied between ledgers.

## 6. Refusal is a fallback; deletion is free

**The deletion theorem.** A checkpoint is a cache of a fold of the log; the log holds everything the
checkpoint holds. Therefore *deleting any checkpoint cannot lose money*, and neither can refusing to
use one. The loader walks candidates newest first, records every refusal, and takes the first file
that stands against the log; if none does, the caller folds the log from byte 0 and loses nothing but
time. The contract asserts the theorem the only way that means anything: it deletes every checkpoint
and requires the recovered state hash to be the same number, and the boundary harness's recovery
comparison includes a reopen with the checkpoint directory emptied.

**Retention: keep the newest two, by default.** One to use and one to fall back to. A single retained
file would mean one torn or bit-flipped checkpoint forces a full fold; a dozen would be a directory
growing without a bound. `retain` is a policy value, and the argument for its default is exactly the
argument in the paragraph above: a refusal is only cheap if there is a second file to try.

**Retention needs no atomicity, and that is a property of the files rather than a shortcut.** Every
file retention deletes is a cache of a fold the log can reproduce, so a crash that resurrects one
leaves a directory with one extra valid checkpoint, and recovery picks the highest watermark and
never notices. There is no fsync after the deletes, and there is no ordering to get wrong. The one
ordering that *is* required — the new file's name must be durable before the old files may go — is
step 6 of §5.

**Stale temps are garbage with a known shape.** A `.tmp` file is never a checkpoint, is deleted by
the next swap before it is opened, and is deleted again by collection. A crash inside the swap
therefore leaves at most one temp file, and the next swap removes it. The boundary harness kills a
child inside each of the five stages precisely to keep this claim honest: at every stage, recovery
must find a valid file or an older valid one, and never a torn file that is selected.

## 7. What it costs

**The file's size** is `72 + 36 + 13 + Σ(2 + kindName + id + 8)` bytes — the 108 bytes of envelope,
the 13-byte section prefix, and roughly 24 bytes per account at realistic ids. Measured on this build:

| State | File | Log covered |
| --- | --- | --- |
| 2 accounts, watermark 3 | 167 bytes | 126 bytes |
| 2,000 accounts, 22,000 records | 47,011 bytes | 1,238,706 bytes |
| 20,000 accounts, 21,000 records | 489,011 bytes | 686,689 bytes |

**The write cost** is one read of the covered prefix (the binding digest), one write of the state,
two forces, one rename and one directory sync — O(covered bytes) + O(state). Measured on this
sandbox: 73 ms for the 2,000-account case, 93 ms for the 20,000-account one. That is why the default
cadence is per 1,024 commits rather than per commit: every commit would make a ledger O(log) per
commit, and the ack path must not pay a digest it does not need.

**The recovery cost, stated honestly.** A checkpointed reopen still scans the log and still hashes
the covered prefix, because the binding digest must be re-derived to be trusted — so it is not
asymptotically cheaper than folding. What it saves is the per-record work of decode, validate, post
and allocate, replaced by a bulk hash; and what it *bounds* is how many records a recovery must fold
at all. Measured, same build and machine:

| Ledger | Reopen with a checkpoint | Reopen from byte 0 |
| --- | --- | --- |
| 2,000 accounts, 22,000 records | 114 ms (replayed 0) | 169 ms (replayed 22,000) |
| 20,000 accounts, 21,000 records | 134 ms (replayed 0) | 128 ms (replayed 21,000) |

The second row is the finding worth keeping: **a checkpoint does not automatically win.** When the
state is large relative to a short log, loading it (hash the covered bytes, decode the state) can cost
more than folding the log would have, and this ADR says so rather than quoting only the favourable
row. The checkpoint's reasons to exist here are (1) the *proof* — a state hash bound to exact log
bytes, at a watermark, comparable across a run and its recovery, and (2) a *bound* on the tail a
recovery folds, which is what rotation will need before it can discard anything. Speed at small
sizes is not a claim this design makes.

**Why SHA-256 rather than the CRC the log uses.** The two questions differ. A CRC answers "did these
bytes survive"; it is cheap, it is in the write path, and a collision is a curiosity. A digest here
answers "is this the same state", across processes, machines and builds, and it is the number a human
copies out of a log line — so it is the standard hash, 64 hex characters, available from
`MessageDigest` with no dependency. The price is real: SHA-256 runs at roughly a tenth of CRC32C's
speed, and it is paid once per checkpoint, off the ack path, and again once per recovery.

**The cadence arithmetic.** Default 1,024 commits bounds a recovery's fold at 1,024 records. The
covered prefix keeps growing with the log, because nothing rotates yet — so the *verification* cost
of a checkpointed recovery grows with the log's size even as the fold is bounded. Rotation is the
answer to that, and ADR 0003 deliberately left it unstarted; §11 records the order it must follow.

## 8. The boundary harness: what it proves, and what it cannot

**Every prefix of a seeded history, five hash comparisons and three structural checks, three
configurations.**
`LsnBoundaryHarness` (and its child, `LsnBoundaryTarget`) takes a history from the same generator the
property suite uses, runs it in a child JVM, and for every prefix *k* of that history:

1. the child is killed with `kill -9` at the instant after the *k*-th commit returns, and prints its
   own state hash first (`LIVE`), which is the "run's final hash" the ticket names;
2. a **golden run** — a separate, uninterrupted process — records a hash after every operation, and
   the crashed run's hash at the boundary must equal the golden run's hash at the same prefix;
3. the crashed log must be a **byte prefix** of the golden log;
4. a fresh ledger is opened over the crashed directory, and its hash must equal the same number,
   with the checkpoint store in play (and the harness requires a checkpoint to be *used* whenever one
   survived the crash, with zero refusals);
5. a **full replay from scratch** — the harness folds the log with `Replay.fold` and hashes the
   result — must equal the same number again; and
6. every checkpoint is deleted and the directory reopened: the same number, the deletion theorem.

Then it keeps writing: the recovered ledger is handed the rest of the history and its hash must
track the golden run's, operation for operation, to the end. Recovery that lands on a state that is
not a legitimate continuation — a re-used LSN, a lost record, a checkpoint applied to the wrong
prefix — shows up there as a divergence rather than as a plausible-looking number. Configurations:
per-commit with no checkpoints; group commit with a checkpoint every commit; no-fsync with a
checkpoint every third commit (retaining three). That crosses the fsync menu with the cadence menu,
including the policy under which the covered bytes would otherwise never be forced.

**Killing inside the swap.** A boundary crash cannot test the swap, because at a boundary no swap is
in flight. So for each of the five `CheckpointStage`s the harness kills the writer *inside* the swap,
at three arms (0%, 50% and 90% of the history): before the temp is forced, after it, after the
rename, after the directory sync, after collection. The child reports the covered state's tail
(hash, watermark, offset) on its way out so the parent can map the instant to a prefix, and recovery
must find that state — from the previous checkpoint and the tail when necessary. The stage list lives
in the main source rather than in the test because it is instrumentation an operator wants anyway
(how long a checkpoint spends being written versus waiting for a device), and it is the honest way to
test an *ordering*: a kill at any stage must leave a directory in which recovery finds a valid
checkpoint or an older valid one, and never a torn file that is selected.

**The kill is real.** The child spawns `kill -9` against its own pid — the JDK refuses
`ProcessHandle.destroyForcibly()` on the current process, and a signal from outside is the honest
model anyway: no shutdown hook runs, no `finally` executes, nothing is flushed that a crash would not
have flushed. A child that could not send the signal prints `HALTING` and is not counted as killed,
so a harness running somewhere without `kill(1)` says so instead of quietly testing a clean exit.

**Results on this build** (`./build.sh boundary`, default 16 operations, seed 20260918):

```
PASS 81/81 boundary cycles byte-identical
  boundary harness: 81 cycles, 81 SIGKILLs, 81 recoveries
  17 prefixes per configuration, plus 15 stage kills
  recoveries folded 125 tail record(s) in total
  56 recoveries used a checkpoint
```

**The checkpoint contract suite** (`./build.sh`, `PASS 12/12 checkpoint checks`) asks
the questions a random kill cannot be relied on to hit: the file's bytes at every offset; each
integrity layer producing its own named refusal (including a state section re-hashed and re-CRC'd so
that only the state's own version check can refuse it); a renamed file refused by name; a truncated
log refusing a checkpoint as `COVERAGE_GONE`; two histories at one watermark told apart only by the
log digest; a corrupt newest falling back to the older one with exactly one record folded; retention
and stale-temp deletion; the swap's stages in declared order; the state hash's sensitivity to
opening order; a restored ledger auditing and continuing; and the ticket's central equality at one
watermark, three ways.

**Stated plainly, what this does not prove.** No power-loss test on real hardware: the harness kills
a process, so the page cache survives, and inside the swap the kill is a *logical* instant at a
stage boundary rather than a byte-level interleaving of the writer. No lying controller, no
out-of-order device, no `fsync` that returns early. No multi-process concurrency: the commit lock
makes a checkpoint a single-writer affair here, and a future multi-writer design would need the
state's construction reconsidered. No rotation, and therefore no proof about what happens after the
log's covered prefix is truncated — that is §11's inheritance. And single-node checkpointing is not
replication safety.

**Two things the harnesses earned while this was being written.** The child could not kill itself:
`ProcessHandleImpl` answers "destroy of current process not allowed", so the first version of the
harness reported 51 failures that were all its own; the fix is an external `kill(1)` plus a
`HALTING` line so the failure mode can never again be mistaken for a durable-write bug. And the
stage sub-harness's "after zero operations" arm never fired: the child armed the listener after an
operation, so zero could not arm it, and the first full run reported ten stage kills (five stages
across the two checkpointed configurations) that had simply not happened. Both are recorded rather than tidied away because they are the exact shape of the bug this
ticket exists to prevent: a harness that green-lights something it never tested.

## 9. Consequences

**The domain gained a restored base, deliberately.** `InMemoryLedger.restore(Account, Money)` seeds
accounts with opening balances, `foldBalances()` starts from that base, and `audit()` is now "the
index equals what the log opened plus what was restored, and every account agrees" — so a ledger
that starts from a checkpoint is auditable in the same sense as one folded from byte 0, and a
checkpoint cannot quietly drop an account the log knew about. This is the only domain change this
ticket made, and it is the shape any future piece of state will need.

**`DurableLedger` grew a recovery path and a cadence**: `open` takes a `CheckpointPolicy`; recovery
restores the newest standing checkpoint and folds only the tail; `stateHash()`, `state()`,
`checkpointLoad()` and counters are observable; an automatic checkpoint that fails is *counted*
rather than thrown, because the money is already durable and the checkpoint is not the money; an
explicit `checkpoint()` throws.

**`Wal.forceData()` became public.** ADR 0003 §10's ordering rule needs a way to force the covered
bytes before the snapshot is written, and under `NO_FSYNC` nothing else would ever force them. The
call had to be added to `Wal`, and it is the single line that discharges the hand-over.

**`JournalDigest` stays test-only.** It hashes accounts, events and balances for the domain suite's
own purposes, and its javadoc says explicitly that it is not the durable state hash; §3 here is the
definition it is not.

**The map's *Not yet specified* item "The replay state-hash scheme — how *byte-identical* is
canonicalized" is cleared** by §3 and §4: the canonical form is specified, the digest is specified,
and the equality is swept by the harness rather than asserted.

**What other tickets inherit.**

- *What are ledger-x's idempotency semantics?* — the key table becomes part of the canonical state
  under §4: emitted in a fixed order (the log's order of insertion), with no wall-clock value inside
  the hash, and with the checkpoint's `restore()` seeding it alongside accounts. An expiry stored as
  an absolute instant would move the hash without a commit; derive it from the log or leave it out
  of the hashed form.
- *How fast is it, honestly?* — §7 is the arithmetic to measure against: O(covered) + O(state) per
  checkpoint, a bounded tail fold, and the honest second row of the recovery table.
- *What must TLC prove?* — the replay predicate is now a function:
  `stateHash(replay(log[0..n])) == hash recorded by the run at n`, and the checkpoint safety
  property "a used checkpoint's `walDigest` equals the digest of the log prefix it was taken over".
- *What breaks it?* — the chaos fuzzer's checkpoint fault set is named by the refusal list: tear the
  file, flip a byte, rename it, truncate the log under it, copy it between directories. Each has a
  defined outcome, and the contract already exercises them.
- **Rotation** (still unstarted) — a checkpoint is the precondition for truncating the log's covered
  prefix, and the order is the one ADR 0003 handed over: snapshot, then directory, then truncate.

## 10. Alternatives considered

**Snapshot as a WAL record type.** Rejected: a snapshot must be written only after its covered bytes
are forced, and a record is behind the ack machinery that makes that ordering expressible but not
free; worse, the log's scan would have to interpret state to stay offset-based, and the log's own
file would stop having an exact length. The separate file is what lets recovery choose a different
point in time without changing the format of the thing it recovers from.

**The event list inside the checkpoint.** Rejected: it would make the checkpoint a copy of the log
rather than a cache of a fold, unbounded in the same way the log is, and it would leave *two*
histories to keep in sync. §3 says what covers the entries instead.

**CRC32C as the only digest.** Rejected: a CRC detects damage; it is not an identity. The number the
run, the recovery and a human compare has to be stable across processes and builds.

**No `walDigest`, watermark only.** Rejected: ADR 0003 §2 reuses LSNs after a cut, so one watermark
can name two histories. The contract's same-watermark/different-bytes check is the counter-example
that keeps this decision honest.

**Content-addressed file names** (the state hash in the name). Rejected: recovery needs "the newest
file that stands", and a name that changes with content cannot be ordered by watermark — and any
future re-derivation of a checkpoint would rename it, which is a directory mutation for a cache.

**Keep checkpoints inside the WAL file.** Rejected: the log is append-only with an offset-based scan,
and a snapshot inside it would make recovery's scan depend on one record's semantics; a separate
file is also what makes the swap's atomicity a rename rather than a format change.

**Trust the directory listing's order.** Rejected: no filesystem promises `readdir` order, and a
recovery that depended on it would be nondeterministic in exactly the way this ticket exists to
remove.

**One retained checkpoint.** Rejected: a refusal is only cheap when there is a second file to try;
the default of two is "one to use, one to fall back to".

**Time-based retention or cadence.** Rejected on §4's rules: a wall clock in the policy would make
both *when* a checkpoint exists and *what* it contains depend on when the process ran.

## 11. What this ADR hands across

To **rotation and WAL retention** (ADR 0003's deferred item, still unstarted): the precondition now
exists. The order is snapshot → directory sync → truncate, never the reverse; a log may not discard
bytes a checkpoint's `walDigest` covers until that checkpoint is durable, and must refuse a
checkpoint whose coverage it has discarded (`COVERAGE_GONE` is the same check, run from the other
side).

To **idempotency semantics**: the ordering rule of §4, and the expiry constraint of §9 — no
wall-clock value inside the hashed state.

To **the chaos fuzzer**: a checkpoint fault set with defined outcomes — tear it, flip it, rename it,
truncate the log under it, copy it between directories — and the boundary harness as a reusable
oracle.

To **the TLA⁺ spec**: the replay equality as a function, and the checkpoint safety predicate above.

To the **map**: the *Not yet specified* item on the replay state-hash scheme is struck, and nothing
new is unblocked by this ticket — the fog it sharpened lands as rules inside tickets that already
exist, which is where a rule belongs.
