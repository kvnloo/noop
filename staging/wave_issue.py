#!/usr/bin/env python3
"""Render the upstream wave issue body from manifest.json, report.json and the CI receipts.

    wave_issue.py <wave> <base-runs> <stage-runs> > issue.md
    # <base-runs>/<stage-runs>: comma-separated fleet-runner run ids for upstream main and the stage tip

Every number in the issue comes from these files or from the GitHub API at render time.
"""
import json
import subprocess
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
JOB_ORDER = ["android", "tools-python", "WhoopProtocol", "OuraProtocol", "PolarProtocol", "WhoopStore",
             "StrandAnalytics", "StrandImport", "StrandDesign", "NoopLocalAccess", "app-Strand", "app-NOOPiOS"]


def jobs(runs):
    import macos_gate
    out = {}
    for run in runs.split(","):
        j = json.loads(subprocess.run(["gh", "run", "view", run, "-R", "kvnloo/fleet-runner", "--json", "jobs,displayTitle"],
                                      text=True, capture_output=True, check=True).stdout)
        names = macos_gate.LANES[next(r for r in macos_gate.LANES if r in j["displayTitle"])][1]
        for x in j["jobs"]:
            if x["name"].startswith("verify-"):
                out[names[int(x["name"].split("-")[1])]] = (x["conclusion"] or x["status"], x["url"])
    return out


def main(wave, base_runs, stage_runs):
    m = json.loads((HERE / "manifest.json").read_text())
    rep = json.loads((HERE / "report.json").read_text())
    entries = {e["pr"]: e for e in m["entries"]}
    lanes = m.get("lanes", [])
    laned = {p: l for l in lanes for p in l["prs"]}
    clean = [r["pr"] for r in rep["results"] if r.get("status") == "merged" and "pr" in r]
    base, stage = jobs(base_runs), jobs(stage_runs)

    w = []
    w.append(f"Merge-order offer, wave {wave}: {len(entries)} open PRs, tested together\n")
    w.append("Hi @ryanbr. Open PRs have been piling up since late October, so I tested a batch of them together, "
             "in case a pre-checked merge order saves you time. Nothing here needs a reply, and if this "
             "format doesn't help, say so and I'll stop.\n")
    w.append("## What was tested\n")
    w.append(f"- Base: `main` at `{rep['base'][:10]}`.")
    w.append(f"- Combined branch: [`stage/upstream`](https://github.com/kvnloo/noop/tree/stage/upstream) at "
             f"`{rep['staging'][:10]}` in my fork. It is `main` plus each PR below, merged with `--no-ff` at the "
             "head commit listed, so contributors' commits are untouched.")
    w.append("- Selection: open, not a draft, mergeable against `main`, and green on every CI job its paths trigger. "
             "Wave 1 is fixes, tests and translations of at most 600 changed lines. UI restyles are left out, since picking one is your call.")
    w.append("- The same CI commands as this repo's workflows ran on `main` and on the combined branch, on GitHub-hosted runners "
             "(ubuntu-latest, macos-15, macos-26).\n")
    w.append("| Job | `main` | combined |\n|---|---|---|")
    for name in JOB_ORDER:
        if name in base or name in stage:
            b, s = base.get(name, ("not run", "")), stage.get(name, ("not run", ""))
            w.append(f"| {name} | [{b[0]}]({b[1]}) | [{s[0]}]({s[1]}) |")
    w.append("")
    w.append(f"## Merge in this order\n\n**{len(clean)} PRs merge cleanly on top of each other in this order:**\n")
    w.append(" ".join(f"#{p}" for p in clean) + "\n")
    w.append(f"**{len(laned)} PRs conflict with earlier ones in the list.** Each conflict has a proposed resolution as a merge "
             "commit you can read or ignore. The PR's own commits are unchanged.\n")
    w.append("| PR | Conflicts with | Files | Resolution |\n|---|---|---|---|")
    conflicts = {r["pr"]: r for r in json.loads((HERE / "report-conflicts.json").read_text())}
    for p in sorted(laned):
        c, l = conflicts.get(p, {}), laned[p]
        w.append(f"| #{p} | {' '.join(f'#{x}' for x in c.get('collides_with', []))} | "
                 f"{', '.join(f'`{f.split(chr(47))[-1]}`' for f in c.get('files', []))} | "
                 f"[`{l['branch']}`](https://github.com/kvnloo/noop/tree/{l['branch']}) |")
    w.append("")
    w.append("Rebuild and check it yourself: [`staging-meta`](https://github.com/kvnloo/noop/tree/staging-meta) has the manifest "
             "(PR number plus pinned head), the builder, and `staging.py verify`, which checks every pinned head is in "
             "the combined history.\n")
    w.append("Refs " + ", ".join(f"#{p}" for p in sorted(entries)))
    return "\n".join(w)


if __name__ == "__main__":
    sys.path.insert(0, str(HERE))
    print(main(*sys.argv[1:4]))
