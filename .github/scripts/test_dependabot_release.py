import copy
import json
import importlib.util
import os
import subprocess
from contextlib import chdir
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
from zipfile import ZipFile

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


def published(tag):
    return {"tag_name": tag, "draft": False, "prerelease": False,
            "assets": [{"name": f"Sumire-Sync-{tag}.apk", "state": "uploaded", "size": 12}]}


def pr_pages(pr):
    paginate = release.pages
    return lambda path, key=None: paginate(path, key) if key else [pr]


class ReleaseTests(unittest.TestCase):
    @staticmethod
    def allows_update(before, after, artifact="com.squareup.okhttp3:okhttp",
                      scope="implementation", suffix=""):
        return release.eligible_update_line(
            f'    {scope}("{artifact}:{before}"){suffix}',
            f'    {scope}("{artifact}:{after}"){suffix}')

    # These are synthetic inputs for rules, not supported-version lists.
    def test_all_configured_dependencies_allow_stable_upgrades(self):
        dependencies = (
            ("implementation", "com.squareup.okhttp3:okhttp"),
            ("testImplementation", "junit:junit"),
            ("testImplementation", "org.robolectric:robolectric"),
            ("testImplementation", "org.bouncycastle:bcprov-jdk18on"),
        )
        for scope, artifact in dependencies:
            with self.subTest(artifact=artifact):
                self.assertTrue(self.allows_update("9.9.1", "9.9.2", artifact, scope))  # Patch
                self.assertTrue(self.allows_update("9.9.1", "9.10.0", artifact, scope))  # Minor
                self.assertTrue(self.allows_update("9.9.1", "10.0.0", artifact, scope))  # Major

    def test_rejects_unchanged_older_and_nonstable_versions(self):
        for after in ("9.9.1", "9.8.9", "8.99.99", "10.0.0-rc1", "10.0.+"):
            with self.subTest(after=after):
                self.assertFalse(self.allows_update("9.9.1", after))

    def test_omitted_patch_means_zero(self):
        self.assertTrue(self.allows_update("1.2", "1.2.1"))
        self.assertFalse(self.allows_update("1.2", "1.2.0"))
        self.assertFalse(self.allows_update("1.2.1", "1.2"))
        self.assertTrue(self.allows_update(
            "1.2", "1.3", "org.bouncycastle:bcprov-jdk18on", "testImplementation", " {"))

    def test_other_dependencies_allow_all_stable_versions(self):
        for scope, artifact in (("implementation", "org.example:lib"),
                                ("testImplementation", "org.example:lib"),
                                ("implementation", "junit:junit")):
            with self.subTest(scope=scope, artifact=artifact):
                self.assertTrue(self.allows_update("1.2.3", "1.2.4", artifact, scope))
                self.assertTrue(self.allows_update("1.2.3", "1.3.0", artifact, scope))
                self.assertTrue(self.allows_update("1.2.3", "2.2.4", artifact, scope))

    def test_mixed_pr_allows_major_and_minor_but_rejects_artifact_substitutions(self):
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
        with patch.object(release, 'pages', side_effect=[
                [mixed_file], [{'author': {'login': release.BOT}}]]):
            self.assertTrue(release.eligible(PR))
        mixed_file['patch'] = mixed_file['patch'].replace('junit:junit:5', 'org.example:lib:5')
        with patch.object(release, 'pages', return_value=[mixed_file]):
            self.assertFalse(release.eligible(PR))

    def test_robolectric_two_part_release_pr_is_eligible(self):
        before = '    testImplementation("org.robolectric:robolectric:4.14.1")'
        after = '    testImplementation("org.robolectric:robolectric:4.17")'
        file = {**FILE, 'patch': f'@@ -68 +68 @@\n-{before}\n+{after}'}
        with patch.object(release, 'pages', side_effect=[
                [file], [{'author': {'login': release.BOT}}]]):
            self.assertTrue(release.eligible(PR))

    def test_rejects_changes_beyond_dependency_version(self):
        self.assertFalse(release.eligible_update_line(OLD, NEW + '; exec { commandLine("evil") }'))
        self.assertFalse(release.eligible_update_line(OLD, NEW.replace("okhttp:", "other:")))
        self.assertFalse(release.eligible_update_line(OLD, NEW.replace("implementation", "testImplementation")))
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

    def test_wrapper_release_requires_manual_merge(self):
        file = {**FILE, "filename": "gradle/wrapper/gradle-wrapper.properties"}
        for merged in (None, "today"):
            with patch.object(release, "pages", side_effect=[[file], [{"author": {"login": release.BOT}}]]):
                self.assertEqual(release.eligible({**PR, "merged_at": merged}), bool(merged))

    def test_reviewed_wrapper_with_maintainer_correction_is_released(self):
        wrapper = {**FILE, "filename": "gradle/wrapper/gradle-wrapper.properties"}
        commits = [{"author": {"login": release.BOT}}, {"author": {"login": "maintainer"}}]
        for files, merged, expected in [([wrapper], None, False), ([wrapper], "today", True),
                                        ([wrapper, FILE], "today", False), ([FILE], "today", False)]:
            with self.subTest(files=files, merged=merged), patch.object(release, "pages", side_effect=[files, commits]):
                self.assertEqual(release.eligible({**PR, "merged_at": merged}), expected)

    def test_root_and_app_gradle_updates(self):
        for line in ('    id("com.android.application") version "1.0.1" apply false',
                     '    classpath("org.example:test:1.0.1")',
                     '    add(securityConstraints.name, "io.netty:$module:1.0.1.Final") {'):
            self.assertTrue(release.eligible_update_line(line, line.replace("1.0.1", "2.0.0")))
        with patch.object(release, "pages", side_effect=[
                [FILE, {**FILE, "filename": "build.gradle.kts"}], [{"author": {"login": release.BOT}}]]):
            self.assertTrue(release.eligible(PR))

    def test_only_bot_notices_deduplicate_each_reason(self):
        comment = {"user": {"login": "github-actions[bot]"}, "body": "<!-- sumire-sync:ci -->"}
        with patch.object(release, "pages", return_value=[comment]), patch.object(release, "api") as api:
            release.notify(1, "ci", "failed")
            api.assert_not_called()
            release.notify(1, "wrapper", "review")
            api.assert_called_once()

    def test_bumps_both_android_versions(self):
        source = release.set_android_version(SOURCE, 4, "0.9.1")
        self.assertEqual(release.version_info(source), (4, "0.9.1"))
        self.assertEqual(source.replace("4", "3").replace("0.9.1", "0.9.0"), SOURCE)
        with self.assertRaises(RuntimeError):
            release.version_info(SOURCE.replace('"0.9.0"', '"0.9.0-beta"'))

    def test_ci_notices_match_current_open_pr(self):
        pr = {**PR, "state": "open", "merged_at": None}
        run = {"id": 123, "run_attempt": 1, "event": "pull_request", "head_repository": {"full_name": release.REPO},
               "head_sha": "head", "html_url": "https://github.com/example/run"}
        with tempfile.TemporaryDirectory() as directory:
            event = Path(directory) / "event.json"
            for result, kind, head, reason in [
                    ("success", "wrapper", "head", "wrapper"), ("failure", "dependency", "head", "ci"),
                    ("timed_out", "wrapper", "head", "ci"), ("success", "dependency", "head", None),
                    ("failure", "wrapper", "stale", None)]:
                event.write_text(json.dumps({"workflow_run": {**run, "conclusion": result, "head_sha": head}}))
                with patch.dict(os.environ, GITHUB_EVENT_PATH=str(event)), \
                        patch.object(release, "pages", return_value=[pr]), \
                        patch.object(release, "latest_android_run", return_value={**run, "status": "completed", "conclusion": result}), \
                        patch.object(release, "android_checks_passed", return_value=True), \
                        patch.object(release, "update_kind", return_value=kind), patch.object(release, "notify") as notice:
                    release.notice()
                    if reason:
                        self.assertEqual(notice.call_args.args[:2], (1, reason))
                    else:
                        notice.assert_not_called()

    def test_push_notices_only_for_current_failed_pr_branch(self):
        branch = "dependabot/gradle/example"
        pr = {**PR, "state": "open", "head": {**PR["head"], "ref": branch}}
        run = {"id": 123, "run_attempt": 1, "event": "push", "head_repository": {"full_name": release.REPO},
               "html_url": "https://github.com/example/run"}
        with tempfile.TemporaryDirectory() as directory:
            event = Path(directory) / "event.json"
            for result, head, ref, expected in [
                    ("failure", "head", branch, True), ("timed_out", "head", branch, True),
                    ("failure", "stale", branch, False),
                    ("failure", "head", "another-branch", False)]:
                event.write_text(json.dumps({"workflow_run": {
                    **run, "conclusion": result, "head_sha": head, "head_branch": ref}}))
                with self.subTest(result=result, head=head, ref=ref), \
                        patch.dict(os.environ, GITHUB_EVENT_PATH=str(event)), \
                        patch.object(release, "latest_android_run", return_value={**run, "status": "completed", "conclusion": result}), \
                        patch.object(release, "pages", return_value=[pr]), patch.object(release, "notify") as notice:
                    release.notice()
                    if expected:
                        notice.assert_called_once()
                        self.assertEqual(notice.call_args.args[:2], (1, "ci"))
                    else:
                        notice.assert_not_called()

    def test_stale_failure_does_not_consume_notification(self):
        branch = "dependabot/gradle/example"
        pr = {**PR, "state": "open", "head": {**PR["head"], "ref": branch}}
        run = {"id": 7, "run_attempt": 1, "head_repository": {"full_name": release.REPO},
               "head_sha": "head", "head_branch": branch, "conclusion": "failure",
               "status": "completed", "html_url": "https://github.com/example/run"}
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "event.json"
            for event in ("push", "pull_request"):
                path.write_text(json.dumps({"workflow_run": {**run, "event": event}}))
                for changes in ({"id": 8}, {"run_attempt": 2}, {"conclusion": "success"},
                                {"status": "in_progress", "conclusion": None}):
                    with self.subTest(event=event, changes=changes), patch.dict(os.environ, GITHUB_EVENT_PATH=str(path)), \
                            patch.object(release, "pages", return_value=[pr]), \
                            patch.object(release, "latest_android_run", return_value={**run, **changes}), \
                            patch.object(release, "notify") as notify:
                        release.notice()
                        notify.assert_not_called()

    def test_wrapper_notice_waits_for_both_workflows_in_either_order(self):
        from urllib.parse import parse_qs, urlsplit

        branch = "dependabot/gradle/wrapper"
        pr = {**PR, "state": "open", "head": {**PR["head"], "ref": branch}}
        repository = {"full_name": release.REPO}
        jobs = [{"name": name, "conclusion": "success"} for name in release.REQUIRED_CHECKS]
        with tempfile.TemporaryDirectory() as directory:
            event_file = Path(directory) / "event.json"
            for event, other in [("push", "pull_request"), ("pull_request", "push")]:
                for state in ("missing", "in_progress", "failure", "skipped_job", "success"):
                    def api(path):
                        if "/jobs?" in path:
                            return {"jobs": jobs[:-1] if state == "skipped_job" and f"/{other}/" in path else jobs}
                        query = parse_qs(urlsplit(path).query)
                        self.assertEqual(query["branch"], [branch])
                        self.assertEqual(query["head_sha"], ["head"])
                        kind = query["event"][0]
                        status = state if kind == other else "success"
                        runs = [] if status == "missing" else [{
                            "id": kind, "head_repository": repository,
                            "status": "in_progress" if status == "in_progress" else "completed",
                            "conclusion": "failure" if status == "failure" else "success"}]
                        return {"workflow_runs": runs}

                    event_file.write_text(json.dumps({"workflow_run": {
                        "event": event, "head_branch": branch, "head_sha": "head", "conclusion": "success",
                        "head_repository": repository, "html_url": "https://github.com/example/run"}}))
                    with self.subTest(event=event, other_state=state), \
                            patch.dict(os.environ, GITHUB_EVENT_PATH=str(event_file)), \
                            patch.object(release, "pages", side_effect=pr_pages(pr)), \
                            patch.object(release, "update_kind", return_value="wrapper"), \
                            patch.object(release, "api", side_effect=api), patch.object(release, "notify") as notify:
                        release.notice()
                        if state == "success":
                            notify.assert_called_once()
                            self.assertEqual(notify.call_args.args[:2], (1, "wrapper"))
                        else:
                            notify.assert_not_called()

    def test_merge_waits_for_both_runs_in_either_completion_order(self):
        pr = {**PR, "merged_at": None, "mergeable_state": "clean"}
        jobs = [{"name": name, "conclusion": "success"} for name in release.REQUIRED_CHECKS]
        for first, last in [("push", "pull_request"), ("pull_request", "push")]:
            with self.subTest(first=first):
                completed = {first}

                def latest(head, event):
                    self.assertEqual(head, pr["head"])
                    return {"id": event, "status": "completed" if event in completed else "in_progress",
                            "conclusion": "success" if event in completed else None}

                def api(path, method="GET", data=None):
                    if path == "pulls/1":
                        return pr
                    if path.endswith("/jobs?filter=all&per_page=100&page=1"):
                        return {"jobs": jobs}
                    self.assertEqual((path, method, data),
                                     ("pulls/1/merge", "PUT", {"sha": "head", "merge_method": "squash"}))
                    return {"merged": True}

                with patch.object(release, "api", side_effect=api) as calls, \
                        patch.object(release, "latest_android_run", side_effect=latest), \
                        patch.object(release, "pages", side_effect=pr_pages(pr)), patch.object(release, "eligible", return_value=True):
                    release.merge_update()
                    self.assertFalse(any(c.args[0].endswith("/merge") for c in calls.call_args_list))
                    completed.add(last)
                    release.merge_update()
                    self.assertEqual(sum(c.args[0].endswith("/merge") for c in calls.call_args_list), 1)

    def test_missing_or_unsuccessful_required_job_blocks_merge(self):
        pr = {**PR, "merged_at": None, "mergeable_state": "clean"}
        for conclusion in ("failure", "cancelled", "skipped", None, "missing"):
            jobs = [{"name": name, "conclusion": "success"} for name in sorted(release.REQUIRED_CHECKS)]
            if conclusion == "missing":
                jobs.pop()
            else:
                jobs[0]["conclusion"] = conclusion
            with self.subTest(conclusion=conclusion), patch.object(release, "eligible", return_value=True), \
                    patch.object(release, "pages", side_effect=pr_pages(pr)), \
                    patch.object(release, "latest_android_run", return_value={
                        "id": 1, "status": "completed", "conclusion": "success"}), \
                    patch.object(release, "api", side_effect=[pr, {"jobs": jobs}]) as api:
                release.merge_update()
                self.assertEqual(api.call_count, 2)
                self.assertFalse(any(call.args[0].endswith("/merge") for call in api.call_args_list))

    def test_partial_rerun_uses_latest_attempt_for_each_job(self):
        audit = "Resolve all dependency configurations"
        original = [{"name": name, "run_attempt": 1, "conclusion": "success"}
                    for name in release.REQUIRED_CHECKS]
        for old, new in [("failure", "success"), ("success", "skipped")]:
            next(job for job in original if job["name"] == audit)["conclusion"] = old
            jobs = [{"name": audit, "run_attempt": 2, "conclusion": new}, *original]
            with patch.object(release, "pages", return_value=jobs), patch.object(
                    release, "latest_android_run", return_value={"id": 1, "status": "completed", "conclusion": "success"}):
                self.assertEqual(release.android_checks_passed(PR), new == "success")

    def test_unpublished_commits_excludes_all_downloadable_tag_histories(self):
        with tempfile.TemporaryDirectory() as directory, chdir(directory):
            def git(*args, input=None):
                return subprocess.check_output(["git", "-c", "user.name=Test", "-c", "user.email=test@example.org",
                                                "-c", "commit.gpgsign=false", *args], input=input, text=True).strip()

            git("init", "--quiet", "-b", "main")
            tree = git("mktree", input="")
            base = git("commit-tree", tree, "-m", "first release")
            tags = [f"v1.0.{i}" for i in range(100)]
            git("update-ref", "--stdin", input="".join(f"create refs/tags/{tag} {base}\n" for tag in tags))
            commits = [base]
            for number in range(5):
                commits.append(git("commit-tree", tree, "-p", commits[-1], "-m", f"merge PR {number}"))
            git("update-ref", "refs/heads/main", commits[-1])
            prs = [{**PR, "merge_commit_sha": sha} for sha in commits[1:]]
            with patch.object(release, "pages", return_value=prs), patch.object(release, "eligible", return_value=True):
                self.assertEqual(release.pending_updates(release.unpublished_commits(tags)), prs)

                # An older-version side release already includes the first two merges.
                side = git("commit-tree", tree, "-p", commits[2], "-m", "release metadata")
                git("tag", "v0.9.0", side)
                tags.append("v0.9.0")
                self.assertEqual(release.pending_updates(release.unpublished_commits(tags)), prs[2:])

            git("update-ref", "refs/heads/main", commits[2])
            self.assertEqual(release.unpublished_commits(tags), set())
            self.assertEqual(release.unpublished_commits([]), set(commits[:3]))

    def test_signed_apk_requires_candidate_versions_and_release_revision(self):
        self.enterContext(chdir(self.enterContext(tempfile.TemporaryDirectory())))
        Path("artifacts").mkdir()
        commit = "a" * 40
        with ZipFile("artifacts/Sumire-Sync-v0.9.1.apk", "w") as archive:
            archive.writestr("META-INF/version-control-info.textproto", f'revision: "{commit}"')
        with patch.dict(os.environ, RELEASE_CODE="4", RELEASE_VERSION="0.9.1", RELEASE_COMMIT=commit):
            for actual, mismatch in [(["4", "0.9.1"], None), (["5"], "version-code"), (["4", "0.9.0"], "version-name")]:
                with self.subTest(actual=actual), patch.object(release.subprocess, "check_output", side_effect=actual):
                    if mismatch:
                        with self.assertRaisesRegex(RuntimeError, mismatch):
                            release.verify_signed_apk_version()
                    else:
                        release.verify_signed_apk_version()
            with patch.dict(os.environ, RELEASE_COMMIT="b" * 40), patch.object(
                    release.subprocess, "check_output", side_effect=["4", "0.9.1"]):
                with self.assertRaisesRegex(RuntimeError, "revision"):
                    release.verify_signed_apk_version()


class PublicationTests(unittest.TestCase):
    """Run the planner and publisher against real Git history and an in-memory GitHub API."""

    def setUp(self):
        self.enterContext(chdir(self.enterContext(tempfile.TemporaryDirectory())))
        self.git("init", "-qb", "main")
        self.git("config", "user.name", "Test")
        self.git("config", "user.email", "test@example.org")
        self.git("config", "commit.gpgsign", "false")
        self.gradle = Path(release.APP)
        self.gradle.parent.mkdir()
        self.gradle.write_text(SOURCE)
        self.git("add", release.APP)
        self.git("commit", "-qm", "initial release")
        self.git("tag", "v0.9.0")
        self.prs = []
        self.releases = [published("v0.9.0")]
        self.outputs = {}
        self.enterContext(patch.dict(os.environ, GITHUB_RUN_ATTEMPT="1", GITHUB_RUN_NUMBER="42"))
        self.enterContext(patch.object(release, "output", side_effect=lambda **kw: self.outputs.update(kw)))
        self.enterContext(patch.object(release, "pages", side_effect=self.pages))
        self.enterContext(patch.object(release, "eligible", return_value=True))

    @staticmethod
    def git(*args, data=None):
        return subprocess.check_output(["git", *args], text=True, input=data).strip()

    def merge(self, number=None):
        self.git("commit", "--allow-empty", "-qm", f"merge {number}")
        head = self.git("rev-parse", "HEAD")
        os.environ["GITHUB_SHA"] = head
        if number:
            self.prs.append({**PR, "number": number, "merge_commit_sha": head, "merged_at": "2020-01-01T00:00:00Z"})
        return head

    def pages(self, path, key=None):
        if path == "releases":
            return copy.deepcopy(self.releases)
        if path.startswith("pulls?"):
            return self.prs
        raise AssertionError(path)

    def reserve(self):
        release.plan()
        tag = "v" + self.outputs["version"]
        self.git("tag", tag, self.outputs["commit"])
        return tag

    def test_fresh_run_collects_failed_and_new_changes_from_current_main(self):
        first = self.merge(1)
        self.assertEqual(self.reserve(), "v0.9.1")
        self.assertEqual(self.git("rev-parse", "main"), first)
        self.git("checkout", "-q", "main")
        newer = self.merge(2)
        with patch.dict(os.environ, GITHUB_SHA=first, GITHUB_RUN_NUMBER="43"):
            self.assertEqual(self.reserve(), "v0.9.2")
        self.assertEqual(self.outputs["prs"], "1 2")
        self.assertEqual(self.git("rev-parse", "HEAD^"), newer)
        self.assertEqual(release.version_info(release.source_file("HEAD")), (51, "0.9.2"))
        self.releases.append(published("v0.9.2"))
        self.git("checkout", "-q", "main")
        self.outputs.clear()
        release.plan()
        self.assertFalse(self.outputs)

    def test_no_pr_required_and_live_release_with_unfetched_tag(self):
        self.merge()
        self.releases.append(published("v2.0.0"))
        self.assertEqual(self.reserve(), "v2.0.1")
        self.assertEqual(self.outputs["prs"], "")

    def test_old_attempt_or_out_of_order_run_cannot_publish(self):
        self.merge(1)
        self.reserve()
        self.git("checkout", "-q", "main")
        self.merge(2)
        self.outputs.clear()
        with patch.dict(os.environ, GITHUB_RUN_ATTEMPT="2"):
            release.plan()
            with self.assertRaisesRegex(RuntimeError, "fresh"):
                release.publish()
        with patch.dict(os.environ, GITHUB_RUN_NUMBER="41"):
            release.plan()
        self.assertFalse(self.outputs)

    def test_withdrawn_release_is_rebuilt_under_new_number(self):
        self.merge(1)
        issued = self.reserve()
        self.releases.append(published(issued))
        self.releases[-1]["assets"].clear()
        self.git("checkout", "-q", "main")
        with patch.dict(os.environ, GITHUB_RUN_NUMBER="43"):
            self.assertEqual(self.reserve(), "v0.9.2")
        self.assertEqual(self.outputs["code"], 51)

    def test_publish_verifies_uploaded_bytes_and_preserves_completed_release(self):
        self.merge(1)
        tag = self.reserve()
        commit = self.outputs["commit"]
        apk = Path("artifacts") / f"Sumire-Sync-{tag}.apk"
        apk.parent.mkdir()
        apk.write_bytes(b"signed apk")
        apk.with_suffix(".apk.sha256").write_text(f"{release.file_digest(apk)}  {apk.name}\n")
        self.enterContext(patch.dict(os.environ, RELEASE_VERSION=tag[1:], RELEASE_COMMIT=commit, RELEASE_PRS="1"))
        for outcome in ("success", "bad digest", "lost response"):
            draft = {"id": 2, "draft": True, "assets": []}
            def api(path, method="GET", data=None):
                if path.startswith("commits/"):
                    return {"sha": commit}
                if method == "PATCH":
                    draft["draft"] = False
                    if outcome == "lost response":
                        raise ConnectionError()
                return draft

            def upload(release_id, path):
                digest = release.file_digest(path) if outcome != "bad digest" else "0" * 64
                draft["assets"].append({"name": path.name, "state": "uploaded", "digest": "sha256:" + digest})

            with self.subTest(outcome=outcome), patch.object(release, "api", side_effect=api) as calls, patch.object(
                    release, "upload_asset", side_effect=upload):
                if outcome == "success":
                    release.publish()
                else:
                    with self.assertRaises((RuntimeError, ConnectionError)):
                        release.publish()
                deleted = any(len(c.args) > 1 and c.args[1] == "DELETE" for c in calls.call_args_list)
                self.assertEqual(deleted, outcome == "bad digest")
                self.assertEqual(draft["draft"], outcome == "bad digest")


if __name__ == "__main__":
    unittest.main()
