# Idempotency in ledger-x — a client's guide

ledger-x takes idempotency keys on payouts. This page is the whole contract, written for the
client; [ADR 0005](adr/0005-idempotency.md) is the same contract written for the engineer who
has to keep it.

## The contract in one table

| You send | ledger-x answers | What happened |
| --- | --- | --- |
| a key never seen, with a valid posting | the posting, `replayed = false` | the money moved; the key now names this intent |
| the same key, the same body, within 24 h | the stored response, `replayed = true`, same record LSN, same capture instant | nothing moved; you got the original answer back |
| the same key, a **different body** | **409 Conflict**, naming both fingerprints | refused — a key means one intent, forever |
| the same key, the same body, past 24 h | **410 Expired**, quoting the capture instant and the retention | the posting is not repeated and not replayed; use a new key |

The key's scope is `(merchant, key)` — two merchants may use the same key without ever meeting.
The body is the entries exactly as you sent them, in the order you sent them: a reordered entry
list is a different body and answers 409.

## How to use a key

- **Generate one key per intent** — "this payout to this merchant for this run of my payout
  job" — not per session, not per hour. A UUID or any unique-enough string up to 255 bytes.
- **Retry with the same key and the same body** as long as you like within the window. Retries
  are safe under every failure mode: timeout, connection reset, process crash. Either ledger-x
  never saw it (your retry posts) or it did (your retry replays). There is no third outcome.
- **Never reuse a key for a new intent.** If you get a 409, your client has a bug: some code
  path is sending a different request under a name that already means something. The 409
  carries both fingerprints so the diff is one glance.
- **A 410 is not an error to retry.** The response window has closed; send the payout under a
  new key or investigate why your retry is a day late.

## Why a 409 and not a replay

The dangerous response to a same-key-different-body request is a successful one. Suppose a
payout client reuses Tuesday's key for Wednesday's payout, and Wednesday's amount is 250.01
rather than 250.00. A ledger that answers 200-replay credits the merchant 250.00, returns
Tuesday's response, and creates no record of the divergence — the books balance, the client
sees success, the amount is off by a cent. That cent is found at reconciliation at best and in
a dispute at worst.

ledger-x refuses the request instead, because a reused key with a different body is not a
malformed request (a 400 would say the wrong thing) — it is a conflict between the intent the
key already names and the intent it just arrived with. The 409 is the loud version of the
divergence, delivered at the moment it happens.

This is a deliberate divergence from Stripe, which returns 400 for the same situation; their
stored responses also expire out of an internal table after 24 hours, after which an old key is
fresh again. ledger-x does not: a key's binding is permanent for the life of the log, so a
409-conflict still answers a different body *after* the 24-hour window, and the window only
governs whether the original response can be replayed.

## The guarantees behind the table

- **One posting per key, ever.** The posting and the key's binding are one record in one
  append — one fsync, one acknowledgement. A crash cannot land between "the money moved" and
  "the key remembers"; there is no between.
- **A replay is a read.** Zero bytes are appended, the original record's LSN is quoted, the
  capture instant is the original's. A hundred concurrent retries of one key produce one
  posting and ninety-nine replays — this is a test, not a hope (`./build.sh` runs it on every
  build).
- **Recovery changes nothing about the table.** After a crash and reopen — from the log, from a
  checkpoint, or with every checkpoint deleted — a retry of a bound key is still a replay of
  the same LSN, an aged key is still refused, and a different body is still a 409.
- **Clock skew only moves the window.** A clock that steps back extends replays; a clock that
  steps forward ends them early; neither can turn a retry into a second posting. The capture
  instant is read once, from a clock the ledger is handed, at the moment of the first posting.

The engineering version of all four — the record's bytes, the precedence, the checkpoint
format, the evidence — is [ADR 0005](adr/0005-idempotency.md).
