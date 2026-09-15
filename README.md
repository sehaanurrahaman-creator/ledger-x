# ledger-x

A double-entry ledger where exactly-once money movement under crashes is implemented and proved, not asserted:
deterministic byte-identical replay after any crash, a TLC-checked TLA⁺ spec, a chaos campaign, and honest
latency numbers per fsync policy.

Work is planned and tracked in the [Wayfinder map](https://github.com/sehaanurrahaman-creator/ledger-x/issues/1);
every ticket resolves a decision, recorded as an ADR under [`docs/adr/`](docs/adr).

## Build and test

One command, no third-party dependencies — a JDK 21 or newer is the only requirement:

```console
$ ./build.sh
```

It runs the style lint, compiles with `javac --release 21 -Xlint:all -Werror`, runs the substrate contract test and
runs the domain model's property suite. The other subcommands, and the `make` targets that delegate to them:

| Command | Make target | What it does |
| --- | --- | --- |
| `./build.sh` | `make` | lint, compile, both test mains — the verdict |
| `./build.sh demo` | `make demo` | post a two-account transfer and print the balances and the ledger |
| `./build.sh campaign` | `make campaign` | the property suite at ~10x the campaign CI runs by default |
| `./build.sh compile` | `make compile` | lint and compile, run nothing |
| `./build.sh lint` | `make lint` | the style lint, including the self-test of its two ADR rules |
| `./build.sh clean` | `make clean` | remove build output |

A failing property prints the seed that produced it and the shrunk case, and can be replayed without editing
anything: `LEDGER_X_SEED=20260921 make test`, or `make campaign TRIALS=400 OPERATIONS=600` for a longer hunt.

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

## Status

Substrate and domain model decided. `src/main` holds the durability primitives ADR 0001 rests on
(`dev.ledgerx.substrate`), the in-memory double-entry ledger and its types (`dev.ledgerx.domain`), and the demo
(`dev.ledgerx.demo`). `src/test` holds the five-check substrate contract and a nine-check property suite that runs
random sequences of valid and invalid operations against an independent `BigInteger` model of the same rules.

Nothing is durable yet. The WAL record format, checkpoints and replay, idempotency, concurrency and the payout
protocol are all still open tickets on the map.
