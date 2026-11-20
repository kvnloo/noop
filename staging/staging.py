#!/usr/bin/env python3
"""Rebuild kvnloo/noop `stage/*` = ryanbr/noop main + open upstream PRs at pinned heads.

    staging.py select  --wave N [--out manifest.json]   # propose entries from live GitHub state
    staging.py build   manifest.json --repo PATH         # rebuild into local branch stage-local
    staging.py build   manifest.json --repo PATH --push  # ...and push snapshot stage/<label> (never forced)

The manifest is the source of truth, and the branch can be rebuilt from it at any time.
Each PR merges --no-ff at its exact pinned head, so contributor commits are never rewritten
and dropping a PR is deleting one manifest entry. Entries upstream has already merged report
"landed" and add nothing. A conflict is recorded with its files and skipped. The builder never
hand-resolves: conflicting PRs go to a resolution lane, a reviewed branch that merges them on top of
the clean tip. The builder merges each lane after the clean pass, so git composes the lanes.
"""
from __future__ import annotations

import argparse
import json
import os
import subprocess
import sys
from pathlib import Path

UPSTREAM = "ryanbr/noop"
UI_RESTYLES = {2892, 2902, 2903, 2907, 2918}  # competing redesigns: the maintainer's pick, not ours
WAVE1_MAX_LINES = 600

# Jobs upstream CI must have passed on the PR head, keyed by the paths that trigger them.
REQUIRED_JOBS = [
    (("android/",), "android", lambda n: n == "build-and-test"),
    (("Strand/", "StrandTests/", "StrandiOS", "NOOPWatch", "project.yml", "Packages/"),
     "app-build", lambda n: n.startswith("build (Strand")),
    (("Packages/",), "swift-packages", lambda n: n.startswith("test (")),
]

QUERY = """query($c:String){repository(owner:"ryanbr",name:"noop"){pullRequests(states:OPEN,first:50,after:$c){
pageInfo{hasNextPage endCursor}
nodes{number title isDraft author{login} headRefOid additions deletions mergeable baseRefName
commits(last:1){nodes{commit{statusCheckRollup{state contexts(first:60){nodes{... on CheckRun{name conclusion}}}}}}}
files(first:100){nodes{path}}}}}}"""


ENV = None  # set by cmd_build from the manifest: fixed identity and date, so a rebuild is byte-identical


def run(*cmd, cwd=None, check=True):
    p = subprocess.run(cmd, cwd=cwd, text=True, capture_output=True, env=ENV)
    if check and p.returncode:
        sys.exit(f"$ {' '.join(cmd)}\n{p.stdout}{p.stderr}")
    return p


def open_prs():
    nodes, cursor = [], None
    while True:
        args = ["gh", "api", "graphql", "-f", f"query={QUERY}"] + (["-f", f"c={cursor}"] if cursor else [])
        page = json.loads(run(*args).stdout)["data"]["repository"]["pullRequests"]
        nodes += page["nodes"]
        if not page["pageInfo"]["hasNextPage"]:
            return nodes
        cursor = page["pageInfo"]["endCursor"]


def gate(pr):
    """Return None if the PR may enter staging, else the reason it may not."""
    if pr["isDraft"]:
        return "draft"
    if pr["baseRefName"] != "main":
        return f"base {pr['baseRefName']}"
    if pr["mergeable"] != "MERGEABLE":
        return f"upstream says {pr['mergeable'].lower()}"
    rollup = pr["commits"]["nodes"][0]["commit"]["statusCheckRollup"]
    if not rollup or rollup["state"] != "SUCCESS":
        return f"ci {rollup['state'].lower() if rollup else 'none'}"
    passed = [c["name"] for c in rollup["contexts"]["nodes"] if c and c.get("conclusion") == "SUCCESS"]
    paths = [f["path"] for f in pr["files"]["nodes"]]
    for prefixes, job, match in REQUIRED_JOBS:
        if any(p.startswith(prefixes) for p in paths) and not any(match(n) for n in passed):
            return f"{job} never ran"
    return None


def wave_of(pr):
    if pr["number"] in UI_RESTYLES:
        return None
    small = pr["additions"] + pr["deletions"] <= WAVE1_MAX_LINES
    return 1 if small and not pr["title"].lower().startswith("feat") else 2


def cmd_select(a):
    entries, held = [], []
    for pr in sorted(open_prs(), key=lambda p: p["number"]):
        reason = gate(pr) or (None if wave_of(pr) else "ui restyle (maintainer's choice)")
        if reason:
            held.append({"pr": pr["number"], "reason": reason})
        elif wave_of(pr) <= a.wave:
            entries.append({"pr": pr["number"], "sha": pr["headRefOid"], "author": pr["author"]["login"],
                            "title": pr["title"], "wave": wave_of(pr)})
    out = {"upstream": UPSTREAM, "base": "main", "entries": entries, "held": held}
    Path(a.out).write_text(json.dumps(out, indent=2) + "\n")
    print(f"{len(entries)} entries through wave {a.wave}, {len(held)} held -> {a.out}")


def cmd_build(a):
    global ENV
    m = json.loads(Path(a.manifest).read_text())
    who, when = m.get("committer", "kvnloo staging <kvnloo@users.noreply.github.com>"), m.get("commit_date", "2026-11-20T00:00:00Z")
    name, email = who.rsplit(" <", 1)
    ENV = {**os.environ, "GIT_AUTHOR_NAME": name, "GIT_COMMITTER_NAME": name, "GIT_AUTHOR_EMAIL": email.rstrip(">"),
           "GIT_COMMITTER_EMAIL": email.rstrip(">"), "GIT_AUTHOR_DATE": when, "GIT_COMMITTER_DATE": when}
    repo = a.repo
    git = lambda *x, check=True: run("git", "-c", "rerere.enabled=false", *x, cwd=repo, check=check)
    if git("status", "--porcelain").stdout.strip():
        sys.exit("working tree not clean")
    if git("remote", "get-url", "upstream", check=False).returncode:
        git("remote", "add", "upstream", f"https://github.com/{m['upstream']}")
    specs = [f"+refs/pull/{e['pr']}/head:refs/staging-pr/{e['pr']}" for e in m["entries"]]
    git("fetch", "-q", "upstream", f"+refs/heads/{m['base']}:refs/remotes/upstream/{m['base']}", *specs)
    base = git("rev-parse", f"upstream/{m['base']}").stdout.strip()
    git("checkout", "-q", "-B", "stage-local", base)

    results = []
    in_lane = {pr for lane in m.get("lanes", []) for pr in lane["prs"]}
    for e in m["entries"]:
        head = git("rev-parse", f"refs/staging-pr/{e['pr']}").stdout.strip()
        r = {"pr": e["pr"], "sha": e["sha"]}
        if head != e["sha"]:
            r.update(status="moved", now=head)  # contributor pushed; re-pin deliberately, never silently
        elif git("merge-base", "--is-ancestor", e["sha"], base, check=False).returncode == 0:
            r.update(status="landed")
        elif e["pr"] in in_lane:
            continue  # merged by its resolution lane below
        else:
            msg = f"staging: merge {m['upstream']}#{e['pr']} by @{e['author']}\n\n{e['title']}\nhead {e['sha']}"
            if git("merge", "--no-ff", "-q", "-m", msg, e["sha"], check=False).returncode == 0:
                r.update(status="merged")
            else:
                files = git("diff", "--name-only", "--diff-filter=U").stdout.split()
                git("merge", "--abort")
                r.update(status="conflict", files=files)
        results.append(r)
        print(f"#{e['pr']:<5} {r['status']}" + (f"  {r['files']}" if r.get("files") else ""))

    # Resolution lanes: reviewed branches that merged their PRs (with conflicts resolved) on top of the
    # clean tip. They all start from that tip, so a plain merge composes them hunk by hunk.
    clean = git("rev-parse", "HEAD").stdout.strip()
    pinned = m.get("clean_base")
    if pinned:
        git("fetch", "-q", "origin", pinned, check=False)
        if git("rev-parse", f"{pinned}^{{tree}}").stdout == git("rev-parse", "HEAD^{tree}").stdout:
            git("reset", "-q", "--hard", pinned)  # same content as the commit the lanes were resolved on
            clean = pinned
    for lane in m.get("lanes", []):
        r = {"lane": lane["name"], "sha": lane["sha"], "prs": lane["prs"]}
        git("fetch", "-q", "origin", lane["sha"], check=False)
        if lane["base"] != clean:
            r.update(status="stale")  # clean set changed since the lane was resolved; redo the lane
        elif git("merge", "--no-ff", "-q", "-m", f"staging: resolution lane {lane['name']} ({', '.join(f'#{p}' for p in lane['prs'])})",
                 lane["sha"], check=False).returncode == 0:
            r.update(status="merged")
        else:
            files = git("diff", "--name-only", "--diff-filter=U").stdout.split()
            git("merge", "--abort")
            r.update(status="conflict", files=files)
        results.append(r)
        print(f"lane {lane['name']:<10} {r['status']}" + (f"  {r['files']}" if r.get("files") else ""))

    # Fixups: reviewed commits for interactions between PRs that each pass alone (e.g. one PR's test
    # assumes a signature another PR changes). Applied last, so stage/upstream keeps fast-forwarding.
    for fx in m.get("fixups", []):
        git("fetch", "-q", "origin", fx["sha"], check=False)
        ok = git("cherry-pick", "--allow-empty", fx["sha"], check=False).returncode == 0
        if not ok:
            git("cherry-pick", "--abort", check=False)
        results.append({"fixup": fx["sha"], "prs": fx["prs"], "status": "merged" if ok else "conflict"})
        print(f"fixup {fx['sha'][:10]} {'applied' if ok else 'CONFLICT'}  ({fx['why']})")

    tip = git("rev-parse", "HEAD").stdout.strip()
    report = {"base": base, "staging": tip, "results": results}
    Path(a.report).write_text(json.dumps(report, indent=2) + "\n")
    counts = {s: sum(r["status"] == s for r in results) for s in ("merged", "landed", "conflict", "moved", "stale")}
    print(f"staging {tip[:10]} on {m['upstream']}@{base[:10]}: {counts}")
    if a.push:
        # Never force: every rebuild is a new immutable snapshot; stage/upstream only fast-forwards.
        snap = f"stage/{a.label}"
        git("push", "-q", "origin", f"HEAD:refs/heads/{snap}")
        ff = git("push", "-q", "origin", "HEAD:refs/heads/stage/upstream", check=False).returncode == 0
        print(f"pushed {snap}; stage/upstream {'advanced' if ff else 'unchanged (not a fast-forward; point readers at the snapshot)'}")


def cmd_verify(a):
    """The property the maintainer needs: every pinned PR head (and every lane) is in the tip's history."""
    m = json.loads(Path(a.manifest).read_text())
    git = lambda *x, check=True: run("git", "-c", "rerere.enabled=false", *x, cwd=a.repo, check=check)
    missing = [str(x) for x in [e["sha"] for e in m["entries"]] + [l["sha"] for l in m.get("lanes", [])]
               if git("merge-base", "--is-ancestor", x, a.tip, check=False).returncode]
    print(f"{len(m['entries'])} PR heads + {len(m.get('lanes', []))} lanes; missing from {a.tip}: {missing or 'none'}")
    sys.exit(1 if missing else 0)


def main():
    ap = argparse.ArgumentParser()
    sub = ap.add_subparsers(dest="cmd", required=True)
    s = sub.add_parser("select"); s.add_argument("--wave", type=int, default=1); s.add_argument("--out", default="manifest.json")
    b = sub.add_parser("build"); b.add_argument("manifest"); b.add_argument("--repo", required=True)
    b.add_argument("--report", default="report.json"); b.add_argument("--push", action="store_true")
    b.add_argument("--label", default="snapshot", help="snapshot branch name under stage/, e.g. w1-20261120-0201")
    v = sub.add_parser("verify"); v.add_argument("manifest"); v.add_argument("--repo", required=True)
    v.add_argument("--tip", default="HEAD")
    a = ap.parse_args()
    {"select": cmd_select, "build": cmd_build, "verify": cmd_verify}[a.cmd](a)


if __name__ == "__main__":
    main()
