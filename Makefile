# ledger-x — the one command is ./build.sh; these targets are the ways of saying it.
#
#   make            lint, compile, run all three suites: substrate, WAL contract, properties
#   make crash        the kill -9 micro-harness: 1,000 random kill/recover cycles
#   make demo       post a two-account transfer and print the balances and the ledger
#   make campaign   the property suite at ~10x the campaign CI runs
#   make test       compile and run both test mains, nothing else
#   make compile    lint and compile, run nothing
#   make lint       the style lint, including the self-test of its own two ADR rules
#   make clean      remove build output
#
# Nothing here duplicates build.sh: every recipe delegates, so there is one place that knows
# how to compile and one verdict. ADR 0001 keeps ./build.sh the command of record even if a
# dependency manager arrives later; this file inherits that.
#
# Recipes are tab-indented by definition, which is why scripts/lint.sh exempts this file from
# its no-tab rule — and still checks it for trailing whitespace, CRLF, a final newline and
# 100 columns.
#
# Campaign size, without editing anything:
#   make campaign TRIALS=400 OPERATIONS=600
#   make test SEED=20260921                 # replay one failing case
#
.DEFAULT_GOAL := all

CAMPAIGN_ENV = \
	LEDGER_X_TRIALS="$(or $(TRIALS),)" \
	LEDGER_X_OPERATIONS="$(or $(OPERATIONS),)" \
	LEDGER_X_SEED="$(or $(SEED),)"

all:
	./build.sh

compile:
	./build.sh compile

test:
	$(CAMPAIGN_ENV) ./build.sh test

demo:
	./build.sh demo

crash:
	LEDGER_X_CRASH_CYCLES="$(or $(CYCLES),)" \
	LEDGER_X_CRASH_OPS="$(or $(OPS),)" \
	./build.sh crash

campaign:
	$(CAMPAIGN_ENV) ./build.sh campaign

lint:
	./build.sh lint

clean:
	./build.sh clean

.PHONY: all compile test demo crash campaign lint clean
