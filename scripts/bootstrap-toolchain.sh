#!/usr/bin/env bash
#
# ledger-x toolchain bootstrap for environments that have no JDK.
#
# This is NOT the build. `./build.sh` remains the one command and still requires nothing but
# a JDK 21 — see ADR 0001. This script exists for the case the Arena sandbox hit: no JDK on
# PATH, and every JDK distribution host blocked by the network allowlist, so only the package
# registries are reachable. It assembles a working compiler + runtime from two of them:
#
#   JRE      jdk4py 21.0.8.2 (manylinux x86_64 wheel)  -> Temurin 21.0.8 runtime from PyPI
#   compiler Eclipse JDT batch compiler (ECJ) 3.45.0   -> from the npm package
#                                                        @vscjava/java-language-server
#
# It then compiles src/main and src/test and runs the substrate contract test, which is the
# same verdict `./build.sh` produces — but by a *different* compiler. Two caveats, stated so
# nobody over-reads the result: ECJ is not javac, so its lint token set is not identical
# (`-err:+unused` is stricter than `-Xlint:all`; `-Xlint:all -Werror` semantics remain CI's
# call), and the runtime here is a JRE, so it can compile and run but not build a JDK image.
#
#   scripts/bootstrap-toolchain.sh          fetch (if needed), compile, run the contract test
#   scripts/bootstrap-toolchain.sh --clean  remove the fetched toolchain
#
# Requires: curl, unzip, tar, sha256sum, python3 — and network access to pypi.org and
# registry.npmjs.org. Artifacts land in build/toolchain/, which is already gitignored.
#
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/.."

readonly TOOLCHAIN_DIR="build/toolchain"
readonly JRE_DIR="${TOOLCHAIN_DIR}/jre"
readonly ECJ_DIR="${TOOLCHAIN_DIR}/ecj"
readonly MAIN_OUT="build/classes/main"
readonly TEST_OUT="build/classes/test"
readonly MAIN_ENTRY="dev.ledgerx.substrate.SubstrateContract"

# Pinned, with digests: a floating "latest" would make a local red build unattributable.
readonly JRE_URL="https://files.pythonhosted.org/packages/a2/61/\
f3b5936908ff6de66c61aef69bf1074cb468fb883f5a0bb9e15e67a6b484/\
jdk4py-21.0.8.2-py3-none-manylinux_2_17_x86_64.whl"
readonly JRE_SHA256="85addfcb57c7051dad6145b9f816fc519337e9a0c705ef01edc9dc7818ee0356"
readonly ECJ_URL="https://registry.npmjs.org/@vscjava/java-language-server/-\
/java-language-server-0.1.2.tgz"
readonly ECJ_SHA256="634f4c82341b8b4157a36387d163c4658ebecaf8f30ef6cd4b0cd4bd06aaca0d"
readonly ECJ_JAR="org.eclipse.jdt.core.compiler.batch_3.45.0.v20260224-0835.jar"

# The subset of javac's `-Xlint:all` that ECJ has an equivalent token for, promoted to errors.
readonly ECJ_LINT="-err:+deprecation,+unchecked,+removal,+finally,+fallthrough,+hiding,+semicolon"

log() { printf '\n==> %s\n' "$*"; }

fetch() {
  local url="$1" dest="$2" want="$3"
  if [ -f "${dest}" ] && [ "$(sha256sum "${dest}" | cut -d' ' -f1)" = "${want}" ]; then
    echo "cached: ${dest}"
    return
  fi
  echo "fetching $(basename "${dest}")"
  curl -sSL --fail --max-time 600 -o "${dest}.part" "${url}"
  local got
  got="$(sha256sum "${dest}.part" | cut -d' ' -f1)"
  if [ "${got}" != "${want}" ]; then
    rm -f "${dest}.part"
    echo "error: sha256 mismatch for ${url}" >&2
    echo "  want ${want}" >&2
    echo "  got  ${got}" >&2
    exit 1
  fi
  mv "${dest}.part" "${dest}"
}

do_clean() {
  log "clean"
  rm -rf "${TOOLCHAIN_DIR}"
  echo "removed ${TOOLCHAIN_DIR}"
}

if [ "${1:-}" = "--clean" ]; then
  do_clean
  exit 0
fi

if command -v javac >/dev/null 2>&1; then
  log "a JDK is already on PATH — this script is unnecessary"
  echo "java: $(command -v java)"
  echo "use ./build.sh; it is the build of record. Continuing anyway to cross-check with ECJ."
fi

command -v curl >/dev/null
command -v unzip >/dev/null
command -v tar >/dev/null
command -v sha256sum >/dev/null

mkdir -p "${TOOLCHAIN_DIR}"

log "JRE: Temurin 21.0.8 from PyPI (jdk4py wheel)"
fetch "${JRE_URL}" "${TOOLCHAIN_DIR}/jdk4py.whl" "${JRE_SHA256}"
if [ ! -x "${JRE_DIR}/bin/java" ]; then
  rm -rf "${TOOLCHAIN_DIR}/jre-extract"
  unzip -q "${TOOLCHAIN_DIR}/jdk4py.whl" -d "${TOOLCHAIN_DIR}/jre-extract"
  mv "${TOOLCHAIN_DIR}/jre-extract/jdk4py/java-runtime" "${JRE_DIR}"
  rm -rf "${TOOLCHAIN_DIR}/jre-extract"
fi
JAVA="${JRE_DIR}/bin/java"
"${JAVA}" -version 2>&1 | sed 's/^/  /'

log "compiler: Eclipse JDT batch compiler (ECJ) 3.45.0 from npm"
fetch "${ECJ_URL}" "${TOOLCHAIN_DIR}/jdtls.tgz" "${ECJ_SHA256}"
if [ ! -f "${ECJ_DIR}/${ECJ_JAR}" ]; then
  rm -rf "${TOOLCHAIN_DIR}/ecj-extract"
  tar xzf "${TOOLCHAIN_DIR}/jdtls.tgz" -C "${TOOLCHAIN_DIR}" \
    "package/server/plugins/${ECJ_JAR}"
  mkdir -p "${ECJ_DIR}"
  mv "${TOOLCHAIN_DIR}/package/server/plugins/${ECJ_JAR}" "${ECJ_DIR}/"
  rm -rf "${TOOLCHAIN_DIR}/package"
fi
ECJ="${ECJ_DIR}/${ECJ_JAR}"
echo "  ${ECJ}"

sources() { find "$1" -name '*.java' | sort; }

log "compile src/main (ECJ ${ECJ_LINT})"
rm -rf "${MAIN_OUT}"
mkdir -p "${MAIN_OUT}"
# shellcheck disable=SC2046  # word splitting is the point: one path per source file
"${JAVA}" -jar "${ECJ}" --release 21 ${ECJ_LINT} -encoding UTF-8 -d "${MAIN_OUT}" \
  $(sources src/main/java)

log "compile src/test"
rm -rf "${TEST_OUT}"
mkdir -p "${TEST_OUT}"
# shellcheck disable=SC2046
"${JAVA}" -jar "${ECJ}" --release 21 ${ECJ_LINT} -encoding UTF-8 -cp "${MAIN_OUT}" \
  -d "${TEST_OUT}" $(sources src/test/java)

log "run the substrate contract test (the ./build.sh verdict, by another compiler)"
"${JAVA}" -cp "${MAIN_OUT}:${TEST_OUT}" "${MAIN_ENTRY}"

log "done — CI's javac run remains the check of record for -Xlint:all -Werror"
