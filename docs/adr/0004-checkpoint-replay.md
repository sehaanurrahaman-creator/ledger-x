# ADR 0004 — Checkpoints and byte-identical replay: a canonical state, a SHA-256 over it, and a snapshot that is a cache rather than a truth

- **Status:** Accepted — 2026-09-17
- **Ticket:** [How is state proven identical after a crash? Checkpoints and byte-identical replay](https://github.com/sehaanurrahaman-creator/ledger-x/issues/6),
  a child of the [Wayfinder map](https://github.com/sehaanurrahaman-creator/ledger-x/issues/1), unblocked by
  [ADR 0003](./0003-wal-fsync.md)
- **Decides:** what "byte-identical state" means, as a byte layout and a digest; the checkpoint file
  format and its two integrity layers; the atomic swap protocol and the crash window at each of its
  steps; the retention rule; the three checks a checkpoint must pass before recovery trusts it, and
  why a checkpoint that fails them is discarded while a log that fails them is an outage; when a
  checkpoint may be written at all; and the determinism rules — what is banned from state, and why
  each ban is load-bearing.
- **Does not decide:** segment rotation and log truncation, which is what would make a checkpoint
  load-bearing rather than an optimisation (*How fast is it, honestly?* inherits the arithmetic);
  idempotency keys and their place in the canonical form
  ([*What are ledger-x's idempotency semantics?*](https://github.com/sehaanurrahaman-creator/ledger-x/issues/7)
  — this file hands it a section and a rule); per-account ordering and the shape of the commit
  critical section (*How do a payout and a refund to the same account never interleave?*); an
  incremental or Merkle state hash, priced and deferred in §6; and the TLA⁺ spec, which this file
  hands a state function rather than a state variable.

## 1. Decision

| Question the ticket left open | Decision |
| --- | --- |
| What does "byte-identical state" mean? | A **canonical serialization** of the materialized state — one row per account, `(id, kind, balance)` — sorted **by id**, fixed-width, length-prefixed, no delimiters, and **nothing positional in it**. |
| How is it hashed? | **SHA-256 over exactly those bytes**, 32 bytes / 64 hex characters. The checkpoint file's payload *is* the hash preimage, so one function serves both. |
| Checkpoint format | `u32 magic "LCKP"`, `u8 version`, `u8 flags`, `u16 headerSize`, `u64 lastLsn`, `u64 walBytes`, `u64 journalEvents`, `u32 payloadLength`, `u8[32] stateHash`, payload, `u32 crc32C` — **68 bytes** of header. |
| Atomic swap | Serialize whole → write scratch in the **same directory** → `fsync` the file → `rename` **atomically** over the live name → `fsync` the **directory**. There is no instant at which the live name points at a partial file, and none at which there is no live file. |
| Retention | **One live checkpoint**, replaced in place. Garbage collection is unlinking orphan scratch files. The log is never truncated for a checkpoint's sake, so replay-from-scratch never depends on one. |
| Recovery | Checkpoint + tail, where the tail is "every record with an LSN above the watermark" — and the watermark is **checked against the log** rather than believed. |
| A checkpoint that fails any check | **Discarded, and the whole log folded.** A checkpoint is derived data; the log is the truth. Damage to a cache is a miss, damage to a truth is an outage. |
| When may a checkpoint be written? | Only when **memory and the durable log agree on how far they go**. Under `NO_FSYNC` that stops being true at the first append, so that policy never snapshots past the prefix its own open forced. |

Everything below is the reasoning, including the two findings this ticket's own harness produced and
the one existing test it broke.

## 2. The state hash, and the determinism rules it exists to enforce

The charter's claim is that replaying the log after a crash yields **byte-identical** state. As
written that is not testable, because "state" has no bytes: a Java object graph has identity hashes,
`HashMap` order, and a `toString` nobody froze. The first decision is therefore not a data structure
but a **definition** — a canonical form with a digest over it — and everything else in this ticket is
consequences.

```
canonical state bytes                              37 bytes for the pinned example below
+0   u8   canonical version (1)
+1   u8   domain tag 'S' (0x53)
+2   u16  reserved (0)
+4   u32  account count
+8   one row per account, ascending by id:
       u8   id length (1..64)
       u8[] id bytes, 7-bit ASCII
       u8   AccountKind ordinal
       i64  balance, signed minor units, big-endian two's complement

stateHash = SHA-256(those bytes)
```

A ledger holding `alpha ASSET −12345` and `beta LIABILITY +12345` serializes to

```
0153000000000002 05 616c706861 00 ffffffffffffcfc7 04 62657461 01 0000000000003039
```

and hashes to `0a4815c507a759141b878e5e9640e7d4a340b37e1199e07d3bc2ad0ecbc4ff19`. Both are pinned in
`CheckpointContract` and the digest was checked independently of this codebase — the same 37 bytes
through `hashlib.sha256` give the same 64 characters — so the pin is a fact about the encoding and
not a transcript of what the code printed.

### What is banned from state, and why each ban is load-bearing

The ticket asked for this list explicitly, and it is worth being precise, because every item is
something a reasonable implementation would have included.

1. **No wall-clock timestamps.** Inherited from ADR 0002's `Transaction`, and the reason is the same:
   a clock read inside state makes the same log produce different state on a second run, so
   "byte-identical" would be false by construction. This also answers standing design-review
   question 5 for state as it does for the log — *there is nothing for two clocks to disagree
   about*, because there is no clock.
2. **No map-iteration order.** The rows are sorted **by account id**, and this one is subtler than it
   looks. The obvious order — account-opening order — is *not* a function of the state: a ledger
   restored from a checkpoint opens the checkpointed accounts first and the tail's accounts after,
   while a from-scratch fold opens them in log order. Two paths through the same history would
   serialize the same money in two different orders and hash differently. Sorting by id is the
   cheapest total order that is a function of the state alone. `AccountState.CANONICAL_ORDER` is the
   only order state is ever emitted in, and `InMemoryLedger.state()` sorts before it returns, so
   there is no second order to disagree with the first.
3. **No random or generated ids.** Also inherited: ADR 0002 refused a transaction id, and
   `AccountId` is caller-chosen. A generated id would enter the canonical form and change on every
   replay.
4. **No floating point.** ADR 0002 made money integer minor units and `scripts/lint.sh` fails the
   build on a `float` or `double` under `domain/`. A `double` would make the canonical bytes depend
   on a formatting routine; an `i64` two's-complement field cannot.
5. **Nothing positional — no LSN, no offset, no record count, no timestamp of the snapshot.** This
   is the ban this ticket adds, and ADR 0003 §2 is why: a log's LSNs are unique only *within a
   surviving prefix*, so a cut and a regrow hand the same numbers to different records. A hash that
   mentioned position would change when the money did not. The corollary is a definition worth
   keeping: **state is what is left when you forget how you got there.**
6. **No delimiters, and therefore no escaping scheme.** Fields are length-prefixed, which works
   because `AccountId`'s alphabet already excludes every character a textual encoding would need to
   quote. An escaping scheme is exactly where two implementations of "the same" string start to
   disagree, and a replay that disagrees by one byte is not a replay.

### What the hash covers, and what it deliberately does not

It covers **materialized state**: accounts, kinds, balances. It does **not** cover the event list,
and it cannot: a checkpoint's whole purpose is to let recovery skip events, so a hash over every
event ever appended could only be reproduced by a checkpoint that stored every event ever appended —
which is not a checkpoint. The log's own bytes are proven separately, by ADR 0003's record format
and its replay check; what this ticket adds is the proof that the state those bytes *mean* comes
back identically.

That has one consequence worth stating plainly rather than burying: **after a checkpointed recovery,
`InMemoryLedger.events()` holds the tail, not the history.** The history is the WAL. This is not a
new idea — the event list was always a projection of the log — but checkpointing makes it observable,
and it broke an existing test (§8). Anything that treats the in-memory event list as the ledger's
history is now wrong, and the state hash is what replaces it.

## 3. The checkpoint file

```
+0   u32  magic "LCKP"                 0x4C434B50
+4   u8   checkpoint format version    1
+5   u8   flags                        0
+6   u16  header size                  68
+8   u64  lastLsn                      every record up to here is folded in
+16  u64  walBytes                     the WAL offset that record ends at
+24  u64  journalEvents                journal events folded, openings included
+32  u32  payload length
+36  u8[32] stateHash                  SHA-256 of the payload
+68  payload                           the canonical state bytes, verbatim
+68+payloadLength  u32 crc32C over [0, 68+payloadLength)
```

The fixture in §8 is 106 bytes for four accounts: 68 header + 34 payload + 4 CRC.

**The payload *is* the hash preimage.** That is the design's central convenience and it is
deliberate: SHA-256 over the bytes after the header must equal the digest inside it, computed by the
same `StateHash` a live ledger hashes itself with. So "the file says X and the code computed Y" is
not a disagreement between two encodings — there is only one encoder, and the file is its output.

**Two integrity layers, because they answer different questions.** The CRC32C covers header and
payload and detects a torn or scribbled file — the same job it does in a WAL frame, at the same
cost. SHA-256 covers the state alone and detects a writer that stored the *wrong state*, which no
CRC can see, because a CRC over the wrong bytes is a perfectly good CRC. The contract suite tests
exactly that split: a balance moved by one minor unit with the CRC recomputed is internally
consistent and only the digest catches it.

The header's own fields need no third layer. `lastLsn` and `walBytes` are checked against the log
itself at recovery, which is a stronger check than any checksum, and `journalEvents` is
cross-checked against the fold.

**What is deliberately absent.** No timestamp — a clock read inside a checkpoint would make two runs
of the same history produce different files, and the harness compares checkpoint *bytes*, not only
hashes. No host name, no process id, no generation counter: nothing that is true of the writer
rather than of the state. And no copy of the log's bytes, which is what makes it a checkpoint.

## 4. The atomic swap, and the crash window at each step

1. **Serialize the whole file in memory.** There is no partial-state window inside the writer
   because there is no partial write: the bytes are complete before any of them are on disk.
2. **Write them to `checkpoint.<lastLsn>.tmp` in the same directory, then `fsync` it.** Same
   directory because `rename(2)` is atomic only within one filesystem, and a cross-device "rename"
   is a copy — which has exactly the half-written window this protocol exists to prevent.
   `fsync` and not `fdatasync`: the rename is about to publish this file's *name and size*, which is
   metadata.
3. **`rename` it over `checkpoint.ckpt` with `ATOMIC_MOVE`.** POSIX `rename(2)` is atomic with
   respect to other readers of the directory.
4. **`fsync` the directory**, which is what makes the rename itself durable.

What a crash at each step leaves, which is the actual content of "atomic":

| crash point | live checkpoint | garbage |
| --- | --- | --- |
| during 1–2 | the previous one, whole | an orphan scratch file |
| after 3, before 4 | either generation, whole — the directory entry may or may not have landed | none |
| after 4 | the new one, whole | none |

There is no instant at which the live name points at a partial file, and no instant at which there
is no live file at all. That is why a checkpoint needs no generation counter, no manifest, and no
"which of these two files is current" rule: the name is the pointer, and the rename moves it.

**The ordering rule ADR 0003 handed here is enforced at the call, not in the writer.** A snapshot may
only be written *after* the WAL bytes it covers have been forced. `CheckpointWriter` cannot check
that — it is handed bytes and told they are true — so `DurableLedger` keeps two numbers, the LSN of
the last record applied to memory and the LSN of the last record a completed force covers, and
refuses to snapshot when they disagree:

```java
if (appliedLsn != forcedLsn) {
  throw new IOException("memory holds LSN " + appliedLsn + " but only LSN " + forcedLsn
      + " is durable; a checkpoint of unforced records is a promise the log cannot keep…");
}
```

Under `PER_COMMIT` and `GROUP_COMMIT` they cannot disagree, because every applied record was acked
by a completed force. Under `NO_FSYNC` they disagree from the first append, which is why that policy
never snapshots past the prefix its own open forced — and note what is *not* refused there:
`Wal.open` forces the prefix it recovered, so after a reopen that prefix genuinely is durable and
snapshotting it is sound. The rule is not "no-fsync never checkpoints"; it is "never past the last
completed force".

§8 records what happens without that guard, measured rather than argued.

## 5. Retention: one checkpoint, and why that is the rule rather than an omission

There is no `checkpoint-N.ckpt` series. The rename replaces the previous generation, and the only
garbage this format can produce is a scratch file from a crash between steps 1 and 3, which
`collectScratchFiles` unlinks.

The ticket's phrasing was "old checkpoints garbage-collectable without breaking replay-from-scratch",
and the honest answer here is that **replay-from-scratch does not depend on a checkpoint at all**,
because this repository never truncates the log: ADR 0003 §11 leaves rotation and retention
unstarted, and nothing in this ticket changed that. So a checkpoint is a pure optimisation, GC is
trivially safe, and deleting every checkpoint on disk costs time and nothing else — which the
contract suite asserts rather than claims.

Keeping N snapshots was considered and refused. It would buy a faster recovery from the rare "the
newest checkpoint is corrupt" case, at the cost of a selection rule, a garbage-collection rule, and
N times the crash window — and the thing it buys is already correct-but-slow, because a cache miss
folds the log. **The trade changes the day the log gets rotated**, because then replay-from-scratch
means reading segments that no longer exist, and a checkpoint stops being a cache and becomes a
load-bearing part of the truth. That is the trigger, and it is recorded here so the rotation ticket
finds it rather than rediscovering it.

## 6. Recovery: three checks, and an asymmetry with ADR 0003

Recovery is checkpoint + tail, and the tail is defined by one number: a checkpoint that survives its
checks says "everything through LSN *k* is folded in, and it ends at byte *b*". Recovery folds
exactly the records with an LSN above *k*. Because ADR 0003 makes LSNs dense from 1 within a
surviving prefix, "above *k*" and "past byte *b*" are the same set — and recovery *checks* that they
are, which is what turns two numbers into a watermark.

The three checks, in order, each with the failure it exists for:

1. **The file must parse and self-check** — magic, version, header size, a payload that fills exactly
   what it claims, CRC, SHA-256. A checkpoint written by a crash or a bad device fails here.
2. **The state must be a possible one.** Σ balances = 0 is invariant (ADR 0002), so a baseline that
   does not sum to zero describes a ledger this log could not have produced.
3. **The watermark must still be true of the log.** The claimed offset must not be past the log's
   clean prefix, the claimed LSN must not be past the last surviving record, and — the load-bearing
   one — **the claimed offset must be exactly where that record ends *now***.

That third check is the one this ticket would have shipped without, and it is the only thing standing
between a checkpoint and resurrection. ADR 0003 §2 warns that LSNs are unique only within a surviving
prefix. So: run A appends LSNs 1–10 and checkpoints at 10. A crash tears the tail at 6; recovery cuts
and appends a marker at 7; the writer goes on to LSN 13. The stale checkpoint now passes both the
byte test (the file is longer than it claims) and the LSN test (13 ≥ 10) — and using it would fold
LSNs 11–13 on top of a state that still contains the *old* 7–10, resurrecting transactions recovery
had already discarded. Comparing the claimed offset against the current end offset of the record it
names catches it exactly, at the cost of one array the scan was already computing.

To make that check possible, `WalRecovery.Scan` gained `endOffsets`, the byte offset just past each
record. A scan already walks every one of those offsets, so it costs an array and no I/O.

**The asymmetry with ADR 0003, stated because it looks like an inconsistency.** A damaged *log* is
either a repair or a refusal, and a refusal is an outage. A damaged *checkpoint* is a cache miss. The
difference is not taste: the log is the only record of what was promised to a client, so damage to it
is a choice between a receipt and some money, while a checkpoint is derived data whose every claim
the log can still be asked to re-derive. Folding from byte 0 is expensive and correct; guessing is
cheap and might be money. So every one of the eleven `CheckpointRejection` causes ends in the same
action — discard, fold, and say which — and the cause is reported so an operator and a harness can
count misses separately from staleness, which is evidence of a bug rather than of a crash.

Nothing here repairs a checkpoint. There is nothing to repair it *from* except the log, and the log is
what gets folded.

**What a checkpoint costs.** Not measured here — the benchmark ticket owns numbers — but the
arithmetic is: one SHA-256 over ~15 bytes per account plus a full state serialization at write time;
one file write, one `fsync`, one rename and one directory `fsync` per checkpoint; and at recovery,
one file read plus a fold of the tail instead of the whole log. The hash is computed on demand over
the whole state rather than maintained incrementally, which is O(accounts) per call. The scale answer
is a Merkle or rolling structure over the same canonical rows, and it changes nothing about *what* is
hashed, only what is recomputed — which is why it is deferred rather than designed here.

## 7. Alternatives considered

**A `CHECKPOINT` record type in the WAL, so the log names its own snapshots.** Refused, for a reason
ADR 0002 will recognize: it makes the log depend on derived data. The WAL would say "there is a
checkpoint at LSN *k*" and the checkpoint would say "I cover up to LSN *k*" — a cycle between a truth
and its cache, which is where staleness bugs live. ADR 0003's `RECOVERY_MARKER` is not a
counterexample: that record describes something recovery did *to the log*, which is the log's own
business. It also costs a format-version bump under ADR 0003 §7's rule that adding a type is a
version change, and buys nothing recovery cannot compute. The checkpoint file is self-describing and
the WAL never mentions it.

**Storing the event list in the checkpoint.** That makes the checkpoint a second log, and the state
hash would then have to cover events, which returns to the argument in §2: a snapshot that stores
every event saves nothing.

**A CRC32C of the WAL prefix as the staleness fingerprint** instead of the record-boundary check. It
would work — the scan already reads every byte, so a running CRC is nearly free — but it is
probabilistic where the boundary check is exact, and it needs new state in `Wal`. Exact beat cheap.

**Keeping the state hash out of the file** and recomputing it at load. That is what happens anyway
(the load re-hashes and compares); storing it as well is what makes the comparison possible.

**Hashing on every commit, incrementally.** Tempting, and deferred: an incremental hash over a
sorted map needs a structure that stays canonical under insertion, which is a Merkle tree by another
name. §6 prices the on-demand version honestly instead.

## 8. Evidence

Implemented in `src/main/java/dev/ledgerx/checkpoint/` (six classes: `StateHash`, `Checkpoint`,
`CheckpointWriter`, `CheckpointRecovery`, `CheckpointRejection`, `CheckpointFormatException`), wired
into `dev.ledgerx.journal.DurableLedger` and `dev.ledgerx.domain.InMemoryLedger`, and proved by
`src/test/java/dev/ledgerx/checkpoint/CheckpointContract.java` — **13 checks**, run by `./build.sh`
on every build.

The verdict lines from this sandbox, at the default campaign (40 histories × 24 ops):

```
PASS 5/5 substrate checks
PASS 19/19 wal checks
PASS 13/13 checkpoint checks
PASS 9/9 property checks
```

and at 10× (`LEDGER_X_CKPT_HISTORIES=400 LEDGER_X_CKPT_OPS=60 ./build.sh test`):

```
  ok   a crash at every lsn boundary reconstructs byte-identical state [26000 lsn boundaries
       across 400 histories, 3 recoveries each, 100% byte-identical (22400 from a checkpoint,
       0 discarded, 3600 absent, 33600 tail records replayed)]
PASS 13/13 checkpoint checks
```

**The harness the ticket asked for, and what it actually does.** For each of 400 random histories,
for **every** LSN boundary — every point a crash can land, including the empty log and the whole
history — cut the log there and then recover three ways: once with whatever checkpoint is on disk,
once more with the checkpoint the first recovery wrote, and once after deleting the checkpoint so
recovery must fold from byte 0. Each digest is compared with the digest of the same prefix folded
**in memory, with no files involved at all**. 26,000 boundaries × 3 recoveries, zero mismatches. The
`0 discarded` in that line is itself a claim: in an honest run a checkpoint is never stale, so every
discard the other checks produce is a hostile input and not a normal event.

The rest of the 13:

- **the state hash is a canonical encoding, not an ordering** — the same money reached by two
  different histories (accounts opened in reverse order, transactions in the other order, each
  transaction's entries listed the other way round) produces identical canonical bytes; the rows come
  out ascending; decoding and re-encoding reproduces the bytes; and a baseline of a state hashes like
  the state.
- **the state hash is exactly these bytes** — the 37-byte canonical form and its SHA-256, pinned,
  with the digest cross-checked against `hashlib.sha256` outside this codebase.
- **a checkpoint file is exactly these bytes** — magic, version, header size, both watermark fields,
  the payload length, the payload equal to the canonical state byte for byte, the header's digest
  equal to SHA-256 of the payload, the trailing CRC covering the file; a round trip through
  `decode`; and the same checkpoint written twice producing the same bytes.
- **a checkpoint names the log it belongs to, exactly** — `lastLsn` is the log's last LSN,
  `walBytes` is its clean prefix, `scan.endOffsetOf(lastLsn)` agrees, the event count matches the
  ledger's, and the file on disk is the checkpoint that was returned.
- **a checkpoint torn at any byte is a cache miss** — **all 145 truncations** of a 144-byte
  checkpoint, each one opening to the correct state and reporting a *damaged* cause rather than a
  stale one.
- **a checkpoint that lies is discarded, not believed** — six doctored files, each with the CRC
  recomputed so the check being tested is the one behind it: a balance off by one minor unit
  (`HASH_MISMATCH`), a scribbled digest field (`HASH_MISMATCH`), an LSN one short
  (`NOT_A_RECORD_BOUNDARY`), an offset one byte past the log (`STALE_WAL_BYTES`), an offset one byte
  short (`NOT_A_RECORD_BOUNDARY`), and a watermark past the end (`STALE_WAL_BYTES`).
- **a baseline that breaks Σ balances = 0 is refused** — a structurally perfect checkpoint whose
  balances sum to 1 minor unit is `IMPOSSIBLE_STATE`, and the log is folded instead.
- **a log cut and regrown cannot resurrect through a stale checkpoint** — the §6 scenario end to
  end: checkpoint at LSN 20, cut below it, regrow to LSN 26 with different transactions, put the
  stale checkpoint back. Discarded as `NOT_A_RECORD_BOUNDARY`, and the recovered state equals the
  from-scratch fold — the cut records stayed gone.
- **a damaged log head still refuses, checkpoint or not** — a scribbled segment header beside a
  valid checkpoint is still `BAD_SEGMENT_HEADER`, file untouched. A checkpoint must not soften ADR
  0003.
- **the log is never truncated for a checkpoint, and orphans are collected** — three checkpoints
  replace one file, the log's size never changes, two orphan scratch files are collected, and
  deleting the checkpoint changes nothing.
- **no-fsync refuses to snapshot records the log has not made durable** — 16 unforced records refuse
  a snapshot and write no file; after a reopen, whose prefix `open()` forces, a checkpoint at LSN 16
  is sound, is written, and is used.

**Four findings, in the order they cost time.**

*(1) The soundness guard is not decoration, and this was measured rather than argued.* With the
`appliedLsn != forcedLsn` check disabled, a `NO_FSYNC` ledger holding 16 unforced records happily
wrote `Checkpoint[lsn<=16 @byte 16, 16 events]` — claiming to cover 16 records at byte 16, which is
the *start* of record 1, not the end of record 16. A power cut then took the unforced tail, and
recovery discarded the snapshot as `STALE_LSN` and correctly recovered the empty state. So the guard
is the explicit refusal at write time and the staleness checks are the backstop at read time —
defence in depth, both halves now verified rather than assumed. Without the backstop the failure
would have been silent; without the guard it would have been late.

*(2) An existing test broke, and it was right to.* `WalContract`'s "replay is deterministic and
byte-identical" failed with `206 live, 206 and 0 replayed`. The cause is §2's consequence: the second
open used the checkpoint the first had written, so its in-memory event list held the empty tail. The
check was rewritten to compare what the claim is actually about — the log folded from byte 0 twice,
**and** the checkpoint-plus-tail recovery reaching the same state digest — which is strictly stronger
than what it asserted before. Recorded here because it is the kind of change that should fail a
build, and because the fix is a strengthening rather than a deletion: a future reader who finds the
check comparing digests should know it used to compare event lists, and why it stopped.

*(3) The harness's own first run was wrong in a way worth writing down.* Cutting the log to boundary
*k* and then to boundary *k+1* does not work, because `FileChannel.truncate` cannot grow a file:
after the k=0 cut the log was 16 bytes and every later "cut" was a no-op. The symptom is diagnostic
and is the reason the check is shaped this way — all three recovery paths agreed with each other
while disagreeing with the in-memory fold, so a harness that only compared recoveries would have
passed. The fix restores the intact log before each cut and asserts the cut took.

*(4) A fourth came from CI and not from this sandbox, and it is the same toolchain split ADR 0002 §11
and ADR 0003 §6 both recorded.* javac's `-Xlint:all` includes `-Xlint:try`, which reports
"auto-closeable resource `open` is never referenced in body of corresponding try statement", and
`-Werror` makes that a failed build. Three places in `CheckpointContract` opened a `DurableLedger`
purely for its side effect on disk — build a history, then close it — and never named the handle.
ECJ, at the flag set `scripts/bootstrap-toolchain.sh` uses, does not report it, so the local build was
green and CI's javac run was red on the first push. The fix was to reference the handles, which each
of those three sites should have been doing anyway: two now assert that the fixture built the event
count it claims, and the third names the ledger it is asserting should never have opened. Recorded
because it is the third time this repository has learned that **a locally green compile is evidence
about the code and not about CI's vocabulary**, and because the lesson keeps being the same one: the
sandbox's compiler is a fallback, and CI's javac is the check of record.

**What this does not prove.** No crash *during* the atomic swap on real hardware: the harness
simulates the outcomes (an orphan scratch file, a truncated checkpoint, a stale one) rather than
aiming a `SIGKILL` at the rename, which is what `./build.sh crash` does for the log and what a
checkpoint-specific chaos run would do here. No `O_DIRECT`, no `mmap`, no filesystem other than the
one this sandbox runs — `rename(2)`'s atomicity is POSIX, but a filesystem that lies about it is out
of scope by ADR 0001. No measurement: the cost table in §6 is arithmetic. And no rotation, so the
case where a checkpoint becomes load-bearing is untested by construction — §5 names it as the
trigger.

## 9. Consequences accepted

- **A checkpoint is a cache, and a cache miss is O(log).** With no rotation, every recovery can fold
  the whole log, so the worst case for any checkpoint failure is slow-and-correct. That is the
  property the retention rule spends, and §5 names what spends it back.
- **`InMemoryLedger.events()` after a checkpointed recovery is the tail, not the history.** Anything
  reading it as the ledger's history is now wrong; the state hash is the replacement, and the WAL is
  still the log. This is the one behavioural change in this ticket and it broke a test (§8).
- **`NO_FSYNC` never checkpoints past the prefix its own open forced.** Consistent — a checkpoint is
  a promise about durable bytes and that policy has none — but it means the policy that most needs
  fast recovery gets the least help. Recorded rather than papered over.
- **The state hash is O(accounts) per call and O(accounts) per checkpoint.** Fine for a prototype
  whose job is the proof; the Merkle answer is deferred, not designed (§6, §7).
- **`Scan` now carries an array of offsets.** One more field for every scan in the repository, spent
  entirely on the record-boundary check — which is the check that prevents resurrection, so the
  trade is not close.
- **Recovery reads the checkpoint file with `Files.readAllBytes`.** A checkpoint is bounded by the
  account count and this prototype's ledgers are small; a streaming reader is a benchmark-ticket
  change and would not alter the format.
- **Two integrity layers is arguably one too many.** The CRC catches tears and the SHA-256 catches
  lies, and a torn file would fail the SHA-256 too — but at 4 bytes per checkpoint, keeping the layer
  that names the *kind* of damage is worth more than it costs, because "torn" and "wrong" have
  different post-mortems.

## 10. What this changes for other tickets

- *What are ledger-x's idempotency semantics?* — the idempotency table **joins the canonical state**
  as another section under a new canonical version, not as a separate hash: a key that survives a
  crash in the log but not in the state is exactly the double-payout the charter is about, and two
  digests can disagree while each is individually correct. The row format is yours; the rules you
  inherit are §2's — sorted by `(merchant_id, key)`, no expiry *timestamp* in the hashed bytes unless
  the expiry is itself derived from log position, because a wall-clock expiry makes replay
  non-deterministic and question 5 comes back. Also: a checkpoint's payload is the hash preimage, so
  adding a section is a `CANONICAL_VERSION` bump and old checkpoints become cache misses, which is
  the safe direction.
- *How do a payout and a refund to the same account never interleave?* — the commit critical section
  now has one more thing in it: `checkpoint()` takes the same lock, because a snapshot taken while an
  append is in flight would claim a watermark the log has not reached. And the `appliedLsn`/`forcedLsn`
  pair is exactly the "ack order equals apply order" hook that ticket needs, already present.
- *How fast is it, honestly?* — the numbers this makes possible are per-policy checkpoint counts,
  checkpoint bytes, recovery time with and without a usable snapshot, and the SHA-256 cost per
  account. §6 is the arithmetic to measure against. The honest finding to report rather than smooth
  over: under `NO_FSYNC` the checkpoint column is zero, and that is the policy's promise and not a
  bug.
- *What must TLC prove?* — the spec now has a **state function** rather than a state variable:
  `StateHash(canonical(accounts, balances))`, and the property is `∀ crash points c:
  hash(recover(c)) = hash(fold(prefix(c)))`. The interesting lemma to model-check is §6's third
  check — that a watermark validated against the log cannot admit a stale snapshot — which is small
  enough to exhaust: a handful of records, one watermark, one cut, one regrow.
- *What breaks it?* — the fault set gains four entries, all now in the contract suite: truncate the
  checkpoint at any byte, scribble inside its payload, forge its watermark, and delete it. What a
  chaos campaign adds is *combinations* — kill during the rename, corrupt the checkpoint while the
  log is also torn — and the oracle is the state hash, which is the first oracle in this project that
  fits in 64 characters.
- *Segment rotation and retention* (unstarted) — §5's trigger: the day the log is truncated, a
  checkpoint stops being a cache and the retention rule has to become "keep enough checkpoints that
  the oldest surviving segment is always covered", with a garbage-collection order that removes
  segments only after the checkpoint that supersedes them is durable.

## 11. Fog graduated

The **replay state-hash scheme** comes off the map's *Not yet specified* — that is this ticket, and it
is now a byte layout, a digest and 26,000 checked boundaries rather than a question. Two things this
answer sharpened into rules for neighbours rather than fog of their own: where the idempotency table
goes in the canonical form (§10), and when a checkpoint stops being a cache (§5).

One item stays open and is now sharper rather than smaller: **the chaos fault set's payout half**, and
specifically the combinations this ticket's harness does not attempt — a kill aimed at the rename
rather than a simulated outcome, and simultaneous damage to the log and the snapshot. The storage
half of the fault set is now fully named and fully walked; the combinations are the chaos ticket's.
