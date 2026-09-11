#!/usr/bin/env bash
#
# ledger-x style lint — no third-party dependencies, so it cannot break on a registry outage.
#
# Rules:
#   1. no tabs anywhere in tracked source
#   2. no trailing whitespace
#   3. no CRLF line endings
#   4. every file ends with exactly one newline
#   5. .java and .sh lines are at most 100 columns
#   6. no `synchronized` in src/ — ADR 0001 criterion 2: on Java 21 it pins a virtual
#      thread to its carrier. Per-account ordering uses ReentrantLock.
#
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/.."

status=0
TAB="$(printf '\t')"

fail() {
  echo "lint: $*" >&2
  status=1
}

mapfile -t FILES < <(git ls-files -- src scripts .github docs/adr build.sh | sort)

if [ "${#FILES[@]}" -eq 0 ]; then
  echo "lint: no files to check (is this a git checkout?)" >&2
  exit 1
fi

for f in "${FILES[@]}"; do
  [ -f "${f}" ] || continue

  if grep -q "${TAB}" "${f}"; then
    fail "${f}: contains tab characters"
  fi

  if grep -nq '[[:space:]]$' "${f}"; then
    lines="$(grep -n '[[:space:]]$' "${f}" | cut -d: -f1 | tr '\n' ' ')"
    fail "${f}: trailing whitespace on line(s) ${lines}"
  fi

  if grep -q "$(printf '\r')" "${f}"; then
    fail "${f}: CRLF line endings"
  fi

  if [ -s "${f}" ] && [ -n "$(tail -c1 "${f}")" ]; then
    fail "${f}: missing final newline"
  fi

  case "${f}" in
    *.java|*.sh)
      long="$(awk 'length > 100 { printf "%d ", FNR }' "${f}")"
      if [ -n "${long}" ]; then
        fail "${f}: line(s) over 100 columns: ${long}"
      fi
      ;;
  esac

  case "${f}" in
    src/*.java)
      if grep -nE '(^|[^A-Za-z_])synchronized([^A-Za-z_]|$)' "${f}" >/dev/null; then
        fail "${f}: uses 'synchronized' (ADR 0001 pinning rule); use ReentrantLock"
      fi
      ;;
  esac
done

if [ "${status}" -ne 0 ]; then
  exit "${status}"
fi

echo "lint: ${#FILES[@]} file(s) clean"
