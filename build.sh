#!/usr/bin/env bash
#
# ledger-x — one command to build and test.
#
#   ./build.sh            lint, compile, the substrate contract, the WAL contract, the properties
#   ./build.sh compile    lint and compile, run nothing
#   ./build.sh crash      the kill -9 micro-harness: 1,000 random kill/recover cycles per policy
#   ./build.sh replay     the checkpoint replay harness: crash at every byte offset of every history
#   ./build.sh test       compile and run both test mains
#   ./build.sh demo       compile and run the transfer demo — what `make demo` runs
#   ./build.sh campaign   the property suite at a much larger campaign than CI runs
#   ./build.sh lint       style lint only
#   ./build.sh clean      remove build output
#
# There are no third-party dependencies and no dependency manager: JDK 21 plus POSIX shell.
# ADR 0001 records the trigger that will introduce one (the first ticket needing JMH or
# JUnit), and this script becomes a thin wrapper over it, so that this command never changes.
# ADR 0002 §9 records why the domain model's property-based tests did not fire that trigger.
#
# Campaign size is an environment variable, not an edit:
#   LEDGER_X_TRIALS=150 LEDGER_X_OPERATIONS=400 ./build.sh test
#   LEDGER_X_SEED=20260921 ./build.sh test      # reproduce one failing case
#
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")"

readonly RELEASE="21"
readonly BUILD_DIR="build"
readonly MAIN_OUT="${BUILD_DIR}/classes/main"
readonly TEST_OUT="${BUILD_DIR}/classes/test"
readonly CONTRACT_ENTRY="dev.ledgerx.substrate.SubstrateContract"
readonly WAL_CONTRACT_ENTRY="dev.ledgerx.wal.WalContract"
readonly CHECKPOINT_ENTRY="dev.ledgerx.checkpoint.CheckpointContract"
readonly REPLAY_ENTRY="dev.ledgerx.checkpoint.ReplayHarness"
readonly CRASH_ENTRY="dev.ledgerx.wal.crash.CrashHarness"
readonly PROPERTIES_ENTRY="dev.ledgerx.domain.DomainModelProperties"
readonly DEMO_ENTRY="dev.ledgerx.demo.TransferDemo"
readonly JAVAC_FLAGS=(--release "${RELEASE}" -Xlint:all -Werror -encoding UTF-8)

# The sources are UTF-8 (the javadoc argues about Σ balances and em dashes); without these the
# JVM falls back to the console encoding and prints '?' for both, which is what the CI
# annotations are read from. Java 19+ honours them, and this build requires 21.
readonly JAVA_OPTS=(-Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8)

# The extended campaign: ~10x the operations CI runs, still under a minute on a runner.
readonly CAMPAIGN_TRIALS=150
readonly CAMPAIGN_OPERATIONS=400

# The crash harness, sized to the ticket's floor (>= 1,000 kill/recover cycles) and to a CI budget
# of a couple of minutes: 1,000 cycles x 24 ops runs in ~3 min here. Override with the env, e.g.
#   LEDGER_X_CRASH_CYCLES=5000 LEDGER_X_CRASH_OPS=200 ./build.sh crash
readonly CRASH_CYCLES="${LEDGER_X_CRASH_CYCLES:-1000}"
readonly CRASH_OPS="${LEDGER_X_CRASH_OPS:-24}"

# The checkpoint replay harness: every byte offset of every history is a crash. Sized so the
# default sweeps tens of thousands of crash offsets in well under a minute; the campaign knob
# multiplies it, e.g.
#   LEDGER_X_REPLAY_TRIALS=60 LEDGER_X_REPLAY_OPS=80 ./build.sh replay
readonly REPLAY_TRIALS="${LEDGER_X_REPLAY_TRIALS:-12}"
readonly REPLAY_OPS="${LEDGER_X_REPLAY_OPS:-40}"
readonly REPLAY_SEED="${LEDGER_X_REPLAY_SEED:-20260917}"

log() { printf '\n==> %s\n' "$*"; }

require_java() {
  if ! command -v java >/dev/null 2>&1 || ! command -v javac >/dev/null 2>&1; then
    echo "error: need a JDK (java and javac) on PATH — Java ${RELEASE} or newer" >&2
    exit 1
  fi
  local feature
  local settings
  settings="$(java -XshowSettings:properties -version 2>&1)"
  feature="$(printf '%s\n' "${settings}" \
    | sed -n 's/.*java\.specification\.version[ =]*\([0-9]*\).*/\1/p' | head -1)"
  if [ -z "${feature}" ] || [ "${feature}" -lt "${RELEASE}" ]; then
    echo "error: Java ${RELEASE}+ required, found '${feature:-unknown}'" >&2
    exit 1
  fi
  echo "JDK: $(java -version 2>&1 | head -1)"
}

compile() {
  local src="$1" out="$2" cp="$3"
  local sources=()
  while IFS= read -r line; do sources+=("${line}"); done < <(find "${src}" -name '*.java' | sort)
  if [ "${#sources[@]}" -eq 0 ]; then
    echo "error: no sources under ${src}" >&2
    exit 1
  fi
  rm -rf "${out}"
  mkdir -p "${out}" "${BUILD_DIR}"
  local args=("${JAVAC_FLAGS[@]}")
  if [ -n "${cp}" ]; then args+=(-cp "${cp}"); fi
  args+=(-d "${out}" "${sources[@]}")
  local javac_log="${BUILD_DIR}/javac.log" status=0
  if ! javac "${args[@]}" 2>&1 | tee "${javac_log}"; then status=1; fi
  if [ "${status}" -ne 0 ]; then
    annotate_failure "javac ${src}" "${javac_log}" head
    exit 1
  fi
  echo "${#sources[@]} source file(s) -> ${out}"
}

do_clean() {
  log "clean"
  rm -rf "${BUILD_DIR}"
}

do_lint() {
  log "lint"
  mkdir -p "${BUILD_DIR}"
  local lint_log="${BUILD_DIR}/lint.log" status=0
  if ! scripts/lint.sh 2>&1 | tee "${lint_log}"; then status=1; fi
  if [ "${status}" -ne 0 ]; then
    annotate_failure "lint" "${lint_log}" head
    exit 1
  fi
}

do_compile() {
  do_lint
  log "compile src/main (javac --release ${RELEASE} -Xlint:all -Werror)"
  compile src/main/java "${MAIN_OUT}" ""
  log "compile src/test"
  compile src/test/java "${TEST_OUT}" "${MAIN_OUT}"
}

# Runs a main class, tees it to a log, and keeps its exit code — safe because this script runs
# with pipefail. Wraps the output in the CI step summary when there is one.
run_main() {
  local name="$1" entry="$2" log_file="$3"
  shift 3
  log "${name}: ${entry}"
  if [ -n "${GITHUB_STEP_SUMMARY:-}" ]; then
    printf '### %s\n\n```\n' "${name}" >> "${GITHUB_STEP_SUMMARY}"
  fi
  local status=0
  if ! java "${JAVA_OPTS[@]}" "$@" -cp "${MAIN_OUT}:${TEST_OUT}" "${entry}" \
    | tee "${log_file}"; then
    status=1
  fi
  if [ -n "${GITHUB_STEP_SUMMARY:-}" ]; then
    printf '```\n' >> "${GITHUB_STEP_SUMMARY}"
  fi
  if [ "${status}" -ne 0 ]; then
    annotate_failure "${name}" "${log_file}" tail
    exit "${status}"
  fi
}

# A failing step publishes its own diagnostics as check annotations, because the runner's log
# lives behind a blob URL that scripts/ci-evidence.sh cannot fetch — annotations are the one CI
# channel readable back through the REST API. 'head' for compiler diagnostics, which come first;
# 'tail' for a failed test, whose detail is printed last.
annotate_failure() {
  local what="$1" log_file="$2" mode="${3:-head}" title line pick
  [ "${GITHUB_ACTIONS:-}" = "true" ] || return 0
  [ -f "${log_file}" ] || return 0
  title="$(printf '%s' "${what}" | sed 's/[^A-Za-z0-9._-]/-/g' | cut -c1-60)"
  if [ "${mode}" = "tail" ]; then pick="tail -n 12"; else pick="head -n 12"; fi
  while IFS= read -r line; do
    line="$(printf '%s' "${line}" | sed 's/%/%25/g' | cut -c1-300)"
    echo "::error title=${title}::${line}"
  done < <(grep -v '^[[:space:]]*$' "${log_file}" | ${pick})
}

# The verdict line is the last line of the log; annotations have a length limit, so it is cut.
annotate() {
  local log_file="$1"
  [ "${GITHUB_ACTIONS:-}" = "true" ] || return 0
  echo "::notice::$(tail -n 1 "${log_file}" | cut -c1-400)"
}

# Fills the global CAMPAIGN_OPTS from the environment, so a failing seed can be replayed
# without editing anything.
CAMPAIGN_OPTS=()
campaign_opts() {
  CAMPAIGN_OPTS=()
  if [ -n "${LEDGER_X_TRIALS:-}" ]; then
    CAMPAIGN_OPTS+=("-Dledgerx.property.trials=${LEDGER_X_TRIALS}")
  fi
  if [ -n "${LEDGER_X_OPERATIONS:-}" ]; then
    CAMPAIGN_OPTS+=("-Dledgerx.property.operations=${LEDGER_X_OPERATIONS}")
  fi
  if [ -n "${LEDGER_X_SEED:-}" ]; then
    CAMPAIGN_OPTS+=("-Dledgerx.property.seed=${LEDGER_X_SEED}")
  fi
}

do_contract_test() {
  local log_file="${BUILD_DIR}/contract-test.log"
  run_main "substrate contract test" "${CONTRACT_ENTRY}" "${log_file}"
  annotate "${log_file}"
  if [ "${GITHUB_ACTIONS:-}" = "true" ]; then
    local detail
    detail="$(grep -o 'platformThreads=[0-9]*' "${log_file}" | head -1 || true)"
    if [ -n "${detail}" ]; then
      echo "::notice::substrate contract measured ${detail} platform threads"
    fi
  fi
}

do_wal_contract() {
  local log_file="${BUILD_DIR}/wal-contract.log"
  run_main "durable-write contract" "${WAL_CONTRACT_ENTRY}" "${log_file}"
  annotate "${log_file}"
}

do_checkpoint_contract() {
  local log_file="${BUILD_DIR}/checkpoint-contract.log"
  run_main "checkpoint contract" "${CHECKPOINT_ENTRY}" "${log_file}"
  annotate "${log_file}"
}

do_properties() {
  local log_file="${BUILD_DIR}/property-test.log"
  campaign_opts
  run_main \
    "domain model properties" \
    "${PROPERTIES_ENTRY}" \
    "${log_file}" \
    "${CAMPAIGN_OPTS[@]+"${CAMPAIGN_OPTS[@]}"}"
  annotate "${log_file}"
}

do_demo() {
  local log_file="${BUILD_DIR}/demo.log"
  run_main "demo" "${DEMO_ENTRY}" "${log_file}"
}

do_replay() {
  local log_file="${BUILD_DIR}/replay-harness.log"
  log "checkpoint replay harness: ${REPLAY_TRIALS} histories x ${REPLAY_OPS} steps, every"
  log "byte offset a crash"
  local status=0
  if ! java "${JAVA_OPTS[@]}" \
    -Dledgerx.replay.trials="${REPLAY_TRIALS}" \
    -Dledgerx.replay.ops="${REPLAY_OPS}" \
    -Dledgerx.replay.seed="${REPLAY_SEED}" \
    -cp "${MAIN_OUT}:${TEST_OUT}" "${REPLAY_ENTRY}" | tee "${log_file}"; then
    status=1
  fi
  if [ -n "${GITHUB_STEP_SUMMARY:-}" ]; then
    {
      printf '### checkpoint replay harness\n\n```\n'
      grep -E "^  trial |^PASS|^FAIL" "${log_file}" || tail -n 20 "${log_file}"
      printf '\n```\n'
    } >> "${GITHUB_STEP_SUMMARY}"
  fi
  if [ "${status}" -ne 0 ]; then
    annotate_failure "replay harness" "${log_file}" tail
    exit 1
  fi
  annotate "${log_file}"
}

do_test() {
  do_contract_test
  do_wal_contract
  do_checkpoint_contract
  do_properties
}

do_crash() {
  local log_file="${BUILD_DIR}/crash-harness.log"
  log "kill -9 micro-harness: ${CRASH_CYCLES} cycles x ${CRASH_OPS} ops across the fsync menu"
  local status=0
  if ! java "${JAVA_OPTS[@]}" \
    -Dledgerx.crash.cycles="${CRASH_CYCLES}" \
    -Dledgerx.crash.ops="${CRASH_OPS}" \
    -cp "${MAIN_OUT}:${TEST_OUT}" "${CRASH_ENTRY}" | tee "${log_file}"; then
    status=1
  fi
  if [ -n "${GITHUB_STEP_SUMMARY:-}" ]; then
    {
      printf '### kill -9 micro-harness\n\n```\n'
      grep -E "^  [a-z-]+ /|^  total:|^PASS|^FAIL" "${log_file}" || tail -n 20 "${log_file}"
      printf '\n```\n'
    } >> "${GITHUB_STEP_SUMMARY}"
  fi
  if [ "${status}" -ne 0 ]; then
    annotate_failure "crash harness" "${log_file}" tail
    exit 1
  fi
  annotate "${log_file}"
}

do_campaign() {
  local log_file="${BUILD_DIR}/property-test.log"
  log "domain model properties: extended campaign (${CAMPAIGN_TRIALS} cases x \
${CAMPAIGN_OPERATIONS} operations)"
  LEDGER_X_TRIALS="${LEDGER_X_TRIALS:-${CAMPAIGN_TRIALS}}" \
  LEDGER_X_OPERATIONS="${LEDGER_X_OPERATIONS:-${CAMPAIGN_OPERATIONS}}" \
    do_properties
  echo "campaign log: ${log_file}"
}

do_build() {
  do_compile
  do_test
}

case "${1:-all}" in
  all)      require_java; do_build ;;
  compile)  require_java; do_compile ;;
  test)     require_java; do_compile; do_test ;;
  replay)   require_java; do_compile; do_replay ;;
  crash)    require_java; do_compile; do_crash ;;
  demo)     require_java; do_compile; do_demo ;;
  campaign) require_java; do_compile; do_campaign ;;
  lint)     do_lint ;;
  clean)    do_clean ;;
  *)        echo "usage: $0 [all|compile|test|demo|crash|campaign|lint|clean]" >&2; exit 2 ;;
esac

log "done"
