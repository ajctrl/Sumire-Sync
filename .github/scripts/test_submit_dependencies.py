import copy
import importlib.util
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("dependencies", Path(__file__).with_name("submit_dependencies.py"))
dependencies = importlib.util.module_from_spec(spec)
spec.loader.exec_module(dependencies)


class DependencySubmissionTests(unittest.TestCase):
    def snapshot(self):
        return {"version": 0, "sha": "checked-out-release", "ref": "refs/heads/main",
                "job": {"id": "123", "correlator": dependencies.CORRELATOR},
                "detector": {"name": "gradle", "version": "test", "url": "https://example.test"},
                "scanned": "2026-10-18T00:00:00Z",
                "manifests": {"app": {"name": "app", "resolved": {"new-version": {}}}}}

    def test_new_snapshot_replaces_both_old_streams_without_leaving_old_dependencies(self):
        snapshot = self.snapshot()
        original = copy.deepcopy(snapshot)
        streams = {key: {"old-version"} for key in dependencies.LEGACY_CORRELATORS}
        for submission in dependencies.submissions(snapshot, "checked-out-release"):
            streams[submission["job"]["correlator"]] = {
                dependency for manifest in submission["manifests"].values()
                for dependency in manifest.get("resolved", {})}
            self.assertEqual(snapshot["detector"], submission["detector"])
        self.assertEqual({"new-version"}, set().union(*streams.values()))
        self.assertEqual(original, snapshot)

    def test_stale_event_sha_and_wrong_identity_are_rejected(self):
        for field, value in (("sha", "old-workflow-event"), ("ref", "refs/heads/other"),
                             ("job", {"correlator": "workflow-specific"}), ("manifests", {})):
            snapshot = self.snapshot()
            snapshot[field] = value
            with self.subTest(field=field), self.assertRaises(ValueError):
                dependencies.submissions(snapshot, "checked-out-release")

    def test_failure_to_submit_current_snapshot_never_retires_old_ones(self):
        with tempfile.TemporaryDirectory() as directory:
            report = Path(directory) / "snapshot.json"
            import json
            report.write_text(json.dumps(self.snapshot()))
            with patch.object(dependencies.Path, "rglob", return_value=iter([report])), \
                    patch.object(dependencies.subprocess, "check_output", return_value="checked-out-release\n"), \
                    patch.dict(dependencies.os.environ, {"GITHUB_REPOSITORY": "owner/repo"}), \
                    patch.object(dependencies, "submit", side_effect=subprocess.CalledProcessError(1, "gh")) as submit:
                with self.assertRaises(subprocess.CalledProcessError):
                    dependencies.main()
                self.assertEqual(1, submit.call_count)
                self.assertEqual(dependencies.CORRELATOR, submit.call_args.args[0]["job"]["correlator"])

    def test_old_checkout_cannot_replace_a_newer_main_snapshot(self):
        with tempfile.TemporaryDirectory() as directory:
            report = Path(directory) / "snapshot.json"
            report.write_text(json.dumps(self.snapshot()))
            for current_main in ("checked-out-release", "newer-main"):
                with self.subTest(main=current_main), \
                        patch.object(dependencies.Path, "rglob", return_value=iter([report])), \
                        patch.object(dependencies.subprocess, "check_output",
                                     side_effect=["checked-out-release\n", current_main + "\n"]) as command, \
                        patch.dict(dependencies.os.environ, {"GITHUB_REPOSITORY": "owner/repo"}), \
                        patch.object(dependencies, "submit") as submit:
                    dependencies.main()
                    self.assertEqual(3 if current_main == "checked-out-release" else 0, submit.call_count)
                    self.assertEqual(["gh", "api", "repos/owner/repo/git/ref/heads/main", "--jq", ".object.sha"],
                                     command.call_args.args[0])

    def test_main_lookup_failure_does_not_submit_or_retire_snapshots(self):
        with tempfile.TemporaryDirectory() as directory:
            report = Path(directory) / "snapshot.json"
            report.write_text(json.dumps(self.snapshot()))
            with patch.object(dependencies.Path, "rglob", return_value=iter([report])), \
                    patch.object(dependencies.subprocess, "check_output",
                                 side_effect=["checked-out-release\n", subprocess.CalledProcessError(1, "gh")]), \
                    patch.dict(dependencies.os.environ, {"GITHUB_REPOSITORY": "owner/repo"}), \
                    patch.object(dependencies, "submit") as submit:
                with self.assertRaises(subprocess.CalledProcessError):
                    dependencies.main()
                submit.assert_not_called()


if __name__ == "__main__":
    unittest.main()
