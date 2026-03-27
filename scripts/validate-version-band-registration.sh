#!/usr/bin/env bash
set -euo pipefail

repo_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)

python3 - <<'PY' "$repo_root"
from pathlib import Path
import re
import sys

root = Path(sys.argv[1])

module_dirs = sorted(
    d.name
    for d in root.iterdir()
    if d.is_dir() and re.fullmatch(r"mc\d+", d.name) and (d / "build.gradle").exists()
)
module_set = set(module_dirs)

settings_text = (root / "settings.gradle").read_text(encoding="utf-8")
settings_modules = set(re.findall(r"include\('([^']+)'\)", settings_text))

workflow_text = (root / ".github" / "workflows" / "build.yml").read_text(encoding="utf-8")

# Supports both:
# - module: [mc121, mc261]
# - include:\n    - module: mc121\n    - module: mc261
inline_match = re.search(r"module:\s*\[([^\]]*)\]", workflow_text)
if inline_match:
    matrix_modules = {
        token.strip()
        for token in inline_match.group(1).split(",")
        if token.strip()
    }
else:
    include_matches = re.findall(r"-\s*module:\s*([A-Za-z0-9_-]+)", workflow_text)
    if not include_matches:
        print("ERROR: Could not find module matrix in .github/workflows/build.yml")
        sys.exit(1)
    matrix_modules = set(include_matches)

missing_in_settings = sorted(module_set - settings_modules)
missing_in_matrix = sorted(module_set - matrix_modules)
extra_in_matrix = sorted(matrix_modules - module_set)

errors = []
if missing_in_settings:
    errors.append(f"Missing from settings.gradle include(...): {', '.join(missing_in_settings)}")
if missing_in_matrix:
    errors.append(f"Missing from workflow matrix module list: {', '.join(missing_in_matrix)}")
if extra_in_matrix:
    errors.append(f"Present in workflow matrix but no module directory: {', '.join(extra_in_matrix)}")

if errors:
    print("Version band registration validation failed:")
    for err in errors:
        print(f"- {err}")
    sys.exit(1)

print("Version band registration validation passed for modules:", ", ".join(module_dirs) if module_dirs else "<none>")
PY
