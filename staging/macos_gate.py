#!/usr/bin/env python3
"""Run upstream's macOS suites for one public kvnloo/noop commit on fleet-runner, on upstream's runners.

    macos_gate.py dispatch <label> <sha>      # macos-15: 8 Swift packages + Strand app test; macos-26: NOOPiOS build
    macos_gate.py collect  <run-id> <out-dir> # per-job conclusion -> <out-dir>/macos.json
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
IOS = ["brew install xcodegen >/dev/null && xcodegen generate && xcodebuild -scheme NOOPiOS -configuration Debug "
       "-destination 'generic/platform=iOS Simulator' CODE_SIGNING_ALLOWED=NO CODE_SIGNING_REQUIRED=NO build"]
LINUX = ['export JAVA_HOME="$JAVA_HOME_17_X64" && cd android && ./gradlew --no-daemon assembleFullDebug testFullDebugUnitTest',
         "cd Tools && python3 -m unittest discover -p 'test_*.py'"]
LANES = {"macos-15": (COMMANDS, NAMES), "macos-26": (IOS, ["app-NOOPiOS"]),
         "ubuntu-latest": (LINUX, ["android", "tools-python"])}


def gh(*a):
    return subprocess.run(["gh", *a], text=True, capture_output=True, check=True).stdout


def dispatch(label, sha):
    for runner in LANES:
        dispatch_one(f"{label}-{runner}", sha, runner)


def dispatch_one(label, sha, runner):
    cmds = LANES[runner][0]
    rid = f"noop-{label}-{int(time.time())}"
    gh("workflow", "run", "verify.yml", "-R", "kvnloo/fleet-runner", "-f", "repository=kvnloo/noop",
       "-f", f"sha={sha}", "-f", f"runner={runner}", "-f", "command=true", "-f", f"matrix={json.dumps(cmds)}",
       "-f", f"parallel={len(cmds)}", "-f", "timeout_minutes=150", "-f", f"request_id={rid}")
    for _ in range(30):
        time.sleep(4)
        runs = json.loads(gh("run", "list", "-R", "kvnloo/fleet-runner", "--workflow", "verify.yml", "-L", "20",
                             "--json", "databaseId,displayTitle"))
        hit = [r["databaseId"] for r in runs if r["displayTitle"] == f"fleet-{rid}"]
        if hit:
            print(json.dumps({"label": label, "sha": sha, "runner": runner, "run": hit[0]}))
            return
    sys.exit(f"dispatched {rid} but no run appeared")


def collect(run, out):
    jobs = json.loads(gh("run", "view", str(run), "-R", "kvnloo/fleet-runner", "--json", "jobs,status,displayTitle"))
    names = LANES[next((r for r in LANES if r in jobs["displayTitle"]), "macos-15")][1]
    res = {}
    for j in jobs["jobs"]:
        if j["name"].startswith("verify-"):
            i = int(j["name"].split("-")[1])
            res[names[i]] = j["conclusion"] or j["status"]
    Path(out).mkdir(parents=True, exist_ok=True)
    Path(out, f"macos-{run}.json").write_text(json.dumps({"run": run, "status": jobs["status"], "jobs": res}, indent=2))
    print(json.dumps(res))


if __name__ == "__main__":
    {"dispatch": lambda: dispatch(*sys.argv[2:4]), "collect": lambda: collect(*sys.argv[2:4])}[sys.argv[1]]()
