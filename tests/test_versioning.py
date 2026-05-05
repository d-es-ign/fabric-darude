from pathlib import Path
from unittest import TestCase
from unittest.mock import MagicMock
from unittest.mock import patch

from scripts import versioning


class VersioningTests(TestCase):
    def test_version_parse_accepts_optional_v_prefix(self) -> None:
        self.assertEqual(versioning.Version.parse("v1.2.3"), versioning.Version(1, 2, 3))
        self.assertEqual(versioning.Version.parse("1.2.3"), versioning.Version(1, 2, 3))

    def test_computed_version_uses_next_minor_for_branch_work(self) -> None:
        repo_root = Path("/repo")

        with (
            patch.object(versioning, "resolve_baseline", return_value=versioning.Baseline(versioning.Version(0, 2, 0), "v0.2.0")),
            patch.object(versioning, "merged_pr_count_since", return_value=3),
            patch.object(versioning, "commits_ahead", return_value=4),
        ):
            resolved = versioning.computed_version(repo_root)

        self.assertEqual(resolved, versioning.Version(0, 6, 4))

    def test_computed_version_keeps_main_on_current_minor(self) -> None:
        repo_root = Path("/repo")

        with (
            patch.object(versioning, "resolve_baseline", return_value=versioning.Baseline(versioning.Version(1, 5, 0), "v1.5.0")),
            patch.object(versioning, "merged_pr_count_since", return_value=2),
            patch.object(versioning, "commits_ahead", return_value=0),
        ):
            resolved = versioning.computed_version(repo_root)

        self.assertEqual(resolved, versioning.Version(1, 7, 0))

    def test_resolved_version_prefers_override(self) -> None:
        repo_root = Path("/repo")

        with patch.dict("os.environ", {"MOD_VERSION_OVERRIDE": "v2.4.6"}, clear=True):
            self.assertEqual(versioning.resolved_version(repo_root), "2.4.6")

    def test_resolved_version_falls_back_when_dynamic_lookup_unavailable(self) -> None:
        repo_root = Path("/repo")

        with (
            patch.object(versioning, "computed_version", return_value=None),
            patch.object(versioning, "fallback_baseline", return_value=versioning.Baseline(versioning.Version(0, 1, 0), None)),
        ):
            self.assertEqual(versioning.resolved_version(repo_root), "0.1.0")

    def test_merged_pr_count_since_uses_timeout(self) -> None:
        repo_root = Path("/repo")
        response = MagicMock()
        response.__enter__.return_value = response
        response.__exit__.return_value = None

        with (
            patch.object(versioning, "repo_slug", return_value="d-es-ign/fabric-darude"),
            patch.object(versioning.json, "load", return_value={"total_count": 7}),
            patch.object(versioning, "urlopen", return_value=response) as mock_urlopen,
        ):
            self.assertEqual(versioning.merged_pr_count_since(repo_root, None), 7)

        self.assertEqual(mock_urlopen.call_args.kwargs["timeout"], versioning.GITHUB_API_TIMEOUT_SECONDS)
