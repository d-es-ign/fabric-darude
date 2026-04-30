#!/usr/bin/env bash
set -euo pipefail

repo_root="${1:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)}"

if [[ -n "${MOD_VERSION_OVERRIDE:-}" ]]; then
  printf '%s\n' "${MOD_VERSION_OVERRIDE#v}"
  exit 0
fi

if [[ -n "${ORG_GRADLE_PROJECT_modVersionOverride:-}" ]]; then
  printf '%s\n' "${ORG_GRADLE_PROJECT_modVersionOverride#v}"
  exit 0
fi

fallback_version=$(python3 - <<'PY' "$repo_root"
from pathlib import Path
import sys

for line in (Path(sys.argv[1]) / 'gradle.properties').read_text(encoding='utf-8').splitlines():
    line = line.strip()
    if line.startswith('mod_version='):
        print(line.split('=', 1)[1].strip())
        raise SystemExit(0)

print('0.1.0')
PY
)

baseline_tag=$(git -C "$repo_root" tag --list --sort=-version:refname | python3 - <<'PY'
import re
import sys

for raw in sys.stdin:
    line = raw.strip()
    if re.fullmatch(r'v?\d+\.\d+\.\d+', line):
        print(line)
        raise SystemExit(0)
PY
)

baseline_version="$fallback_version"
if [[ -n "$baseline_tag" ]]; then
  baseline_version="${baseline_tag#v}"
fi

IFS='.' read -r major minor _ <<< "$baseline_version"

repo_slug=$(python3 - <<'PY' "$repo_root"
import re
import subprocess
import sys

remote = subprocess.check_output(['git', '-C', sys.argv[1], 'remote', 'get-url', 'origin'], text=True).strip()
for pattern in (r'github\.com:(.+?)(?:\.git)?$', r'github\.com/(.+?)(?:\.git)?$'):
    match = re.search(pattern, remote)
    if match:
        print(match.group(1))
        raise SystemExit(0)
PY
)

query="repo:${repo_slug} is:pr is:merged base:main"
if [[ -n "$baseline_tag" ]]; then
  merged_since=$(git -C "$repo_root" log -1 --format=%cI "$baseline_tag")
  query="$query merged:>=$merged_since"
fi

merged_count=$(gh api graphql -f "query=query { search(query: \"$query\", type: ISSUE) { issueCount } }" --jq '.data.search.issueCount' 2>/dev/null || true)
if [[ -z "$merged_count" ]]; then
  printf '%s\n' "$fallback_version"
  exit 0
fi

base_ref="main"
if ! git -C "$repo_root" rev-parse --verify main >/dev/null 2>&1; then
  base_ref="origin/main"
fi

ahead_count=$(git -C "$repo_root" rev-list --count "$base_ref..HEAD" 2>/dev/null || true)
if [[ -z "$ahead_count" ]]; then
  printf '%s\n' "$fallback_version"
  exit 0
fi

resolved_minor=$((minor + merged_count))
if [[ "$ahead_count" -gt 0 ]]; then
  resolved_minor=$((resolved_minor + 1))
fi

printf '%s.%s.%s\n' "$major" "$resolved_minor" "$ahead_count"
