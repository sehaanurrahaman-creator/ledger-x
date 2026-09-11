# Closeout actions for *Substrate decision: Java + Loom or Go — and a hand-rolled log or RocksDB?*

The decision is recorded as [`docs/adr/0001-substrate.md`](./0001-substrate.md), the CI skeleton on the chosen
stack is green on branch `arena/01a09264-ledger-x`, and the fog it sharpened is graduated. The GitHub-side closeout
is partly blocked by permissions; this file records exactly which parts, and holds the remaining payloads in final
form so they can be posted verbatim by an identity that has the permission.

## What worked, what didn't

Probed 2026-09-11 from session `arena/01a09264-ledger-x` as `arena-ai-coding-agent[bot]`.

| Action | Call | Result |
| --- | --- | --- |
| Push the branch | `git push origin arena/01a09264-ledger-x` | **done** — 4 commits, `contents=write` present |
| Run CI on it | GitHub Actions, `.github/workflows/ci.yml` | **done** — runs `34652362122`, `34652600248`, `34652731695`, `34652834123`, all `completed/success` |
| Read the CI verdict | check-run annotations API | **done** — `notice: PASS 5/5 substrate checks` on check run `103438670842` |
| Create the graduated fog tickets | `POST /repos/{owner}/{repo}/issues` | **done** → [#17](https://github.com/sehaanurrahaman-creator/ledger-x/issues/17), [#18](https://github.com/sehaanurrahaman-creator/ledger-x/issues/18) |
| Attach them to the map | GraphQL `addSubIssue` × 2 | **done** — both appear under #1's sub-issues (17 children now) |
| Wire the blocking edges | GraphQL `addBlockedBy` × 4 | **done** — `#17 blockedBy=[3] blocking=[13]`, `#18 blockedBy=[3] blocking=[11]` |
| Open a pull request | `gh pr create` | **done** — see the link at the foot of this file |
| Claim by self-assigning | `POST …/issues/3/assignees`; `PATCH …/issues/3`; GraphQL `addAssigneesToAssignable` | **blocked** — 403 × 2, plus the bot is not assignable at all |
| Post the claim comment | `POST …/issues/3/comments` | **blocked** — 403 |
| Post the resolution comment | same endpoint, retested after the PR opened | **blocked** — 403 |
| Close the ticket | `PATCH …/issues/3` with `state=closed`; GraphQL `closeIssue` | **blocked** — 403 × 2 |
| Append the *Decisions so far* line | `PATCH …/issues/1` | **blocked** — 403 |
| Read Actions step logs | `gh run view --log` | **blocked by the sandbox, not by GitHub** — `results-receiver.actions.githubusercontent.com` is unreachable here; step conclusions and annotations are readable via `api.github.com` |

`GET /repos/{owner}/{repo}` still reports `{"admin":false,"maintain":false,"pull":false,"push":false,"triage":false}`.
The split is the same one the previous session found: **issues are read-only for this integration, pull requests and
repo contents are writable.** Reconnecting GitHub in Arena with `issues:write` unblocks §1–§4 below.

**Two schema notes for the next session**, because the GraphQL schema has moved since the earlier closeout was
written:

- `addBlockedBy` takes `{issueId, blockingIssueId}` — *not* `blockedIssueId`, which now returns
  `argumentNotAccepted`.
- `addAssigneesToAssignable` no longer accepts `assigneeUserNames`. Moot here anyway: `GET …/assignees` returns
  exactly one assignable user, `sehaanurrahaman-creator`, so the bot could not be self-assigned even with write
  access. A claim comment is the only claim record this integration can leave.

---

## 1. Claim (blocked)

```
**Claimed.** Working this ticket in session `arena/01a09264-ledger-x`.

Self-assignment is not available to this integration, on two independent counts: `GET /repos/{owner}/{repo}/assignees`
returns exactly one assignable user — `sehaanurrahaman-creator` — so there is no login to assign; and every write path
is refused anyway (`POST …/issues/3/assignees` → 403, `PATCH …/issues/3` with `assignees[]` → 403, GraphQL
`addAssigneesToAssignable` no longer accepts `assigneeUserNames`). This comment is therefore the claim record:
treat the ticket as off the frontier.
```

## 2. Resolution comment (blocked)

The body is in [`0001-substrate.resolution-comment.md`](./0001-substrate.resolution-comment.md), ready to post as-is:

```
gh api -X POST repos/{owner}/{repo}/issues/3/comments \
  -F body=@docs/adr/0001-substrate.resolution-comment.md
```

## 3. Close the ticket (blocked)

```
gh api -X PATCH repos/{owner}/{repo}/issues/3 -f state=closed -f state_reason=completed
```

## 4. Append to the map's *Decisions so far* (blocked)

Insert as the last lines under `## Decisions so far` in issue #1, keeping the HTML comment intact. The first line is
owed from the previous ticket's closeout (that ticket was closed by the maintainer without its line ever being
appended, and its payload is unchanged); the second is this ticket's.

```
- [What do Stripe's public API and engineering writing actually say about idempotency and payout safety?](https://github.com/sehaanurrahaman-creator/ledger-x/issues/2): Stripe's guarantee is `(account, key) → stored status+body` for a **≥24h floor** after which the key is *forgotten and re-executes*, replays are flagged by `Idempotent-Replayed: true`, and **same-key/different-body is 400 `idempotency_error` while 409 `idempotency_key_in_use` means a concurrent duplicate that Stripe's own client retries** — so ledger-x's charter 409-on-mismatch and its GC policy are both deliberate divergences to justify; payouts can read `paid` and regress to `failed` within 5 business days. Note: `docs/research/stripe-idempotency.md`.
- [Substrate decision: Java + Loom or Go — and a hand-rolled log or RocksDB?](https://github.com/sehaanurrahaman-creator/ledger-x/issues/3): **Java 21 with virtual threads, on a hand-rolled append-only WAL owned by this repo** — RocksDB rejected because it owns the WAL record format, the group-commit boundary and the recovery rules, which are the three things this project exists to decide; Go rejected because TLC is a Java program and the spec would leave the language. Commit = the group's `FileChannel.force(false)` (`fdatasync`) has returned; `force(true)` (`fsync`) only at open/rotate/checkpoint, plus an `fsync` of the WAL directory. Ships a green CI skeleton: `./build.sh` — lint, `javac --release 21 -Xlint:all -Werror`, and a five-check substrate contract test, no third-party dependencies. Note: `docs/adr/0001-substrate.md`.
```

The full replacement body used for the (refused) `PATCH` was built by inserting those two lines after the HTML
comment in the current body of #1, changing nothing else.

## 5. Fog graduated (done)

Both new tickets are children of the map and blocked by this one:

| Ticket | Why it exists now | Edges |
| --- | --- | --- |
| [Which collector and allocation budget keeps the JVM's GC out of the p99 column?](https://github.com/sehaanurrahaman-creator/ledger-x/issues/17) | Choosing the JVM puts GC pauses in the metrics table's p99 column. That cost was accepted in the ADR and immediately turned into a decision: collector, heap shape, allocation budget on the WAL path. | `blockedBy=[3]`, `blocking=[13]` |
| [How does TLC actually run here — Jaunt, or TLC's own tla2tools.jar?](https://github.com/sehaanurrahaman-creator/ledger-x/issues/18) | The charter's "Jaunt" could not be found publicly; the TLA⁺ ticket's title assumes it. The requirement is recorded, the assumption is not inherited. | `blockedBy=[3]`, `blocking=[11]` |

## 6. Nothing else graduates

Checked against the map's remaining fog and deliberately left in place:

- The **chaos fault set and oracle** — the substrate half is now nameable (`SIGKILL` a JVM mid-`force`, scribble on a
  WAL tail we own, restart cost of a JVM × 10,000), but the item still hangs on the payout protocol, so it stays.
  Its substrate half is recorded in the ADR's "what this changes for other tickets" instead of a new ticket.
- The **benchmark workload shape** — the concurrency model half is settled (virtual threads, per-account
  `ReentrantLock`, group commit), but the workload still hangs on the domain model.
- The **replay state-hash scheme** and **TLC bounds** — untouched by a substrate decision.
- The **public API surface** — a JVM in-process library vs an HTTP service is no more decided than before.
- The **CI budget** — still waits on timing data, though the JVM-startup term in it is now concrete.

Three findings feed existing tickets rather than creating new ones, and are written into the ADR for them: the
`synchronized`-pins-virtual-threads rule goes to *How do a payout and a refund to the same account never
interleave?* (and is enforced by the lint); the `write`+`force` vs `mmap` question and the fsync primitive table go
to *What does a durable write look like?*; and JMH `Mode.SampleTime` as the harness shape goes to *How fast is it,
honestly?*.
