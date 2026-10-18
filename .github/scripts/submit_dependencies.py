"""Submit one checked-out build, replacing stale snapshots from both old workflows."""

import copy
import json
import os
from pathlib import Path
import subprocess

CORRELATOR = "sumire-sync-gradle"
LEGACY_CORRELATORS = (
    "submit_gradle_dependencies-dependencies",
    "dependabot_dependency_release-dependencies",
)


def submissions(snapshot, checkout_sha):
    if snapshot.get("sha") != checkout_sha:
        raise ValueError("Dependency snapshot SHA does not match checked-out HEAD")
    if snapshot.get("ref") != "refs/heads/main":
        raise ValueError("Dependency snapshot must describe main")
    if snapshot.get("job", {}).get("correlator") != CORRELATOR:
        raise ValueError("Unexpected dependency snapshot correlator")
    if not snapshot.get("manifests") or not snapshot.get("detector"):
        raise ValueError("No dependency manifests or detector generated")
    result = [copy.deepcopy(snapshot)]
    # GitHub merges the latest snapshot for each detector/correlator. A new common
    # correlator alone does not remove the old dependencies. Empty replacements
    # retire the old streams, but only AFTER the current complete snapshot succeeds.
    for correlator in LEGACY_CORRELATORS:
        retired = copy.deepcopy(snapshot)
        retired["job"]["correlator"] = correlator
        retired["manifests"] = {}
        result.append(retired)
    return result


def submit(snapshot, repository):
    subprocess.run(
        ["gh", "api", "--method", "POST", f"repos/{repository}/dependency-graph/snapshots",
         "--input", "-"],
        input=json.dumps(snapshot), text=True, check=True,
    )


def main():
    files = list(Path("dependency-graph-reports").rglob("*.json"))
    if len(files) != 1:
        raise ValueError(f"Expected one complete Gradle dependency snapshot, found {len(files)}")
    sha = subprocess.check_output(["git", "rev-parse", "HEAD"], text=True).strip()
    snapshots = submissions(json.loads(files[0].read_text()), sha)
    repository = os.environ["GITHUB_REPOSITORY"]
    # The workflow serializes every submitter. A build may still become stale
    # while Gradle runs; never publish that build over the current main snapshot.
    main_sha = subprocess.check_output(
        ["gh", "api", f"repos/{repository}/git/ref/heads/main", "--jq", ".object.sha"],
        text=True,
    ).strip()
    if main_sha != sha:
        print("main advanced during dependency analysis; skipping stale snapshot submission.")
        return
    for snapshot in snapshots:
        submit(snapshot, repository)


if __name__ == "__main__":
    main()
