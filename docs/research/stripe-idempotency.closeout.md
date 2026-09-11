# Closeout actions for *What do Stripe's public API and engineering writing actually say about idempotency and payout safety?*

The ticket's research is done and committed as
[`docs/research/stripe-idempotency.md`](./stripe-idempotency.md) (commit `c49f269`, branch
`arena/01a0920a-ledger-x`). Part of the GitHub closeout went through; part is blocked by
permissions. This file records exactly which, and holds the remaining payloads in final form so
they can be posted verbatim by an identity that has the permission.

## What worked, what didn't

Probed 2026-09-11 from session `arena/01a0920a-ledger-x` as `arena-ai-coding-agent[bot]`. The token
is not uniformly read-only — it can *create* issues and wire relationship edges, but it cannot
*comment on, edit, assign, or close* one.

| Action | Call | Result |
| --- | --- | --- |
| Create the graduated fog ticket | `POST /repos/{owner}/{repo}/issues` | **done** → [#15](https://github.com/sehaanurrahaman-creator/ledger-x/issues/15) |
| Attach it to the map | GraphQL `addSubIssue(#1, #15)` | **done** — `parent_issue_url` on #15 now points at #1 |
| Block it on the idempotency-semantics ticket | GraphQL `addBlockedBy(#15, #7)` | **done** — #15 `blocked_by: 2` |
| Block it on the domain-model ticket | GraphQL `addBlockedBy(#15, #4)` | **done** |
| Make `STRIPE-DIFFS.md` depend on it | GraphQL `addBlockedBy(#14, #15)` | **done** — #14 `blocked_by: 6` |
| Push the note | `git push origin arena/01a0920a-ledger-x` | **done** — `contents=write` is present |
| Open a pull request | `gh pr create` → [#16](https://github.com/sehaanurrahaman-creator/ledger-x/pull/16) | **done** — `pull_requests=write` is present |
| Comment on that pull request | `POST …/issues/16/comments` | **done** — see the note below |
| Claim by self-assigning | `POST …/issues/2/assignees`; `PATCH …/issues/2`; GraphQL `addAssigneesToAssignable` | **403** × 3 paths |
| Post the resolution comment | `POST …/issues/2/comments`; GraphQL `addComment` | **403** × 2 paths, retested after the PR comment succeeded |
| Close the ticket | `PATCH …/issues/2`; GraphQL `closeIssue` | **403** × 2 paths |
| Append the *Decisions so far* line | `PATCH …/issues/1` (needs `updateIssue`) | **403** |

Every 403 came back as `Resource not accessible by integration`, and the comment call returned
`x-accepted-github-permissions: issues=write; pull_requests=write`. `GET /repos/{owner}/{repo}`
reports `{"admin":false,"maintain":false,"pull":false,"push":false,"triage":false}`. So: **the GitHub
connection needs `issues:write` for comments/edits/closes** — reconnecting GitHub in Arena with that
scope unblocks §1–§4 below.

**The split is issues-vs-pull-requests, not a blanket write block.** The same token, minutes apart:
`POST …/issues/16/comments` on **pull request** #16 returned a comment URL, while
`POST …/issues/2/comments` and `POST …/issues/15/comments` — both **issues**, one of them created by
this same integration — both returned 403. So a future session should not read the 403 as "GitHub is
read-only": creating issues, wiring sub-issue/blocked-by edges, opening PRs and commenting on them all
work. Only issue comments, issue edits (including close) and assignment are refused.

Issue #2 verified still `state: open`, `comments: 0`, `assignees: 0` after the attempts.

---

## 1. Claim (blocked)

```
**Claimed.** Working this ticket in session `arena/01a0920a-ledger-x`.

Self-assignment is not available to this integration: `POST /repos/{owner}/{repo}/issues/2/assignees`,
`PATCH /repos/{owner}/{repo}/issues/2` and the `addAssigneesToAssignable` GraphQL mutation all return
`403 Resource not accessible by integration` (the bot is also absent from the repo's assignable list —
only `sehaanurrahaman-creator` is). This comment is therefore the claim record; treat the ticket as
off the frontier from here.
```

## 2. Resolution comment on the ticket (blocked)

The body is in `stripe-idempotency.resolution-comment.md` alongside this file, ready to post as-is:

```
gh api -X POST repos/{owner}/{repo}/issues/2/comments \
  -F body=@docs/research/stripe-idempotency.resolution-comment.md
```

## 3. Close the ticket (blocked)

```
gh api -X PATCH repos/{owner}/{repo}/issues/2 -f state=closed -f state_reason=completed
```

## 4. Append to the map's *Decisions so far* (blocked)

Insert as the last line under `## Decisions so far` in issue #1, keeping the HTML comment intact:

```
- [What do Stripe's public API and engineering writing actually say about idempotency and payout safety?](https://github.com/sehaanurrahaman-creator/ledger-x/issues/2): Stripe's guarantee is `(account, key) → stored status+body` for a **≥24h floor** after which the key is *forgotten and re-executes*, replays are flagged by `Idempotent-Replayed: true`, and **same-key/different-body is 400 `idempotency_error` while 409 `idempotency_key_in_use` means a concurrent duplicate that Stripe's own client retries** — so ledger-x's charter 409-on-mismatch and its GC policy are both deliberate divergences to justify; payouts can read `paid` and regress to `failed` within 5 business days. Note: `docs/research/stripe-idempotency.md`.
```

## 5. Graduate the fog (done, except the strike)

**Done:** the map's fog item *"The **cross-shard transfer design** argued in `STRIPE-DIFFS.md`. Hangs on
the Stripe research."* is graduated into
[How does a two-account transfer stay atomic across shards without 2PC? The argument for STRIPE-DIFFS.md](https://github.com/sehaanurrahaman-creator/ledger-x/issues/15),
a child of the map, blocked by *What are ledger-x's idempotency semantics?…* and *What is a
transaction? The double-entry domain model, proven in memory*, and itself blocking *What did Stripe
teach us, and what breaks at 10M merchants? STRIPE-DIFFS.md and the design review*.

**Still pending:** striking that bullet from *Not yet specified* in issue #1 — it needs `PATCH`,
which is blocked. Until it is struck, the map lists a fog item that already has a ticket; #15 is the
ticket, and this line is the record that the strike is owed.

## 6. Nothing else graduates

Checked against the map's other fog, deliberately left in place: the replay state-hash scheme and the
TLC bounds both still hang on idempotency semantics (that ticket is still open); the public API
surface sharpens only once those semantics are *fixed*; the chaos fault set and the benchmark
workload hang on storage and payout decisions this note doesn't touch. Three findings feed existing
tickets rather than creating new ones — the five key states go to *What must TLC prove?*, the
pre-layer fault surface and the concurrent same-key race go to *What breaks it?*, and the
`paid` → `failed` regression goes to *How does a payout become exactly-once across the bank
boundary?* All three are written up in §10 of the note.
