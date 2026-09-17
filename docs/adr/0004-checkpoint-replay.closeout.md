# Closeout actions for *How is state proven identical after a crash? Checkpoints and byte-identical replay*

The decision is recorded as [`docs/adr/0004-checkpoint-replay.md`](./0004-checkpoint-replay.md),
implemented in `src/main/java/dev/ledgerx/checkpoint/` (`StateHash`, `Checkpoint`), wired through
`dev.ledgerx.wal` (`Wal.force`, `Scan.frameEnds`, `Corruption.CHECKPOINT_MISMATCH`),
`dev.ledgerx.domain` (`InMemoryLedger.restored`) and `dev.ledgerx.journal`
(`DurableLedger.open`/`writeCheckpoint`), and proved by
`src/test/java/dev/ledgerx/checkpoint/` (`CheckpointContract`, 13 checks; `ReplayHarness`,
12 trials × every byte offset in CI, 40 × 60 in the extended campaign). **[PR
#25](https://github.com/sehaanurrahaman-creator/ledger-x/pull/25)** carries the work on branch
`arena/01a0afb2-ledger-x` as four commits — `eec49f4` (the implementation and the two suites),
`1eff5d0` (the ADR, README, closeout and resolution comment), `3a42e56` (a demo declaration
javac's widened `size()` asked for), and `44466df` (the `-Xlint:try` fix below). **CI is green
on them** (run `35235664712`): `PASS 5/5 substrate checks`, `PASS 19/19 wal checks`,
`PASS 13/13 checkpoint checks`, `PASS 1000/1000 kill -9` cycles (280 unforced acks lost across
`NO_FSYNC`'s power-loss model — the moving column), `PASS 9/9 property checks` at both sizes,
and the replay harness step green in 39.6 s on the runner.

**Everything the ticket asked for is in those commits; what is left is GitHub-side writing this
integration cannot do**, as in [ADR 0003's closeout](./0003-wal-fsync.closeout.md): the token
attached after a reconnect writes *repo contents* (`git push` succeeds) while issue comments stay
`403 Resource not accessible by integration`, so #6 will not close itself when the PR merges and
the claim/resolution comments below stay payload files. Verify the permission object with
`gh api repos/{owner}/{repo} --jq '.permissions'` before spending a token rotation on what is a
scope setting.

A third finding came from CI rather than from this sandbox, and it is the same toolchain story
ADR 0003 §8 told: javac's `-Xlint:try` — promoted to an error by `-Werror`, not reported by the
ECJ this sandbox compiles with — refuses an auto-closeable resource that is named but never
referenced in its try body, which is exactly the shape of the refusal checks
(`try (DurableLedger ignored = DurableLedger.open(...)) { throw new AssertionError(...); }`).
The fix (`44466df`) references the resource in the assertion message, which is the better
message anyway; ECJ cannot see this class of bug, so a locally green compile stays evidence
about the code and never about CI's lint vocabulary.

Two bugs this ticket's own tests found and fixed inside the session, both worth the sentence:
the first draft of `DurableLedger.open` validated the checkpoint *after* `Wal.open` had repaired a
torn tail, so a refusal for data loss had already edited the evidence — the recovery-order rule in
ADR 0004 §5 exists because the contract's "the refusal edited the log" assertion failed. And the
first draft of the restore surface put three non-final fields on `InMemoryLedger`, which the
domain's no-mutation-surface property rejected by reflection; what shipped keeps every field
final and fixes the restore state at construction.

## 0. Fastest route

1. **Merge the PR** (`gh pr merge <n> --squash`). CI runs six steps; the new one is
   "Checkpoint replay harness" (`make replay`), which sweeps ~24,000 crash offsets in ~50 s. The
   verdict lines are published as run annotations and summarized in the job summary; read them
   back with `scripts/ci-evidence.sh <branch>` rather than trusting this file.
2. **A human then does three things this integration cannot**: paste §2 as a comment on #6,
   close #6 (`state_reason=completed`), and replace the map's *Decisions so far* with §4 —
   which carries **five** lines, because the four before it are still unappended.

## 1. Claim (blocked — post-ready as-is)

```
**Claimed.** Worked in session `arena/01a0afb2-ledger-x` (2026-09-17). Self-assignment is not
available to this integration, so this comment is the claim record: treat *How is state proven
identical after a crash?* as off the frontier. Scope: the canonical state hash, the checkpoint
format + atomic swap + retention, checkpoint-aware recovery, and the crash-at-every-byte harness
ADR 0004 cites. Idempotency semantics, concurrency, the payout protocol and the benchmark stay
with their own tickets.
```

```
gh api -X POST repos/{owner}/{repo}/issues/6/comments -f body='<the text above>'
```

## 2. Resolution comment (blocked — post-ready as-is)

The body is [`0004-checkpoint-replay.resolution-comment.md`](./0004-checkpoint-replay.resolution-comment.md):
no bot-permission meta, every link pointing at `main`, and it ends by naming the map line it owes.

```
gh api -X POST repos/{owner}/{repo}/issues/6/comments \
  -F body=@docs/adr/0004-checkpoint-replay.resolution-comment.md
```

## 3. Close the ticket (blocked)

```
gh api -X PATCH repos/{owner}/{repo}/issues/6 -f state=closed -f state_reason=completed
```

## 4. Append to the map's *Decisions so far* (blocked)

**Paste-ready:** replace issue #1's entire `## Decisions so far` section (heading through the
blank line before `## Not yet specified`) with the block below, as one edit. The first four
lines are carried verbatim from [`0003-wal-fsync.closeout.md`](./0003-wal-fsync.closeout.md) §4 —
they are still unappended, because #2–#5 closed with zero comments from an identity that could
not write issues — and the fifth is this ticket's.

````
## Decisions so far

<!-- Append one line per closed ticket, newest last: - [ticket title](link): one-line gist of the answer. Nothing lives here until the first ticket closes. -->

- [What do Stripe's public API and engineering writing actually say about idempotency and payout safety?](https://github.com/sehaanurrahaman-creator/ledger-x/issues/2): Stripe's guarantee is `(account, key) → stored status+body` for a **≥24h floor** after which the key is *forgotten and re-executes*, replays are flagged by `Idempotent-Replayed: true`, and **same-key/different-body is 400 `idempotency_error` while 409 `idempotency_key_in_use` means a concurrent duplicate that Stripe's own client retries** — so ledger-x's charter 409-on-mismatch and its GC policy are both deliberate divergences to justify; payouts can read `paid` and regress to `failed` within 5 business days. Note: `docs/research/stripe-idempotency.md`.
- [Substrate decision: Java + Loom or Go — and a hand-rolled log or RocksDB?](https://github.com/sehaanurrahaman-creator/ledger-x/issues/3): **Java 21 with virtual threads, on a hand-rolled append-only WAL owned by this repo** — RocksDB rejected because it owns the WAL record format, the group-commit boundary and the recovery rules, which are the three things this project exists to decide; Go rejected because TLC is a Java program and the spec would leave the language. Commit = the group's `FileChannel.force(false)` (`fdatasync`) has returned; `force(true)` (`fsync`) only at open/rotate/checkpoint, plus an `fsync` of the WAL directory. Ships a green CI skeleton: `./build.sh` — lint, `javac --release 21 -Xlint:all -Werror`, and a five-check substrate contract test, no third-party dependencies. Note: `docs/adr/0001-substrate.md`.
- [What is a transaction? The double-entry domain model, proven in memory](https://github.com/sehaanurrahaman-creator/ledger-x/issues/4): **a transaction is n entries, not two** — ≥2 entries, Σ debits = Σ credits, every account already opened, no total or resulting balance allowed to overflow — so a two-account transfer is one unit of commit and standing design-review question 4 is a storage question, not a protocol one. Money is a signed `long` of integer minor units, one currency, exponent 2 as a constant, every operation overflow-checked; three account kinds (`ASSET`/`LIABILITY`/`EQUITY`) as **metadata only — no posting rule reads them**; balances **may go negative on every kind**; rejection is validate-then-apply-the-checked-deltas with a closed `RejectionReason` enum in a fixed order; one sealed event log (`AccountOpened`, `Posted`) where the sequence number is the position, and a transaction with no id, no timestamp and no idempotency key. Proved by a hand-rolled property suite with an independent `BigInteger` oracle: `PASS 9/9` at 12,000 random operations by default, 60,000 under `make campaign`. It found a real atomicity bug on its first run — validation range-checked per-account nets while application walked entries one at a time, so an intermediate balance overflowed *after* the event was appended. Note: `docs/adr/0002-domain-model.md`.
- [What does a durable write look like? WAL record format, fsync policy menu, torn-tail rules](https://github.com/sehaanurrahaman-creator/ledger-x/issues/5): **a record is 20 bytes of framing around the payload — `u32 frameLength`, `u64 lsn`, `u8 type`, `u8 reserved`, `u16 payloadLength`, payload, `u32 crc32C` over all of it** — under a 16-byte self-describing segment header, with **dense LSNs** (frame *n* carries *n*) so a hole is a tear rather than a mystery and an unmapped type byte is fatal rather than skippable. **The ack rule:** a commit is acknowledged only after the `force` covering its bytes returns — its own under `PER_COMMIT`, its group's under `GROUP_COMMIT(n, t)`, and `write()` returning under `NO_FSYNC`, which is the only promise that policy makes; the policy is a per-commit value, so a payout intent can demand a force inside a lazy log. **Torn tail:** recovery classifies before it repairs — short header / dishonest length / frame past EOF / CRC mismatch / LSN gap are *cuttable* because only an unfinished write can produce them, while an intact CRC-valid frame this reader cannot honour (unknown type, undecodable payload, a posting the domain refuses, head damage) **refuses to open**, because truncating at it would delete acked money to work around damage a crash cannot cause; the cut is made durable before the next append, and a `RECOVERY_MARKER` records what was cut and why (an audit record, *not* a resurrection guard — dense LSNs plus truncation already do that). Safety is a lemma, argued in the ADR: nothing past the last durable force was ever acknowledged, so a cut and an ack are disjoint. **Write amplification:** 72 bytes per realistic two-entry transfer, one `write` + one `fdatasync` per *group*, and — the part that matters — an `fdatasync` flushes whole pages, so a lone 72-byte commit is ~57× at the block layer against ~1.8× for a 64-record group: group commit's win is the page, not the syscall. Two findings the harness earned: an unconditional batch window is a **latency floor** (a lone writer waited the full 5 s per commit — `group:64:5000` cycles took 90 s against 0.3 s; the rule is now "collect only while the queue has work", asserted by a check that a lone writer gets *no* batching), and ADR 0001's `mmap` option stays deferred because `msync` is not offset-ordered, which is where the lemma would have to be re-proved. Evidence: a 19-check contract (exact bytes; 68 single-bit flips; all 113 truncate offsets; cut-vs-refuse split; per-policy force counts) and `./build.sh crash` — **1,000 `SIGKILL` cycles, 6,543 acks, 0 acked-but-lost under either forcing policy in both crash models, 284 lost under `NO_FSYNC`'s power-loss model, 114 torn cycles, 154 s**, with a second crash model (truncate past the last force) because `kill -9` alone would have passed `NO_FSYNC` and measured nothing. Note: `docs/adr/0003-wal-fsync.md`.
- [How is state proven identical after a crash? Checkpoints and byte-identical replay](https://github.com/sehaanurrahaman-creator/ledger-x/issues/6): **"byte-identical" is now a function, not an adjective** — SHA-256 over a canonical state encoding (accounts in opening order, each with its balance, pinned by the journal length; big-endian, length-prefixed, no escaping), with the determinism rules stated as bans with reasons: no wall clock, no random, no map-iteration order, and **no LSNs** — a crash inserts a recovery marker and shifts every later LSN while state stays put, so an LSN in the hash would break exactly the crash-recover-continue identity the proof demands. **The checkpoint is one file** — 36-byte header (`"LCHK"`, watermark LSN *and* byte offset, state length, header CRC), the canonical bytes, their SHA-256, a whole-file CRC — swapped in by force-log → write-temp → fsync → atomic rename → fsync-dir, with **one live checkpoint per directory and the log never truncated behind it**, so replay-from-scratch never depends on the checkpoint and deleting them is always safe. **Recovery validates before it repairs:** the checkpoint is cross-checked against a read-only scan (offset within the surviving bytes; the frame its LSN names ends exactly where it claims) *before* `Wal.open` cuts anything — a damaged checkpoint is discarded (renamed to `checkpoint.rejected`, evidence kept, replay from scratch), but an intact checkpoint the log cannot prove is `CHECKPOINT_MISMATCH` and refuses, because bytes it vouches for were forced before it existed, so their absence is lost acknowledgements, not a torn tail. Evidence: a 13-check contract (both layouts byte for byte; the hash's three near-misses — depth, order, kind; the refusal/discard/temp matrices) and `./build.sh replay` — **crash at every byte offset of every history: 12 seeded histories × 40 steps, 24,031 crash points, 23,347 tears cut, 1,514 crash-recover-continue boundaries, zero mismatches — checkpoint + tail, full replay from scratch, and crash-recover-continue all landing on the clean run's exact bytes** (40 × 60 extended: also zero), plus the kill-9 harness unchanged at 1,000/1,000. The ticket's own tests found two real bugs in their first drafts: a refusal that repaired the log before reading it, and a restore surface the domain's immutability property rejected by reflection. Note: `docs/adr/0004-checkpoint-replay.md`.
````

## 5. Fog: one line struck, three rules handed across

The map's *Not yet specified* line **"the replay state-hash scheme — how 'byte-identical' is
canonicalized"** is decided and should be struck from #1 when the map is edited. Three things
became rules inside existing tickets rather than new questions: the idempotency table joins the
canonical state as a versioned section in canonical key order (*What are ledger-x's idempotency
semantics?*); numbered checkpoints + keep-newest-K supersede the one-file retention rule when
segment rotation lands, and the watermark becomes (segment, LSN, offset) with a per-segment
frame-end check (the rotation work, when someone plots it); and the chaos fault set's storage
half is now fully named — log head (refuse), log tail (cut), checkpoint file (discard), swap
(atomic or not at all) — each with its required verdict (*What breaks it?*).

## 6. What was verified, and by what

**Locally, with the fallback toolchain** (`scripts/bootstrap-toolchain.sh`: Temurin 21.0.8 via
jdk4py + ECJ, since no JDK distribution host is reachable from this sandbox — re-run it if a
fresh turn finds `build/` wiped):

- `./build.sh` end to end: lint over 62 files (including the two self-tested ADR rules), 29 main
  and 17 test sources compiled, `PASS 5/5 substrate checks`, `PASS 19/19 wal checks`,
  **`PASS 13/13 checkpoint checks`**, `PASS 9/9 property checks`, `demo ok`.
- `./build.sh replay` (the CI-sized campaign): **`PASS 12/12 replay trials`** — 12 histories ×
  40 steps, **24,031 crash offsets, 23,347 tears, 1,514 continue-checks, 0 mismatches**, 49 s.
- Extended campaign (`LEDGER_X_REPLAY_TRIALS=40 LEDGER_X_REPLAY_OPS=60`): **`PASS 40/40`**,
  zero mismatches, 293 s — see `build/replay-campaign-40x60.log` for the per-trial rows while
  it exists (build/ is wiped between sessions; the numbers are quoted in the ADR).
- `./build.sh crash`: **`PASS 1000/1000 kill -9 cycles`**, 6,543 acks, 0 acked-but-lost forced
  transactions under both crash models, 289 unforced acks lost under `NO_FSYNC`'s power-loss
  model (the column moves a few cycles per run — the kill instant is not seeded — and the zero
  columns never do), 161 s. The new `Wal.force` barrier and the recovery reordering did not
  move the forked-kill verdict.
- `./build.sh demo`: green, Σ balances = 0 asserted before printing.

**Known local-vs-CI divergences**, carried over from ADR 0003's closeout: this sandbox compiles
with ECJ at `build.sh`'s flag set, CI compiles with javac `-Xlint:all -Werror`, and CI is the
check of record. Nothing in this ticket uses a suppression; the one lint sensitivity is the
100-column rule counted in bytes, and every file here was written under it.

**Not verified here, and labelled as such in the ADR:** real power loss (the byte harness
truncates; `kill -9` covers process death; a lying controller is out of scope by ADR 0001), any
filesystem without atomic rename (the swap *refuses* rather than degrading — untested on such a
filesystem because none was available), and auto-checkpoint policy (deliberately undecided; §9
records why).
