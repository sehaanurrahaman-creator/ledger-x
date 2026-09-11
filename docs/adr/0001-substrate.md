# ADR 0001 — Substrate: Java 21 (Loom) with a hand-rolled append-only WAL

- **Status:** Accepted — 2026-09-11
- **Ticket:** [Substrate decision: Java + Loom or Go — and a hand-rolled log or RocksDB?](https://github.com/sehaanurrahaman-creator/ledger-x/issues/3),
  a child of the [Wayfinder map](https://github.com/sehaanurrahaman-creator/ledger-x/issues/1)
- **Decides:** language/runtime, storage substrate, and what "commit" means on the chosen stack.
- **Does not decide:** the WAL record format, the fsync policy menu or the torn-tail rules (those belong to
  *What does a durable write look like?*), anything about the domain model, and the build tool (deferred with an
  explicit trigger, below).

## Decision

ledger-x is **Java 21 (LTS) on the JVM**, using **virtual threads** for concurrency, on top of a **hand-rolled
append-only write-ahead log owned by this repository**. No storage engine is taken as a dependency; **RocksDB is
rejected**.

Durability is expressed only through `java.nio.channels.FileChannel`:

- `force(false)` — `fdatasync` — for the WAL record group;
- `force(true)` — `fsync` — for the WAL at open/close/rotation and for checkpoints;
- an explicit `fsync` of the WAL's **parent directory** at create, rotate and checkpoint swap.

**"Commit" means:** the record's bytes are in the WAL file and the group's `fdatasync` has returned. Group commit is
a single committer thread that batches WAL appends from many virtual threads and issues one `fdatasync` per group;
callers park on the group instead of blocking a thread per write.

## Criteria, in the order the ticket ranks them

1. Direct control of fsync — the project *is* durability boundaries.
2. Concurrency-model fit — per-account ordering without a global lock.
3. One repo, one language — TLC/TLA⁺ runs in the JVM.
4. Measurement tooling — the ops/sec and p50/p99 table.

## Alternatives considered

| Option | Verdict | One-line reason |
| --- | --- | --- |
| **Java 21 + Loom + hand-rolled WAL** | **Chosen** | Both fsync syscalls behind one object, TLC stays in-language, JMH gives percentiles for free. |
| Go + hand-rolled WAL | Rejected | Equally good on fsync, but spec and code split across two languages/toolchains, and percentiles need a third-party harness. |
| Java + RocksDB | Rejected | RocksDB owns the WAL record format, the group-commit boundary and the recovery rules — the three things this project exists to decide and prove. |
| Go + RocksDB | Rejected | Dominated by both of the above: the fsync boundary is hidden *and* the TLA⁺ story leaves the language. |
| Java + hand-rolled WAL on `mmap` | Deferred, not rejected | A legitimate second write path; it changes torn-tail and force semantics, so it belongs to *What does a durable write look like?* as a named option. |

## Criterion 1 — direct control of fsync

This was expected to be the deciding criterion and turned out to be the closest one. The facts, each read from
source on 2026-09-11:

**Java reaches both syscalls through one method.** `FileChannel.force` is documented as "Forces any updates to this
channel's file to be written to the storage device that contains it", with a `metaData` parameter that "can be used
to limit the number of I/O operations that this method is required to perform"
([`java/nio/channels/FileChannel.java`, openjdk/jdk21u](https://github.com/openjdk/jdk21u/blob/master/src/java.base/share/classes/java/nio/channels/FileChannel.java)).
On Unix the boolean *is* the syscall choice
([`UnixFileDispatcherImpl.c`](https://github.com/openjdk/jdk21u/blob/master/src/java.base/unix/native/libnio/ch/UnixFileDispatcherImpl.c)):

```c
Java_sun_nio_ch_UnixFileDispatcherImpl_force0(JNIEnv *env, jobject this, jobject fdo, jboolean md)
{
    jint fd = fdval(env, fdo);
    if (md == JNI_FALSE) {
        result = fdatasync(fd);
    } else {
        result = fsync(fd);
    }
```

**Go reaches both too, with a rawer edge.** `os.File.Sync()` calls `f.pfd.Fsync()`
([`src/os/file_posix.go`](https://github.com/golang/go/blob/master/src/os/file_posix.go)), which is
`syscall.Fsync(fd)` ([`src/internal/poll/fd_fsync_posix.go`](https://github.com/golang/go/blob/master/src/internal/poll/fd_fsync_posix.go)).
`fdatasync` exists in the standard library but only as `syscall.Fdatasync` on Linux
([`src/syscall/syscall_linux.go`](https://github.com/golang/go/blob/master/src/syscall/syscall_linux.go)) — outside the
`os.File` abstraction, on a raw fd. So the honest reading: **capability is a tie; Java wins on API shape only.**
Anyone repeating this decision should not be told Go was weak here.

**RocksDB exposes the choice but not the timing.** From
[`include/rocksdb/options.h`](https://github.com/facebook/rocksdb/blob/main/include/rocksdb/options.h):

- `use_fsync` — "By default, writes to stable storage use fdatasync (on platforms where this function is available).
  If this option is true, fsync is used instead." The same comment adds that "fsync and fdatasync are equally safe
  for our purposes", i.e. the engine has already made the write-amplification argument for us.
- `WriteOptions.sync` — "A DB write with `sync==true` has similar crash semantics to a `write()` system call
  followed by `fdatasync()`."
- `manual_wal_flush` — "If true WAL is not flushed automatically after each write. Instead it relies on manual
  invocation of FlushWAL to write the WAL buffer to its file."

That last line is the whole argument. RocksDB cuts its own WAL groups; the alternatives are "let it" (we no longer
own the group boundary) or "flush manually" (we forfeit the batching the metrics table is supposed to measure).
Either way the ticket's third question — *what group commit looks like* — would be answered by RocksDB's internals,
and the record format and torn-tail rules that *What does a durable write look like?* exists to decide would be
inherited rather than designed. The counterweight is real and is recorded here rather than buried: RocksDB would
have given us the memtable, SSTables, compaction, block cache and crash-consistent recovery for free — roughly one
subsystem not written. We pay for that in code, and buy back the part of the project that is actually the point.

## Criterion 2 — concurrency-model fit

`java.lang.Thread`'s own javadoc describes the exact workload
([`Thread.java`, openjdk/jdk21u](https://github.com/openjdk/jdk21u/blob/master/src/java.base/share/classes/java/lang/Thread.java)):
"a single Java virtual machine may support millions of virtual threads. Virtual threads are suitable for executing
tasks that spend most of the time blocked, often waiting for I/O operations to complete." A ledger commit is
precisely that: block on a per-account lock, then block on a group fsync.

**One hard rule follows from the substrate, and it is easy to get wrong.** Per-account ordering must use
`java.util.concurrent.locks.ReentrantLock`, never `synchronized`: a virtual thread that blocks inside a
`synchronized` block or method is *pinned* to its carrier thread in Java 21. The machinery is right there in
[`VirtualThread.java`](https://github.com/openjdk/jdk21u/blob/master/src/java.base/share/classes/java/lang/VirtualThread.java)
(`PINNED` / `TIMED_PINNED` states, `VirtualThreadPinnedEvent`, `parkOnCarrierThread`), and the JDK ships the
diagnostic `jdk.tracePinnedThreads` — but `Thread.java`, the file everyone actually reads, never uses the word
"pin": the only occurrences of that letter sequence in it are `spin`/`spins`/`onSpinWait` (verified on jdk21u).
The hazard is invisible unless you already know to look for it. It is also not going away on its own:
`VirtualThread.java` on `openjdk/jdk` master still contains `PINNED` and `TIMED_PINNED` (7 of each, checked
2026-09-11). So the rule is permanent, not transitional: it is load-bearing input to *How do a payout and a refund
to the same account never interleave?*, it gets a code-review rule, and the chaos harness runs with
`-Djdk.tracePinnedThreads`.

Goroutines have no equivalent trap. That is the one place Go is genuinely better, and it is why this section says
"Java, with one hard rule" rather than "Java, obviously".

## Criterion 3 — one repo, one language (decisive)

TLC is a Java program — Lamport's own account of the model checker says "It is also coded in Java, rather than in
language like C that would have been more efficient" ([*Model Checking TLA+ Specifications*](https://lamport.azurewebsites.net/pubs/yuanyu-model-checking.pdf)) —
and the TLA⁺ tooling "need[s] JVM (Java Virtual Machine) to run" ([docs.tlapl.us](https://docs.tlapl.us/using:vscode:installing_java)).
On Go, spec and code live in two languages with two toolchains, two CI jobs and a translation seam exactly where the
proof is supposed to touch the implementation. On Java the spec, the checker and the ledger share a language, a
build and a CI job.

**An honest gap in this criterion.** The charter names **Jaunt** as the thing that "runs TLA⁺ inside Java". Searched
2026-09-11: `gh search repos jaunt` and a repository search for `jaunt tla` in name/description return no TLA⁺ tool
at all (the only Java project called Jaunt is a web-scraping library), and a web search for a Jaunt/TLA⁺ toolchain
returns nothing either. So this ADR records a **requirement**, not a tool: *TLC must run from this repository with
one command.* If Jaunt is private, internal or unavailable, the fallback is TLC's own distribution
(`tla2tools.jar`) invoked from `./build.sh tla`, and the TLA⁺ ticket's wording changes from "run via Jaunt" to "run
in-JVM". Graduated as its own ticket blocking *What must TLC prove?* so the assumption cannot ride along silently.

## Criterion 4 — measurement tooling

The charter's table asks for ops/sec at 1/8/64 threads and p50/p99 per fsync policy. That is a latency *distribution*
benchmark, and JMH has a mode for exactly that: `Mode.SampleTime` "instead of measuring the total time, we measure
the time spent in *some* of the benchmark method calls. This allows us to infer the distributions, percentiles,
etc." ([`JMHSample_02_BenchmarkModes.java`, openjdk/jmh](https://github.com/openjdk/jmh/blob/master/jmh-samples/src/main/java/org/openjdk/jmh/samples/JMHSample_02_BenchmarkModes.java)).
Go's `testing.B` reports ns/op for a throughput loop; percentiles and a warmup discipline come from a third-party
harness. Not decisive on its own — but it removes a whole class of "how do I trust this number" work from the
metrics ticket.

## What "commit" means on this stack

| Boundary | Primitive | What it buys |
| --- | --- | --- |
| bytes visible to other readers in this process | `FileChannel.write(ByteBuffer)`, no intermediate buffering | nothing across a crash; process-crash safety only |
| WAL records durable across OS/kernel crash | `FileChannel.force(false)` → `fdatasync(fd)` | data durable; file *metadata* (size, mtime) not guaranteed |
| WAL plus its size/mtime durable | `FileChannel.force(true)` → `fsync(fd)` | used at open, close, rotation, checkpoint |
| WAL *creation* or rename durable | `fsync(dirfd)`: open the directory `READ`, then `force(true)` | required after create/rename; a crash can otherwise lose the entry |

Commit protocol, decided here and elaborated by the WAL ticket:

1. A caller serializes its record bytes and appends them to the WAL **under its per-account `ReentrantLock`** — no
   global lock, and per-account ordering falls out of the lock rather than being enforced separately.
2. It hands its sequence number to a single **committer** thread and parks on the group (a `CompletableFuture` or
   `Phaser` per group — never a blocked thread per write).
3. The committer cuts the group and calls `force(false)` **once**, then completes the futures.
4. "Committed" = the future completed. A crash before that is "unknown", which the idempotency layer maps to replay,
   never to success.

`force(false)` rather than `force(true)` per group because the log is append-only and its length is recoverable by
scanning the tail after a crash — which is the torn-tail rule the WAL ticket owns. That choice is the first real
answer to standing design-review question 1 (write amplification): one `fdatasync` per group, no metadata sync per
record, no page rewrite.

Two honest limits, stated so they cannot be discovered late: a returned `fdatasync` is a promise from the kernel, not
from the device, so a lying controller is out of scope; and the chaos fuzzer's `SIGKILL` tests *process* crashes,
not power loss.

## Consequences accepted

- **GC pauses land in the p99 column.** This is the cost of the JVM and it is not a vibe to be waved at — collector
  choice, heap sizing and the allocation budget for the WAL path are a decision. Graduated as a ticket blocking the
  metrics ticket.
- **Virtual-thread pinning** (criterion 2) — permanent code-review rule plus a diagnostic flag in the harness.
- **No page-cache bypass we can rely on.** Direct I/O is reachable only through `jdk.unsupported`
  ([`com.sun.nio.file.ExtendedOpenOption.DIRECT`](https://github.com/openjdk/jdk21u/blob/master/src/jdk.unsupported/share/classes/com/sun/nio/file/ExtendedOpenOption.java),
  "Requires that direct I/O be used for read or write access … `UnsupportedOperationException` if the operating
  system or file system does not support Direct I/O", `@since 10`). We do not build on it; at most it is an
  experiment for the benchmark ticket.
- **JIT warmup** means every number needs a warmup discipline; JMH supplies it, hand-rolled timing would not.
- **JVM startup** inside a 10,000-injection chaos loop is a real budget item. Unchanged by this ADR; it stays in the
  map's CI-budget fog.

## Build tool: deliberately not decided, with a trigger

The CI skeleton in this ticket has **zero third-party dependencies** — `javac`, `java` and POSIX shell. Nothing the
substrate decision needs requires a dependency manager, and a skeleton that resolves nothing cannot break on a
registry outage. **Trigger:** the first ticket that needs JMH or JUnit (the metrics ticket) introduces a
dependency-managing build — Gradle with the toolchains plugin, versions pinned — and `./build.sh` becomes a thin
wrapper over it, so that the one command never changes. Written down here so the shape of the build is a decision
somebody made rather than an accident of what was handy.

## The CI skeleton that ships with this ADR

- **One command:** `./build.sh` — style lint, `javac --release 21 -Xlint:all -Werror`, compile tests, run the
  contract test, non-zero exit on any failure.
- `.github/workflows/ci.yml` — `ubuntu-latest`, `actions/setup-java@v4` with Temurin 21, then `./build.sh`.
- `src/main/java/dev/ledgerx/substrate/DurableChannel.java` — the four primitives in the table above. It is
  substrate, not ledger: no accounts, no entries, no balances, no replay.
- `src/test/java/dev/ledgerx/substrate/SubstrateContract.java` — five checks that pin down the platform facts this
  ADR rests on, so a future JDK upgrade that quietly breaks one of them fails CI instead of corrupting a ledger.

## What this changes for other tickets

- *What does a durable write look like?* — inherits the primitive table and the group-commit shape; still owns the
  record format, the fsync policy menu, the torn-tail rules, and now also the `write`+`force` vs `mmap` question.
- *How do a payout and a refund to the same account never interleave?* — inherits the pinning rule: any
  per-account serialization built on `synchronized` is a defect on this substrate.
- *What must TLC prove?* — blocked by the new "how does TLC actually run here" ticket until the Jaunt assumption is
  settled.
- *How fast is it, honestly?* — blocked by the new collector/allocation-budget ticket; JMH `Mode.SampleTime` is the
  harness shape.
- *What breaks it?* — the fault surface is now nameable: `SIGKILL` a JVM mid-`force`, truncate or scribble on a WAL
  tail we own. The rest of that ticket still hangs on the payout protocol.

## Verification

- Every quoted sentence about the JDK, Go, RocksDB and JMH above was read from the source file linked beside it on
  2026-09-11, not recalled.
- The claims that came back *against* this decision are recorded rather than dropped: Go has both fsync primitives;
  RocksDB does expose `fdatasync` vs `fsync`; goroutines have no pinning trap; `PINNED` is still in the JDK on
  master.
- Unchecked, and marked so: **Jaunt** — no public artifact by that name was found (see criterion 3).
- The skeleton is verified by GitHub Actions on branch `arena/01a09264-ledger-x`: eight runs, every one
  `completed/success`, the head one publishing `notice: PASS 5/5 substrate checks` and
  `notice: substrate contract measured platformThreads=10 platform threads` (run `34653473125`). The
  virtual-thread claim in criterion 2 is therefore measured, not asserted: 10,000 concurrently-parked virtual
  threads on 10 platform threads. Not verified locally — the sandbox this was written in has no JDK and no
  reachable JDK download — so CI is the check of record for the compile and the test.
