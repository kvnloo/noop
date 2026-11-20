# NOOP staging

This branch (`staging-meta`) holds the tools that build `staging`. It shares no history with the app code.

## Branches

| Branch | Contents | Rule |
|---|---|---|
| `main` | Exact mirror of `ryanbr/noop` main | Fast-forward only. Upstream PRs branch from here |
| `staging` | `main` plus open upstream PRs, each merged `--no-ff` at its pinned head | Rebuilt from `manifest.json`. Never edit it by hand |
| `preview` | `staging` plus fork-only work (`fav/*`, `fork/*`) | What noop-channels builds for the phone |

## Rebuild

```bash
python3 staging/staging.py select --wave 1 --out staging/manifest.json   # propose entries from live GitHub state
python3 staging/staging.py build staging/manifest.json --repo ../noop --report staging/report.json
staging/gate.sh ../noop <base-sha>    runs/base                           # upstream main, the baseline
staging/gate.sh ../noop <staging-sha> runs/staging                        # same suites on staging
```

A suite counts against staging only if it passes on the baseline and fails on staging.

## Entry rules (`select`)

A PR enters staging when all of these hold:
- it is open, not a draft, and based on `main`;
- upstream says it merges cleanly;
- upstream CI is green on its head, including every job its paths trigger (Android, app build, Swift packages). A rollup with no compile legs doesn't count.

Wave 1 is fixes, tests and translations of at most 600 changed lines. Wave 2 is features and larger PRs. UI restyles stay out, because choosing between them is the maintainer's call.

## Build results

`build` records each entry as `merged`, `landed` (upstream already merged it), `conflict` (files plus the earlier entries it collides with) or `moved` (the contributor pushed since it was pinned, so re-pin it deliberately). It never resolves a conflict. A resolution has to be its own reviewed entry.
