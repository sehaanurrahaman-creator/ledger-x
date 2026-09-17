# Closeout actions for *What are ledger-x's idempotency semantics? Scoping, expiry, the 409-vs-200 trap, GC reuse, clock skew*

Ticket: [#7](https://github.com/sehaanurrahaman-creator/ledger-x/issues/7). Session:
`arena/01a0b02d-ledger-x`, 2026-09-17. The ADR is [`0005-idempotency.md`](./0005-idempotency.md);
the client-facing contract is [`../idempotency.md`](../idempotency.md); the post-ready issue
comment is [`0005-idempotency.resolution-comment.md`](./0005-idempotency.resolution-comment.md).

The permission split is the same one ADR 0004's closeout mapped and the research ticket's
closeout probed: **branch pushes, pull requests (created and commented on), issue creation and
relationship edges work; commenting on issues, editing them, assigning and closing them do not**
(`Resource not accessible by integration`; the connection needs `issues: write`). So the
graduation step below is *done* — ticket [#27](https://github.com/sehaanurrahaman-creator/ledger-x/issues/27)
was created by this session, attached to the map, and wired blocked-by this ticket — and the
claim, comment, close and map-append steps are carried here as paste-ready text.

## 0. Fastest route

1. **The pull request exists and is green**: [#28](https://github.com/sehaanurrahaman-creator/ledger-x/pull/28),
   body = [`0005-idempotency.resolution-comment.md`](./0005-idempotency.resolution-comment.md)
   verbatim, CI `pass` on `ab33633` (run `35256255161`, 2m22s: lint with rules 6/7/8
   self-tested, javac `--release 21 -Xlint:all -Werror` on main and test, the four contracts
   5/5 · 20/20 · 13/13 · 15/15, both property suites 9/9 · 3/3, the demo, the 1,000-cycle
   kill -9 harness, `PASS 71/71` boundary cycles and both extended campaigns). Merging it will
   **not** close #7: this integration has no `issues: write`, the same finding as ADR 0004's
   closeout.
2. **A human then does three things**: paste §2 as a comment on #7; close #7 as completed;
   and make the two map edits — replace the map's *Decisions so far* with §4 and replace
   *Not yet specified* with §5, which strikes the *Public API surface* item this ticket
   graduated into [#27](https://github.com/sehaanurrahaman-creator/ledger-x/issues/27).

## 1. Claim (blocked — `403 Resource not accessible by integration`)

```
**Claimed.** Worked in session `arena/01a0b02d-ledger-x` (2026-09-17). Self-assignment is not
available to this integration (the assignee list holds only `sehaanurrahaman-creator`), so this
comment is the claim record: treat *What are ledger-x's idempotency semantics?* as off the
frontier. Scope: the (merchant, key) contract, the request fingerprint, the 409-vs-200
precedence, the single-record commit, expiry and GC, clock skew, the table's homes in memory /
log / checkpoint, and the evidence suites. The HTTP surface that will carry the statuses is
explicitly out — graduated to "What is ledger-x's public API surface?" (#27), wired blocked-by
this ticket.
```

```
gh api -X POST repos/{owner}/{repo}/issues/7/comments -f body='<the text above>'
```

## 2. Resolution comment (blocked — post-ready as-is)

The body is [`0005-idempotency.resolution-comment.md`](./0005-idempotency.resolution-comment.md):
no bot-permission meta, every link pointing at `main` (post-merge), and it ends by naming the two
map edits it owes.

```
gh api -X POST repos/{owner}/{repo}/issues/7/comments \
  -F body=@docs/adr/0005-idempotency.resolution-comment.md
```

## 3. Close (blocked)

```
gh issue close 7 --repo sehaanurrahaman-creator/ledger-x --reason completed \
  --comment "Closed by the ADR 0005 pull request; the resolution comment above is the record."
```

## 4. Map edit 1 — *Decisions so far*

The section on the map is still carrying only its comment: the five lines the earlier closeouts
kept post-ready (research #2, substrate #3, domain #4, durable write #5, checkpoint #6) have not
been appended by an identity with `issues: write` either. This replacement carries all six, so
one paste clears the backlog and this ticket's line together. Replace everything between
`## Decisions so far` and `## Not yet specified` with:

```
- [What do Stripe's public API and engineering writing actually say about idempotency and payout safety?](https://github.com/sehaanurrahaman-creator/ledger-x/issues/2): Stripe replays stored responses for 24 h, 400s a same-key-different-body, and prunes keys after — the divergence ledger-x answers with a permanent 409; nine documented gaps marked, every claim tiered by source, in `docs/research/stripe-idempotency.md`.
- [Substrate decision: Java + Loom or Go — and a hand-rolled log or RocksDB?](https://github.com/sehaanurrahaman-creator/ledger-x/issues/3): Java 21 with virtual threads (no `synchronized` anywhere — per-account `ReentrantLock`) and a hand-rolled append-only WAL; no storage-engine dependency (ADR 0001).
- [What is a transaction? The double-entry domain model, proven in memory](https://github.com/sehaanurrahaman-creator/ledger-x/issues/4): n-entry transactions with Σ debits = Σ credits, integer minor units in a `long`, balances allowed negative, one append-only event log, and rejection that changes nothing (ADR 0002).
- [What does a durable write look like? WAL record format, fsync policy menu, torn-tail rules](https://github.com/sehaanurrahaman-creator/ledger-x/issues/5): 20 bytes of framing per record — length, dense LSN, type, payload, CRC32C over all four — a three-item fsync menu with the ack-under-watermark rule, torn tails cut and recorded, damaged heads refused (ADR 0003).
- [How is state proven identical after a crash? Checkpoints and byte-identical replay](https://github.com/sehaanurrahaman-creator/ledger-x/issues/6): a checkpoint is a file named for its watermark, swapped in atomically and refused by name — never guessed at — and the state hash makes the run's own hash, the recovery's and a fold from byte 0 one comparison, crashed at every prefix by the boundary harness (ADR 0004).
- [What are ledger-x's idempotency semantics? Scoping, expiry, the 409-vs-200 trap, GC reuse, clock skew](https://github.com/sehaanurrahaman-creator/ledger-x/issues/7): `(merchant, key) → response` with the posting and the binding in one record — one append, one fsync, one ack; a retry replays the stored response flagged as a replay; a different body is 409 at any age, because a 200-replay would hide the divergence; expiry is a 410 refusal that never re-arms (no GC — a key never means a second intent); clock skew moves the window only, floored at zero (ADR 0005).
```

## 5. Map edit 2 — *Not yet specified*

Strike the graduated line, leave the rest:

```
- The replay **state-hash scheme** — how "byte-identical" is canonicalized. Hangs on the WAL-format and checkpoint decisions.
- The chaos **fault set and oracle** in final form. Hangs on storage + the payout protocol.
- The **benchmark workload** shape (accounts, hotness, mix). Hangs on the domain model + concurrency decisions.
- **TLC bounds** and state-space sizes worth exhausting. Hangs on idempotency semantics + the WAL protocol.
- The **cross-shard transfer design** argued in `STRIPE-DIFFS.md`. Hangs on the Stripe research.
- **CI budget** for the chaos campaign and the TLC runs. Hangs on timing data from the benchmarks.
```

(The *Public API surface* item is gone — it is now
[What is ledger-x's public API surface?](https://github.com/sehaanurrahaman-creator/ledger-x/issues/27),
created by this session, attached to the map, and blocked by this ticket until it closes.)

## 6. What this session already did on GitHub

| Action | Result |
| --- | --- |
| Create the graduated fog ticket | [#27](https://github.com/sehaanurrahaman-creator/ledger-x/issues/27) — *What is ledger-x's public API surface? In-process library vs HTTP service, and the 409/410 mapping* |
| Attach it to the map (`addSubIssue` → #1) | done — `parent_issue_url` on #27 points at #1 |
| Wire `#27 blocked by #7` (`addBlockedBy`) | done — verified read-back |
| Push this branch | done — `arena/01a0b02d-ledger-x`, head `ab33633` |
| Open the pull request | done — [#28](https://github.com/sehaanurrahaman-creator/ledger-x/pull/28), body is the resolution comment verbatim |
| CI on the pull request | done — `pass`, run `35256255161` |
| Claim / comment on / close #7 | blocked — `issues: write` needed, §1–§3 |
| Append the *Decisions so far* line | blocked — same, §4 |
