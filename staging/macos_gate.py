#!/usr/bin/env python3
"""Run upstream's macOS suites for one public kvnloo/noop commit on fleet-runner (free macos-14).

    macos_gate.py dispatch <label> <sha>      # 9 parallel jobs: 8 Swift packages + Strand app test
    macos_gate.py collect  <run-id> <out-dir> # per-job conclusion -> <out-dir>/macos.json

The iOS app leg needs the iOS 26 SDK (macos-26), which fleet-runner can't provide: NOT_EXECUTED.
"""
import json
import subprocess
import sys
import time
from pathlib import Path

PACKAGES = ["WhoopProtocol", "OuraProtocol", "PolarProtocol", "WhoopStore", "StrandAnalytics",
            "StrandImport", "StrandDesign", "NoopLocalAccess"]
COMMANDS = [f"cd Packages/{p} && swift test" for p in PACKAGES] + [
    "brew install xcodegen >/dev/null && xcodegen generate && xcodebuild -scheme Strand -configuration Debug "
    "-destination 'platform=macOS' CODE_SIGNING_ALLOWED=NO CODE_SIGNING_REQUIRED=NO test"]
NAMES = PACKAGES + ["app-Strand"]


def gh(*a):
    return subprocess.run(["gh", *a], text=True, capture_output=True, check=True).stdout


def dispatch(label, sha):
    rid = f"noop-{label}-{int(time.time())}"
    gh("workflow", "run", "verify.yml", "-R", "kvnloo/fleet-runner", "-f", "repository=kvnloo/noop",
       "-f", f"sha={sha}", "-f", "runner=macos-14", "-f", "command=true", "-f", f"matrix={json.dumps(COMMANDS)}",
       "-f", f"parallel={len(COMMANDS)}", "-f", "timeout_minutes=150", "-f", f"request_id={rid}")
    for _ in range(30):
        time.sleep(4)
        runs = json.loads(gh("run", "list", "-R", "kvnloo/fleet-runner", "--workflow", "verify.yml", "-L", "20",
                             "--json", "databaseId,displayTitle"))
        hit = [r["databaseId"] for r in runs if r["displayTitle"] == f"fleet-{rid}"]
        if hit:
            print(json.dumps({"label": label, "sha": sha, "run": hit[0]}))
            return
    sys.exit(f"dispatched {rid} but no run appeared")


def collect(run, out):
    jobs = json.loads(gh("run", "view", str(run), "-R", "kvnloo/fleet-runner", "--json", "jobs,status"))
    res = {}
    for j in jobs["jobs"]:
        if j["name"].startswith("verify-"):
            i = int(j["name"].split("-")[1])
            res[NAMES[i]] = j["conclusion"] or j["status"]
    res["app-NOOPiOS"] = "NOT_EXECUTED (needs macos-26)"
    Path(out).mkdir(parents=True, exist_ok=True)
    Path(out, "macos.json").write_text(json.dumps({"run": run, "status": jobs["status"], "jobs": res}, indent=2))
    print(json.dumps(res))


if __name__ == "__main__":
    {"dispatch": lambda: dispatch(*sys.argv[2:4]), "collect": lambda: collect(*sys.argv[2:4])}[sys.argv[1]]()
