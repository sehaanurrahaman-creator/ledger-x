# ledger-x — charter

**Exactly-once money movement with a crash-recovery proof.**

## Problem

Every payout bug in the industry is the same shape: a client retries, a worker gets killed mid-flight, a message is redelivered, and the ledger now has two entries where there should be one. Stripe's API solves part of this with **idempotency keys**; their payouts work is explicitly about "safer payouts." This project is implementing the guarantee ourselves, in the small, and *proving* it.

## Build

1. **Double-entry ledger** — every transaction debits and credits accounts; the sum of all balances is invariantly 0; entries are immutable and append-only.
2. **Own storage** — WAL + fsync policy, checkpointing, and **deterministic replay**: redoing the log after a crash yields byte-identical state (a great property to test).
3. **Key-scoped idempotency** — `(merchant_id, idempotency_key) → response`, with expiry, plus Stripe's actual trap: the same key reused with a *different* request body. Correct answer: **409 conflict**, documented — including why 200-replay would be dangerous.
4. **Concurrency without pessimistic locks everywhere** — snapshot isolation or optimistic row-versioning, plus a **per-account transaction ordering** rule so a payout and a refund to the same account can't interleave weirdly.
5. **Reconciliation job** — detects drift between the ledger and the "bank" (a mock double-entry counterpart). That's how real payments teams sleep.
6. **Verification, both ways** — a TLA⁺ spec of the idempotency + replay behavior with TLC over small bounds (Jaunt runs TLA⁺ inside Java, so the whole thing stays in one repo/language), *and* a jepsen-flavored chaos fuzzer that SIGKILLs the process and corrupts WAL tails while asserting the invariants hold.
7. **`STRIPE-DIFFS.md`** — what we took from Stripe's public API, what we'd do differently at 10M-merchant scale (sharding by account, hot-account contention, cross-shard transfers, gap-free audit sequences). Understanding scale you haven't touched, rather than pretending to.

## Metrics (definition of done)

- Ops/sec at 1/8/64 threads.
- p50/p99 commit latency at each fsync policy.
- **Zero invariant violations across N = 10,000 crash injections.**
- **Replay determinism on 100% of runs.**
- A table of which bugs the TLA⁺ spec *found* while being written — the most impressive line in the README.

## Stack guidance

Java (Project Loom makes the concurrency story elegant) or Go; RocksDB or a hand-rolled log; TLA⁺ via Jaunt.

## Standing design-review questions

1. What is your write amplification?
2. Why fsync where you fsync?
3. Can an idempotency key be reused after GC, and what then?
4. How do you make a two-account transfer atomic without 2PC across shards?
5. What's your behavior on clock skew?
