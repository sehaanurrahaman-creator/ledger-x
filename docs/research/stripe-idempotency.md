# Stripe's documented idempotency and payout-safety semantics

**Research note for the ticket *What do Stripe's public API and engineering writing actually say about idempotency and payout safety?*** (ledger-x Wayfinder map, issue #1).

Retrieved **2026-09-11**. Every claim below is tied to a primary source — Stripe's API reference, Stripe's docs, Stripe's engineering blogs, or Stripe's own client-library source. Where I could not find a primary source, the claim is marked **[no primary source]** rather than inferred silently. Secondary commentary is used only to point at a primary artifact, and is labelled.

**Source tiers used here**

| Tier | What counts | Examples cited |
| --- | --- | --- |
| P1 | Stripe API reference / docs (docs.stripe.com) | `api/idempotent_requests`, `error-low-level`, `error-codes`, `api/errors`, `api/payouts` |
| P1 | Stripe engineering / developer blogs | `stripe.com/blog/idempotency`, `stripe.dev/blog/because-nobody-likes-being-charged-twice` |
| P1 | Stripe's own client-library source | `stripe/stripe-ruby` `lib/stripe/api_requestor.rb` |
| P2 | Written by a Stripe engineer, published outside Stripe | Brandur Leach, `brandur.org/idempotency-keys`, `brandur.org/fragments/idempotency-key-draft` |
| P3 | Observed wire payloads reported by third parties | `code-corps/stripity_stripe#564` |

---

## 1. What the key is: format, transport, scope

- The key travels in the **`Idempotency-Key` header**, and **all `POST` requests accept idempotency keys**. Stripe states plainly: *"Don't send idempotency keys in `GET` and `DELETE` requests because it has no effect. These requests are idempotent by definition."* — [API reference, Idempotent requests](https://docs.stripe.com/api/idempotent_requests) (P1).
- **Format**: client-generated, *"we suggest using V4 UUIDs, or another random string with enough entropy to avoid collisions. Idempotency keys are up to 255 characters long. Avoid using sensitive data (for example, email addresses or personal identifiers) as idempotency keys."* — [API reference, Idempotent requests](https://docs.stripe.com/api/idempotent_requests) (P1). The 255-character limit is restated in [Error handling → Idempotency errors](https://docs.stripe.com/error-handling) (P1).
- **Scope is the account, not the endpoint.** The docs require that a key *"unambiguously identify a single operation within your account over the last 24 hours, at a minimum"* — [Advanced error handling → Sending idempotency keys](https://docs.stripe.com/error-low-level#sending-idempotency-keys) (P1). Two independent confirmations that the namespace is account-wide and spans endpoints:
  - The `idempotency_error` type is defined as *"Idempotency errors occur when an `Idempotency-Key` is re-used on a request that does not match the first request's **API endpoint and parameters**."* — [API reference, Errors](https://docs.stripe.com/api/errors) (P1). Reusing one key on a *different endpoint* is therefore an error, not a fresh namespace — so one key namespace per account across all endpoints.
  - Stripe's own guidance is that the key identifies one *operation* ("create a payment for Order #123"), not a session or an API call — [stripe.dev, *Because nobody likes being charged twice*](https://stripe.dev/blog/because-nobody-likes-being-charged-twice) (P1).
- **Scope by API version: [no primary source].** Nothing in the API reference or error docs scopes keys by `Stripe-Version`. The documented mismatch test is "API endpoint and parameters", and versioning is a separate, per-request/per-account mechanism ([API reference, Versioning](https://docs.stripe.com/api/versioning)). Treat version-scoping as *unspecified*, not absent.
- **Connected accounts: [no primary source].** `Stripe-Account` is *"set per-request"* ([API reference, Connected Accounts](https://docs.stripe.com/api/connected-accounts), P1), but the docs never say whether the idempotency namespace is per-platform-account or per-connected-account. Unspecified.

**Consequence for ledger-x.** Stripe's `(scope) → response` is `(account, key)`, with endpoint-and-parameters as the *validation* payload rather than part of the identity. ledger-x's charter key is `(merchant_id, idempotency_key) → response`, which is the same shape with `merchant_id` in the place of Stripe's account.

## 2. Lifetime, expiry, and what happens at the boundary

- **24 hours, stated as a floor.** *"You can remove keys from the system automatically after they're **at least** 24 hours old."* — [API reference, Idempotent requests](https://docs.stripe.com/api/idempotent_requests) (P1). The client-facing phrasing is tighter: *"Clients can safely retry requests that include an idempotency key as long as the second request occurs within 24 hours from when you first receive the key (keys expire out of the system after 24 hours)."* — [Advanced error handling → POST requests](https://docs.stripe.com/error-low-level#idempotency) (P1). Restated again by Stripe's developer blog: *"Stripe recognizes the idempotency key for 24 hours and returns the original response instead of processing a new transaction."* — [stripe.dev](https://stripe.dev/blog/because-nobody-likes-being-charged-twice) (P1).
- **At expiry the key is not refused — it is forgotten.** *"We generate a new request if a key is reused after the original is pruned."* — [API reference, Idempotent requests](https://docs.stripe.com/api/idempotent_requests) (P1). So the documented answer to the standing design-review question *"Can an idempotency key be reused after GC, and what then?"* is: **yes, it can, and it then executes as a brand-new operation.** The duplicate-protection window is exactly the retention window; after it, a late retry silently becomes a second charge. Stripe publishes no tombstone, no "this key was used and expired" refusal, and no warning header.
- **Operational corroboration** that expiry is a hard edge and not a soft one: *"idempotency keys should generally expire, and not all implementations easily allow this at large volumes (running big `DELETE` jobs can be a problem)."* — Brandur Leach (Stripe engineer, writing personally), [`brandur.org/fragments/idempotency-key-draft`](https://brandur.org/fragments/idempotency-key-draft) (P2).

**Consequence for ledger-x.** "24 hours" is a *minimum* retention promise, not a maximum, and expiry converts a retry into a duplicate. Two design pressures follow: (a) the retention window must be long enough to outlive every client retry policy, and (b) GC needs a decision about tombstones. Stripe's documented answer (forget) is a defensible choice for an API whose clients are told to retry within 24h; for a ledger where the point is *proving* exactly-once, forgetting is exactly the hole a proof should refuse to have. This is a decision for the ticket *What are ledger-x's idempotency semantics? Scoping, expiry, the 409-vs-200 trap, GC reuse, clock skew*, not a fact.

## 3. Same key, different request body

- **The rule.** *"The idempotency layer compares incoming parameters to those of the original request and errors if they're not the same to prevent accidental misuse."* — [API reference, Idempotent requests](https://docs.stripe.com/api/idempotent_requests) (P1). Restated: *"Sending the same idempotency with different parameters produces an error indicating that the new request didn't match the original"* — [Advanced error handling → Network errors](https://docs.stripe.com/error-low-level#idempotency) (P1). The comparison spans **endpoint *and* parameters** (see §1).
- **The error type is `idempotency_error`.** It is one of exactly four error types: `api_error`, `card_error`, `idempotency_error`, `invalid_request_error` — [API reference, Errors](https://docs.stripe.com/api/errors) (P1). In the SDKs it surfaces as `Stripe::IdempotencyError` / `stripe.IdempotencyError`: *"You used an idempotency key for something unexpected, like replaying a request but passing different parameters."* — [Error handling](https://docs.stripe.com/error-handling) (P1).
- **The HTTP status Stripe actually returns is 400, not 409.** Stripe's own Ruby client maps it explicitly:
  ```ruby
  case resp.http_status
  when 400, 404
    case error_data[:type]
    when "idempotency_error"
      IdempotencyError.new(error_data[:message], **opts)
  ```
  — [`stripe-ruby` `lib/stripe/api_requestor.rb`](https://github.com/stripe/stripe-ruby/blob/master/lib/stripe/api_requestor.rb), `handle_error_response` (P1, read 2026-09-11). The docs never print the numeric status for the mismatch; the SDK mapping is the primary evidence. **[Docs silent on the number]**
- **The wire message, as seen in production:** *"Keys for idempotent requests can only be used with the same parameters they were first used with. Try using a key other than '…' if you meant to execute a different request."* — verbatim message reported by integrators (P3: [wordpress.org support thread](https://wordpress.org/support/topic/keys-for-idempotent-requests-error-on-some-order-attempts/), and the same wording in [Stack Overflow, *stripe, multiple requests using idempotency keys return errors*](https://stackoverflow.com/questions/54544723)). Stripe also links the collision to the original request via a Dashboard request log (`iar_…`) — same source, P3.
- **Stripe's stated reason** is client-bug containment, quoted verbatim above: *"to prevent accidental misuse"*.
- **The `code` field:** Stripe's [error-code table](https://docs.stripe.com/error-codes) (P1) contains exactly one idempotency entry, `idempotency_key_in_use` (§5). There is **no documented error code for the parameter mismatch** — the mismatch is identified by `type: "idempotency_error"`.

> **This is the load-bearing finding for ledger-x.** The charter says *"same key + different body ⇒ 409 conflict"*. Stripe's documented behaviour is **400 + `type: idempotency_error`**, and Stripe uses **409 for something else entirely** — a *concurrent* duplicate on the same key (§5). So ledger-x's 409-on-mismatch is a genuine, deliberate divergence from the system it says it copies, and it needs an argument, not an assertion. The argument available: 409 says "your request conflicts with server-side state you can observe", which is true of a key/body collision and arguably more actionable than 400 "your request was malformed"; but it collides with the meaning 409 already carries in the Stripe world (in-flight duplicate), so a client written against Stripe's semantics will retry-on-409 exactly the case ledger-x means as terminal. Both readings are defensible; **picking one and writing down why** is the decision the ticket *What are ledger-x's idempotency semantics?…* owns, and `STRIPE-DIFFS.md` must record it.

## 4. Replay semantics: what comes back, and how a client knows

- **What is stored is the outcome, including failures.** *"Stripe's idempotency works by saving the resulting status code and body of the first request made for any given idempotency key, **regardless of whether it succeeds or fails**. Subsequent requests with the same key return the same result, **including `500` errors**."* — [API reference, Idempotent requests](https://docs.stripe.com/api/idempotent_requests) (P1). Corroborated at the HTTP level: *"as long as an API method began execution, Stripe's API servers will cache the results of the request regardless of what they were. A request that returns a `400` sends back the same `400` if followed by a new request with the same idempotency key."* — [Advanced error handling → Content errors](https://docs.stripe.com/error-low-level#errors-in-http) (P1).
- **Replay detection is a response header.** *"To identify a previously executed response that's being replayed from the server, look for the header **`Idempotent-Replayed: true`**."* — [Advanced error handling → Sending idempotency keys](https://docs.stripe.com/error-low-level#sending-idempotency-keys) (P1). Nothing in the response *body* marks a replay; the body is byte-for-byte the original.
- **A replayed 500 is a replayed 500, and Stripe says so in its retry logic.** From `stripe-ruby`'s `should_retry?`: *"Note that we expect the `stripe-should-retry` header to be false in most cases when a 500 is returned, since our idempotency framework would typically replay it anyway."* — [`lib/stripe/api_requestor.rb`](https://github.com/stripe/stripe-ruby/blob/master/lib/stripe/api_requestor.rb) (P1). So a retry of a failed request under the same key is expected to be a **no-op that returns the stored failure**, and Stripe's client is told not to bother.
- **Full bodies, not digests.** The docs say "status code and body" (above), so the response is stored in a replayable form. Whether that is the literal body or a reconstructable equivalent is not stated. **[Docs silent]** What *is* implied: because parameters are compared (§3), the original **request parameters are retained in comparable form** — Stripe does not document whether as a digest or in full. **[Docs silent]**
- **Request identity on replay: [no primary source].** Every request has a `Request-Id` response header ([API reference, Request IDs](https://docs.stripe.com/api/request_ids), P1), but Stripe does not document whether a replay carries the original request's ID or a new one. The only documented replay signal is `Idempotent-Replayed`.

## 5. Concurrency: two requests racing on one key

- **The cache is only written once execution begins.** *"We save results only after the execution of an endpoint begins. If incoming parameters fail validation, or **the request conflicts with another request that's executing concurrently**, we don't save the idempotent result because no API endpoint initiates the execution. **You can retry these requests.**"* — [API reference, Idempotent requests](https://docs.stripe.com/api/idempotent_requests) (P1). This is the single most useful sentence in the docs for ledger-x: Stripe's key has an explicit **in-flight** state that is *neither* "unknown" *nor* "complete", and the response for it is retryable.
- **The status for that race is 409 Conflict.** *"409 | Conflict | The request conflicts with another request (perhaps due to using the same idempotent key)."* — [API reference, Errors → HTTP Status Code Summary](https://docs.stripe.com/api/errors) (P1), repeated verbatim in [Advanced error handling → HTTP Status Code Reference](https://docs.stripe.com/error-low-level) (P1).
- **The error code is `idempotency_key_in_use`.** *"The idempotency key provided is currently being used in another request. This occurs if your integration is making duplicate requests simultaneously."* — [Error codes](https://docs.stripe.com/error-codes) (P1).
- **Observed payload** (P3, [`stripity_stripe#564`](https://github.com/code-corps/stripity_stripe/issues/564)): `http_status: 409`, `type: "invalid_request_error"`, `code: "idempotency_key_in_use"`, `doc_url: https://stripe.com/docs/error-codes/idempotency-key-in-use`, message *"There is currently another in-progress request using this Idempotent Key … Please try again later."* Note the type is `invalid_request_error`, not `idempotency_error` — so **409/in-use and 400/mismatch are distinguishable on both status and type**.
- **Stripe's client retries the 409.** `should_retry?` in `stripe-ruby`: `# 409 Conflict` → `return true if error.http_status == 409` (P1). So the 409 is a *transient* signal in Stripe's world — which is precisely why reusing 409 for the *terminal* mismatch case (§3) would be ambiguous for a Stripe-shaped client.
- **How the claim is made atomically: [no primary source in Stripe docs].** The strongest public statement is from the Stripe engineer who built the layer: *"Stripe used a unique index for the job, detecting duplicates atomically by handling a duplicate key error."* — [`brandur.org/fragments/idempotency-key-draft`](https://brandur.org/fragments/idempotency-key-draft) (P2). His longer write-up describes the surrounding pattern — **atomic phases** with recovery points, where a phase's local writes are transactional and the non-rollbackable external call (the Stripe charge) sits in its own phase, driven to completion by client retries of the same key — [`brandur.org/idempotency-keys`](https://brandur.org/idempotency-keys) with runnable code at [`brandur/rocket-rides-atomic`](https://github.com/brandur/rocket-rides-atomic) (P2).
- **Stripe also serializes per object, separately from idempotency.** Concurrent requests on the *same object* can fail with `lock_timeout` (HTTP 429): *"This object can't be accessed right now because another API request or Stripe process is currently accessing it. … If you see this error frequently and are making multiple concurrent requests to a single object, make your requests serially or at a lower rate."* — [Error codes](https://docs.stripe.com/error-codes) (P1), pointing at [Object lock timeouts](https://docs.stripe.com/rate-limits#object-lock-timeouts). `stripe-ruby` retries `429` only when `code == "lock_timeout"`, not for rate limiting (P1).

**The three key states, in Stripe's documented terms** (this table is the synthesis; each row cites the quote above):

| Key state | Stripe response | Terminal? | Source |
| --- | --- | --- | --- |
| Absent | Executes normally; result cached | — | §4 |
| In flight (concurrent duplicate) | **409** `idempotency_key_in_use`, result *not* cached, retry | No — retryable | §5 |
| Complete | Stored status code + body, `Idempotent-Replayed: true` | Yes | §4 |
| Complete, different params | **400** `idempotency_error` | Yes | §3 |
| Pruned (>24h) | Executes as a **new request** | — | §2 |

## 6. Where the guarantee stops: the pre-execution boundary

- **Rejections before execution are outside the idempotency layer.** *"a request that's rate limited with a `429` can produce a different result with the same idempotency key because rate limiters run before the API's idempotency layer. The same goes for a `401` that omitted an API key, or most `400`s that sent invalid parameters. Even so, the safest strategy where `4xx` errors are concerned is to always generate a new idempotency key."* — [Advanced error handling → Content errors](https://docs.stripe.com/error-low-level#errors-in-http) (P1).
- **Server errors are cached, and retrying under a new key is discouraged:** *"the idempotency layer caches the result of POST mutations that result in server errors (specifically 500s …), so retrying them with the same idempotency key usually produces the same result. The client can retry the request with a new idempotency key, but **we advise against it because the original key may have produced side effects**."* — [Advanced error handling → Server errors](https://docs.stripe.com/error-low-level#idempotency) (P1).

**Consequence for ledger-x.** Idempotency at Stripe is a *layer* with a boundary in front of it (auth, rate limiting, validation). Whatever sits in front of ledger-x's idempotency layer must be enumerated the same way, and the chaos campaign should inject faults *in front of* the layer as well as behind it.

## 7. What Stripe's public engineering writing says

- **The original design post** — Brandur Leach, [*Designing robust and predictable APIs with idempotency*](https://stripe.com/blog/idempotency) (stripe.com/blog, 2017) (P1) — frames the whole thing as *"exactly once"* semantics for operations that must not repeat: *"what if we have an operation that needs to be invoked exactly once and no more? An example might be if we were designing an API endpoint to charge a customer money; accidentally calling it twice would lead to the customer being double-charged, which is very bad."* It enumerates the three failure positions and their answers:
  - connection failure → *"on the second request the server will see the ID for the first time, and process it normally."*
  - failure midway → *"the server picks up the work and carries it through. … if the previous operation was successfully rolled back by way of an ACID database, it'll be safe to retry it wholesale. Otherwise, state is recovered and the call is continued."*
  - response failure → *"the server simply replies with a cached result of the successful operation."*
  The middle case is the one ledger-x exists to prove: **state recovery and continuation**, not rollback, is the general answer.
- **Client-side discipline is part of the guarantee.** Same post: exponential backoff plus jitter to avoid the thundering herd — *"it's also a good idea to mix in an element of randomness."* Stripe's library generates keys and retries automatically: *"It is only safe to retry network failures on post and delete requests if we add an `Idempotency-Key` header"* — `stripe-ruby` then does `headers["Idempotency-Key"] ||= SecureRandom.uuid` for `post`/`delete` when retries are enabled (and unconditionally for API v2 mode) — [`lib/stripe/api_requestor.rb`](https://github.com/stripe/stripe-ruby/blob/master/lib/stripe/api_requestor.rb) (P1).
- **The double-charge failure mode, in Stripe's own words** — Ben Smith, [*Because nobody likes being charged twice*](https://stripe.dev/blog/because-nobody-likes-being-charged-twice) (stripe.dev, 2025-04-10) (P1). The scenario is a dropped connection plus a backend retry. Two named pitfalls are worth copying into ledger-x's docs because both are *client* bugs the server can't see:
  - **Key reuse across operations/users**: *"An idempotency key should uniquely identify one specific action, such as 'create a payment for Order #123', not a general session or API call. Using the same key for different users or operations can lead to unexpected behavior, like retrieving someone else's payment response."*
  - **Keys generated too early**: *"If the user later modifies their order before checking out, that stale key could bind the new request to an old, now-incorrect response. It's best to generate the key as close as possible to the point of execution."*
  Note what this implies: **a key/body collision is not always a bug — it is the server correctly detecting one of these two client bugs.** That is the honest framing for the 400-vs-409 decision in §3.
- **A published vulnerability caused by ignoring the replay header** — `wevm/mppx` advisory [`GHSA-8mhj-rffc-rcvw`](https://github.com/wevm/mppx/security/advisories/GHSA-8mhj-rffc-rcvw) (P3): an integration that did not check `Idempotent-Replayed` accepted a replayed success as a fresh payment, so an attacker *"could pay once and consume unlimited resources by replaying the credential"*; the fix was to reject replayed responses. This is concrete evidence for ledger-x's replay-detection requirement: **a replayed success must be distinguishable from a fresh one, or downstream consumers will treat replays as new money.**

## 8. Payouts safety: what is documented

Stripe does not publish a payout-safety postmortem or a "safer payouts" engineering post that I could find (**[no primary source found]** for a postmortem of a Stripe double-charge incident; the searches turned up third-party write-ups, not Stripe incident reports). What *is* documented is a state machine with an uncomfortable edge, plus a set of money-movement guardrails:

- **The payout state machine.** `status` is one of `paid`, `pending`, `in_transit`, `canceled`, `failed`: *"A payout is pending until it's submitted to the bank, when it becomes in_transit. The status changes to paid if the transaction succeeds, or to failed or canceled (within 5 business days)."* — [The Payout object](https://docs.stripe.com/api/payouts/object) (P1).
- **A terminal-looking state can regress.** *"Some payouts that fail might initially show as `paid`, then change to `failed`."* — same source (P1). Combined with the 5-business-day window, **a payout is not durable-settled at the moment it reads `paid`.** Any ledger that posts a settlement entry on `paid` will need a compensating entry.
- **Failures carry their own bookkeeping.** The Payout object has `failure_balance_transaction`, `failure_code`, `failure_message`, plus `original_payout`, `reversed_by` and `reconciliation_status` — [The Payout object](https://docs.stripe.com/api/payouts/object) / [Payouts](https://docs.stripe.com/api/payouts) (P1). Stripe models a failed or reversed payout as *more ledger records*, never as an edit to the original — the same append-only instinct ledger-x's charter requires.
- **Payouts are `POST`s, so §1–§6 apply.** [Payouts](https://docs.stripe.com/api/payouts) exposes `POST /v1/payouts`, plus `POST /v1/payouts/:id`, `:id/cancel`, `:id/reverse` (P1); *"All `POST` requests accept idempotency keys"* (§1). Stripe's own client will attach a UUID key to those calls automatically when retries are on (§7).
- **Guardrails documented as error codes** ([Error codes](https://docs.stripe.com/error-codes), P1): `payouts_limit_exceeded` (*"You've reached your daily processing limits for this payout type"*), `payouts_not_allowed`, `instant_payouts_limit_exceeded`, `instant_payouts_config_disabled`, `invalid_card_type` (*"The card provided as an external account isn't supported for payouts"*), `insufficient_funds`.
- **Stripe runs anomaly detection over money-movement requests.** `anomalous_money_movement_request`: *"This request was blocked because our anomaly detection system flagged it as anomalous. To retry the request, disable anomaly detection in your Dashboard settings by going to Settings > Developers > Manage API keys > Anomaly detection."* — [Error codes](https://docs.stripe.com/error-codes) (P1). This is the closest thing to a documented "safer payouts" control: a second, non-idempotency check on money movement that can block an otherwise-valid request.
- **Key hygiene around payouts.** *"An API key might have its access limited if it hasn't been used to create transfers, payouts, or update payout destinations for over 180 days. You can't use a limited access key to create payouts and transfers…"* — [API keys](https://docs.stripe.com/keys) (P1).
- **Reconciliation is a first-class Stripe product surface**, not an afterthought: the docs carry dedicated pages for [Payout reconciliation](https://docs.stripe.com/payouts/reconciliation) and [Payout trace IDs](https://docs.stripe.com/payouts/trace-id) (P1, cited as existing pages from the docs navigation; their contents were not read for this note). Stripe's own guidance positions reconciliation as the backstop when upstream controls miss: *"It works by comparing the payments you intended to make and the payments that actually cleared"* — [*How to Prevent Duplicate Payments*](https://stripe.com/resources/more/how-to-prevent-duplicate-payments) (P1, Stripe-authored marketing/resource content — lower evidentiary weight than the API reference).

**Consequence for ledger-x.** The `paid → failed` regression is the most important payout fact here. ledger-x's intent log must treat the bank boundary as **provisionally settled**, with a state that can move backwards on bank feedback, and the reconciliation job is what detects the cases the bank never reports. That belongs to the tickets *How does a payout become exactly-once across the bank boundary?* and *What is drift, and how is it found?*

---

## 9. Gaps — what Stripe does **not** document

Stated plainly, because a diff against an undocumented behaviour is not a diff:

1. **No numeric HTTP status is printed for the parameter-mismatch error.** Inferred 400 from Stripe's own SDK mapping (§3).
2. **No documented error code for the parameter mismatch** — only the `idempotency_error` type. The single documented idempotency code is `idempotency_key_in_use` (in-flight duplicate).
3. **No documented retention maximum.** "at least 24 hours" is a floor (§2); the actual prune schedule is unpublished.
4. **No documented storage format** — bodies vs digests, and whether request parameters are kept verbatim or hashed (§4).
5. **No documented semantics for API-version or connected-account key scoping** (§1).
6. **No documented replay behaviour for `Request-Id`** (§4).
7. **No documented implementation of the atomic claim** in Stripe docs; the unique-index mechanism is from the engineer's personal writing (P2).
8. **No published double-charge postmortem.** No Stripe incident report describing a duplicate-charge or duplicate-payout incident was found. Third-party commentary exists; nothing from Stripe.
9. **No published throughput or latency numbers for the idempotency layer.**

---

## 10. Facts other tickets depend on

By ticket title, as the map requires.

**[*What are ledger-x's idempotency semantics? Scoping, expiry, the 409-vs-200 trap, GC reuse, clock skew*](https://github.com/sehaanurrahaman-creator/ledger-x/issues/7)** — the main consumer of this note.
- Stripe's identity is `(account, key)`; endpoint+parameters are a *validation* payload, not part of identity (§1). ledger-x's `(merchant_id, key)` is the same shape.
- Stripe's retention is a **24h floor** and expiry means **forget, then execute fresh** (§2). The "GC reuse" question has a documented answer to argue against: Stripe accepts the duplicate.
- **Stripe's mismatch status is 400 + `idempotency_error`, not 409; 409 means "in-flight duplicate" and is retried by Stripe's own client** (§3, §5). The charter's 409-on-mismatch is a divergence and needs its justification written down — and the clock-skew answer has to survive a retention window that is a floor, not a fixed TTL.

**[*How does a payout become exactly-once across the bank boundary? Intent log, state machine, idempotent bank client*](https://github.com/sehaanurrahaman-creator/ledger-x/issues/9)**
- `paid` can regress to `failed`, and resolution can take 5 business days (§8) — the intent log needs a provisionally-settled state that can move backwards.
- Failures and reversals are *additional* records (`failure_balance_transaction`, `original_payout`, `reversed_by`), never mutations (§8) — matching the append-only charter rule.
- The bank client is a `POST`-shaped call, so it needs its own key, and Stripe's own client shows the pattern: generate a key per attempt-group, retry under it, and treat 409-style in-flight as retryable (§7, §5).
- Stripe's recovery model for a midway failure is **recover and continue, not roll back** (§7) — that is the shape of the intent-log state machine.

**[*What did Stripe teach us, and what breaks at 10M merchants? STRIPE-DIFFS.md and the design review*](https://github.com/sehaanurrahaman-creator/ledger-x/issues/14)**
- Three diffs are now concrete rather than asserted: (a) 400 vs 409 on mismatch (§3); (b) forget-on-expiry vs tombstone (§2); (c) replay detection by header, with a published vulnerability proving the header matters (§7).
- The scale story has a documented anchor: Stripe's prune-at-24h is described by its author as a *volume* problem — big `DELETE` jobs (§2) — which is precisely the 10M-merchant concern.
- Standing design-review question 3 ("Can an idempotency key be reused after GC, and what then?") now has a documented comparator (§2).

**[*How do a payout and a refund to the same account never interleave? Concurrency without global locks*](https://github.com/sehaanurrahaman-creator/ledger-x/issues/8)**
- Stripe answers same-key concurrency with an explicit in-flight state and a retryable 409, *not* by blocking (§5) — three states, not two.
- Separately, Stripe serializes per *object* with `lock_timeout` (429) and tells clients to serialize their own requests (§5). Two different mechanisms, and ledger-x should say which one it copies for per-account ordering.

**[*What must TLC prove? The TLA⁺ spec of idempotency and replay, run via Jaunt*](https://github.com/sehaanurrahaman-creator/ledger-x/issues/11)**
- The state machine to model has **five** key states, not two: absent, in-flight, complete, complete-with-different-params, pruned (§5 table). Pruned is the interesting one — it is where exactly-once quietly becomes at-least-once.
- The pre-execution boundary (§6) means the spec needs a step *before* the idempotency claim where a request can be rejected without leaving any record.

**[*What breaks it? Chaos fuzzer: SIGKILL, WAL-tail corruption, 10,000 injections*](https://github.com/sehaanurrahaman-creator/ledger-x/issues/12)**
- Faults must be injected **in front of** the idempotency layer as well as behind it (§6): a rejection that leaves no cached result must be retriable and must not produce a partial write.
- The concurrent-duplicate race (§5) is a required fault, not an optional one: two requests on one key inside the claim window.

**[*What is drift, and how is it found? Reconciling the ledger against the bank*](https://github.com/sehaanurrahaman-creator/ledger-x/issues/10)**
- The `paid → failed` regression and the 5-business-day window (§8) define how long the ledger must consider a payout unsettled, and Stripe ships reconciliation as a product surface (§8) — the reconciliation job is the backstop for what the bank boundary never reports.

**[*Substrate decision: Java + Loom or Go — and a hand-rolled log or RocksDB?*](https://github.com/sehaanurrahaman-creator/ledger-x/issues/3)**
- Weak dependency, noted for completeness: the key store must support **atomic claim + TTL/expiry** (§2, §5). Brandur's note that expiry is the hard part at volume — *"not all implementations easily allow this"* with big `DELETE` jobs (§2, P2) — is a real input to "hand-rolled log vs RocksDB".

**[*What is a durable write like?* / WAL, *checkpoints and byte-identical replay*](https://github.com/sehaanurrahaman-creator/ledger-x/issues/5)** — no dependency. This note is about API semantics; storage format is untouched by it.

**[*Public API surface* — fog item on the map, not yet a ticket](https://github.com/sehaanurrahaman-creator/ledger-x/issues/1)** — if ledger-x exposes HTTP, it inherits the whole header contract: `Idempotency-Key` ≤255 chars, `Idempotent-Replayed: true`, and the 400/409 split (§1, §3, §4). An in-process library still needs the same three-state key model, minus the headers.

---

## Appendix: primary sources consulted

| # | Source | Tier | Used for |
| --- | --- | --- | --- |
| 1 | [API reference — Idempotent requests](https://docs.stripe.com/api/idempotent_requests) | P1 | §1 §2 §3 §4 §5 §6 |
| 2 | [Advanced error handling (`error-low-level`)](https://docs.stripe.com/error-low-level) | P1 | §1 §2 §4 §5 §6 |
| 3 | [API reference — Errors (types + status summary)](https://docs.stripe.com/api/errors) | P1 | §1 §3 §5 |
| 4 | [Error codes table](https://docs.stripe.com/error-codes) | P1 | §3 §5 §8 |
| 5 | [Error handling (SDK error classes)](https://docs.stripe.com/error-handling) | P1 | §1 §3 |
| 6 | [The Payout object](https://docs.stripe.com/api/payouts/object), [Payouts](https://docs.stripe.com/api/payouts) | P1 | §8 |
| 7 | [API reference — Request IDs](https://docs.stripe.com/api/request_ids), [Connected Accounts](https://docs.stripe.com/api/connected-accounts), [Versioning](https://docs.stripe.com/api/versioning) | P1 | §1 §4 |
| 8 | [API keys](https://docs.stripe.com/keys), [Rate limits / object lock timeouts](https://docs.stripe.com/rate-limits#object-lock-timeouts) | P1 | §5 §8 |
| 9 | [stripe.com/blog — *Designing robust and predictable APIs with idempotency*](https://stripe.com/blog/idempotency) | P1 | §7 |
| 10 | [stripe.dev — *Because nobody likes being charged twice*](https://stripe.dev/blog/because-nobody-likes-being-charged-twice) | P1 | §1 §2 §7 |
| 11 | [`stripe/stripe-ruby` — `lib/stripe/api_requestor.rb`](https://github.com/stripe/stripe-ruby/blob/master/lib/stripe/api_requestor.rb) | P1 | §3 §4 §5 §7 |
| 12 | [`stripe/stripe-python` — `stripe/_error.py`](https://github.com/stripe/stripe-python/blob/master/stripe/_error.py) | P1 | §3 (confirms `IdempotencyError` exists as a distinct class) |
| 13 | [brandur.org — *Implementing Stripe-like Idempotency Keys in Postgres*](https://brandur.org/idempotency-keys), [`rocket-rides-atomic`](https://github.com/brandur/rocket-rides-atomic) | P2 | §5 |
| 14 | [brandur.org — *`Idempotency-Key` IETF standards draft*](https://brandur.org/fragments/idempotency-key-draft) | P2 | §2 §5 |
| 15 | [`wevm/mppx` advisory GHSA-8mhj-rffc-rcvw](https://github.com/wevm/mppx/security/advisories/GHSA-8mhj-rffc-rcvw) | P3 | §7 |
| 16 | [`code-corps/stripity_stripe#564`](https://github.com/code-corps/stripity_stripe/issues/564) | P3 | §3 §5 |
| 17 | [stripe.com/resources — *How to Prevent Duplicate Payments*](https://stripe.com/resources/more/how-to-prevent-duplicate-payments) | P1 (low weight) | §8 |
