# Closeout actions for *How is state proven identical after a crash? Checkpoints and byte-identical replay*

Ticket: [#6](https://github.com/sehaanurrahaman-creator/ledger-x/issues/6). Session:
`arena/01a0afb5-ledger-x`, 2026-09-17. The ADR is [`0004-checkpoint-replay.md`](./0004-checkpoint-replay.md);
the post-ready comment is [`0004-checkpoint-replay.resolution-comment.md`](./0004-checkpoint-replay.resolution-comment.md).

This ticket's session found the write wall precisely: **branch pushes and pull requests work; issues do
not.** `git push origin arena/01a0afb5-ledger-x` succeeded, `gh pr create` opened [#24](https://github.com/sehaanurrahaman-creator/ledger-x/pull/24),
and CI ran green on both the push and the pull request — but every issue write answers
`Resource not accessible by integration` (`triage: false`, §6), and self-assignment is impossible because the
assignable list holds only `sehaanurrahaman-creator`. So this file carries the three actions a human (or a
later session with `issues: write`) takes, with the text to paste.

## 0. Fastest route

1. **The pull request exists and is green**: [#24](https://github.com/sehaanurrahaman-creator/ledger-x/pull/24),
   body = [`0004-checkpoint-replay.resolution-comment.md`](./0004-checkpoint-replay.resolution-comment.md)
   verbatim, CI `success` on `a332454` (run `35234797105`; §6 has the read-back evidence). Merging it will
   **not** close #6: this integration has no `issues: write`, and a `Closes #6` in a body silently no-ops
   for an identity that cannot write issues — the same finding as ADR 0003's closeout.
2. **A human then does four things**: merge #24; paste §2 as a comment on #6; close #6 as completed
   (blocked here with `Resource not accessible by integration`, §6); and make the two map edits —
   replace the map's *Decisions so far* with §4 (which carries **five** lines, the four before this
   ticket's still unappended) and replace *Not yet specified* with §5, which strikes the replay
   state-hash item this ticket answered.

## 1. Claim (blocked — `403 Resource not accessible by integration`)

```
**Claimed.** Worked in session `arena/01a0afb5-ledger-x` (2026-09-17). Self-assignment is still not
available to this integration: `repos/{owner}/{repo}/assignees` lists only `sehaanurrahaman-creator`,
and `gh issue edit 6 --add-assignee @me` cannot resolve this identity — so this comment is the claim
record: treat *How is state proven identical after a crash?* as off the frontier. Scope: the
checkpoint format, the atomic swap, retention, the state-hash scheme, the determinism rules, and the
crash-at-every-LSN-boundary harness. Rotation and WAL retention stay with *What does a durable write
look like?*'s deferred item; idempotency, concurrency, the payout protocol, the TLA+ spec, the chaos
campaign and the benchmarks stay with their own tickets.
```

```
gh api -X POST repos/{owner}/{repo}/issues/6/comments -f body='<the text above>'
```

## 2. Resolution comment (blocked — post-ready as-is)

The body is [`0004-checkpoint-replay.resolution-comment.md`](./0004-checkpoint-replay.resolution-comment.md):
no bot-permission meta, every link pointing at `main`, and it ends by naming the two map edits it owes.

```
gh api -X POST repos/{owner}/{repo}/issues/6/comments \
  -F body=@docs/adr/0004-checkpoint-replay.resolution-comment.md
```

## 3. Close the ticket (blocked)

```
gh api -X PATCH repos/{owner}/{repo}/issues/6 -f state=closed -f state_reason=completed
```

## 4. Append to the map's *Decisions so far* (blocked)

**Paste-ready:** replace issue #1's entire `## Decisions so far` section (heading through the blank
line before `## Not yet specified`) with the block below, as one edit. The first four lines are
carried verbatim from [`0003-wal-fsync.closeout.md`](./0003-wal-fsync.closeout.md) §4 — they were
still unappended there, because #2, #3, #4 and #5 closed with zero comments or with a comment posted
by hand — and the fifth is this ticket's.

````
## Decisions so far

<!-- Append one line per closed ticket, newest last: - [ticket title](link): one-line gist of the answer. Nothing lives here until the first ticket closes. -->

- [What do Stripe's public API and engineering writing actually say about idempotency and payout safety?](https://github.com/sehaanurrahaman-creator/ledger-x/issues/2): Stripe's guarantee is `(account, key) → stored status+body` for a **≥24h floor** after which the key is *forgotten and re-executes*, replays are flagged by `Idempotent-Replayed: true`, and **same-key/different-body is 400 `idempotency_error` while 409 `idempotency_key_in_use` means a concurrent duplicate that Stripe's own client retries** — so ledger-x's charter 409-on-mismatch and its GC policy are both deliberate divergences to justify; payouts can read `paid` and regress to `failed` within 5 business days. Note: `docs/research/stripe-idempotency.md`.
- [Substrate decision: Java + Loom or Go — and a hand-rolled log or RocksDB?](https://github.com/sehaanurrahaman-creator/ledger-x/issues/3): **Java 21 with virtual threads, on a hand-rolled append-only WAL owned by this repo** — RocksDB rejected because it owns the WAL record format, the group-commit boundary and the recovery rules, which are the three things this project exists to decide; Go rejected because TLC is a Java program and the spec would leave the language. Commit = the group's `FileChannel.force(false)` (`fdatasync`) has returned; `force(true)` (`fsync`) only at open/rotate/checkpoint, plus an `fsync` of the WAL directory. Ships a green CI skeleton: `./build.sh` — lint, `javac --release 21 -Xlint:all -Werror`, and a five-check substrate contract test, no third-party dependencies. Note: `docs/adr/0001-substrate.md`.
- [What is a transaction? The double-entry domain model, proven in memory](https://github.com/sehaanurrahaman-creator/ledger-x/issues/4): **a transaction is n entries, not two** — ≥2 entries, Σ debits = Σ credits, every account already opened, no total or resulting balance allowed to overflow — so a two-account transfer is one unit of commit and standing design-review question 4 is a storage question, not a protocol one. Money is a signed `long` of integer minor units, one currency, exponent 2 as a constant, every operation overflow-checked; three account kinds (`ASSET`/`LIABILITY`/`EQUITY`) as **metadata only — no posting rule reads them**; balances **may go negative on every kind**; rejection is validate-then-apply-the-checked-deltas with a closed `RejectionReason` enum in a fixed order; one sealed event log (`AccountOpened`, `Posted`) where the sequence number is the position, and a transaction with no id, no timestamp and no idempotency key. Proved by a hand-rolled property suite with an independent `BigInteger` oracle: `PASS 9/9` at 12,000 random operations by default, 60,000 under `make campaign`. It found a real atomicity bug on its first run — validation range-checked per-account nets while application walked entries one at a time, so an intermediate balance overflowed *after* the event was appended. Note: `docs/adr/0002-domain-model.md`.
- [What does a durable write look like? WAL record format, fsync policy menu, torn-tail rules](https://github.com/sehaanurrahaman-creator/ledger-x/issues/5): **a record is 20 bytes of framing around the payload — `u32 frameLength`, `u64 lsn`, `u8 type`, `u8 reserved`, `u16 payloadLength`, payload, `u32 crc32C` over all of it** — under a 16-byte self-describing segment header, with **dense LSNs** (frame *n* carries *n*) so a hole is a tear rather than a mystery and an unmapped type byte is fatal rather than skippable. **The ack rule:** a commit is acknowledged only after the `force` covering its bytes returns — its own under `PER_COMMIT`, its group's under `GROUP_COMMIT(n, t)`, and `write()` returning under `NO_FSYNC`, which is the only promise that policy makes; the policy is a per-commit value, so a payout intent can demand a force inside a lazy log. **Torn tail:** recovery classifies before it repairs — short header / dishonest length / frame past EOF / CRC mismatch / LSN gap are *cuttable* because only an unfinished write can produce them, while an intact CRC-valid frame this reader cannot honour (unknown type, undecodable payload, a posting the domain refuses, head damage) **refuses to open**, because truncating at it would delete acked money to work around damage a crash cannot cause; the cut is made durable before the next append, and a `RECOVERY_MARKER` records what was cut and why (an audit record, *not* a resurrection guard — dense LSNs plus truncation already do that). Safety is a lemma, argued in the ADR: nothing past the last durable force was ever acknowledged, so a cut and an ack are disjoint. **Write amplification:** 72 bytes per realistic two-entry transfer, one `write` + one `fdatasync` per *group*, and — the part that matters — an `fdatasync` flushes whole pages, so a lone 72-byte commit is ~57× at the block layer against ~1.8× for a 64-record group: group commit's win is the page, not the syscall. Two findings the harness earned: an unconditional batch window is a **latency floor** (a lone writer waited the full 5 s per commit — `group:64:5000` cycles took 90 s against 0.3 s; the rule is now "collect only while the queue has work", asserted by a check that a lone writer gets *no* batching), and ADR 0001's `mmap` option stays deferred because `msync` is not offset-ordered, which is where the lemma would have to be re-proved. Evidence: a 19-check contract (exact bytes; 68 single-bit flips; all 113 truncate offsets; cut-vs-refuse split; per-policy force counts) and `./build.sh crash` — **1,000 `SIGKILL` cycles, 6,543 acks, 0 acked-but-lost under either forcing policy in both crash models, 284 lost under `NO_FSYNC`'s power-loss model, 114 torn cycles, 154 s**, with a second crash model (truncate past the last force) because `kill -9` alone would have passed `NO_FSYNC` and measured nothing. Note: `docs/adr/0003-wal-fsync.md`.
- [How is state proven identical after a crash? Checkpoints and byte-identical replay](https://github.com/sehaanurrahaman-creator/ledger-x/issues/6): **a checkpoint is a file named for its watermark** — `checkpoints/checkpoint-<19-digit lsn>.lckp`, a 72-byte header + canonical state section + a 36-byte trailer (`u8[32]` SHA-256 state hash, then `u32 crc32C` over everything before it) — swapped in with the order ADR 0003 handed over: delete the stale temp, write, `forceAll` (a checkpoint's *length* is part of its claim), `rename(2)`, `fsync(dirfd)`, then collect; newest two kept by default, because a refusal is only cheap when there is a second file to try. **The state hash is SHA-256 over one canonical serialization** — version, `lastLsn`, account count, then accounts in the log's opening order with kind-by-name, id and signed balance in minor units — so `lastLsn` is inside the identity, one list cannot disagree with another, and two runs agree exactly when the bytes do; the event list is *not* in it (the log is the history), and byte-identical is the conjunction: the state hash, the WAL contract's re-encoded-payload comparison, the checkpoint's `walDigest` binding the state to the exact covered log bytes (ADR 0003 §2 reuses LSNs after a cut, so a watermark is not an identity), and the harness's byte-prefix rule. **Determinism, enforced rather than asserted:** no wall clock, no randomness, no map/set iteration order (the serializer reads the log's opening order), no identity hash, no default locale or charset, no floating point, no environment identity, and recovery sorts candidates by parsed watermark rather than `readdir` order. **Refusal is a fallback, never an outage** — ten named refusals (a tear, a renamed file, a coverage the log lost, a checkpoint over a different history) each mean skip to the next candidate, else fold from byte 0, so deleting every checkpoint cannot lose money, which the contract asserts by deleting them all and requiring the same hash. Evidence: a 12-check checkpoint contract (the bytes at every offset; each integrity layer refused by its own name; a corrupt newest falling back with exactly one tail record folded; retention and stale temps; the swap's stage order; a restored ledger that audits) and `./build.sh boundary` — **every prefix of a seeded history in three configurations, `PASS 81/81` cycles, 81 real `SIGKILL`s, zero refusals, checkpoint + tail = a fold from byte 0 = a recovery with the checkpoints deleted**, plus a kill inside every stage of the swap. Pricing with the unfavourable row: a checkpoint write is O(covered) + O(state) (73–93 ms measured), and a reopen with one is **not** automatically faster (114 vs 169 ms at 22,000 records; 134 vs 128 ms when the state is large and the log short) — the checkpoint buys the guarantee and bounds the tail, not raw speed. Note: `docs/adr/0004-checkpoint-replay.md`.
````

## 5. Strike the state-hash item from *Not yet specified* (blocked)

**Paste-ready:** replace issue #1's entire `## Not yet specified` section with the block below. The
only change is the removal of its first item, which §3 of the ADR answers.

````
## Not yet specified

- The chaos **fault set and oracle** in final form. Hangs on storage + the payout protocol.
- The **benchmark workload** shape (accounts, hotness, mix). Hangs on the domain model + concurrency decisions.
- **TLC bounds** and state-space sizes worth exhausting. Hangs on idempotency semantics + the WAL protocol.
- **Public API surface** — in-process library vs HTTP service. Sharpens once idempotency semantics are fixed.
- The **cross-shard transfer design** argued in `STRIPE-DIFFS.md`. Hangs on the Stripe research.
- **CI budget** for the chaos campaign and the TLC runs. Hangs on timing data from the benchmarks.
````

## 6. What was verified, and by what

**The write wall, measured rather than assumed — and it is only the issues that are walled.** What
*works*: `git push origin arena/01a0afb5-ledger-x` (`* [new branch]`, exit 0) and
`gh pr create` ([#24](https://github.com/sehaanurrahaman-creator/ledger-x/pull/24)), with CI running on both
the push and the pull request. What is *blocked*: `gh issue comment 6 --body …` answered
`Message: Resource not accessible by integration`, and `gh api repos/sehaanurrahaman-creator/ledger-x --jq '.permissions'`
reports `{"admin":false,"maintain":false,"pull":false,"push":false,"triage":false}` — the App's installation
lacks `issues: write`, which `scripts/ci-evidence.sh` also states as "all issue writes 403 for this integration
when triage=false". Assignment specifically: `gh api repos/…/assignees` lists only `sehaanurrahaman-creator`,
so a self-assignment claim can never resolve for this identity. #6 is therefore still **OPEN and
unassigned** as this file was written: the claim, the resolution comment, the close and the two map
edits are all carried here for a human. (ADR 0003's closeout reached the same split from the other
side: "this integration *can* merge a pull request, it just cannot close the ticket".)

**CI, read back rather than assumed** (`scripts/ci-evidence.sh arena/01a0afb5-ledger-x`): run
`35234797105` on the pull request, `success` on `a332454` — one job, all steps — with these notice lines:
`PASS 5/5 substrate checks` (measured `platformThreads=10`), `PASS 19/19 wal checks`,
**`PASS 12/12 checkpoint checks`**, `PASS 9/9 property checks` at both the default campaign and the
extended one (307 cases, 30,344 accepted), **`PASS 81/81 boundary cycles byte-identical`**, and
`PASS 1000/1000 kill -9 cycles — zero invariant violations, zero acked-but-lost forced transactions;
unforced acks lost across a power cut: 277 (which is what NO_FSYNC promises)`. The checkpoint step and
the boundary step are new in `ci.yml` and both fit the budget: the job finished in under four minutes.

**Locally, with the fallback toolchain** (`scripts/bootstrap-toolchain.sh`: Temurin 21.0.8 runtime +
Eclipse JDT batch compiler, because no JDK distribution host is reachable from this sandbox):

- `./build.sh` end to end, **exit 0**: lint over 69 files including the two self-tested ADR rules, 37
  main and 17 test sources compiled, `PASS 5/5 substrate checks`, `PASS 19/19 wal checks`,
  **`PASS 12/12 checkpoint checks`**, `PASS 9/9 property checks` — the domain suite unchanged and
  green, which matters because this ticket changed `InMemoryLedger` (a restored base and an audit rule)
  and `DurableLedger` (recovery and cadence).
- `./build.sh boundary` at its default 16 operations, seed 20260918: **`PASS 81/81 boundary cycles
  byte-identical`** — 81 cycles, 81 real `SIGKILL`s, 81 recoveries, 125 tail records folded, 56
  recoveries using a checkpoint, 20 s wall clock. `./build.sh boundary` is the new CI step; the whole
  build stays under a minute.
- Two harness bugs found and fixed, both recorded in ADR 0004 §8 because each was a green light for
  something never tested: the child could not `destroyForcibly()` itself (`ProcessHandleImpl`: "destroy
  of current process not allowed"), so the first run's 51 failures were all the harness's own and the
  fix is an external `kill(1)` plus a `HALTING` marker; and the stage sweep's "after zero operations"
  arm never fired because the child armed the listener after an operation, so ten stage kills had
  simply not happened.
- Measured for §7, on this sandbox, and stated in the ADR with the unfavourable row rather than one:
  checkpoint write 73 ms (22,000 records, 47 KB state) and 93 ms (21,000 records, 489 KB state);
  reopen-with-checkpoint against reopen-from-byte-0, 114 vs 169 ms, and — the row worth keeping —
  **134 vs 128 ms**, where the state is large and the log short and the checkpoint does not pay for
  itself in time. The checkpoint's case here is the proof and the bound, not raw speed.

**Not verified here, and labelled as such in the ADR:** any real power loss (the harness kills a process, so the page cache survives, and inside the
swap the kill is a stage boundary rather than a byte-level interleaving), a lying controller or
out-of-order device, multi-process checkpoint writing, and rotation — which remains unstarted, with
ADR 0004 §11 recording the order it must follow and the `COVERAGE_GONE` check as the guard.

**The rollback rule, still in force:** after any reconnect or restored sandbox, re-read `git log` and
`git ls-remote origin` before trusting that a commit exists, and never push a `HEAD` that was not just
looked at. `build/` is wiped at every turn boundary; `scripts/bootstrap-toolchain.sh` restores the
toolchain (Temurin 21.0.8 + ECJ 3.45.0) without a network surprise.
