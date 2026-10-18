import base64
import copy
import importlib.util
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("release", Path(__file__).with_name("dependabot_release.py"))
release = importlib.util.module_from_spec(spec)
spec.loader.exec_module(release)

SOURCE = 'android {\n    versionCode = 3\n    versionName = "0.9.0"\n}\n'
OLD = '    implementation("com.squareup.okhttp3:okhttp:4.12.0")'
NEW = '    implementation("com.squareup.okhttp3:okhttp:4.12.1")'
PR = {"number": 1, "user": {"login": release.BOT}, "base": {"ref": "main"},
      "head": {"sha": "head", "repo": {"full_name": release.REPO}},
      "merged_at": "2026-01-01", "merge_commit_sha": "merge"}
FILE = {"filename": release.APP, "status": "modified", "deletions": 1, "additions": 1,
        "patch": f"@@ -1 +1 @@\n-{OLD}\n+{NEW}"}


class ReleaseTests(unittest.TestCase):
    def test_minor_update_policy_boundaries(self):
        cases = [
            ('implementation', 'com.squareup.okhttp3:okhttp', '5.5.2', '5.6.0', True),
            ('implementation', 'com.squareup.okhttp3:okhttp', '5.5.2', '5.10.0', True),
            ('implementation', 'com.squareup.okhttp3:okhttp', '4.12.0', '5.5.0', False),
            ('implementation', 'com.squareup.okhttp3:okhttp', '5.5.0', '6.0.0', False),
            ('implementation', 'com.squareup.okhttp3:okhttp', '6.0.0', '6.1.0', False),
            ('implementation', 'com.squareup.okhttp3:okhttp', '5.6.0', '5.5.9', False),
            ('implementation', 'com.squareup.okhttp3:okhttp', '5.5.0', '5.6.0-rc1', False),
            ('implementation', 'org.example:lib', '5.5.0', '5.6.0', False),
            ('testImplementation', 'junit:junit', '4.13.2', '4.14.0', True),
            ('testImplementation', 'org.robolectric:robolectric', '4.14.1', '4.15.0', True),
            ('testImplementation', 'org.bouncycastle:bcprov-jdk18on', '1.86', '1.87', True),
            ('testImplementation', 'org.bouncycastle:bcprov-jdk18on', '1.86', '2.0', False),
            ('testImplementation', 'org.bouncycastle:bcprov-jdk18on', '1.86', '1.85', False),
            ('testImplementation', 'org.bouncycastle:bcprov-jdk18on', '1.86', '1.86', False),
            ('testImplementation', 'org.bouncycastle:bcprov-jdk18on', '1.86', '1.87-beta', False),
            ('testImplementation', 'junit:junit', '4.13.2', '5.0.0', False),
            ('testImplementation', 'org.robolectric:robolectric', '4.14.1', '5.0.0', False),
            ('testImplementation', 'org.example:lib', '4.1.0', '4.2.0', False),
            ('implementation', 'junit:junit', '4.13.2', '4.14.0', False),
        ]
        for scope, artifact, old, new, expected in cases:
            with self.subTest(artifact=artifact, old=old, new=new, scope=scope):
                # Include the constraint-block syntax used by Bouncy Castle.
                suffix = ' {' if artifact.startswith('org.bouncycastle:') else ''
                before = f'    {scope}("{artifact}:{old}"){suffix}'
                after = f'    {scope}("{artifact}:{new}"){suffix}'
                self.assertEqual(release.eligible_update_line(before, after), expected)

    def test_mixed_pr_rejects_major_update_even_with_allowed_minor(self):
        before = '    implementation("com.squareup.okhttp3:okhttp:5.5.0")'
        after = before.replace('5.5.0', '5.6.0')
        minor_file = {**FILE, 'patch': f'@@ -1 +1 @@\n-{before}\n+{after}'}
        with patch.object(release, 'pages', side_effect=[
                [minor_file], [{'author': {'login': release.BOT}}]]):
            self.assertTrue(release.eligible(PR))
        mixed_file = {**minor_file, 'deletions': 2, 'additions': 2,
                      'patch': minor_file['patch'] +
                      '\n-    testImplementation("junit:junit:4.13.2")' +
                      '\n+    testImplementation("junit:junit:5.0.0")'}
        with patch.object(release, 'pages', return_value=[mixed_file]):
            self.assertFalse(release.eligible(PR))

    def test_only_stable_patch_dependency_changes_are_eligible(self):
        self.assertTrue(release.eligible_update_line(OLD, NEW))
        self.assertTrue(release.eligible_update_line(
            '        testImplementation("org.example:lib:1.2.3") {',
            '        testImplementation("org.example:lib:1.2.4") {'))
        for version in ("4.13.0", "5.0.0", "4.12.0", "4.11.9", "4.12.2-rc1", "4.12.+"):
            self.assertFalse(release.eligible_update_line(OLD, NEW.replace("4.12.1", version)))
        self.assertFalse(release.eligible_update_line(OLD, NEW + '; exec { commandLine("evil") }'))
        self.assertFalse(release.eligible_update_line(OLD, NEW.replace("okhttp:", "other:")))
        self.assertFalse(release.eligible_update_line('versionName = "1.0.0"', 'versionName = "1.0.1"'))

    def test_rejects_forks_human_commits_and_incomplete_diffs(self):
        def data(path):
            return [FILE] if path.endswith("files") else [{"author": {"login": release.BOT}}]
        with patch.object(release, "pages", side_effect=data):
            self.assertTrue(release.eligible(PR))
            pr = copy.deepcopy(PR)
            pr["head"]["repo"]["full_name"] = "someone/fork"
            self.assertFalse(release.eligible(pr))
        with patch.object(release, "pages", side_effect=[[FILE], [{"author": {"login": "human"}}]]):
            self.assertFalse(release.eligible(PR))
        for changed in ({**FILE, "deletions": 2}, {**FILE, "patch": ""},
                        {**FILE, "filename": ".github/workflows/android.yml"}):
            with patch.object(release, "pages", return_value=[changed]):
                self.assertFalse(release.eligible(PR))

    def test_bumps_both_android_versions(self):
        source, version = release.bump(SOURCE)
        self.assertEqual(version, "0.9.1")
        self.assertEqual(release.version_info(source), (4, "0.9.1"))
        self.assertEqual(source.replace("4", "3").replace("0.9.1", "0.9.0"), SOURCE)
        with self.assertRaises(RuntimeError):
            release.bump(SOURCE.replace('"0.9.0"', '"0.9.0-beta"'))

    def plan_api(self, pending=False):
        source, version = release.bump(SOURCE) if pending else (SOURCE, "0.9.0")
        def fake(path, method="GET", data=None):
            responses = {
                "releases/latest": {"tag_name": "v0.9.0"},
                "commits/main": {"sha": "base", "commit": {"tree": {"sha": "tree"},
                    "message": release.RELEASE_MESSAGE.format(version) if pending else "Merge patch"}},
                "compare/v0.9.0...base": {"status": "ahead", "total_commits": 1,
                                         "commits": [{"sha": "merge"}]},
                f"contents/{release.APP}?ref=base": {"content": base64.b64encode(source.encode()).decode()},
                "git/trees": {"sha": "new-tree"},
                "git/commits": {"sha": "release-sha"},
            }
            return responses[path]
        return fake

    def test_plan_prepares_commit_without_advancing_main(self):
        with patch.object(release, "api", side_effect=self.plan_api()) as api, \
                patch.object(release, "pages", return_value=[PR]), \
                patch.object(release, "eligible", return_value=True), \
                patch.object(release, "output") as output:
            release.plan()
            output.assert_called_once_with(sha="release-sha", base="base", version="0.9.1")
            self.assertFalse(any("refs/" in call.args[0] for call in api.call_args_list))

    def test_retry_reuses_version_after_main_was_advanced(self):
        with patch.object(release, "api", side_effect=self.plan_api(pending=True)) as api, \
                patch.object(release, "pages", return_value=[PR]), \
                patch.object(release, "eligible", return_value=True), \
                patch.object(release, "output") as output:
            release.plan()
            output.assert_called_once_with(sha="base", base="base", version="0.9.1")
            self.assertFalse(any(call.args[0] == "git/commits" for call in api.call_args_list))

    def test_no_merge_without_successful_build(self):
        pr = {**PR, "merged_at": None, "mergeable_state": "clean"}
        def pages(path):
            return [] if "state=closed" in path else [pr]
        base_api = self.plan_api()
        def api(path, method="GET", data=None):
            if path == "pulls/1":
                return pr
            if path.startswith("commits/head/check-runs"):
                return {"check_runs": [{"name": "Build APK", "conclusion": "failure",
                                         "app": {"slug": "github-actions"}}]}
            return base_api(path, method, data)
        for conclusion in ('failure', None, 'cancelled', 'skipped'):
            def checked_api(path, method="GET", data=None):
                result = api(path, method, data)
                if path.startswith('commits/head/check-runs'):
                    result['check_runs'][0]['conclusion'] = conclusion
                return result
            with self.subTest(conclusion=conclusion), \
                    patch.object(release, "api", side_effect=checked_api) as calls, \
                    patch.object(release, "pages", side_effect=pages), \
                    patch.object(release, "eligible", return_value=True), \
                    patch.object(release, "output") as output:
                release.plan()
                output.assert_not_called()
                self.assertFalse(any(call.args[0].endswith("/merge") for call in calls.call_args_list))

    def test_publish_stops_if_main_advanced(self):
        with tempfile.TemporaryDirectory() as directory:
            old_cwd = os.getcwd()
            try:
                os.chdir(directory)
                Path("artifacts").mkdir()
                Path("artifacts/Sumire-Sync-v0.9.1.apk").write_bytes(b"apk")
                with patch.dict(os.environ, RELEASE_SHA="release", RELEASE_BASE="base",
                                RELEASE_VERSION="0.9.1"), \
                        patch.object(release, "api", return_value={"sha": "new-main"}) as api:
                    with self.assertRaisesRegex(RuntimeError, "main advanced"):
                        release.publish()
                    api.assert_called_once_with("commits/main")
            finally:
                os.chdir(old_cwd)

    def test_publish_records_real_build_before_advancing_main_and_uploads_before_publication(self):
        with tempfile.TemporaryDirectory() as directory:
            old_cwd = os.getcwd()
            try:
                os.chdir(directory)
                Path("artifacts").mkdir()
                Path("artifacts/Sumire-Sync-v0.9.1.apk").write_bytes(b"apk")
                def api(path, method="GET", data=None):
                    return {"sha": "base"} if path == "commits/main" else []
                with patch.dict(os.environ, RELEASE_SHA="release", RELEASE_BASE="base",
                                RELEASE_VERSION="0.9.1", GITHUB_RUN_ID="123"), \
                        patch.object(release, "api", side_effect=api) as calls, \
                        patch.object(release, "pages", return_value=[]), \
                        patch.object(release, "gh") as gh:
                    release.publish()
                    endpoints = [call.args[0] for call in calls.call_args_list]
                    self.assertLess(endpoints.index("statuses/release"), endpoints.index("git/refs/heads/main"))
                    update = next(c for c in calls.call_args_list if c.args[0] == "git/refs/heads/main")
                    self.assertEqual(update.args[2], {"sha": "release", "force": False})
                    commands = [call.args[1] for call in gh.call_args_list]
                    self.assertEqual(commands, ["create", "upload", "edit"])
                    self.assertIn("--draft", gh.call_args_list[0].args)
                    self.assertIn("--draft=false", gh.call_args_list[-1].args)
            finally:
                os.chdir(old_cwd)


if __name__ == "__main__":
    unittest.main()
