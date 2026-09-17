# Closeout actions for *How is state proven identical after a crash? Checkpoints and byte-identical replay*

The decision is recorded as [`docs/adr/0004-checkpoint-replay.md`](./0004-checkpoint-replay.md),
implemented in `src/main/java/dev/ledgerx/checkpoint/` (six classes) and wired into
`dev.ledgerx.journal.DurableLedger` and `dev.ledgerx.domain.InMemoryLedger`, and proved by
`src/test/java/dev/ledgerx/checkpoint/CheckpointContract.java` (13 checks, on every `./build.sh`).
It is committed on branch `arena/01a0af9b-ledger-x` as three commits — `0cf16c7` (the implementation
and the suite), `2f36d63` (this ADR and the README), and `adf6836` (the `-Xlint:try` fix CI asked
for, §8 finding 4). **[PR #26](https://github.com/sehaanurrahaman-creator/ledger-x/pull/26)**
carries all three.

**Everything the ticket asked for is in those commits; what is left is GitHub-side writing this
integration cannot do.** `gh api repos/{owner}/{repo}` reports the permission object as
`{"admin":false,"maintain":false,"pull":false,"push":false,"triage":false}` and `gh api user`
answers `403 Resource not accessible by integration`, yet `git push` and `gh pr create` both
succeeded — so the split ADR 0003's closeout recorded is still the operative one, and sharper: this
integration can write *refs and pull requests* while every *issue* write is refused. #6 will not
close itself when #26 merges, and the resolution comment stays a payload file.

## 0. Fastest route

1. **Merge #26** — `gh pr merge 26 --squash`, which is how #20, #22 and #23 landed. **CI is green on
   `adf6836`** (run `35237479079`, 2m55s) and its annotations read back through the API confirm every
   suite under javac rather than ECJ: `PASS 5/5 substrate checks`, `PASS 19/19 wal checks`,
   **`PASS 13/13 checkpoint checks`**, `PASS 9/9 property checks` at both campaign sizes, and
   `PASS 1000/1000 kill -9 cycles — zero invariant violations, zero acked-but-lost forced
   transactions; unforced acks lost across a power cut: 290`. The first push (`2f36d63`) went **red
   in 17 seconds** on javac's `-Xlint:try` under `-Werror`, which the sandbox's ECJ does not report;
   §8 finding 4 records it and `adf6836` fixes it by naming the three handles rather than suppressing
   the warning. Read any of this back with
   `scripts/ci-evidence.sh arena/01a0af9b-ledger-x` — the runner's log blob is behind a URL this
   sandbox cannot fetch (`gh run view --log-failed` answers `EOF`), and the annotations that script
   reads are the only CI channel that comes back through the API. That is how the 17-second failure
   was diagnosed without ever seeing a log.
2. **Add one line to the PR body before merging:** merging will *not* close #6, because this
   integration has no `issues: write` and a `Closes #6` silently no-ops for an App that cannot write
   issues.
3. **A human then does three things this integration cannot**: paste §2 as a comment on #6, close #6
   (`state_reason=completed`), and replace the map's *Decisions so far* with §4 — which carries
   **five** lines, because the four before it are still unappended.

## 1. Claim (blocked — `403 Resource not accessible by integration`)

```
**Claimed.** Worked in session `arena/01a0af9b-ledger-x` (2026-09-17). Self-assignment is still not
available to this integration, so this comment is the claim record: treat *How is state proven
identical after a crash?* as off the frontier. Scope: the checkpoint format, the atomic swap
protocol, the retention rule, the state-hash function, and the crash-at-every-LSN-boundary harness
ADR 0004 cites. Segment rotation and log truncation stay unstarted; idempotency, concurrency and the
payout protocol stay with their own tickets.
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

**Paste-ready:** replace issue #1's entire `## Decisions so far` section (heading through the blank
line before `## Not yet specified`) with the block below, as one edit. The first four lines are
carried verbatim from [`0003-wal-fsync.closeout.md`](./0003-wal-fsync.closeout.md) §4 — still
unappended, because #2, #3, #4 and #5 all closed with an identity that could not write issues — and
the fifth is this ticket's.

````
## Decisions so far

<!-- Append one line per closed ticket, newest last: - [ticket title](link): one-line gist of the answer. Nothing lives here until the first ticket closes. -->

- [What do Stripe's public API and engineering writing actually say about idempotency and payout safety?](https://github.com/sehaanurrahaman-creator/ledger-x/issues/2): Stripe's guarantee is `(account, key) → stored status+body` for a **≥24h floor** after which the key is *forgotten and re-executes*, replays are flagged by `Idempotent-Replayed: true`, and **same-key/different-body is 400 `idempotency_error` while 409 `idempotency_key_in_use` means a concurrent duplicate that Stripe's own client retries** — so ledger-x's charter 409-on-mismatch and its GC policy are both deliberate divergences to justify; payouts can read `paid` and regress to `failed` within 5 business days. Note: `docs/research/stripe-idempotency.md`.
- [Substrate decision: Java + Loom or Go — and a hand-rolled log or RocksDB?](https://github.com/sehaanurrahaman-creator/ledger-x/issues/3): **Java 21 with virtual threads, on a hand-rolled append-only WAL owned by this repo** — RocksDB rejected because it owns the WAL record format, the group-commit boundary and the recovery rules, which are the three things this project exists to decide; Go rejected because TLC is a Java program and the spec would leave the language. Commit = the group's `FileChannel.force(false)` (`fdatasync`) has returned; `force(true)` (`fsync`) only at open/rotate/checkpoint, plus an `fsync` of the WAL directory. Ships a green CI skeleton: `./build.sh` — lint, `javac --release 21 -Xlint:all -Werror`, and a five-check substrate contract test, no third-party dependencies. Note: `docs/adr/0001-substrate.md`.
- [What is a transaction? The double-entry domain model, proven in memory](https://github.com/sehaanurrahaman-creator/ledger-x/issues/4): **a transaction is n entries, not two** — ≥2 entries, Σ debits = Σ credits, every account already opened, no total or resulting balance allowed to overflow — so a two-account transfer is one unit of commit and standing design-review question 4 is a storage question, not a protocol one. Money is a signed `long` of integer minor units, one currency, exponent 2 as a constant, every operation overflow-checked; three account kinds (`ASSET`/`LIABILITY`/`EQUITY`) as **metadata only — no posting rule reads them**; balances **may go negative on every kind**; rejection is validate-then-apply-the-checked-deltas with a closed `RejectionReason` enum in a fixed order; one sealed event log (`AccountOpened`, `Posted`) where the sequence number is the position, and a transaction with no id, no timestamp and no idempotency key. Proved by a hand-rolled property suite with an independent `BigInteger` oracle: `PASS 9/9` at 12,000 random operations by default, 60,000 under `make campaign`. It found a real atomicity bug on its first run — validation range-checked per-account nets while application walked entries one at a time, so an intermediate balance overflowed *after* the event was appended. Note: `docs/adr/0002-domain-model.md`.
- [What does a durable write look like? WAL record format, fsync policy menu, torn-tail rules](https://github.com/sehaanurrahaman-creator/ledger-x/issues/5): **a record is 20 bytes of framing around the payload — `u32 frameLength`, `u64 lsn`, `u8 type`, `u8 reserved`, `u16 payloadLength`, payload, `u32 crc32C` over all of it** — under a 16-byte self-describing segment header, with **dense LSNs** (frame *n* carries *n*) so a hole is a tear rather than a mystery and an unmapped type byte is fatal rather than skippable. **The ack rule:** a commit is acknowledged only after the `force` covering its bytes returns — its own under `PER_COMMIT`, its group's under `GROUP_COMMIT(n, t)`, and `write()` returning under `NO_FSYNC`, which is the only promise that policy makes. **Torn tails are cut, never skipped, and only the tail may be cut:** a finding a partial write could have produced is truncatable, a finding that needs a complete CRC-matching frame refuses to open with the file untouched. Proved by a 19-check contract (68 single-bit flips, all 113 truncate offsets) and **1,000 real `SIGKILL`s across two crash models — zero invariant violations, zero acked-but-lost forced transactions**, with `NO_FSYNC` losing 284 acknowledged records across a simulated power cut, which is exactly what it promises. Note: `docs/adr/0003-wal-fsync.md`.
- [How is state proven identical after a crash? Checkpoints and byte-identical replay](https://github.com/sehaanurrahaman-creator/ledger-x/issues/6): **"byte-identical state" is now a byte layout and a digest** — a canonical serialization of the materialized state (one `(id, kind, balance)` row per account, **sorted by id**, fixed-width, length-prefixed, nothing positional) and **SHA-256 over exactly those bytes**; `alpha ASSET −12345, beta LIABILITY +12345` is 37 bytes and hashes to `0a4815c507a759141b878e5e…`, pinned and cross-checked against `hashlib.sha256` outside the codebase. The determinism rules, each load-bearing: no clocks, no random ids, no floats (inherited), **no map-iteration order** — account-*opening* order is not a function of the state, because a restored ledger opens checkpointed accounts before the tail's while a from-scratch fold opens them in log order — and the ban this ticket adds, **nothing positional**, because ADR 0003 makes LSNs unique only within a surviving prefix. A **checkpoint is 68 bytes of header around those exact bytes** (the payload *is* the hash preimage), CRC32C for tears and SHA-256 for lies, written **temp → fsync → atomic rename → fsync dir**, retained as **one live file** because the log is never truncated, so replay-from-scratch never depends on one. Recovery is checkpoint + tail, and **a checkpoint that fails any of three checks is discarded and the whole log folded** — the deliberate asymmetry with ADR 0003, where a damaged log is an outage: a snapshot is a cache, so damage to it is a miss. The load-bearing check is the third: a stale snapshot over a log that was **cut and then regrown** passes the byte test and the LSN test, and using it would resurrect transactions recovery already discarded, so the claimed offset is compared with where that record ends *now*. Proved by a 13-check contract whose headline walks **every LSN boundary of every random history**, recovers three ways, and compares each digest with the same prefix folded in memory — **26,000 boundaries, 3 recoveries each, 100% byte-identical** at the extended campaign, plus all 145 truncations of a checkpoint, six doctored files, and `NO_FSYNC` refusing to snapshot unforced records. It broke an existing WAL check on the way, correctly: after a checkpointed recovery the in-memory event list is the tail, not the history. Note: `docs/adr/0004-checkpoint-replay.md`.
````

## 5. What the session actually measured, for the next one

- **The toolchain split is now three for three.** ADR 0002 §11 recorded ECJ ignoring
  `@SuppressWarnings("unchecked")`, ADR 0003 §6 recorded javac's `-Xlint:serial` on a non-transient
  field, and this ticket adds javac's `-Xlint:try` on an unreferenced try-with-resources resource —
  each one green under `scripts/bootstrap-toolchain.sh` and red in CI. The rule this repository
  should stop re-learning: **the sandbox's compiler is a fallback and CI's javac is the check of
  record**, so a local green build is worth reading but not worth trusting.
- **`gh run view --log-failed` does not work from this sandbox** — the results blob answers `EOF` —
  and `scripts/ci-evidence.sh` is the only way back in. It returned all 13 annotations for the
  17-second failure, including the three file:line pairs, which was enough to fix the build without
  ever seeing a log line. Worth knowing before spending a turn on `--log`.
- **A harness that only compares recoveries against each other can pass while being wrong.** The
  boundary harness's first run had all three recovery paths agreeing and all three disagreeing with
  the in-memory fold, because `FileChannel.truncate` cannot grow a file and every cut after the first
  was a no-op. The in-memory fold is not a fourth opinion, it is the reference; §8 finding 3 keeps the
  detail because the shape of the symptom is the useful part.
- **Unstarted, deliberately:** segment rotation and log truncation, which is the trigger that turns a
  checkpoint from a cache into a load-bearing part of the truth (§5), and a `SIGKILL` aimed at the
  rename rather than a simulated outcome (§8, handed to the chaos ticket).
