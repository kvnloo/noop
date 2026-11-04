# RFC-0: The NOOP factory — how our NOOP work flows from idea to upstream

Status: **accepted for the fork** (owner request 2026-11-03). Upstream posting stays owner-gated.
Owner: Kevin (@kvnloo). Maintainers of the code we contribute to: `ryanbr/noop` (Fanboynz and contributors).

## TL;DR

1. **`main` is a pure mirror of upstream** (`ryanbr/noop` main). Nobody commits to it. A sync fast-forwards it.
2. **Every change is one small branch + one Draft PR into `kvnloo/noop` main.** That runs upstream's own CI, so a green fork PR is a credible upstream PR.
3. **Phones get our favourite features through channels**, not hand-made integration branches:
   `channels/base` (main + fork tooling) → `channels/nightly` (every feature in progress) and `channels/preview` (features we trust).
4. **The ChatGPT app does most of the work**: hundreds of single-turn chats, one atomic task each, all reporting to one tracking issue.
5. **Upstream is the goal**: a green, reviewed, upstream-sized fork PR becomes an upstream PR after Kevin's yes.

```
 ryanbr/noop main ──ff──▶ kvnloo/noop main ──▶ topic branch ──▶ Draft PR → kvnloo main  (upstream CI)
                                 │                                  │ green + reviewed
                                 ▼                                  ▼
                         channels/base ──compose──▶ channels/nightly ──promote──▶ channels/preview
                                                        │ demo APK (side by side)     │ full APK (S25, keeps strap/ring data)
                                                                                    ▼
                                                              owner "go" ──▶ PR to ryanbr/noop
```

## Why

Most NOOP work so far came from the ChatGPT app and it worked: 7 of our PRs are merged upstream
(`ryanbr/noop` #1583, #1752, #1767, #1769, #1772, #1796, #1861). But the fork drifted:

- 33 open fork PRs, many stacked on old `sync/*`, `qa/*` or feature bases, so CI never ran on several of them (#81 had zero checks).
- The S25 runs hand-cherry-picked `integrate/s25-*` builds; nobody can say which features are on the phone.
- Upstream moved on (fork main was 19 commits behind until today's sync), and has 421 open issues and 140 open PRs we can help with.

## Branch kinds (kvnloo/noop)

| Branch | What it is | Who writes it |
|---|---|---|
| `main` | Exact copy of `ryanbr/noop` main. Fast-forward only. | sync job only |
| `up/<issue>-<slug>` | One upstream issue fixed, one concern, based on `main`. Draft PR → `main`. | ChatGPT chats, Hermes |
| `fav/<slug>` | One of our favourite features, rebuilt on current `main` in an upstream-sized slice. Draft PR → `main`. | ChatGPT chats, Hermes |
| `fork/<slug>` | Features that are ours only (Oura → z0live gestures, Hermes agent API). Never proposed upstream unless a maintainer asks. | Hermes |
| `channels/base` | `main` + one fork commit (channel CI workflow, this RFC). Rebased on every sync. | `noop-channel sync` |
| `channels/nightly` | `channels/base` + every listed feature branch, merged `--no-ff` with rerere. Rebuilt, never edited. | `noop-channel compose` |
| `channels/preview` | `channels/base` + promoted features only. | `noop-channel compose` |
| older `feat/*`, `fix/*`, `qa/*`, `sync/*` | History. Each open PR gets a `fav/` successor or a written reason to close. | — |

Rules: never force-push someone else's branch (fix a PR by adding a commit); never merge into `main`;
channel branches are generated, so fixes go to the feature branch and the channel is recomposed.

## CI/CD

- **Pull requests into `main`** run upstream's real workflows: Android CI (`build-and-test`), i18n Coverage,
  Source Hygiene, Tools Python CI, Swift Packages CI and App build (path-filtered, see upstream CONTRIBUTING).
  A fork PR is "green" only when every check that applies to its paths passed on the exact head.
- **Pushes to `channels/**`** run `fork-channels.yml`: Kotlin unit tests, then `assembleDemoDebug` (nightly)
  or `assembleFullDebug` (preview), APK certificate check against the tracked fork debug key, SHA-256,
  and a rolling pre-release `channel-<name>` with the APK and `build-info.txt` (commit, features, run).
- **Install**: `noop-channel install <channel>` downloads that release, verifies the signer matches the
  installed app, and runs `adb install -r` (never uninstall: the strap and ring keys live in NOOP's data).
  - nightly = **demo flavour** (`com.noop.whoop.demo.debug`, synthetic data) installs beside the real app, so a
    broken feature can never touch the strap or ring.
  - preview = **full flavour** (`com.noop.whoop.debug`), the app on the S25 today; it replaces `integrate/s25-*`.
- **Sync**: `noop-channel sync` fast-forwards `main` to upstream, rebases `channels/base`, recomposes both channels.

## The ChatGPT factory

- Run dir `chatgpt-driver/noop-1103/` (wave **NF1103**), sent by the existing `tern-queue-loop@s22|s25` services at the
  shared pace (≤50 sends/hour across both phones). Ordinary ChatGPT chats, never Codex/Codex Cloud.
- One chat = one atomic card = one branch + one Draft PR (or one written blocker with 3 follow-up cards).
- Results land as one tagged comment each on the tracking issue `[noop chats] NF1103` in `kvnloo/oss-factory`.
- Card families:
  - **NF-UP-<n>**: fix upstream issue `ryanbr/noop#<n>` (364 open issues have no linked open PR). Bugs first, newest first.
  - **NF-FAV-<pr>**: rebuild an open fork PR as an upstream-sized `fav/` branch on current `main`, or explain why it is fork-only.
  - **NF-RECALL-***: ChatGPT memory + connector recall of which NOOP features Kevin cares about most, and what upstream maintainers are working on now.
- Hermes (desktop) owns what phones cannot do: device builds/installs, BLE/strap testing, channel composition, CI repair across many PRs, and verification.

## Promotion upstream (the goal)

A fork PR is promoted when all hold:
1. One concern, follows `ryanbr/noop` AGENTS.md and CONTRIBUTING (tokens only, parity or a stated reason, real-hardware note for BLE, validation protocol for scoring changes).
2. Green on the exact head; an independent review (Hermes/Opus) found no blocker.
3. It does not duplicate an open upstream PR; the upstream issue is still open.
4. Kevin approves the proposal in Dash Approvals (upstream-gate `pr_open`). Agents never post upstream on their own.

Status words are separate and never mixed: proposed → drafted → green → reviewed → in preview → approved → upstream PR → merged upstream.

## What success looks like (first two weeks)

- Every open fork PR has a `fav/` successor or a recorded close reason.
- `channels/nightly` and `channels/preview` build green and are installed on the phones from a known commit.
- At least 100 `up/` Draft PRs with green CI; the best 10–20 proposed upstream, one per day per maintainer's pace.

## Open questions for Kevin

1. Upstream pace: how many NOOP PRs per day are you comfortable opening on `ryanbr/noop`? (Default: ≤3/day.)
2. Should `fork/` features (Oura gestures, Hermes agent API) ever be offered upstream as opt-in plugins?
