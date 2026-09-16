# ADR 0003 — A durable write: 20 bytes of framing, three fsync policies, and a tail that is cut rather than skipped

- **Status:** Accepted — 2026-09-16
- **Ticket:** [What does a durable write look like? WAL record format, fsync policy menu, torn-tail rules](https://github.com/sehaanurrahaman-creator/ledger-x/issues/5),
  a child of the [Wayfinder map](https://github.com/sehaanurrahaman-creator/ledger-x/issues/1), unblocked by
  [ADR 0001](./0001-substrate.md) and [ADR 0002](./0002-domain-model.md)
- **Decides:** the segment header and record-frame layout; what an LSN is and why it is dense; which
  fields the CRC covers; the fsync policy menu and the promise of each entry; **the ack rule**; the
  torn-tail rules, including which findings are cuttable and which refuse to open; the payload
  encoding of the two journal events; the payload ceiling and therefore the entry-count ceiling; and
  what `kill -9` can and cannot prove.
- **Does not decide:** checkpoints, the durable state-hash scheme, segment rotation and retention
  (*How is state proven identical after a crash?*); idempotency keys and their storage (that ticket
  gets a record type beside these two, and this ADR hands it a constraint); locking, per-account
  ordering and backpressure (*How do a payout and a refund to the same account never interleave?*);
  the payout intent log; and the latency numbers (*How fast is it, honestly?* — this file supplies
  the arithmetic it measures against, not the percentiles).

## 1. Decision

| Question the ticket left open | Decision |
| --- | --- |
| Frame format | `u32 frameLength`, `u64 lsn`, `u8 type`, `u8 reserved`, `u16 payloadLength`, payload, `u32 crc32C` — **20 bytes** of framing around the payload, big-endian, the CRC over every one of them. |
| Segment header | 16 bytes: `u32 magic "LWAL"`, `u8 version`, `u8 flags`, `u16 headerSize`, `u32 reserved`, `u32 crc32C` — written once at create, fsynced with the directory entry. |
| LSN | A `long`, **dense from 1**: frame *n* carries *n*, and recovery stops where the sequence stops. Not derived from the byte offset, and not caller-supplied. |
| Type table | Closed: `ACCOUNT_OPENED`, `POSTED`, `RECOVERY_MARKER`, plus 0 which is *not* a type. An unmapped byte is a fatal finding, never a record to skip — which makes adding a type a version bump. |
| Checksum | CRC32C (`java.util.zip.CRC32C`, `@since 9`), over length, LSN, type, reserved and payload — not over the payload alone. |
| fsync menu | **Per-commit**, **group commit** (`maxRecords`, `maxDelay`), **no fsync** — as a per-commit *value*, not a process-wide mode. |
| Ack rule | A commit is acknowledged after the `force` that covers its bytes returns — its own under per-commit, its group's under group commit — and under no-fsync after `write()` returned, which is the only promise that policy ever makes. |
| Torn tail | Recovery cuts at the first **structural** failure (short header, dishonest length, frame past EOF, CRC mismatch, LSN gap) and records the cut. A complete, CRC-valid frame that this reader cannot honour **refuses to open**. |
| Payload ceiling | 64 KiB per record, which is the entry-count ceiling ADR 0002 handed here: **5,957 entries** at one-byte account ids, bounded by the frame rather than by the `u16` count. |

Everything below is the reasoning, including the two bugs this ticket's harnesses found and the
claims that came back against it.

## 2. The format, and why each field earns its bytes

```
segment header, 16 bytes                    record frame
+---------------------------+   +--------------------------------------------------+
| u32 magic "LWAL"          |   | u32 frameLength   = 20 + payloadLength           |
| u8  format version        |   | u64 lsn           dense from 1                   |
| u8  flags  (0)            |   | u8  record type                                  |
| u16 header size (16)      |   | u8  reserved (0)                                 |
| u32 reserved (0)          |   | u16 payloadLength                                |
| u32 crc32C  over [0,12)   |   | … payload                                         |
+---------------------------+   | u32 crc32C      over [0, frameLength-4)          |
                                +--------------------------------------------------+
```

**The length covers the whole frame, including itself and the CRC.** A reader can therefore find the
next record without parsing this one, which is what makes a scan a loop over offsets rather than a
negotiation with payloads, and it makes `payloadLength + 20 != frameLength` a *checkable*
inconsistency rather than a detail: two fields that must agree detect a class of damage that one
field cannot. `frameLength` is bounded by the format (`MAX_FRAME_BYTES`), so a corrupt length cannot
turn into a 2 GiB allocation during recovery.

**The LSN is dense, and that is the load-bearing choice.** A number that merely increases would let
recovery accept a log with a hole, and a hole is exactly the shape of the two failures this project
must tell apart — a record lost after an ack, and a record that a truncation failed to remove. Under
the dense rule both become *a tear at a known offset*: recovery stops, cuts, and the log is honest
again. It costs 8 bytes per record, which §6 prices, and it is not derived from the byte offset
because offset arithmetic cannot distinguish "the record at offset 4,120 is missing" from "that
record is a recovery marker and correctly has no event".

Dense LSNs also mean the number can be re-used after a cut — record 4 is appended after a tear at
record 4's bytes, so a log's LSNs are only unique *within a surviving prefix*, not across history.
That is deliberate, and §4 is the argument for why it cannot resurrect a transaction: the stale copy
is past the cut, and the cut is durable before the next append.

**The type byte belongs to a closed table.** Unknown means stop, not skip, so a reader that cannot
interpret a record cannot silently misapply it; the cost is that a writer with a newer table produces
a log the old reader refuses to open, which is the correct failure (a *refusal* is recoverable by
upgrading; a silent skip is not). Type 0 is reserved as "not a type" because 0 is what a zeroed byte
reads as — the value damage takes, not the value a writer emits.

**CRC32C, and over what.** `java.util.zip.CRC32C` is "A class that can be used to compute the
CRC-32C of a data stream", defined by RFC 3720 (iSCSI) with polynomial `0x1EDC6F41`, `@since 9`; its
bulk `updateBytes` carries `@IntrinsicCandidate`, and the Java fallback is *slicing-by-8* against
8×256 lookup tables rather than a bit loop
([`CRC32C.java`, openjdk/jdk21u](https://github.com/openjdk/jdk21u/blob/master/src/java.base/share/classes/java/util/zip/CRC32C.java)).
So the choice costs a table walk at worst and an instruction at best. It was chosen over Adler-32 —
which is what `java.util.zip` was born with — because Adler-32's weakness on *short* buffers is
exactly this format's regime (tens to hundreds of bytes), and a checksum that misses a two-byte
corruption in a 72-byte frame is a checksum that will be blamed for the corruption later. Which CPUs
HotSpot's intrinsic actually covers was **not** verified here; the fallback is, and the fallback is
fine, which is the property that matters for a correctness prototype.

The CRC covers the length, the type, the LSN and the payload — **all four**, not the payload alone.
This is the whole reason a torn tail can be detected rather than half-parsed: a scribbled length
field would otherwise tell recovery to skip 4 GiB, and a scribbled LSN would otherwise read as a
deliberate gap. A payload-only CRC is the design that turns one bad byte into an unrecoverable log.

**Reserved bytes instead of version fields per record.** One `u8 reserved` per frame and one `u32`
per segment, both required to read 0. A version *inside* every record would be a per-record field
that is the same in every record — dead weight — while the reserved fields buy growth with a
mechanism recovery already has: an unexpected value is a fatal finding, so a future format cannot be
half-read by an old build. `flags` exists and nothing sets it, so a flag this build does not
understand is a refusal rather than an accident.

**The payload ceiling is the entry-count ceiling.** `payloadLength` is a `u16`, so a frame holds at
most 65,535 bytes of payload — but the number that matters is what that buys: an entry is
`u8 idLength | id | u8 side | i64 minorUnits`, i.e. 10 bytes plus the id, so a transaction of
one-character account ids caps at (65536 − 2) / 11 = **5,957 entries**. ADR 0002 decided "n entries,
no ceiling at the domain level" and left the ceiling to this ticket; it is the frame, not the count
field, that sets it, and `WalContract` exercises 5,900 entries round-tripping through the codec.
Money stays a `long` of minor units and one currency with a constant exponent (ADR 0002 §2), so a
payload carries no currency code — the cost of that is recorded there, and adding one is the same
class of change as widening money to 128 bits: a format change, priced and deferred rather than
pretended otherwise.

**What a payload does *not* carry, and why, is half the format.** No LSN inside the payload (the
frame has it — a second copy would be a second source of truth about position, the argument ADR 0002
used to refuse a transaction id). No transaction id, no timestamp: nothing in the log reads a clock,
which answers standing design-review question 5 for the storage layer as *there is nothing for two
clocks to disagree about*. No idempotency key inside a transaction — key scoping, expiry and the
409-vs-200 trap are ticket
[*What are ledger-x's idempotency semantics?*](https://github.com/sehaanurrahaman-creator/ledger-x/issues/7)
and the key arrives as a record type in the same group as the posting, which is what "one unit of
commit" means here. `dev.ledgerx.journal.EventCodec` carries the full list with each reason.

## 3. Why fsync happens exactly where it does

Design-review question 2. The answer is five sentences and one table.

A `write()` is a promise to the kernel; an `fdatasync` is a promise from the kernel to the device;
and nothing in either is a promise about a controller's cache, which ADR 0001 already declared out of
scope. [`fsync(2)`](https://man7.org/linux/man-pages/man2/fdatasync.2.html) says it plainly: `fsync`
"transfers (\"flushes\") all modified in-core data … to the disk device … so that all changed
information can be retrieved even if the system crashes or is rebooted", the call "blocks until the
device reports that the transfer has completed", and — the sentence that decides the directory rule —
"calling fsync() does not necessarily ensure that the entry in the directory containing the file has
also reached disk. For that an explicit fsync() on a file descriptor for the directory is also
needed."

| Where | Call | Why here and not elsewhere |
| --- | --- | --- |
| create, before the first record | `force(true)` then `fsync(dirfd)` | A created file whose *directory entry* is not durable can vanish; an empty file with no header is indistinguishable from a create that lost its header, so the header is forced before anything is appended. Measured in `WalContract`. |
| open, after recovery | `force(true)` | This is what makes the *recovered prefix* durable before a single new byte is appended, including tail bytes a no-fsync predecessor left in the page cache. Without it, a log repaired by an old build under no-fsync could lose bytes this build has already folded into its ledger. |
| per commit group | `force(false)` = `fdatasync` | The promise a commit needs is data, not `mtime`. `fdatasync` is the cheaper of the two and the log's length is recoverable by scanning the tail — the property ADR 0001 traded on and this file now spends. |
| torn-tail truncate | `force(true)` | See the note below: the honest reading is that `force(false)` would do, and this is where the ADR declines to be clever about a non-hot path. |
| close | `force(true)` | A clean exit is not a crash: bytes the log holds are flushed rather than abandoned, so closing never discards what the writer wrote. |

**The truncate-and-`fsync` note, stated against ourselves.** The same man page says `fdatasync` "does
not flush modified metadata unless that metadata is needed in order to allow a subsequent data
retrieval to be correctly handled. On the other hand, a change to the file size (`st_size`, as made by
say `ftruncate(2)`), would require a metadata flush." So on Linux `force(false)` after a truncation is
*sufficient* — the choice to use `force(true)` is not. It is made because POSIX phrases the guarantee
as "needed for a subsequent data read", which leaves a filesystem free to treat a *shrunk* size
differently; because recovery is not a hot path, so the extra flush costs a prototype nothing; and
because ADR 0001's table already puts `force(true)` at open, close, rotation and checkpoint, and
consistency about the cold paths is worth more than one syscall saved on a path that runs once per
crash.

**One fsync per group, and a group that only forms when there is a queue.** The committer drains up
to `maxRecords`, writes them with one `write()`, issues at most one `forceData()`, then completes the
futures; callers park on the future, not on a thread. The batch window is capped by `maxDelay` *only
while more work is already queued* — and that restriction is a bug this ticket found rather than a
counsel of perfection. The first version waited out the window unconditionally, so the harness's
`group:64:5000` cycle (64 records or 5 s) took **90 seconds** for 24 commits: a lone writer submits one
append and blocks on its ack, so the queue is empty, so nobody arrives, so the committer sits in
`poll()` for 5 s and then writes one record anyway. A timeout-based group commit that parks the record
it already holds converts a throughput optimisation into a latency floor of the window size, per
commit. The fixed rule — collect while the queue has work, cut at `maxRecords` or `maxDelay`, cut
immediately when the queue is empty — is what the *benchmark* ticket needs to know, because it says
the batching win exists only where a queue exists, and the p50 column at 1 thread will show
`GROUP_COMMIT` and `PER_COMMIT` the same. Measured: 32 virtual threads × 8 appends → **256 frames, 5
forces**; 20 sequential appends → **20 forces**.

**Why the committer also assigns the LSNs.** A frame cannot be finished before its sequence number
is known, because the number is inside the CRC's coverage; so framing happens on the committer and a
caller prepares only its payload. This is a real per-commit cost the metrics ticket will see (one
`byte[]` allocation plus one `ByteBuffer` copy per group) and it is bought on purpose: the alternative
is a caller-supplied LSN, and a caller that can supply a number can supply a stale one.

**The pinning rule, inherited.** The writers are virtual threads and the lock the ledger takes is a
`ReentrantLock`, because ADR 0001's `synchronized`-pins-a-virtual-thread rule is permanent on this
substrate. The harness therefore runs every child with `-Djdk.tracePinnedThreads=full`, and the
parent treats any output containing `pinned` as a cycle failure — the rule is enforced by the crash
harness rather than trusted to review.

## 4. The torn-tail rules, and why cutting them is safe

The ticket's wording was "recovery stops at the first CRC failure and truncates — which is only safe
if nothing past that point was ever acked". Accepted, with the rule sharpened into three parts.

**R1 — Classify before you repair.** A scan's findings split by whether a *partial write* could have
produced them.

| Cuttable (`dev.ledgerx.wal.Tail`) | Fatal (`dev.ledgerx.wal.Corruption`) |
| --- | --- |
| `SHORT_FRAME_HEADER` — fewer bytes remain than a frame header | `BAD_SEGMENT_HEADER` — magic, version, size or CRC of the head |
| `BAD_FRAME_LENGTH` — a length the format cannot describe, or one it contradicts | `UNKNOWN_RECORD_TYPE` — a complete frame whose type is unmapped |
| `SHORT_FRAME` — a declared frame that runs past EOF | `PAYLOAD_MALFORMED` — intact bytes that do not decode |
| `CRC_MISMATCH` — the frame is there and is not what was written | `DOMAIN_REJECTED` — a posting the ledger refuses, at replay |
| `LSN_MISMATCH` — a hole, a repeat, or a rewind | *(raised as `UnrecoverableLogException`, file untouched)* |

Every left-hand finding can only be an unfinished write. Every right-hand finding requires a
*complete* frame whose CRC matches — which a torn write cannot produce, because the CRC covers the
length and the type as well as the payload. That asymmetry is the whole rule: **damage from a crash
can live only at the tail, so only the tail may be repaired; anything else is data.**

**R2 — Cut, and make the cut durable before appending.** Truncation is not cosmetic; skipping the
tear and appending after it would put new records *behind* a hole, where the next scan stops at the
hole and never reaches them — records acked, durable, and invisible forever. Cutting is also what
makes the next append's LSN the next number in the sequence rather than a re-use (§2), and what makes
recovery idempotent: a second scan of a repaired log reports `CLEAN_EOF`, which the harness asserts
after every cycle rather than assuming.

**R3 — The ack rule is what makes R2 safe, so state the lemma rather than the vibe.** *No record past
the last durable `force` was ever acknowledged.* The committer completes a future only after the force
whose range contains that record's bytes returned (§5), so every acked record lies at or below a
watermark that is itself already durable. Recovery cuts at the first *invalid* frame, which is at or
past the last valid one, which is at or past every acked record. So the cut discards a set of records
disjoint from the set ever acknowledged — which is the exact sense in which "no half-committed
transaction" is true here: not that a transaction is atomic in the log (it is trivially so, one frame
per transaction, whole thing under one CRC), but that **recovery can never be choosing between a
client's receipt and a client's money.**

The lemma has one honest loophole, and it is a policy's, not the format's: under `NO_FSYNC` an ack
means only "`write()` returned", so an acked-but-unforced record *can* sit past the watermark and be
cut. That is not a bug and it is not fixed by recovery — it is what that policy promises (§5), and the
harness measures it rather than hiding it: **284 acknowledged records lost across 100 power-loss
cycles**, with `0` lost under either forcing policy in all 1,000 cycles.

**The recovery marker is for the reader, not for correctness.** After a cut, recovery appends one
`RECOVERY_MARKER` frame — reason, bytes cut, records kept, last surviving LSN — forced whatever the
active policy says, because it is a statement about the log rather than a transaction. It is *not* a
resurrection guard: dense LSNs plus truncation already prevent that, and an ADR that credited the
marker with preventing it would be describing a mechanism it does not have. What it buys is that the
file is self-describing about its own repair — "the writer died at this byte" and "recovery cut here,
and here is why" are different stories, and the second is the one you want when a customer asks how a
transaction went missing. It is also where a future segment boundary will carry continuity across.

One semantic decision inside the marker is worth recording because the contract suite argued it out:
the marker reports the bytes **recovery cut** (the torn frame's remnant), not the bytes the crash lost,
which may be more. A log cannot report data it never saw, and a number that quietly mixes the two is a
number a post-mortem will misread.

## 5. The ack rule, and the menu it is relative to

`FsyncPolicy` is a value carried by the append, not a global read at commit time, because the ticket's
"pluggable per commit" is what makes one record's durability independent of its neighbours' — a
payout intent can demand a force inside a lazy log, and a diagnostic can waive one inside a forced
log.

| Policy | Ack waits for | Survives | Does not survive | What it is for |
| --- | --- | --- | --- | --- |
| `PER_COMMIT` | this record's `forceData` | process crash, OS crash, power cut | a controller that lied, a filesystem that ignores flushes | the correctness floor; the only mode where an ack's meaning does not depend on who else is writing |
| `GROUP_COMMIT(n, t)` | the ack of the *group* containing this record | as above | as above; also, at 1 thread, any throughput advantage | the default: same survival, /n the syscalls under load |
| `NO_FSYNC` | `write()` returning | process crash (the page cache outlives the process) | OS crash, power cut, anything that matters for money | the benchmark's third column, and the harness's negative control |

Two consequences of the middle row that are easy to miss and are the reason this table exists:
a group's members are acked together, so *one slow record's durability deadline becomes everybody
else's* (that is the p99 story, not the average), and a member appended with a no-fsync override in a
forcing log is completed **as soon as the bytes are written, before the group's force**, so a caller
that waived the promise is never made to wait for somebody else's. Forcing *more* than a policy asked
for is allowed in one direction only, and that direction is stated here rather than left to the code:
the committer issues `forceData` if any member of the group asked for it, and it never acks a member
earlier than its own mode permits.

**A group commit that also fixes the tail is not possible without another fsync, and we do not
pretend otherwise.** Nothing in this design gives an ack a *deadline*; the delay is a cap on
collection (§3). If ticket
[*How fast is it, honestly?*](https://github.com/sehaanurrahaman-creator/ledger-x/issues/13)
wants a bounded-latency promise per policy, it is a fourth menu entry, not a tuning knob.

## 6. Write amplification — the arithmetic behind standing design-review question 1

Bytes per *logical transaction*, from the format, not from a guess:

| Item | Bytes |
| --- | --- |
| frame framing (header + CRC) | 20, fixed |
| a two-account transfer with realistic ids (16 chars, e.g. `merchant-payable`) | payload 52 → **frame 72** |
| the same, at 12 entries (the widest the property generator produced in ADR 0002) | payload 266 → **frame 286** |
| `RECOVERY_MARKER` | 41, at most one per open — not per commit |
| segment header | 16, once per file |
| a forced group of *k* records | one `write()` of Σ frames, one `fdatasync` |

Measured on this build rather than derived: 51 records of 4-byte payloads under no-fsync produced a
1,240-byte file — (1,240 − 16) / 51 = **exactly 24 bytes per record**, which is 20 framing + 4
payload; and 24 records of 1-byte payloads under per-commit produced 504 bytes of frames, 21 each.
The harness's whole-run ratio was 58 bytes per record across all five policies at 24-op cycles, which
is the same arithmetic with a 16-byte header amortised over tiny logs.

**Syscalls per logical transaction** is the number that actually differentiates the menu: per-commit
is 1 `write` + 1 `fdatasync` per transaction; group commit is 1 `write` + 1 `fdatasync` per *group*
(measured 5 forces for 256 records under 32-way load); no-fsync is 1 `write` and 0 forces. And
because a flush writes whole pages, an `fdatasync` after a 72-byte append costs the device up to a
4 KiB block — so a lone 72-byte commit is ~57× at the block layer, while a 64-record group (4,608
bytes ≈ two pages) is ~1.8×. **That is where group commit's real win lives: not the syscall, the
page.** This is arithmetic about a page cache, not a measurement of a device: the sandbox has no
`blktrace` and no way to see below the filesystem, and ext4's own journal and its `data=ordered` mode
add their own writes on top. The metrics ticket gets to replace this paragraph with numbers; nobody
else should quote it as one.

What the format deliberately does *not* spend bytes on, each a decision with a cost attached: no
full-page images (Postgres writes them so a torn *page* is survivable — ledger-x has no pages to
tear, which is the entire benefit of an append-only log of logical records), no compression (a
compressor in the write path would have to be deterministic across JDK versions to keep replay
byte-identical, which is a research ticket of its own), no per-record `xl_prev` back-pointer (Postgres
carries one so it can walk backwards; recovery here reads forward from a checkpoint and needs
nothing else), and no second checksum layer (a payload-internal digest would be a field a corrupt
writer could leave consistent).

## 7. Alternatives considered

| Option | Verdict | One-line reason |
| --- | --- | --- |
| **Length+type+LSN+CRC32C frame, cut at the first structural failure** | **Chosen** | Every field is either a boundary or a proof, and the recovery rule splits on whether a partial write could have made the finding. |
| Derive the sequence from the byte offset, no LSN field | Rejected | Saves 8 bytes per record and loses the ability to tell a lost record from a deliberate skip; a hole *is* the failure signal, and this option deletes the signal. |
| CRC over the payload only | Rejected | A scribbled length then instructs recovery to skip an arbitrary distance; the reason `BAD_FRAME_LENGTH` is a truncatable finding at all is that the CRC covers the length. |
| Skip a bad record and keep scanning past it | Rejected | Sounds like it loses less; in fact it acks records into a hole the next scan will never reach, and it makes the log non-repairable. §4 R2. |
| Truncate at *any* finding, including unknown types and refused postings | Rejected | Would delete possibly-acked data to work around damage a torn write cannot produce. This is the row the ticket asked to be argued. |
| A version byte per record | Rejected | The segment already has one and a file has one format; per-record versioning buys mixing records in one log, which recovery would then have to arbitrate. |
| `mmap` write path (ADR 0001's named option) | Deferred, not rejected | Under `mmap` the ack is `msync`, torn tails become *page*-granular rather than frame-granular, and `dirty` page writeback is not ordered by offset — so R3's lemma needs re-proving before this menu can be offered on that path. Belongs to the metrics ticket's experiments, and the record format above is deliberately offset-addressable so it can be tried without a format change. |
| `O_DSYNC` instead of an explicit `forceData` | Rejected | It forces per `write`, which forfeits the group boundary the whole menu is about, and it is invisible in a latency table — you cannot measure a policy you removed. |
| `O_DIRECT` to escape the page cache | Rejected (as a dependency), kept as an experiment | `com.sun.nio.file.ExtendedOpenOption.DIRECT` throws where the OS or filesystem declines (ADR 0001 quoted it); building correctness on an unsupported option would be building it on a maybe. |
| A storage engine's log (RocksDB) | Rejected at ADR 0001; still rejected | Its `WriteOptions.sync` and `manual_wal_flush` put the group boundary in the engine's hands, which is the thing this ticket exists to decide. |

## 8. Evidence

**19 checks in `dev.ledgerx.wal.WalContract`, run by `./build.sh`** — exact frame and header bytes
written down rather than computed by the code under test; **68 single-bit flips** across the length,
LSN, type and payload of four frames, each truncating at exactly its own record; **all 113 truncate
offsets** of a four-record log, each asserted to keep precisely the whole frames beneath it; head
damage refused with the file untouched; unknown type (0x7F *and* 0x00) refused with the file
untouched; an LSN hole truncated with the next append taking the cut's number rather than the stale
one's; per-commit forcing once per record and group commit 5 forces for 256 frames; 20 sequential
appends under group commit forcing 20 times (§3's finding, asserted rather than explained away); the
ack watermark never behind an acked record; 206 events replayed identically twice with 206 payloads
re-encoding byte-identically; the codec injective over 400 random events plus a 5,900-entry posting;
a refused transaction leaving the log byte-identical; a valid frame the ledger refuses being
`DOMAIN_REJECTED`, not a tear; the payload ceiling enforced before the queue.

**1,000 kill -9 cycles in `dev.ledgerx.wal.crash.CrashHarness`** (`./build.sh crash`, `make crash`,
and its own CI step), 24 ops per child, every policy in the menu in turn × two crash models. The
workload is seeded so the ack counts below are reproducible; the instant of the kill is not, so the
tear and cut-byte columns move a few cycles between runs and the two zero columns never do.

| Policy / model | cycles | acks | lost after ack [forced] | [unforced] | cycles torn | bytes cut | markers |
| --- | --- | --- | --- | --- | --- | --- | --- |
| per-commit / process | 100 | 662 | **0** | 0 | 0 | 0 | 0 |
| group / process | 100 | 641 | **0** | 0 | 0 | 0 | 0 |
| no-fsync / process | 100 | 647 | **0** | 0 | 0 | 0 | 0 |
| group:1:0 / process | 100 | 643 | **0** | 0 | 0 | 0 | 0 |
| group:64:5000 / process | 100 | 635 | **0** | 0 | 0 | 0 | 0 |
| per-commit / power-loss | 100 | 653 | **0** | 0 | 4 | 45 | 4 |
| group / power-loss | 100 | 679 | **0** | 0 | 5 | 79 | 5 |
| no-fsync / power-loss | 100 | 614 | **0** | **284** | 98 | 3,213 | 98 |
| group:1:0 / power-loss | 100 | 706 | **0** | 0 | 4 | 54 | 4 |
| group:64:5000 / power-loss | 100 | 663 | **0** | 0 | 3 | 35 | 3 |

`killed=100` in every row: all 1,000 cycles ended in an actual `SIGKILL`, not in a child that
finished before the parent stopped reading. 6,543 acks observed in 154 s, 58 bytes per record, with **zero invariant
violations** — no accepted tear, no hole, no fold that refused, no Σ balances ≠ 0, no log that would
not open, no repair that was not idempotent, no record that vanished behind a post-repair append — and
**zero acknowledged-but-lost transactions under either forcing policy, in both crash models**. The
`no-fsync / power-loss` row is the point of the second model: 284 acknowledged records discarded by a
cut that stayed entirely within the bytes no promise covered. A harness with only the `kill -9` model
would have reported that policy as clean, which is the specific way this ticket could have lied.

The forcing policies show few tears under the power-loss model *by construction*: their floor is at
EOF most of the time, because everything written had also been forced. Cutting below the floor would
model a lying controller, and ADR 0001 puts those out of scope; the tear coverage that would have come
from those cycles comes instead from the process model (where the kill lands inside a `write()` often
enough to be seen) and exhaustively from the contract's 113-offset scan. Recorded as a limit of the
harness rather than a property of the log.

One harness note, because it is the difference between a trustworthy red run and a green one: the
first 1,000-cycle campaign printed 28 failures, all of them "the child acked nothing before it
stopped", in consecutive cycles while another build was running in the same sandbox. That is a JVM that
could not start, not a log that lost money — so the parent now reports the child's exit code and its
own captured output with that message and retries such a cycle exactly once, which is an environment
guard and not a durability one: a second failure is reported as real. Re-running with the fix: 28
retries printed, 0 failures.

**A third finding came from CI, not from this sandbox, and says something about the toolchain.** javac's
`-Xlint:all` reports a non-transient instance field of a non-serializable type inside a `Serializable`
class as a warning, and `-Werror` makes that a failed build: `UnrecoverableLogException` carried an
`Lsn` value for the reader's benefit and had to mark it `transient`, since the number it needs is
already in the message and nothing in this repo serializes exceptions. The compiler this sandbox can
install (ECJ, at the flag set `build.sh` uses) does not report it, so a locally green compile is
evidence about the code and not about CI's vocabulary — the same split ADR 0002 §11 records for
`@SuppressWarnings`, and the reason this ADR labels its local runs as ECJ runs.

**Two bugs this harness and suite found, both worth the fix's description.** (1) The recovery scan
*hung*: `WalRecovery.readAt` looped on top of `DurableChannel.readAt`, which already loops, so a read
at EOF returned `0` — an answer, not a request to retry — and the outer loop spun forever. Found by
the exhaustive truncate-offset check, which a "write some records, crash, check" smoke test would
never have produced. (2) The group-commit window stalled a lone writer for the full window per commit
(§3), found by the harness running a 5 s window: one policy's cycles took 90 s while its neighbours
took 0.3 s, which is what a latency *floor* looks like in a throughput table.

## 9. Consequences accepted

- **20 bytes per record of pure framing**, and 8 of those are an LSN nobody can use to address a
  record. Priced in §6; the alternative was a weaker recovery, which is not cheaper.
- **`NO_FSYNC` is on the menu and can lose acked money.** Documented in the mode's own
  `promise()` string, so a log line names the exposure it is running in.
- **A torn tail is a *repair*, and a repair discards.** Records past the cut that a client was never
  told about are gone, and the log records that it cut them — it does not attempt to reconstruct them,
  which would mean guessing at CRC-failed bytes.
- **Refusing to open is an outage**, and this ADR chose the outage over the alternative twice
  (`Corruption`, and `Wal.open` with repair off). A ledger that comes up on a log it only half
  understands is the failure mode the charter exists to prevent.
- **`DurableLedger` validates and applies in memory *before* the append**, so a storage failure
  leaves memory ahead of the log; the ledger therefore poisons itself rather than keep serving.
  Fixing this needs `validate` and `apply` exposed separately, which is a domain change ADR 0002 does
  not offer; §10 hands it to the concurrency ticket, where the same critical section already has to
  hold the append.
- **One committer thread is the only writer**, so a burst bigger than the 4,096-deep queue is refused
  with a message that names backpressure as another ticket's. There is no disk-bound fairness story
  here yet, and the metrics ticket will find that out at 64 threads.
- **Recovery is O(log)**: no index, no checkpoint. The checkpoint ticket buys that back and inherits
  §10's ordering rule for free.
- **No durability across a lying device, and no power-loss test.** A returned `fdatasync` is a promise
  from the kernel (the same man page notes that "the fsync() implementations in older kernels and
  lesser used filesystems do not know how to flush disk caches"), the power-loss model is a truncate,
  and single-node durability is not replication safety.

## 10. What this changes for other tickets

- *How is state proven identical after a crash?* — inherits: the marker's `lastLsn` and the cut's
  offset, so a snapshot can say "everything up to here was durable" and be checked; **one rule to
  keep it safe** — a snapshot may only ever be written *after* the WAL bytes it covers have been
  forced, and recovery must refuse a snapshot whose claimed WAL offset the file does not have (the
  stale-snapshot-with-torn-WAL case is data loss, not a torn tail); fsync the snapshot, then the
  directory, then truncate or rotate the log — in that order, never the reverse; and the durable
  state hash is yours to canonicalise, since `EventCodec`'s rendering is only
  `JournalDigest`-shaped test code (ADR 0002 §13).
- *What are ledger-x's idempotency semantics?* — the key and its status need a record type beside
  `POSTED`, written **in the same group** as the posting: the log has no cross-record atomicity, so a
  key stored after the ack is a key that can be lost while the money is not, which is precisely the
  double-payout the charter is about. Also inherits: a refusal at replay is fatal, so a key record
  must never encode a rejection in a way that a fold would try to post.
- *How do a payout and a refund to the same account never interleave?* — the whole commit protocol
  (validate → append → ack → apply) is one critical section, and *the order appends are acked in must
  be the order they were applied in*, or the log and the index disagree forever; the queue is currently
  the only serializer. Plus the `validate`-without-`apply` hook this ticket needed and did not take
  (§9), and the backpressure question the 4,096-deep queue defers.
- *What must TLC prove?* — the spec's durability predicate is now nameable: `acked ⇒ ∃ force covering
  these bytes`, and the lemma in §4 R3 is the property worth model-checking (it is small enough to
  exhaust: a handful of frames, a watermark, and a cut). The dense-LSN rule gives the spec a state
  variable instead of a convention.
- *What breaks it?* — the fault surface is now exactly nameable, and this ticket's harness is a subset
  of it: `SIGKILL` at an arbitrary instant, truncate to an arbitrary byte, scribble one bit inside a
  payload (must truncate), scribble inside the header (must refuse), delete-and-recreate the file, and
  the corruption classes in `Corruption`. What the chaos ticket adds is *combinations* — kill during
  recovery, corrupt while a snapshot is being written — and the ledger oracle this ticket's
  `DurableLedger` now exposes for reuse.
- *How fast is it, honestly?* — the table this makes possible is per-policy `write`s, `fdatasync`es
  and bytes per transaction (§6), and the two findings it must report rather than smooth over: batching
  is a property of load (§3), and a batch window spent waiting on an empty queue is a latency floor.
- *Which collector and allocation budget keeps the JVM's GC out of the p99 column?* — the write path
  allocates one payload copy, one frame array and one group `ByteBuffer` per commit, before any
  encoding; those are the numbers for the allocation budget, and the copies are `WalRecord`'s defensive
  cloning that §2 argued for.

## 11. Fog graduated

Nothing new is *unblocked* by this ticket that the map did not already list, and one thing is
sharpened enough to be a rule handed to a neighbour rather than a question left in the fog: the
snapshot/WAL fsync ordering (now in *How is state proven identical after a crash?*'s terms above).
Segment rotation and retention stay unstarted — the format is per-file self-describing and the LSN
stream is continuous specifically so rotation is additive, but nothing here needs it, and this ticket
says so rather than building it. Checked against the map's *Not yet specified*: the **replay
state-hash scheme** stays open (this file hands over the codec and the marker, not a hash); the
**chaos fault set** is now half-cleared, the storage half named and the payout half not; the
**benchmark workload** has its durability axis fixed (three policies, two knobs, one measured
page-fill effect) and still lacks the concurrency shape.
