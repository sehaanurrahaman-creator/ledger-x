#!/usr/bin/env bash
#
# ledger-x — one command to build and test.
#
#   ./build.sh           lint, compile, run the substrate contract test
#   ./build.sh lint      style lint only
#   ./build.sh clean     remove build output
#
# There are no third-party dependencies and no dependency manager: JDK 21 plus POSIX
# shell. ADR 0001 records the trigger that will introduce one (the first ticket needing
# JMH or JUnit), and this script becomes a thin wrapper over it, so that this command
# never changes.
#
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")"

readonly RELEASE="21"
readonly BUILD_DIR="build"
readonly MAIN_OUT="${BUILD_DIR}/classes/main"
readonly TEST_OUT="${BUILD_DIR}/classes/test"
readonly MAIN_ENTRY="dev.ledgerx.substrate.SubstrateContract"
readonly JAVAC_FLAGS=(--release "${RELEASE}" -Xlint:all -Werror -encoding UTF-8)

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
  mkdir -p "${out}"
  if [ -n "${cp}" ]; then
    javac "${JAVAC_FLAGS[@]}" -cp "${cp}" -d "${out}" "${sources[@]}"
  else
    javac "${JAVAC_FLAGS[@]}" -d "${out}" "${sources[@]}"
  fi
  echo "${#sources[@]} source file(s) -> ${out}"
}

do_clean() {
  log "clean"
  rm -rf "${BUILD_DIR}"
}

do_lint() {
  log "lint"
  scripts/lint.sh
}

do_build() {
  do_lint
  log "compile src/main (javac --release ${RELEASE} -Xlint:all -Werror)"
  compile src/main/java "${MAIN_OUT}" ""
  log "compile src/test"
  compile src/test/java "${TEST_OUT}" "${MAIN_OUT}"
  log "test: ${MAIN_ENTRY}"
  # tee is safe here because this script runs with pipefail, so the test's exit code survives.
  if [ -n "${GITHUB_STEP_SUMMARY:-}" ]; then
    printf '### %s\n\n```\n' "$(git log -1 --pretty=%s 2>/dev/null || echo ledger-x)" \
      >> "${GITHUB_STEP_SUMMARY}"
  fi
  java -cp "${MAIN_OUT}:${TEST_OUT}" "${MAIN_ENTRY}" | tee "${BUILD_DIR}/contract-test.log"
  if [ -n "${GITHUB_STEP_SUMMARY:-}" ]; then
    printf '```\n' >> "${GITHUB_STEP_SUMMARY}"
  fi
}

case "${1:-all}" in
  all)   require_java; do_build ;;
  lint)  do_lint ;;
  clean) do_clean ;;
  *)     echo "usage: $0 [all|lint|clean]" >&2; exit 2 ;;
esac

log "done"
