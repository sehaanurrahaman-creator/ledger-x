# ledger-x

A double-entry ledger where exactly-once money movement under crashes is implemented and proved, not
asserted:
deterministic byte-identical replay after any crash, a TLC-checked TLA⁺ spec, a chaos campaign, and honest
latency numbers per fsync policy.

Work is planned and tracked in the [Wayfinder map](https://github.com/sehaanurrahaman-creator/ledger-x/issues/1);
every ticket resolves a decision, recorded as an ADR under [`docs/adr/`](docs/adr).

## Build and test

One command, no third-party dependencies — a JDK 21 or newer is the only requirement:

```console
$ ./build.sh
```

It runs the style lint, compiles with `javac --release 21 -Xlint:all -Werror`, runs the substrate
contract test, the durable-write contract, the checkpoint contract, and the domain model's property
suite. The other subcommands, and the `make` targets that delegate to them:

| Command | Make target | What it does |
| --- | --- | --- |
| `./build.sh` | `make` | lint, compile, both test mains — the verdict |
| `./build.sh demo` | `make demo` | post a two-account transfer and print the balances and the ledger |
| `./build.sh crash` | `make crash` | the kill -9 micro-harness: 1,000 random kill/recover cycles across the fsync menu |
| `./build.sh boundary` | `make boundary` | the checkpoint harness: every prefix of a seeded history crashed, recovered and hashed, plus a kill inside every stage of the swap |
| `./build.sh campaign` | `make campaign` | the property suite at ~10x the campaign CI runs by default |
| `./build.sh compile` | `make compile` | lint and compile, run nothing |
| `./build.sh lint` | `make lint` | the style lint, including the self-test of its two ADR rules |
| `./build.sh clean` | `make clean` | remove build output |

A failing property prints the seed that produced it and the shrunk case, and can be replayed without editing
anything: `LEDGER_X_SEED=20260921 make test`, or `make campaign TRIALS=400 OPERATIONS=600` for a longer hunt.

The crash harness is sized the same way — `make crash CYCLES=5000 OPS=200` — and prints a per-policy
table (acks observed, acknowledged-then-lost under each fsync policy, cycles whose tail was torn, bytes
cut, recovery markers written) into the CI job summary. Its child processes are forked with
`-Djdk.tracePinnedThreads=full`, because ADR 0001's pinning rule is a durability fact on this
substrate and the harness treats a pinned thread as a failed cycle.

`make demo` prints a ledger being used: four accounts, a two-account transfer, a three-entry transaction, two
transactions refused atomically, the whole journal, and every balance — with Σ balances = 0 asserted before it is
printed, so a demo that lies cannot get through CI.

The numbers the ADRs quote about CI — run counts, conclusions, the annotations this build publishes — are read back
from the API by `scripts/ci-evidence.sh`, which needs `gh` and network and is therefore not part of the build.

Environments with no JDK and no reachable JDK distribution (the Arena sandbox is one: Maven Central and Adoptium both
fail the TLS handshake there) can still compile and run everything: `scripts/bootstrap-toolchain.sh` fetches a
digest-pinned Temurin 21 runtime from PyPI and the Eclipse JDT batch compiler from npm, installs `java`/`javac`
shims, and `PATH="$PWD/build/toolchain/bin:$PATH" ./build.sh` then runs the real build — with a different compiler.
It is a fallback, not the build: ECJ's lint vocabulary is not javac's, so CI's javac run stays the check of record.

## Decisions

| ADR | Decision |
| --- | --- |
| [0001 — Substrate](docs/adr/0001-substrate.md) | Java 21 with virtual threads, and a hand-rolled append-only WAL; no storage engine dependency |
| [0002 — Domain model](docs/adr/0002-domain-model.md) | n-entry transactions with Σ debits = Σ credits, three account kinds as metadata, balances may go negative, integer minor units in a `long`, one append-only event log, and rejection that changes nothing |
| [0003 — Durable write](docs/adr/0003-wal-fsync.md) | 20 bytes of framing per record — length, dense LSN, type, payload, CRC32C over all four — and three fsync policies selectable per commit, with the ack rule that a commit is acknowledged only after the `force` covering its bytes returns. Recovery cuts at the first *structural* failure and records the cut in the log; a complete, CRC-valid frame it cannot honour makes it refuse to open instead |
| [0004 — Checkpoint and replay](docs/adr/0004-checkpoint-replay.md) | A checkpoint is a file named for its watermark — 72-byte header, a canonical state section, a 36-byte trailer — swapped in with temp → force → rename → directory sync → collect, retained newest-two, and refused (never guessed at) by ten named findings that all mean *skip it and fold the log*. The state hash is SHA-256 over one canonical serialization with `lastLsn` inside it, so a run's own hash, the post-recovery hash and a fold from byte 0 are one comparison — swept at every prefix of a seeded history by `./build.sh boundary` |

## Status

Substrate, domain model and the durable write are decided. `src/main` holds the durability primitives
ADR 0001 rests on (`dev.ledgerx.substrate`), the append-only log that owns the record format, the
fsync menu and recovery (`dev.ledgerx.wal`, ADR 0003), the ledger wired onto that log
(`dev.ledgerx.journal`), the in-memory double-entry model itself (`dev.ledgerx.domain`), and the demo
(`dev.ledgerx.demo`). `src/test` holds the five-check substrate contract, the nineteen-check
durable-write contract — exact frame bytes, 68 single-bit flips, every truncate offset of a log, the
cut-versus-refuse split of the torn-tail rules, per-policy force counts — the twelve-check checkpoint
contract (each integrity layer refused by its own name, the corrupt-newest fallback, retention and
stale temps, the deletion theorem), and the nine-check property suite that runs random sequences of
valid and invalid operations against an independent `BigInteger` model of the same rules.

Money now survives a crash: `DurableLedger` validates a transaction, appends it, waits for the ack,
and applies it, and `./build.sh crash` proves the contract against 1,000 real `SIGKILL`s — every
acknowledged transaction was still in the log after recovery, under both crash models, with zero
invariant violations. State is now *provable* rather than merely durable: `DurableLedger` recovers
through the newest checkpoint that stands against the log and folds only the tail, every state has a
`stateHash()`, and `./build.sh boundary` crashes a seeded history at every prefix — plus inside every
stage of the checkpoint swap — and requires the run's hash, the recovered hash, a fold from byte 0
and a recovery with every checkpoint deleted to agree (`PASS 81/81` cycles). What is still open on the
map: idempotency semantics, per-account concurrency, the payout protocol, the benchmark table, and
the TLA⁺ spec.

