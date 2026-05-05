#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import os
import re
import shutil
import subprocess
import sys
from dataclasses import dataclass
from pathlib import Path
from urllib.parse import quote
from urllib.request import Request, urlopen


SEMVER_RE = re.compile(r"^v?(\d+)\.(\d+)\.(\d+)$")
GITHUB_API_TIMEOUT_SECONDS = 5


@dataclass(frozen=True)
class Version:
    major: int
    minor: int
    patch: int

    @classmethod
    def parse(cls, raw: str) -> "Version":
        match = SEMVER_RE.fullmatch(raw.strip())
        if not match:
            raise ValueError(f"Invalid version: {raw}")

        return cls(
            major=int(match.group(1)),
            minor=int(match.group(2)),
            patch=int(match.group(3)),
        )

    def __str__(self) -> str:
        return f"{self.major}.{self.minor}.{self.patch}"


@dataclass(frozen=True)
class Baseline:
    version: Version
    tag_name: str | None


def run(command: list[str], cwd: Path) -> str | None:
    executable = shutil.which(command[0])
    if executable:
        command = [executable, *command[1:]]

    try:
        result = subprocess.run(
            command,
            cwd=cwd,
            check=False,
            capture_output=True,
            text=True,
        )
    except OSError:
        return None

    if result.returncode != 0:
        return None

    output = result.stdout.strip()
    return output or None


def read_gradle_properties(repo_root: Path) -> dict[str, str]:
    props: dict[str, str] = {}

    for raw_line in (repo_root / "gradle.properties").read_text(encoding="utf-8").splitlines():
        line = raw_line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue

        key, value = line.split("=", 1)
        props[key.strip()] = value.strip()

    return props


def latest_tag_baseline(repo_root: Path) -> Baseline | None:
    output = run(["git", "tag", "--list", "--sort=-version:refname"], cwd=repo_root)
    if not output:
        return None

    for line in output.splitlines():
        candidate = line.strip()
        if not candidate:
            continue

        try:
            version = Version.parse(candidate)
        except ValueError:
            continue

        return Baseline(version=version, tag_name=candidate)

    return None


def fallback_baseline(repo_root: Path) -> Baseline:
    props = read_gradle_properties(repo_root)
    return Baseline(version=Version.parse(props["mod_version"]), tag_name=None)


def resolve_baseline(repo_root: Path) -> Baseline:
    return latest_tag_baseline(repo_root) or fallback_baseline(repo_root)


def repo_slug(repo_root: Path) -> str | None:
    remote = run(["git", "remote", "get-url", "origin"], cwd=repo_root)
    if not remote:
        return None

    ssh_match = re.search(r"github\.com:(.+?)(?:\.git)?$", remote)
    https_match = re.search(r"github\.com/(.+?)(?:\.git)?$", remote)
    match = ssh_match or https_match
    return match.group(1) if match else None


def tag_commit_date(repo_root: Path, tag_name: str) -> str | None:
    return run(["git", "log", "-1", "--format=%cI", tag_name], cwd=repo_root)


def merged_pr_count_since(repo_root: Path, tag_name: str | None) -> int | None:
    slug = repo_slug(repo_root)
    if not slug:
        return None

    query_parts = [f"repo:{slug}", "is:pr", "is:merged", "base:main"]
    if tag_name:
        merged_since = tag_commit_date(repo_root, tag_name)
        if not merged_since:
            return None
        query_parts.append(f"merged:>={merged_since}")

    query = " ".join(query_parts)
    token = os.getenv("GH_TOKEN") or os.getenv("GITHUB_TOKEN")
    request = Request(
        f"https://api.github.com/search/issues?q={quote(query)}&per_page=1",
        headers={
            "Accept": "application/vnd.github+json",
            "User-Agent": "darude-versioning",
            **({"Authorization": f"Bearer {token}"} if token else {}),
        },
    )

    try:
        with urlopen(request, timeout=GITHUB_API_TIMEOUT_SECONDS) as response:
            payload = json.load(response)
    except Exception:
        return None

    try:
        return int(payload["total_count"])
    except (KeyError, TypeError, ValueError):
        return None


def base_branch_ref(repo_root: Path) -> str:
    env_ref = os.getenv("VERSION_BASE_REF")
    if env_ref:
        return env_ref

    if run(["git", "rev-parse", "--verify", "main"], cwd=repo_root) is not None:
        return "main"

    return "origin/main"


def commits_ahead(repo_root: Path) -> int | None:
    base_ref = base_branch_ref(repo_root)
    output = run(["git", "rev-list", "--count", f"{base_ref}..HEAD"], cwd=repo_root)
    if output is None:
        return None

    try:
        return int(output)
    except ValueError:
        return None


def computed_version(repo_root: Path) -> Version | None:
    baseline = resolve_baseline(repo_root)
    merged_pr_count = merged_pr_count_since(repo_root, baseline.tag_name)
    ahead_count = commits_ahead(repo_root)
    if merged_pr_count is None or ahead_count is None:
        return None

    minor = baseline.version.minor + merged_pr_count
    if ahead_count > 0:
        minor += 1

    return Version(
        major=baseline.version.major,
        minor=minor,
        patch=ahead_count,
    )


def resolved_version(repo_root: Path) -> str:
    override = os.getenv("MOD_VERSION_OVERRIDE") or os.getenv("ORG_GRADLE_PROJECT_modVersionOverride")
    if override:
        return override.removeprefix("v")

    version = computed_version(repo_root)
    if version is not None:
        return str(version)

    return str(fallback_baseline(repo_root).version)


def sync_gradle_properties(repo_root: Path) -> int:
    version = resolved_version(repo_root)
    gradle_properties = repo_root / "gradle.properties"
    lines = gradle_properties.read_text(encoding="utf-8").splitlines()
    updated_lines: list[str] = []
    changed = False

    for line in lines:
        if line.startswith("mod_version="):
            desired = f"mod_version={version}"
            updated_lines.append(desired)
            changed = changed or line != desired
            continue

        updated_lines.append(line)

    if not changed:
        return 0

    gradle_properties.write_text("\n".join(updated_lines) + "\n", encoding="utf-8")
    return 0


def show_metadata(repo_root: Path) -> int:
    baseline = resolve_baseline(repo_root)
    merged_pr_count = merged_pr_count_since(repo_root, baseline.tag_name)
    ahead_count = commits_ahead(repo_root)
    payload = {
        "baseline_tag": baseline.tag_name,
        "baseline_version": str(baseline.version),
        "merged_pr_count": merged_pr_count,
        "commits_ahead_of_main": ahead_count,
        "resolved_version": resolved_version(repo_root),
    }
    json.dump(payload, sys.stdout)
    sys.stdout.write("\n")
    return 0


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("command", choices=["resolve", "sync-gradle-properties", "metadata"])
    parser.add_argument("--repo-root", default=Path(__file__).resolve().parents[1], type=Path)
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    repo_root = args.repo_root.resolve()

    if args.command == "resolve":
        print(resolved_version(repo_root))
        return 0
    if args.command == "sync-gradle-properties":
        return sync_gradle_properties(repo_root)

    return show_metadata(repo_root)


if __name__ == "__main__":
    raise SystemExit(main())
