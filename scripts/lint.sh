#!/usr/bin/env bash
#
# ledger-x style lint — no third-party dependencies, so it cannot break on a registry outage.
#
# Rules:
#   1. no tabs anywhere in tracked source (except a Makefile's recipe lines, which are
#      tab-indented by definition)
#   2. no trailing whitespace
#   3. no CRLF line endings
#   4. every file ends with exactly one newline
#   5. .java and .sh lines are at most 100 columns, counted as bytes
#   6. no `synchronized` in src/ — ADR 0001 criterion 2: on Java 21 it pins a virtual
#      thread to its carrier. Per-account ordering uses ReentrantLock.
#   7. no floating point in src/main/java/dev/ledgerx/domain/ — ADR 0002: money is integer
#      minor units, and a `double` in the domain is a rounding error about somebody's money.
#   8. the wall clock is read in exactly one file: IdempotencyPolicy.java — ADR 0005: a key
#      binding's capture instant comes from the injected clock, so a replay's idea of "how old
#      is this key" is a decision the ledger can be handed rather than one it takes in
#      secret. System.nanoTime() is allowed anywhere: it is a monotonic deadline, not a
#      reading of what day it is, and group commit's delay budget is nobody's idempotency.
#
# Rules 6, 7 and 8 are the ADRs enforced mechanically rather than in review, and a rule that
# never fires is indistinguishable from a rule that is never violated — so each pattern runs
# against lines that must match and lines that must not, every time this script runs. That is
# selftest(), below; it is not optional and it is not decoration.
#
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/.."

status=0
TAB="$(printf '\t')"

# Comment lines are skipped by rules 6 and 7: javadoc in this repository discusses both
# `synchronized` and `double` at length, and prose is not a pinning hazard or a rounding
# error. The residual false positive is a *string literal* on a code line that contains one of
# the words followed by a space ("a double entry"); write "double-entry" instead.
readonly COMMENT_LINE='^[[:space:]]*(\*|//|/\*)'

# Rule 6: the keyword, not a substring of something else.
readonly SYNCHRONIZED='(^|[^A-Za-z_])synchronized([^A-Za-z_]|$)'

# Rule 8: every way of asking what time it is on the wall, as code shapes. The one file with
# permission to match is excluded in the loop below, not by the pattern.
readonly CLOCK_NOW='Instant\.now\(|Local(Date|Time)\.now\(|(Zoned|Offset)DateTime\.now\('
readonly CLOCK_YEAR='Year(Month)?\.now\('
readonly CLOCK_READ='System\.currentTimeMillis|Clock\.system[A-Za-z]*\('
readonly WALL_CLOCK="${CLOCK_NOW}|${CLOCK_YEAR}|${CLOCK_READ}"

# Rule 7, in four pieces, each one a code shape rather than a word.
readonly FLOAT_DECL='(^|[^A-Za-z_."])(float|double)[[:space:]]+[A-Za-z_(]'
readonly FLOAT_CAST='\((float|double)\)[[:space:]]*[A-Za-z_(0-9]'
readonly FLOAT_CONV='\.(float|double)Value\(|\.parse(Double|Float)\('
readonly FLOAT_LITERAL='(^|[^A-Za-z_0-9."])[0-9]*\.?[0-9]+[fdFD]([^A-Za-z_0-9]|$)'
readonly FLOATING_POINT="${FLOAT_DECL}|${FLOAT_CAST}|${FLOAT_CONV}|${FLOAT_LITERAL}"

fail() {
  echo "lint: $*" >&2
  status=1
}

# Prints "line:text" for every non-comment line matching the pattern. awk rather than grep so
# that skipping comment lines does not lose the real line numbers.
#
# The pattern reaches awk through the environment and never through -v. POSIX escape-processes
# a -v value, so under gawk '\(' arrives as '(' and these stop being the regexes that were
# written: the floating-point rule then fails to compile at all, and the build goes red on the
# selftest rather than silently linting nothing. mawk and busybox awk pass -v through untouched,
# which is why this only ever showed up in CI. ENVIRON values are literal on all three.
matches() {
  local file="$1" pattern="$2"
  LC_ALL=C LX_SKIP="${COMMENT_LINE}" LX_PAT="${pattern}" awk '
    BEGIN { skip = ENVIRON["LX_SKIP"]; pat = ENVIRON["LX_PAT"] }
    $0 ~ skip { next }
    $0 ~ pat { printf "%d:%s\n", FNR, $0 }
  ' "${file}"
}

selftest() {
  local tmp hits
  tmp="$(mktemp -d)"

  printf '%s\n' \
    'private double rate;' \
    'double apply(long minorUnits) {' \
    'return (double) minorUnits / 100;' \
    'long cents = money.doubleValue() * 100;' \
    'float fee = 0.5f;' \
    'BigDecimal parsed = Double.parseDouble(text);' \
    'if (amount == 1.0d) {' \
    > "${tmp}/MustMatch.java"

  printf '%s\n' \
    '/** No floating point reaches money. {@code double} cannot represent 0.1, and a */' \
    ' * rounding error in a ledger is not a rounding error. Double-entry bookkeeping has */' \
    ' * two verbs; a float of capital is not one of them. */' \
    'public record Money(long minorUnits) {' \
    '  // a double is exactly what this type is not' \
    '  public Money plus(Money other) {' \
    '    return new Money(Math.addExact(minorUnits, other.minorUnits()));' \
    '  }' \
    '}' \
    > "${tmp}/MustNotMatch.java"

  hits="$(matches "${tmp}/MustMatch.java" "${FLOATING_POINT}" | grep -c . || true)"
  if [ "${hits}" -ne 7 ]; then
    fail "selftest: the floating-point rule matched ${hits} of the 7 violations it must match"
    matches "${tmp}/MustMatch.java" "${FLOATING_POINT}" | sed 's/^/         matched /' >&2
  fi
  hits="$(matches "${tmp}/MustNotMatch.java" "${FLOATING_POINT}" | grep -c . || true)"
  if [ "${hits}" -ne 0 ]; then
    fail "selftest: the floating-point rule matched ${hits} line(s) of prose it must not"
    matches "${tmp}/MustNotMatch.java" "${FLOATING_POINT}" | sed 's/^/         matched /' >&2
  fi

  # Tripwire for the escape-processing bug above, and it is written to *discriminate*: this
  # line has no parentheses, so an intact \(double\) matches nothing while an escape-stripped
  # (double) matches the bare word. "0 of 7" says something broke; this says what.
  printf '%s\n' 'x = double y;' > "${tmp}/RoundTrip.java"
  hits="$(matches "${tmp}/RoundTrip.java" '\(double\)' | grep -c . || true)"
  if [ "${hits}" -ne 0 ]; then
    fail "selftest: awk mangled the regex it was given — patterns must not travel by -v"
  fi

  printf '%s\n' \
    'synchronized (account) {' \
    'public synchronized void post() {' \
    'this.synchronizedLock = lock;' \
    '* <p>Never {@code synchronized}: it pins a virtual thread. */' \
    > "${tmp}/Sync.java"
  hits="$(matches "${tmp}/Sync.java" "${SYNCHRONIZED}" | grep -c . || true)"
  if [ "${hits}" -ne 2 ]; then
    fail "selftest: the synchronized rule matched ${hits} of the 2 lines it must match"
    matches "${tmp}/Sync.java" "${SYNCHRONIZED}" | sed 's/^/         matched /' >&2
  fi

  printf '%s\n' \
    'Instant captured = Instant.now();' \
    'long at = System.currentTimeMillis();' \
    'Clock clock = Clock.systemUTC();' \
    'Clock zoned = Clock.systemDefaultZone();' \
    'java.time.LocalDate today = LocalDate.now();' \
    '* <p>Prose may say {@code Instant.now()} — comments are skipped, and prose is not' \
    ' * <p>a hidden reading of the wall clock.</p>' \
    > "${tmp}/Clock.java"
  hits="$(matches "${tmp}/Clock.java" "${WALL_CLOCK}" | grep -c . || true)"
  if [ "${hits}" -ne 5 ]; then
    fail "selftest: the wall-clock rule matched ${hits} of the 5 violations it must match"
    matches "${tmp}/Clock.java" "${WALL_CLOCK}" | sed 's/^/         matched /' >&2
  fi

  rm -rf "${tmp}"
}

# --others --exclude-standard as well as the index: a new file is linted from the moment it
# exists, not from the moment somebody remembers to `git add` it. Ignored paths (build/) stay
# out either way.
mapfile -t FILES < <(
  git ls-files --cached --others --exclude-standard -- \
    src scripts .github docs/adr build.sh Makefile | sort
)

if [ "${#FILES[@]}" -eq 0 ]; then
  echo "lint: no files to check (is this a git checkout?)" >&2
  exit 1
fi

selftest

for f in "${FILES[@]}"; do
  [ -f "${f}" ] || continue

  case "${f}" in
    Makefile)
      # A recipe line is tab-indented by definition, so rule 1 cannot apply here. Every other
      # rule still does.
      ;;
    *)
      if grep -q "${TAB}" "${f}"; then
        fail "${f}: contains tab characters"
      fi
      ;;
  esac

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
      # LC_ALL=C, so awk counts bytes rather than characters. In a UTF-8 locale awk counts
      # characters, and a line of javadoc with an em dash in it then measures differently here
      # than it does in CI — a lint that disagrees with itself about what a column is goes red
      # for no reason. Bytes is the stricter reading, so a green lint is green everywhere.
      long="$(LC_ALL=C awk 'length > 100 { printf "%d ", FNR }' "${f}")"
      if [ -n "${long}" ]; then
        fail "${f}: line(s) over 100 columns: ${long}"
      fi
      ;;
  esac

  case "${f}" in
    src/*.java)
      found="$(matches "${f}" "${SYNCHRONIZED}")"
      if [ -n "${found}" ]; then
        fail "${f}: uses 'synchronized' (ADR 0001 pinning rule); use ReentrantLock"
        printf '%s\n' "${found}" | sed 's/^/         /' >&2
      fi
      ;;
  esac

  case "${f}" in
    src/main/java/dev/ledgerx/domain/*.java)
      found="$(matches "${f}" "${FLOATING_POINT}")"
      if [ -n "${found}" ]; then
        fail "${f}: floating point in the domain (ADR 0002 — money is integer minor units)"
        printf '%s\n' "${found}" | sed 's/^/         /' >&2
      fi
      ;;
  esac

  case "${f}" in
    src/main/java/dev/ledgerx/idempotency/IdempotencyPolicy.java)
      # The one file with permission: the default policy is where the wall clock is read, and
      # the lint exists to keep it the only place.
      ;;
    src/main/java/*.java)
      found="$(matches "${f}" "${WALL_CLOCK}")"
      if [ -n "${found}" ]; then
        fail "${f}: reads the wall clock (ADR 0005 — inject it, don't read it)"
        printf '%s\n' "${found}" | sed 's/^/         /' >&2
      fi
      ;;
  esac
done

if [ "${status}" -ne 0 ]; then
  exit "${status}"
fi

echo "lint: ${#FILES[@]} file(s) clean, rules 6, 7 and 8 self-tested"
