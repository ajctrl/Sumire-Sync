"""Merge eligible, tested Dependabot updates and publish tested release commits.

Build the version-only release commit and publish the verified, signed APK.
"""

import hashlib
import json
import os
from pathlib import Path
from urllib.parse import urlencode
from urllib.request import Request, urlopen
import re
import subprocess
import sys
from zipfile import ZipFile


REPO = os.environ.get("GITHUB_REPOSITORY", "ajctrl/Sumire-Sync")
APP = "app/build.gradle.kts"
BOT = "dependabot[bot]"
REQUIRED_CHECKS = {"Build APK", "Verify APK / Compare APKs", "Resolve all dependency configurations"}
STABLE = re.compile(r"v\d+\.\d+\.\d+")
RELEASE_MESSAGE = "chore: release v{}"


def gh(*args, input=None):
    result = subprocess.run(["gh", *args], input=input, text=True, capture_output=True)
    if result.returncode:
        raise RuntimeError(f"GitHub command failed: {result.stderr.strip()}")
    return result.stdout


def api(path, method="GET", data=None):
    args = ["api", f"repos/{REPO}/{path}", "--method", method]
    if data is not None:
        args += ["--input", "-"]
    result = gh(*args, input=json.dumps(data) if data is not None else None)
    return json.loads(result) if result.strip() else None


def pages(path, key=None):
    separator = "&" if "?" in path else "?"
    for page in range(1, 101):
        items = api(f"{path}{separator}per_page=100&page={page}")
        if key:
            items = items[key]
        yield from items
        if len(items) < 100:
            return
    raise RuntimeError("Pagination limit reached; review manually")


def version_parts(version):
    parts = list(map(int, version.removesuffix(".Final").split(".")))
    while parts and parts[-1] == 0:
        parts.pop()
    return parts


def eligible_update_line(before, after):
    # Only the literal version may change, including in tool classpath constraints.
    patterns = (
        r'\s*(?:implementation|testImplementation|classpath)\("[\w.$-]+:[\w.$-]+:',
        r'\s*add\(securityConstraints\.name, "[\w.$-]+:[\w.$-]+:',
        r'\s*id\("[\w.-]+"\) version "',
    )
    for prefix in patterns:
        pattern = rf'({prefix})(\d+(?:\.\d+)+(?:\.Final)?)("\)(?: \{{)?|"(?: apply false)?)\s*'
        old, new = re.fullmatch(pattern, before), re.fullmatch(pattern, after)
        if old and new and old[1] == new[1] and old[3] == new[3]:
            return version_parts(new[2]) > version_parts(old[2])
    return False


WRAPPER_PROPERTIES = "gradle/wrapper/gradle-wrapper.properties"
WRAPPER_FILES = {"gradlew", "gradlew.bat", "gradle/wrapper/gradle-wrapper.jar", WRAPPER_PROPERTIES}


def wrapper_distribution_update(file):
    if file["filename"] != WRAPPER_PROPERTIES or file["deletions"] != 2 or file["additions"] != 2:
        return False
    patch = file.get("patch", "").split("\n")
    url = r"distributionUrl=https\\?://services\.gradle\.org/distributions/gradle-([0-9]+(?:\.[0-9]+)+)-bin\.zip"
    versions = []
    for prefix in ("-", "+"):
        lines = sorted(line[1:] for line in patch if line.startswith(prefix))
        if len(lines) != 2 or not re.fullmatch(r"distributionSha256Sum=[0-9a-f]{64}", lines[0]):
            return False
        match = re.fullmatch(url, lines[1])
        if not match:
            return False
        versions.append(version_parts(match[1]))
    return versions[1] > versions[0]


def dependency_file(file):
    if file["filename"] not in {APP, "build.gradle.kts"} or file["status"] != "modified":
        return False
    patch = file.get("patch", "")
    removed = [line[1:] for line in patch.splitlines() if line.startswith("-")]
    added = [line[1:] for line in patch.splitlines() if line.startswith("+")]
    return (bool(removed) and len(removed) == len(added) == file["deletions"] == file["additions"]
            and all(eligible_update_line(a, b) for a, b in zip(removed, added)))


def update_kind(pr):
    if (pr["user"]["login"] != BOT or pr["base"]["ref"] != "main" or pr.get("draft")
            or (pr["head"].get("repo") or {}).get("full_name") != REPO):
        return None
    files = list(pages(f"pulls/{pr['number']}/files"))
    wrapper = bool(files) and all(f["filename"] in WRAPPER_FILES and f["status"] == "modified" for f in files)
    if wrapper:
        if len(files) != 1 or not wrapper_distribution_update(files[0]):
            return "wrapper"
    elif not files or not all(dependency_file(f) for f in files):
        return None
    commits = list(pages(f"pulls/{pr['number']}/commits"))
    if commits and all((c.get("author") or {}).get("login") == BOT for c in commits):
        return "dependency"
    if wrapper:
        return "wrapper"
    return None


def eligible(pr):
    kind = update_kind(pr)
    return kind == "dependency" or (kind == "wrapper" and bool(pr.get("merged_at")))


def notify(number, reason, message):
    path = f"issues/{int(number)}/comments"
    marker = f"<!-- sumire-sync:{reason} -->"
    if any(c["user"]["login"] == "github-actions[bot]" and marker in c["body"] for c in pages(path)):
        return
    api(path, "POST", {"body": f"@ajctrl {message}\n\n{marker}"})


def latest_android_run(head, event):
    query = urlencode({"event": event, "branch": head["ref"], "head_sha": head["sha"], "per_page": 1})
    runs = api(f"actions/workflows/android.yml/runs?{query}")["workflow_runs"]
    if runs and (runs[0].get("head_repository") or {}).get("full_name") == REPO:
        return runs[0]
    return None


def android_checks_passed(pr):
    for event in ("push", "pull_request"):
        run = latest_android_run(pr["head"], event)
        if not run or run["status"] != "completed" or run["conclusion"] != "success":
            return False
        jobs = pages(f"actions/runs/{run['id']}/jobs?filter=all", key="jobs")
        latest = {}
        for job in jobs:
            name = job["name"]
            if name not in latest or job["run_attempt"] > latest[name]["run_attempt"]:
                latest[name] = job
        passed = {name for name, job in latest.items() if job["conclusion"] == "success"}
        if not REQUIRED_CHECKS <= passed:
            return False
    return True


def notice():
    run = json.loads(Path(os.environ["GITHUB_EVENT_PATH"]).read_text())["workflow_run"]
    result = run["conclusion"]
    if (run["head_repository"]["full_name"] != REPO or run["event"] not in {"pull_request", "push"}
            or result not in {"success", "failure", "timed_out"}):
        return
    for pr in pages(f"commits/{run['head_sha']}/pulls"):
        if (pr["state"] != "open" or pr["head"]["sha"] != run["head_sha"]
                or (run["event"] == "push" and pr["head"]["ref"] != run["head_branch"])
                or pr["user"]["login"] != BOT or pr["base"]["ref"] != "main"
                or (pr["head"].get("repo") or {}).get("full_name") != REPO):
            continue
        link = f"[実行ログ]({run['html_url']})"
        if result != "success":
            latest = latest_android_run(pr["head"], run["event"])
            if (not latest or latest["id"] != run["id"] or latest["run_attempt"] != run["run_attempt"]
                    or latest["status"] != "completed" or latest["conclusion"] != result):
                continue
            notify(pr["number"], "ci", f"CI が失敗またはタイムアウトしました。{link}")
        elif update_kind(pr) == "wrapper" and android_checks_passed(pr):
            notify(pr["number"], "wrapper", f"Wrapper の CI が成功しました。手動レビューとマージをお願いします。{link}")


def release_notice():
    url = f"https://github.com/{REPO}/actions/runs/{os.environ['GITHUB_RUN_ID']}"
    for number in os.environ["RELEASE_PRS"].split():
        notify(number, "release", f"マージ後の APK リリース処理が失敗しました。未公開の変更は次の定期実行で再度公開します。main に修正をマージした後は Actions の「Dependabot dependency release」を手動実行できます。[実行ログ]({url})")


def version_info(source):
    codes = re.findall(r"^\s*versionCode = (\d+)\s*$", source, re.M)
    names = re.findall(r'^\s*versionName = "(\d+\.\d+\.\d+)"\s*$', source, re.M)
    if len(codes) != 1 or len(names) != 1:
        raise RuntimeError("Expected one literal versionCode and stable versionName")
    return int(codes[0]), names[0]


def set_android_version(source, code, name):
    source = re.sub(r"(?m)^(\s*versionCode = )\d+\s*$", rf"\g<1>{code}", source)
    source = re.sub(r'(?m)^(\s*versionName = ")[^"]+("\s*)$', rf"\g<1>{name}\2", source)
    return source


def output(**values):
    with open(os.environ["GITHUB_OUTPUT"], "a") as stream:
        for key, value in values.items():
            stream.write(f"{key}={value}\n")
    print(json.dumps(values))


def uploaded_assets(release):
    return {asset["name"]: asset["size"] for asset in release["assets"]
            if asset["state"] == "uploaded" and asset["size"] > 0}


def published_tags(releases):
    """Stable releases that users can actually download, newest version first."""
    tags = []
    for release in releases:
        tag = release["tag_name"]
        if release["draft"] or release["prerelease"] or not re.fullmatch(r"v\d+\.\d+\.\d+", tag):
            continue
        if f"Sumire-Sync-{tag}.apk" in uploaded_assets(release):
            tags.append(tag)
    return sorted(tags, key=lambda tag: version_parts(tag[1:]), reverse=True)


def unpublished_commits(revisions, source="HEAD"):
    # Exclude commits already included in downloadable stable releases.
    revisions = [source, *("^" + ref for ref in revisions)]
    result = subprocess.check_output(["git", "rev-list", "--stdin"],
                                     input="\n".join(revisions) + "\n", text=True)
    return set(result.splitlines())


def pending_updates(history):
    return [pr for pr in pages("pulls?state=closed&base=main&sort=updated&direction=desc")
            if pr.get("merged_at") and pr["merge_commit_sha"] in history and eligible(pr)]


def merge_update():
    for summary in pages("pulls?state=open&base=main&sort=created&direction=asc"):
        if summary["user"]["login"] != BOT:
            continue
        pr = api(f"pulls/{summary['number']}")
        if not eligible(pr) or pr.get("mergeable_state") != "clean":
            continue
        if not android_checks_passed(pr):
            continue
        result = api(f"pulls/{pr['number']}/merge", "PUT", {
            "sha": pr["head"]["sha"], "merge_method": "squash"})
        if not result.get("merged"):
            raise RuntimeError(f"PR #{pr['number']} could not be merged")
        print(f"Merged #{pr['number']}; it will be included in the next release batch.")
        return


def git(*args):
    return subprocess.check_output(["git", *args], text=True).strip()


def source_file(ref):
    return subprocess.check_output(["git", "show", f"{ref}:{APP}"], text=True)


# The last release before switching to workflow run numbers used versionCode 8.
VERSION_CODE_OFFSET = 8


def plan():
    if os.environ["GITHUB_RUN_ATTEMPT"] != "1":
        return
    source = git("rev-parse", "HEAD")
    tags = {tag for tag in git("tag", "--list").splitlines() if STABLE.fullmatch(tag)}
    releases = list(pages("releases"))
    # Only use refs from the same fetch as main. A later remote tag cannot
    # introduce an object that is missing from this checkout.
    covered = [f"refs/tags/{tag}" for tag in published_tags(releases) if tag in tags]
    history = unpublished_commits(covered, source)
    if not history:
        print("All changes are already in a downloadable release.")
        return

    code = VERSION_CODE_OFFSET + int(os.environ["GITHUB_RUN_NUMBER"])
    if code > 2100000000:
        raise RuntimeError("Android versionCode limit reached")
    content = source_file(source)
    main_code, name = version_info(content)
    if tags:
        latest = max(tags, key=lambda tag: version_parts(tag[1:]))
        last_code, _ = version_info(source_file("refs/tags/" + latest))
        if code <= last_code:
            print(f"Run code {code} was superseded by {latest} (code {last_code}).")
            return
    if code <= main_code:
        raise RuntimeError("Workflow versionCode must exceed the versionCode on main")
    reserved = tags | {r["tag_name"] for r in releases if STABLE.fullmatch(r["tag_name"])}
    latest_name = max(reserved | {"v" + name}, key=lambda tag: version_parts(tag[1:]))[1:]
    major, minor, patch = map(int, latest_name.split("."))
    version = f"{major}.{minor}.{patch + 1}"
    prs = " ".join(str(pr["number"]) for pr in pending_updates(history))

    git("checkout", "--quiet", "--detach", source)
    Path(APP).write_text(set_android_version(content, code, version))
    git("-c", "user.name=github-actions[bot]", "-c", "user.email=41898282+github-actions[bot]@users.noreply.github.com",
        "commit", "--quiet", "-m", RELEASE_MESSAGE.format(version), "--", APP)
    output(commit=git("rev-parse", "HEAD"), version=version, code=code, prs=prs)


def verify_signed_apk_version():
    version = os.environ["RELEASE_VERSION"]
    apk = Path("artifacts") / f"Sumire-Sync-v{version}.apk"
    for field, expected in (("version-code", os.environ["RELEASE_CODE"]), ("version-name", version)):
        actual = subprocess.check_output(["apkanalyzer", "manifest", field, str(apk)], text=True).strip()
        if actual != expected:
            raise RuntimeError(f"{apk.name} has {field} {actual!r}; expected {expected}")

    with ZipFile(apk) as archive:
        vcs = archive.read("META-INF/version-control-info.textproto").decode()
    revisions = re.findall(r'^\s*revision: "([0-9a-f]{40})"\s*$', vcs, re.M)
    if revisions != [os.environ["RELEASE_COMMIT"]]:
        raise RuntimeError("Signed APK revision differs from the planned release commit")


def file_digest(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def verify_assets(release, expected):
    assets = {a["name"]: a.get("digest") for a in release["assets"] if a["state"] == "uploaded"}
    if any(assets.get(name) != "sha256:" + digest for name, digest in expected.items()):
        raise RuntimeError("Uploaded release assets do not match the signed artifact")


def upload_asset(release_id, path):
    url = f"https://uploads.github.com/repos/{REPO}/releases/{int(release_id)}/assets?"
    headers = {"Authorization": "Bearer " + os.environ["GH_TOKEN"],
               "Content-Type": "application/octet-stream", "Content-Length": str(path.stat().st_size)}
    with path.open("rb") as data:
        request = Request(url + urlencode({"name": path.name}), data=data, headers=headers, method="POST")
        with urlopen(request, timeout=120) as response:
            if response.status != 201:
                raise RuntimeError(f"Failed to upload {path.name}: HTTP {response.status}")


def publish():
    if os.environ["GITHUB_RUN_ATTEMPT"] != "1":
        raise RuntimeError("Start a fresh release run")
    version = os.environ["RELEASE_VERSION"]
    tag = "v" + version
    commit = os.environ["RELEASE_COMMIT"]
    if git("rev-parse", "HEAD") != commit or api(f"commits/tags/{tag}")["sha"] != commit:
        raise RuntimeError("Release tag differs from the verified checkout")

    apk = Path("artifacts") / f"Sumire-Sync-{tag}.apk"
    checksum = apk.with_suffix(".apk.sha256")
    digest = file_digest(apk)
    if not apk.stat().st_size or checksum.read_text().strip() != f"{digest}  {apk.name}":
        raise RuntimeError("Signed APK or checksum is invalid")
    notes = " ".join(f"#{int(n)}" for n in os.environ["RELEASE_PRS"].split()) or f"Source: {commit}"
    draft = api("releases", "POST", {"draft": True, "name": tag, "tag_name": tag,
                "target_commitish": commit, "body": notes})
    path = f"releases/{draft['id']}"
    try:
        upload_asset(draft["id"], apk)
        upload_asset(draft["id"], checksum)
        verify_assets(api(path), {apk.name: digest, checksum.name: file_digest(checksum)})
        api(path, "PATCH", {"draft": False, "make_latest": "true"})
    except Exception:
        # The publish request can succeed even if its response is lost.
        if api(path)["draft"]:
            api(path, "DELETE")
        raise


if __name__ == "__main__":
    {"merge": merge_update, "plan": plan, "publish": publish, "notice": notice,
     "release-notice": release_notice, "verify-apk-version": verify_signed_apk_version}[sys.argv[1]]()
