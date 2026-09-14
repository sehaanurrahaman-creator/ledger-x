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

It runs the style lint, compiles with `javac --release 21 -Xlint:all -Werror`, and runs the substrate contract
test. `./build.sh lint` and `./build.sh clean` are the only other subcommands.

The numbers the ADRs quote about CI — run counts, conclusions, the annotations this build publishes — are read back
from the API by `scripts/ci-evidence.sh`, which needs `gh` and network and is therefore not part of the build.

Environments with no JDK and no reachable JDK distribution (the Arena sandbox is one) can still compile and run the
contract test: `scripts/bootstrap-toolchain.sh` fetches a digest-pinned Temurin 21 runtime from PyPI and the Eclipse
JDT batch compiler from npm, then produces the same verdict with a different compiler. It is a fallback, not the
build — CI's javac run stays the check of record.

## Decisions

| ADR | Decision |
| --- | --- |
| [0001 — Substrate](docs/adr/0001-substrate.md) | Java 21 with virtual threads, and a hand-rolled append-only WAL; no storage engine dependency |

## Status

Substrate decided; no ledger code yet. `src/` currently holds only the durability primitives the substrate
decision rests on and the test that pins them down.
