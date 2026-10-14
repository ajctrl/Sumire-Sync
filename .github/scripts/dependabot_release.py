"""Merge tested, version-only Dependabot patches and publish tested release commits.

Only the planning job uses PR data. Signing jobs build an immutable commit based
on main, never a PR checkout. GitHub's required checks still gate the PR merge.
"""

import base64
import json
import os
from pathlib import Path
import re
import subprocess
import sys


REPO = os.environ.get("GITHUB_REPOSITORY", "ajctrl/Sumire-Sync")
APP = "app/build.gradle.kts"
BOT = "dependabot[bot]"
RELEASE_MESSAGE = "chore: release v{} (Dependabot)"


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


def pages(path):
    separator = "&" if "?" in path else "?"
    for page in range(1, 101):
        items = api(f"{path}{separator}per_page=100&page={page}")
        yield from items
        if len(items) < 100:
            return
    raise RuntimeError("Pagination limit reached; review manually")


def patch_line(before, after):
    # Exact stable SemVer only. No prereleases, ranges, downgrades, plugin code,
    # app version edits, or arbitrary Gradle expressions are eligible.
    pattern = (r'(\s*(?:implementation|testImplementation)\("[^"\s]+:[^"\s]+:)'
               r'(\d+)\.(\d+)\.(\d+)("\)(?:\s*\{)?\s*)')
    old, new = re.fullmatch(pattern, before), re.fullmatch(pattern, after)
    return bool(old and new and old[1] == new[1] and old[5] == new[5]
                and old.groups()[1:3] == new.groups()[1:3] and int(new[4]) > int(old[4]))


def eligible(pr):
    if (pr["user"]["login"] != BOT or pr["base"]["ref"] != "main"
            or (pr["head"].get("repo") or {}).get("full_name") != REPO):
        return False
    files = list(pages(f"pulls/{pr['number']}/files"))
    if len(files) != 1 or files[0]["filename"] != APP or files[0]["status"] != "modified":
        return False
    file = files[0]
    patch = file.get("patch", "")
    removed = [line[1:] for line in patch.splitlines() if line.startswith("-")]
    added = [line[1:] for line in patch.splitlines() if line.startswith("+")]
    if (not removed or len(removed) != len(added)
            or len(removed) != file["deletions"] or len(added) != file["additions"]
            or not all(patch_line(a, b) for a, b in zip(removed, added))):
        return False
    commits = list(pages(f"pulls/{pr['number']}/commits"))
    return bool(commits) and all((c.get("author") or {}).get("login") == BOT for c in commits)


def version_info(source):
    codes = re.findall(r"^\s*versionCode = (\d+)\s*$", source, re.M)
    names = re.findall(r'^\s*versionName = "(\d+\.\d+\.\d+)"\s*$', source, re.M)
    if len(codes) != 1 or len(names) != 1:
        raise RuntimeError("Expected one literal versionCode and stable versionName")
    return int(codes[0]), names[0]


def bump(source):
    code, name = version_info(source)
    major, minor, patch = map(int, name.split("."))
    name = f"{major}.{minor}.{patch + 1}"
    if code >= 2100000000:
        raise RuntimeError("Android versionCode limit reached")
    source = re.sub(r"(?m)^(\s*versionCode = )\d+\s*$", rf"\g<1>{code + 1}", source)
    source = re.sub(r'(?m)^(\s*versionName = ")[^"]+("\s*)$', rf"\g<1>{name}\2", source)
    return source, name


def output(**values):
    with open(os.environ["GITHUB_OUTPUT"], "a") as stream:
        for key, value in values.items():
            stream.write(f"{key}={value}\n")
    print(json.dumps(values))


def plan():
    # Finish an outstanding release before merging another PR.
    latest = api("releases/latest")
    main = api("commits/main")
    comparison = api(f"compare/{latest['tag_name']}...{main['sha']}")
    if comparison["status"] not in ("ahead", "identical"):
        raise RuntimeError("Latest release is not an ancestor of main")
    if comparison["total_commits"] > len(comparison["commits"]):
        raise RuntimeError("Too many unreleased commits; review manually")
    unreleased = {c["sha"] for c in comparison["commits"]}
    pending = []
    for pr in pages("pulls?state=closed&base=main&sort=updated&direction=desc"):
        if pr.get("merged_at") and pr.get("merge_commit_sha") in unreleased and eligible(pr):
            pending.append(pr["number"])

    if not pending:
        for summary in pages("pulls?state=open&base=main&sort=created&direction=asc"):
            if summary["user"]["login"] != BOT:
                continue
            pr = api(f"pulls/{summary['number']}")
            if not eligible(pr) or pr.get("mergeable_state") != "clean":
                continue
            checks = api(f"commits/{pr['head']['sha']}/check-runs?per_page=100")
            builds = [c for c in checks["check_runs"] if c["name"] == "Build APK"
                      and c["app"]["slug"] == "github-actions"]
            if not builds or any(c["conclusion"] != "success" for c in builds):
                continue
            result = api(f"pulls/{pr['number']}/merge", "PUT", {
                "sha": pr["head"]["sha"], "merge_method": "squash"})
            if not result.get("merged"):
                raise RuntimeError(f"PR #{pr['number']} could not be merged")
            pending = [pr["number"]]
            main = api("commits/main")
            if main["sha"] != result["sha"]:
                raise RuntimeError("main advanced during merge; retry on the next run")
            break

    if not pending:
        print("No tested Dependabot patch is ready for release.")
        return

    base = main["sha"]
    file = api(f"contents/{APP}?ref={base}")
    source = base64.b64decode(file["content"]).decode()
    _, current_version = version_info(source)
    # If publication failed after advancing main, reuse that version/commit.
    if main["commit"]["message"] == RELEASE_MESSAGE.format(current_version):
        sha, version = base, current_version
    else:
        source, version = bump(source)
        tree = api("git/trees", "POST", {
            "base_tree": main["commit"]["tree"]["sha"],
            "tree": [{"path": APP, "mode": "100644", "type": "blob", "content": source}]})
        commit = api("git/commits", "POST", {
            "message": RELEASE_MESSAGE.format(version),
            "tree": tree["sha"], "parents": [base]})
        sha = commit["sha"]
    output(sha=sha, base=base, version=version)


def publish():
    sha, base, version = (os.environ[k] for k in ("RELEASE_SHA", "RELEASE_BASE", "RELEASE_VERSION"))
    tag = f"v{version}"
    apk = Path("artifacts") / f"Sumire-Sync-{tag}.apk"
    if not apk.is_file() or apk.stat().st_size == 0:
        raise RuntimeError("Signed release APK is missing")
    if api("commits/main")["sha"] not in (base, sha):
        raise RuntimeError("main advanced during build; retry to test the new main")
    # The build job tested this exact release commit. Record that result so
    # the version-only commit satisfies main's required status check too.
    api(f"statuses/{sha}", "POST", {
        "state": "success", "context": "Build APK",
        "description": "Unit tests and signed release APK passed",
        "target_url": f"https://github.com/{REPO}/actions/runs/{os.environ['GITHUB_RUN_ID']}"})
    api("git/refs/heads/main", "PATCH", {"sha": sha, "force": False})

    existing = [r for r in pages("releases") if r["tag_name"] == tag]
    if existing and not existing[0]["draft"]:
        raise RuntimeError(f"{tag} is already published; refusing to replace it")
    refs = api(f"git/matching-refs/tags/{tag}")
    matching = [r for r in refs if r["ref"] == f"refs/tags/{tag}"]
    if matching:
        if matching[0]["object"]["sha"] != sha:
            raise RuntimeError("Release tag already points to another commit")
    else:
        api("git/refs", "POST", {"ref": f"refs/tags/{tag}", "sha": sha})
    if not existing:
        gh("release", "create", tag, "--repo", REPO, "--draft", "--title", tag,
           "--generate-notes", "--verify-tag")
    gh("release", "upload", tag, str(apk), "--repo", REPO, "--clobber")
    gh("release", "edit", tag, "--repo", REPO, "--draft=false", "--latest")
    print(f"Published https://github.com/{REPO}/releases/tag/{tag}")


if __name__ == "__main__":
    {"plan": plan, "publish": publish}[sys.argv[1]]()
