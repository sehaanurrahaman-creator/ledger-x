#!/usr/bin/env bash
#
# ledger-x CI evidence — read, not remembered.
#
# The ADRs quote CI run ids, conclusions and annotations. Hand-counting those went wrong
# once (see docs/adr/0001-substrate.closeout.md), so this script prints them from the API
# instead. It is a developer tool, not part of ./build.sh: it needs `gh`, authenticated,
# and network access, none of which the build may depend on.
#
#   scripts/ci-evidence.sh              current repository, main plus the checked-out branch
#   scripts/ci-evidence.sh <branch>…    those branches
#
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/.."

command -v gh >/dev/null 2>&1 || {
  echo "error: needs the GitHub CLI (gh) on PATH — this is not part of ./build.sh" >&2
  exit 1
}

readonly REPO="${LEDGER_X_REPO:-sehaanurrahaman-creator/ledger-x}"
readonly MAIN="${LEDGER_X_MAIN:-main}"

if [ "$#" -gt 0 ]; then
  BRANCHES=("$@")
else
  BRANCHES=("${MAIN}")
  branch="$(git rev-parse --abbrev-ref HEAD 2>/dev/null || true)"
  if [ -n "${branch}" ] && [ "${branch}" != "${MAIN}" ]; then
    BRANCHES+=("${branch}")
  fi
fi

echo "repository: ${REPO}"
echo "permissions: $(gh api "repos/${REPO}" --jq '.permissions | tostring')"

for branch in "${BRANCHES[@]}"; do
  echo
  echo "branch: ${branch}"
  gh api "repos/${REPO}/actions/runs?branch=${branch}&per_page=100" --jq '
    .workflow_runs as $runs
    | "  runs: \($runs | length)"
      + " (\($runs | map(.event) | group_by(.) | map("\(.[0]) \(length)") | join(", ")))"
      + ", not success: \($runs | map(select(.conclusion != "success")) | length)",
      ($runs | sort_by(.id) | last
        | "  latest: run \(.id), \(.event), \(.conclusion), head \(.head_sha[0:7])"
      )
  '
  head_sha="$(gh api "repos/${REPO}/actions/runs?branch=${branch}&per_page=1" \
    --jq '.workflow_runs[0].head_sha')"
  if [ -n "${head_sha}" ] && [ "${head_sha}" != "null" ]; then
    gh api "repos/${REPO}/commits/${head_sha}/check-runs" --jq '.check_runs | unique_by(.name)
      | .[] | "  check: \(.name) -> \(.conclusion) (\(.output.annotations_count) annotations)"'
    check_id="$(gh api "repos/${REPO}/commits/${head_sha}/check-runs" \
      --jq '.check_runs[0].id')"
    gh api "repos/${REPO}/check-runs/${check_id}/annotations" --jq '.[] | "  notice: \(.message)"'
  fi
done

echo
echo "issue permissions (all issue writes 403 for this integration when triage=false):"
gh api "repos/${REPO}/issues/1" --jq '
  "  map #1: sub-issues \(.sub_issues_summary.total), completed \(.sub_issues_summary.completed)"'
gh api "repos/${REPO}/issues/3" \
  --jq '"  ticket #3: \(.state), comments \(.comments), assignees \(.assignees | length)"'
