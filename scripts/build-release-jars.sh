#!/usr/bin/env bash
set -euo pipefail

repo_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
release_dir="$repo_root/releases"

clean=false
if [[ "${1:-}" == "--clean" ]]; then
  clean=true
fi

if [[ "$clean" == true ]]; then
  rm -rf "$release_dir"
fi

mkdir -p "$release_dir"

module_rows=()
while IFS= read -r line; do
  module_rows+=("$line")
done < <(
  python3 - <<'PY' "$repo_root"
from pathlib import Path
import re
import sys

root = Path(sys.argv[1])
builds = root / "builds"

if not builds.exists():
    sys.exit(0)

for d in sorted(builds.iterdir()):
    if d.is_dir() and re.fullmatch(r"mc\d+", d.name) and (d / "build.gradle").exists():
        print(f"{d.name}\t{d.as_posix()}")
PY
)

if [[ ${#module_rows[@]} -eq 0 ]]; then
  echo "No version-band modules found under builds/."
  exit 1
fi

echo "Building release jars for modules:"
for row in "${module_rows[@]}"; do
  module="${row%%$'\t'*}"
  echo "- $module"
done

for row in "${module_rows[@]}"; do
  module="${row%%$'\t'*}"
  module_path="${row#*$'\t'}"

  printf '\n==> Building %s (%s)\n' "$module" "$module_path"
  "$repo_root/gradlew" --no-configuration-cache -p "$module_path" build

  jar_path=$(python3 - <<'PY' "$module_path"
from pathlib import Path
import sys

module_path = Path(sys.argv[1])
libs = module_path / "build" / "libs"
jars = sorted(
    p for p in libs.glob("*.jar")
    if "-sources" not in p.name and "-dev" not in p.name and "-javadoc" not in p.name
)

if not jars:
    print(f"No publishable jar found in {libs}", file=sys.stderr)
    sys.exit(1)

print(jars[-1].as_posix())
PY
)

  target="$release_dir/$(basename "$jar_path")"
  cp "$jar_path" "$target"
  echo "Copied: $target"
done

printf '\nDone. Release jars are in: %s\n' "$release_dir"
