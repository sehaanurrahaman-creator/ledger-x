# Closeout actions for *What does a durable write look like? WAL record format, fsync policy menu, torn-tail rules*

The decision is recorded as [`docs/adr/0003-wal-fsync.md`](./0003-wal-fsync.md), implemented in
`src/main/java/dev/ledgerx/wal/` and `src/main/java/dev/ledgerx/journal/`, and proved by
`src/test/java/dev/ledgerx/wal/` (`WalContract`, 19 checks) and
`src/test/java/dev/ledgerx/wal/crash/` (`CrashHarness`, 1,000 `kill -9` cycles). It is committed on
branch `arena/01a0a4f8-ledger-x` as four commits — `315e06b` (the implementation and the two suites),
`6d7db40` (this ADR, the README, and the harness's environment guard), this file plus the resolution
comment, and `d0d41aa` (a one-word fix CI asked for, §6). **[PR #23](https://github.com/sehaanurrahaman-creator/ledger-x/pull/23)**
carries all four and **CI is green on them** (run `35150070017`); the verdict lines are quoted in §6
because a claim about CI that was not read back is not evidence.

**Everything the ticket asked for is in those commits; what is left is GitHub-side writing this
integration cannot do.** Two credentials lived and died mid-session, which is worth recording because
it changed the artifacts rather than only the schedule. The first (`arena-…`, 24 characters) began the
session answering `gh api user` fine and ended it on `401 Bad credentials`. The token attached after a
reconnect writes *repo contents* — `git push` succeeds against `refs/heads/arena/01a0a4f8-ledger-x` —
while issue comments stay `403 Resource not accessible by integration`, so the closeout below is
payload files and not posted comments, as in [ADR 0002's closeout](./0002-domain-model.closeout.md).
The failure that cost real time was the other one: **the sandbox was restored from a snapshot between
two turns and reverted the three commits** — `HEAD` back at `433f820`, `build/` gone, the harness log
gone — while leaving every edited file intact as uncommitted work. The push that followed landed a
branch identical to `main`, so `gh pr create` answered `No commits between main and
arena/01a0a4f8-ledger-x`. The rule this session learned is in §6.

Two things the session measured rather than assumed, both written into the ADR rather than smoothed
over: the issue-side writes are refused by *permission*, not by a dead token — after the credential
recovered, `POST …/issues/5/comments` answered `403 Resource not accessible by integration` again while
`GET …/issues/5` returned the ticket and `git push` returned `d0d41aa`, and the permission object still
reads `{"admin":false,"maintain":false,"pull":false,"push":false,"triage":false}`. So #5 will not close
itself when #23 merges, and the claim comment in §1 stays a payload file. The distinction is worth the
sentence: reconnecting GitHub fixes pushes and not issue comments, and a future session should not spend
a token rotation hunting a bug that is a scope setting.

## 0. Fastest route

1. **Merge #23** — `gh pr merge 23 --squash`, which is how #20 and #22 landed as well: this integration
   *can* merge a pull request, it just cannot close the ticket that request resolves (§0 step 2, and
   ADR 0001's closeout table). CI ran all four steps green on `d0d41aa` (run `35150070017`): lint over 56 files,
   `javac --release 21 -Xlint:all -Werror`, `PASS 5/5 substrate checks`, **`PASS 19/19 wal checks`**, the
   demo, **`PASS 1000/1000 kill -9 cycles — zero invariant violations, zero acked-but-lost forced
   transactions; unforced acks lost across a power cut: 284`**, and `PASS 9/9 property checks` at both
   campaign sizes. The crash step fits the budget: the whole job finished in under four minutes.
   `scripts/ci-evidence.sh <branch>` is the way to read any of that back, and it is the tool that turned
   "CI failed" into the one-line `[serial]` diagnosis in §6.
2. **Merge it.** The PR body is [`0003-wal-fsync.resolution-comment.md`](./0003-wal-fsync.resolution-comment.md)
   verbatim, so add one line saying that merging will *not* close #5: this integration has no
   `issues: write`, and a `Closes #5` in a body silently no-ops for an App that cannot write issues.
3. **A human then does three things this integration cannot**: paste §2 as a comment on #5, close #5
   (`state_reason=completed`), and replace the map's *Decisions so far* with §4 — which carries
   **four** lines, because the three before it are still unappended.

## 1. Claim (blocked — `403 Resource not accessible by integration`)

```
**Claimed.** Worked in session `arena/01a0a4f8-ledger-x` (2026-09-16). Self-assignment is still not
available to this integration, so this comment is the claim record: treat *What does a durable write
look like?* as off the frontier. Scope: the WAL record format, the fsync policy menu, the torn-tail
rules, the ack rule, and the kill -9 harness ADR 0003 cites. Checkpoints and the durable state hash
stay with *How is state proven identical after a crash?*; idempotency, concurrency and the payout
protocol stay with their own tickets.
```

```
gh api -X POST repos/{owner}/{repo}/issues/5/comments -f body='<the text above>'
```

## 2. Resolution comment (blocked — post-ready as-is)

The body is [`0003-wal-fsync.resolution-comment.md`](./0003-wal-fsync.resolution-comment.md): no
bot-permission meta, every link pointing at `main`, and it ends by naming the map line it owes.

```
gh api -X POST repos/{owner}/{repo}/issues/5/comments \
  -F body=@docs/adr/0003-wal-fsync.resolution-comment.md
```

## 3. Close the ticket (blocked)

```
gh api -X PATCH repos/{owner}/{repo}/issues/5 -f state=closed -f state_reason=completed
```

## 4. Append to the map's *Decisions so far* (blocked)

**Paste-ready:** replace issue #1's entire `## Decisions so far` section (heading through the blank
line before `## Not yet specified`) with the block below, as one edit. The first three lines are
carried verbatim from [`0002-domain-model.closeout.md`](./0002-domain-model.closeout.md) §4 — they
are still unappended, because #2, #3 and #4 closed with zero comments from an identity that could not
write issues — and the fourth is this ticket's.

````
## Decisions so far

<!-- Append one line per closed ticket, newest last: - [ticket title](link): one-line gist of the answer. Nothing lives here until the first ticket closes. -->

- [What do Stripe's public API and engineering writing actually say about idempotency and payout safety?](https://github.com/sehaanurrahaman-creator/ledger-x/issues/2): Stripe's guarantee is `(account, key) → stored status+body` for a **≥24h floor** after which the key is *forgotten and re-executes*, replays are flagged by `Idempotent-Replayed: true`, and **same-key/different-body is 400 `idempotency_error` while 409 `idempotency_key_in_use` means a concurrent duplicate that Stripe's own client retries** — so ledger-x's charter 409-on-mismatch and its GC policy are both deliberate divergences to justify; payouts can read `paid` and regress to `failed` within 5 business days. Note: `docs/research/stripe-idempotency.md`.
- [Substrate decision: Java + Loom or Go — and a hand-rolled log or RocksDB?](https://github.com/sehaanurrahaman-creator/ledger-x/issues/3): **Java 21 with virtual threads, on a hand-rolled append-only WAL owned by this repo** — RocksDB rejected because it owns the WAL record format, the group-commit boundary and the recovery rules, which are the three things this project exists to decide; Go rejected because TLC is a Java program and the spec would leave the language. Commit = the group's `FileChannel.force(false)` (`fdatasync`) has returned; `force(true)` (`fsync`) only at open/rotate/checkpoint, plus an `fsync` of the WAL directory. Ships a green CI skeleton: `./build.sh` — lint, `javac --release 21 -Xlint:all -Werror`, and a five-check substrate contract test, no third-party dependencies. Note: `docs/adr/0001-substrate.md`.
- [What is a transaction? The double-entry domain model, proven in memory](https://github.com/sehaanurrahaman-creator/ledger-x/issues/4): **a transaction is n entries, not two** — ≥2 entries, Σ debits = Σ credits, every account already opened, no total or resulting balance allowed to overflow — so a two-account transfer is one unit of commit and standing design-review question 4 is a storage question, not a protocol one. Money is a signed `long` of integer minor units, one currency, exponent 2 as a constant, every operation overflow-checked; three account kinds (`ASSET`/`LIABILITY`/`EQUITY`) as **metadata only — no posting rule reads them**; balances **may go negative on every kind**; rejection is validate-then-apply-the-checked-deltas with a closed `RejectionReason` enum in a fixed order; one sealed event log (`AccountOpened`, `Posted`) where the sequence number is the position, and a transaction with no id, no timestamp and no idempotency key. Proved by a hand-rolled property suite with an independent `BigInteger` oracle: `PASS 9/9` at 12,000 random operations by default, 60,000 under `make campaign`. It found a real atomicity bug on its first run — validation range-checked per-account nets while application walked entries one at a time, so an intermediate balance overflowed *after* the event was appended. Note: `docs/adr/0002-domain-model.md`.
- [What does a durable write look like? WAL record format, fsync policy menu, torn-tail rules](https://github.com/sehaanurrahaman-creator/ledger-x/issues/5): **a record is 20 bytes of framing around the payload — `u32 frameLength`, `u64 lsn`, `u8 type`, `u8 reserved`, `u16 payloadLength`, payload, `u32 crc32C` over all of it** — under a 16-byte self-describing segment header, with **dense LSNs** (frame *n* carries *n*) so a hole is a tear rather than a mystery and an unmapped type byte is fatal rather than skippable. **The ack rule:** a commit is acknowledged only after the `force` covering its bytes returns — its own under `PER_COMMIT`, its group's under `GROUP_COMMIT(n, t)`, and `write()` returning under `NO_FSYNC`, which is the only promise that policy makes; the policy is a per-commit value, so a payout intent can demand a force inside a lazy log. **Torn tail:** recovery classifies before it repairs — short header / dishonest length / frame past EOF / CRC mismatch / LSN gap are *cuttable* because only an unfinished write can produce them, while an intact CRC-valid frame this reader cannot honour (unknown type, undecodable payload, a posting the domain refuses, head damage) **refuses to open**, because truncating at it would delete acked money to work around damage a crash cannot cause; the cut is made durable before the next append, and a `RECOVERY_MARKER` records what was cut and why (an audit record, *not* a resurrection guard — dense LSNs plus truncation already do that). Safety is a lemma, argued in the ADR: nothing past the last durable force was ever acknowledged, so a cut and an ack are disjoint. **Write amplification:** 72 bytes per realistic two-entry transfer, one `write` + one `fdatasync` per *group*, and — the part that matters — an `fdatasync` flushes whole pages, so a lone 72-byte commit is ~57× at the block layer against ~1.8× for a 64-record group: group commit's win is the page, not the syscall. Two findings the harness earned: an unconditional batch window is a **latency floor** (a lone writer waited the full 5 s per commit — `group:64:5000` cycles took 90 s against 0.3 s; the rule is now "collect only while the queue has work", asserted by a check that a lone writer gets *no* batching), and ADR 0001's `mmap` option stays deferred because `msync` is not offset-ordered, which is where the lemma would have to be re-proved. Evidence: a 19-check contract (exact bytes; 68 single-bit flips; all 113 truncate offsets; cut-vs-refuse split; per-policy force counts) and `./build.sh crash` — **1,000 `SIGKILL` cycles, 6,543 acks, 0 acked-but-lost under either forcing policy in both crash models, 284 lost under `NO_FSYNC`'s power-loss model, 114 torn cycles, 154 s**, with a second crash model (truncate past the last force) because `kill -9` alone would have passed `NO_FSYNC` and measured nothing. Note: `docs/adr/0003-wal-fsync.md`.
````

## 5. Fog: nothing to file, three rules to hand across

The ADR's §10–§11 are the payload: **no new ticket** is graduated (the map's open set is unchanged),
and three things become rules inside existing tickets rather than questions in the fog — snapshot/WAL
fsync ordering and the "refuse a snapshot whose offset the log does not have" check
(*How is state proven identical after a crash?*); the idempotency key as a record type in the **same
group** as the posting, because there is no cross-record atomicity (*What are ledger-x's idempotency
semantics?*); and "validate → append → ack → apply is one critical section, and ack order must equal
apply order", plus the `validate`-without-`apply` hook this ticket needed from ADR 0002 and did not
take, which is why `DurableLedger` poisons itself on a storage failure (*How do a payout and a refund
to the same account never interleave?*). Rotation and retention remain unstarted deliberately: the
format is per-file self-describing and the LSN stream is continuous so that rotation stays additive.

## 6. What was verified, and by what

**Locally, with the fallback toolchain** (`scripts/bootstrap-toolchain.sh`: Temurin 21.0.8 runtime +
Eclipse JDT batch compiler, since no JDK distribution host is reachable from this sandbox):

- **CI, read back rather than assumed:** the first run on this branch (`35149368720`) failed in 11 s
  with a single warning — `[serial] non-transient instance field of a serializable class declared with a
  non-serializable type` on `UnrecoverableLogException`'s `Lsn lsn` field. The log line came from
  `gh api …/check-runs/<id>/annotations`, since `gh run view --log-failed` hit an `EOF` on the log
  download; that endpoint is the fallback worth knowing. Fixed by marking the field `transient` (the
  number a reader needs is already in the message, and records are serializable by definition, so
  `Corruption` needed nothing), and the ADR now carries it in §8 as a statement about toolchains: ECJ at
  `build.sh`'s flag set does not report it, so a locally green compile was evidence about the code and
  never about CI's lint vocabulary. Same shape as ADR 0002 §11's `@SuppressWarnings` divergence, one
  commit and one red run later.
- `./build.sh` end to end: lint over 56 files including the two self-tested ADR rules, 27 main and
  14 test sources compiled, `PASS 5/5 substrate checks`, **`PASS 19/19 wal checks`**,
  `PASS 9/9 property checks` — the domain suite unchanged and still green, which matters because this
  ticket added no domain type and touched only `DurableChannel` in `src/main/java/dev/ledgerx/substrate`.
- `./build.sh crash` twice: the first 1,000-cycle campaign reported 28 failures, all of them "the
  child acked nothing before it stopped", in consecutive cycles while another build was running in the
  same sandbox — a JVM that could not start, not a log that lost money. The harness now prints the
  child's exit code and captured output with that message and retries such a cycle once; the re-run is
  **`PASS 1000/1000 kill -9 cycles`, 6,543 acks, 154 s, 58 bytes per record, `killed=100` in every one
  of the ten policy×model rows**, and CI's own run reproduced the same 284 unforced losses exactly while
  the tear columns moved by a cycle or two — the workload is seeded, the instant of the kill is not. Retried-with-a-guard is recorded here because a green run after a guard is a
  different claim from a green run, and the difference should be visible.
- Three bugs the suites caught while this was being written, each now a check rather than a fix:
  `WalRecovery.readAt` looped on top of `DurableChannel.readAt`, which already loops, so a read at EOF
  returned `0` — an answer, not a request to retry — and recovery spun forever (found by the
  exhaustive truncate-offset check; a smoke test would never have hit it); an unconditional group-commit
  window stalled a lone writer for the window per commit; and the contract initially asserted the
  recovery marker reported *crash* damage (48 bytes) where the log honestly reports only the cut it made
  (11) — the product was right, the expectation was the bug, and the ADR now states the semantics
  because the two numbers are easy to conflate.
- The lint's 100-column rule is counted **as bytes**, and this ADR and its sources are full of em
  dashes and `Σ` and `⇒`: several lines were over the limit at 98 characters. Nothing to fix in the
  rule, everything to fix in the prose.

**Not verified here, and labelled as such in the ADR:** `-Xlint:all -Werror` under *javac* (CI is the
check of record; ECJ's vocabulary differs — ADR 0002 §11 records the `@SuppressWarnings("unchecked")`
divergence, and nothing here uses a suppression), which CPUs HotSpot's CRC32C intrinsic covers (the
slicing-by-8 fallback is what was read in source), device-level block writes (no `blktrace` below the
filesystem here, so §6 of the ADR is arithmetic and says so), and any real power loss — the second crash
model is a truncate, and both it and its limits are stated rather than implied.

**The rollback rule, earned the hard way:** after any reconnect or restored sandbox, re-read `git log`
and `git ls-remote origin` before trusting that a commit exists, and never push a `HEAD` you have not
just looked at — a branch can be pushed pointing at the commit it was rolled *back* to, which is
indistinguishable from success at the time and reads as "no commits between main and the branch" later.
`build/` is wiped at every turn boundary regardless: `scripts/bootstrap-toolchain.sh` restores the
toolchain, and its `/home/user/toolchain` cache did not survive either.

`scripts/ci-evidence.sh` is how CI evidence is read back for this branch; the numbers above are from
`crash-final.log` on this machine.
