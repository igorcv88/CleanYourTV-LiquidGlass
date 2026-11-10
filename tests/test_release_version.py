"""Release tags sort numerically and retain Android's monotonic versionCode."""

import subprocess
import sys
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / ".github/scripts"))
from release_version import release_identity  # noqa: E402


class ReleaseVersionTest(unittest.TestCase):
    def test_old_single_digit_to_double_digit_transition(self):
        previous = release_identity("stable", 10)
        current = release_identity("beta", 13)
        self.assertEqual(("3.3.10", 330010, "v3.3.10"), previous)
        self.assertEqual(("3.3.13-beta", 330013, "v3.3.13-beta"), current)
        self.assertGreater(current[1], previous[1])

    def test_next_releases_have_valid_semver(self):
        for run in (13, 14, 99, 100, 1000):
            for channel in ("beta", "stable"):
                with self.subTest(run=run, channel=channel):
                    version, code, tag = release_identity(channel, run)
                    self.assertRegex(version, r"^3\.3\.(0|[1-9][0-9]*)(-beta)?$")
                    self.assertEqual("v" + version, tag)
                    self.assertEqual(330000 + run, code)

    def test_stable_and_beta_use_one_global_run_counter(self):
        self.assertEqual("v3.3.14-beta", release_identity("beta", 14)[2])
        self.assertEqual("v3.3.15", release_identity("stable", 15)[2])

    def test_invalid_inputs_fail_closed(self):
        for channel, run in (("test", 14), ("beta", 0), ("stable", -1), ("beta", True)):
            with self.subTest(channel=channel, run=run):
                with self.assertRaises(ValueError):
                    release_identity(channel, run)

    def test_cli_output_consumed_by_release_workflow(self):
        result = subprocess.run(
            [sys.executable, str(ROOT / ".github/scripts/release_version.py"), "beta", "14"],
            capture_output=True, text=True, check=True,
        )
        self.assertEqual("3.3.14-beta\t330014\tv3.3.14-beta\n", result.stdout)

    def test_no_legacy_prerelease_sorting_or_actions_artifacts(self):
        workflow = (ROOT / ".github/workflows/release.yml").read_text(encoding="utf-8")
        self.assertIn('python3 .github/scripts/release_version.py "$CHANNEL" "$GITHUB_RUN_NUMBER"', workflow)
        self.assertIn('args+=(--latest=false)', workflow)
        self.assertNotIn('args+=(--prerelease', workflow)
        self.assertNotIn('actions/upload-artifact', workflow)


if __name__ == "__main__":
    unittest.main()
