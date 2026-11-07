# Prospective experiment contract — design slice

**Status: Draft design slice. Not accepted, not approved to merge, and not product work.**
This document is the written contract, reconciliation and exit criteria requested by issue
[#76](https://github.com/kvnloo/noop/issues/76). It adds no code, no schema, no store and no UI.
Everything below is either (a) quoted from the issue it cites, or (b) an observation made against
`kvnloo/noop` `main` at the pinned SHA named in §8. A claim without its source is not made.

## 1. Purpose and provenance

Recover the 2025–26 Limitless Protocol **Personal Evidence Engine / Experiment Lab /
Protocol Builder / prediction registry** as a *thin, local-first* contract over NOOP's existing
Journal, metrics, baselines and Lab Book. The one-line summary of this slice: **before any
experiment subsystem is proposed, the contract has to be written down, reconciled against the
drafts that are already held, and given a graduation test that can actually fail.**

Related records: held drafts [#42](https://github.com/kvnloo/noop/pull/42) (P0),
[#43](https://github.com/kvnloo/noop/pull/43) (P1), [#44](https://github.com/kvnloo/noop/pull/44)
(P2), synthetic native Journal witness [#46](https://github.com/kvnloo/noop/pull/46),
explicit-No repair [#49](https://github.com/kvnloo/noop/pull/49), existing PR ledger
[#71](https://github.com/kvnloo/noop/issues/71), research program
[#94](https://github.com/kvnloo/noop/issues/94), factory GitOps RFC-0
[#103](https://github.com/kvnloo/noop/issues/103), and the Human Capability Loop master RFC
[oss-factory #212](https://github.com/kvnloo/oss-factory/issues/212).

This slice is governed by #94 as a *research exploration*, not product work: one exploration
active, bounded before it starts, no new platform, human in charge, one receipt at the end.

## 2. The proposed contract (verbatim from #76, not yet accepted)

```text
experiment_id, title, hypothesis, canonical_factor_key,
primary_outcome_metric_key, study_unit (wake-day/session),
baseline_window, exposure_window, outcome_window + lag,
predicted_direction, minimum_coverage, minimum_samples,
predeclared_support_and_falsification_rule,
created_at, prediction_locked_at, status, analysis_recipe_version,
source_identity/version, consent/export policy
```

Two rules from the issue that this slice treats as load-bearing:

- An experiment references **existing native factor IDs and metric keys**. It never treats a
  free-form alias like `caffeine_timing` as a confirmed canonical Journal question.
- Calendar/wake-day/timezone, **multiple exposures** and **multi-day outcomes** must be defined
  *before* accepting a generalized chronology validator.

## 3. Fields the issue names as still unresolved

| Field / concern | Why it is not settled | Smallest thing that would settle it |
|---|---|---|
| `study_unit` (wake-day vs session) | A study may span several wake-days with outcomes separately dated; a single-window chronology rule rejects a valid two-week design (#42's own review says so) | One fixture: a 14-wake-day exposure with a next-morning outcome, asserted valid |
| Multiple exposures per wake-day / session | #76 names it as unresolved; a wake-day with several exposures of the factor is not representable as one boolean occurrence | One fixture: two exposures on one wake-day, with an explicit exposure count per study unit |
| Calendar / wake-day / timezone | Day keys are local; an outcome dated the next morning must join to the wake-day it follows, across DST and leap-day | Portable Swift/Kotlin fixtures: DST, leap-day, timezone, duplicate imports (named by #76) |
| `canonical_factor_key` resolution | Must resolve against the current catalog, not a fork-era alias | Resolve through `MetricCatalog.metric(key:source:)` and `JournalCatalogStore.starterQuestions` on current `main` |
| `prediction_locked_at` / preregistration | A pure P0 type alone cannot prove the hypothesis existed before exposure | Durable, auditable identity: lock before exposure, amendments retain prior snapshots |
| `primary_outcome_metric_key` identity | Preserve `source`, import precedence, coverage and observation provenance | Native-over-import merge rules asserted on fixtures |
| `minimum_coverage` / `minimum_samples` | Must be validated together with the unit and the lag, not as bare integers | Threshold validation tied to the declared unit + window |
| `source_identity/version`, `consent/export policy` | Unspecified for a local-first, no-server app | Versioned export/backup contract, only if persistence is justified |

## 4. Current-base comparison (required by #76 before porting any stale assumption)

Every symbol #76 names to compare exists on current `main`. Verified at the pinned SHA in §8:

| #76 names | On current `main` |
|---|---|
| `BehaviorInsights.effect(behaviorDays:controlDays:outcomeByDay:)` | `Packages/StrandAnalytics/Sources/StrandAnalytics/BehaviorInsights.swift:99` |
| Android `EffectRanker.effect` | `android/app/src/main/java/com/noop/analytics/EffectRanker.kt:242` |
| `MetricCatalog.metric(key:source:)` | `Strand/Data/MetricCatalog.swift:218` |
| `JournalCatalogStore.starterQuestions` | `Strand/Data/JournalCatalog.swift`; Android `android/app/src/main/java/com/noop/ui/JournalLog.kt` |
| native/import `mergeJournal` | `Strand/Data/Repository.swift:2715`; Android `mergeJournalEntries` in `android/app/src/main/java/com/noop/ui/JournalLog.kt:93` |

**The one invariant #76 states that current `main` still violates** — *no unlogged day is a
baseline control*:

- Swift: `Strand/Screens/InsightsView.swift:814` selects the experiment baseline as
  `.filter { $0 < experimentStartedDay && !loggedDays.contains($0) }`, where `loggedDays` is the
  **yes**-answer set only (see `InsightsView.swift:354`, "only \"yes\" answers count as the
  behaviour occurring"). A day that was answered **No** and a day that was **never answered**
  both fail `!loggedDays.contains`, so both enter the baseline. The map builder states the
  separation itself — "Yes days and NO days, kept apart. A day with no journal row for the
  question lands in neither, so an unanswered day is never counted as a No
  (BehaviorInsights.effect)" (`InsightsView.swift:359`) — so the explicit-No set is produced in
  the same pass and is already available; the snapshot baseline simply does not consult it.
- Kotlin: `buildExperimentSnapshot` in `android/app/src/main/java/com/noop/ui/InsightsScreen.kt`
  uses the same shape (`.filter { it < startedDay && it !in loggedDays }`).

Both platforms already build the explicit-No set and document it as the only legitimate control
group — `InsightModel.controls` in `InsightsScreen.kt:143-145` ("Per behaviour, the days it was
logged NO — the only legitimate control group. A day with no journal row for the question is in
neither map") and the matching `controls` cache in `InsightsView.swift:43-45`, filled from
`controlsByBehaviour` at `:427`. **The explicit-No map
exists at both call sites and is not used by the experiment snapshot.**

The Behavior Effects path already holds the invariant: `EffectRanker.effect`
(`EffectRanker.kt:242`) and `BehaviorInsights.effect` (`BehaviorInsights.swift:99`) each take
`behaviorDays` + `controlDays` and ignore a day in neither set. So the same fact has two readouts
that can disagree — the Behavior Effects ranking excludes unlogged days, the Personal Experiment
snapshot baseline includes them. This is precisely the failure shape the repository already forbids
("two readouts of one fact must not be able to disagree"), and it is the exact defect held draft
[#49](https://github.com/kvnloo/noop/pull/49) repairs.

## 5. Reconciliation of the held drafts

| Draft | Contract field(s) it implements | Field(s) it is missing | What stays held |
|---|---|---|---|
| **#42 P0** — prospective experiment contract (draft, base `staging`) | The structural half of §2: baseline/exposure/outcome windows, `predicted_direction`, `minimum_coverage`, `minimum_samples`, `predeclared_support_and_falsification_rule`, `prediction_locked_at`, `analysis_recipe_version`, `status` — as a pure DB-free type on Swift and Kotlin | `canonical_factor_key` / `primary_outcome_metric_key` identity against current `main`; `study_unit` + wake-day/timezone; multiple exposures; multi-day outcomes (its single-window rule rejects a valid two-week study); a durable/auditable preregistration identity (a pure type cannot prove lock-before-exposure) | **HOLD, fork-only.** Base is 1,705 origin commits behind with 18 fork-side commits ahead. No stale assumption is forward-ported; #46's clean-current-base pilot is the substitute |
| **#43 P1** — auditable evidence receipts | The receipt half: embeds the complete P0 contract snapshot, records source provenance, sample counts, coverage/missingness, effect estimate, uncertainty, confounders, result; derives lag from exposure end → outcome start; restricts verdicts to `supports`/`contradicts` only when the preregistered gates are met, else `inconclusive` | Everything #42 is missing, by construction (it stacks on #42). Persistence/audit is explicitly deferred, so it cannot prove the contract existed before exposure | **HOLD, fork-only.** Waits for two actual local observations and chosen P0 semantics; no infrastructure meanwhile |
| **#44 P2** — versioned external outcome observations | The external numeric outcome shape: stable observation id, measurement + ingest timestamps, outcome key, finite value, explicit unit, source id, source-native record id, session id, measurement protocol/version; preserves external clock skew | Persistence, import transport, UI, aggregation into `metricSeries`, higher-is-better semantics — and the prerequisite the RFC itself sets: two real experiment recipes that cannot be represented losslessly with existing primitives | **HOLD, fork-only.** Explicitly *not* another metric store; promotion beyond experiment status still requires the two-recipe evidence |
| **#46 witness** — synthetic native Journal pilots | Evidence for #76's final acceptance gate: two existing Journal → outcome recipes exercised through `JournalCatalogStore.starterQuestions`, `MetricCatalog.metric(key:source:)`, `Repository.mergeJournal(imported:native:)`, `BehaviorInsights.effect(behaviorDays:controlDays:outcomeByDay:)` and the Android twins, with synthetic rows; asserts unanswered days never become No controls, that explicit Yes/No days without a metric row never become outcome samples, and that unverified-source values never fill absent WHOOP coverage | Study-level prospective pre-lock and multi-day semantics under #76/#77; runtime parity and real-device verification (its head conflicts with current `main`) | **HOLD — test-only witness.** No production claim; the two-recipe evidence it collects is the input to the keep/drop decision in §7 |
| **#49 explicit-No repair** | The §4 invariant at the experiment snapshot call site: baseline days only from the intersection of outcome days, explicit-No days and days before the experiment start, then the newest N | It is not on `main` — verified live at §4's two call sites. Its own body notes an overlapping Android integration check on `InsightsScreen.kt` to re-check before composing | **HOLD, closed draft.** The invariant is the smallest bounded product slice and the honest candidate for the first code change under this contract |

**`fav/` successors already rebuilt on current `main`** (verified live at the time of writing —
all five are Draft PRs based on `main`): the #46 witness already has three,
[#110](https://github.com/kvnloo/noop/pull/110) `fav/test-journal-exercise-two-existing-h-46`
(both-platform persisted healthspan recipes),
[#168](https://github.com/kvnloo/noop/pull/168) `fav/journal-lag-witness-46`
(Journal next-morning lag across New Year) and
[#174](https://github.com/kvnloo/noop/pull/174) `fav/journal-clear-imported-46`
(imported Yes revealed after a native clear); the #49 repair already has two,
[#114](https://github.com/kvnloo/noop/pull/114) `fav/fix-experiments-count-only-explicit-49`
and [#169](https://github.com/kvnloo/noop/pull/169) `fav/intervention-finite-window-49`
(nonfinite days excluded from the intervention window). **#42/#43/#44 have no successor yet, and
this reconciliation is their RFC-0 disposition**: no stale assumption is forward-ported, and any
code slice graduates through §7's exit criteria on a fresh `fav/` branch, never as a rebase of the
held drafts.

## 6. Design invariants and acceptance gates (from #76)

- [ ] Compare #42 with **current upstream** Journal and `BehaviorInsights.effect(behaviorDays:controlDays:outcomeByDay:)`, Android `EffectRanker.effect`, `MetricCatalog.metric(key:source:)`, `JournalCatalogStore.starterQuestions`, native/import `mergeJournal` before porting any stale assumption. *(Partially satisfied by §4 of this slice; the rest is re-run per PR.)*
- [ ] Explicit Yes / explicit No / Skipped / **unanswered** are distinct. No unlogged day is a baseline control; no missing biometric metric becomes a failed intervention. Preserve `source`, import precedence, coverage and observation provenance. *(Live violation on `main` — §4.)*
- [ ] Lock the hypothesis and primary outcome **before exposure** using a durable/auditable identity (pure P0 type alone cannot prove preregistration). Amendments retain prior snapshots.
- [ ] Validate predeclared baseline/exposure/outcome unit, lag, sample/coverage threshold, effect direction, falsification and insufficiency; forbid non-finite measurements or inferred causality.
- [ ] Support studies spanning multiple wake-days and separately dated outcomes; do not reject valid two-week studies just because windows touch by calendar label.
- [ ] Portable fixtures on Swift/Kotlin (including leap-day, DST, timezones, duplicate imports, native/health-source conflicts, no observation, contradictory observation, ambiguous aliases); versioned backup/export when persistence is justified.
- [ ] First demonstrate **two independent genuine user workflows that cannot be expressed losslessly through existing Journal/Insights**; record result and alternatives in #76 before deciding whether P0 should ship.

## 7. Exit criteria — the smallest set of checks that lets a slice of this contract graduate

Graduation is through [oss-factory #212](https://github.com/kvnloo/oss-factory/issues/212)'s gates
(#94 rule 6): **M2+** for learning comparisons, **M3** for context/health signals, **M4** for
adaptive policy. The smallest set that would let a slice of this contract graduate:

1. **Two-workflow evidence.** Two independent genuine user workflows that cannot be expressed
   losslessly through existing Journal/Insights are demonstrated and recorded in #76, with the
   alternatives that were tried first. #46 is the synthetic collection point. *If both candidate
   recipes are expressible losslessly, the answer is to ship no contract and close P0.*
2. **Invariant repaired on `main`.** The §4 violation is fixed on both platforms from the
   explicit-No set, with pure deterministic selection helpers and matching synthetic vectors on
   Swift and Kotlin (explicit No vs missing/Yes, zero controls, last-N, No without an outcome), and
   cross-platform parity asserted by oracle rather than by eye.
3. **One bounded exploration receipt.** One comment per #94 rule 5: what was tried, what was
   observed (including "nothing"), what is now believed, and keep / revise / drop.
4. **Fork gates on the exact head.** `noop-channel gate isolate` on the slice's own head, then
   `gate union` on a throwaway union ref that is never pushed, never merged and never targeted. A
   suite that cannot run is `NOT_EXECUTED` and the verdict is `LIMITED`; a runner refusal is
   `BLOCKED` and names the owner action. Neither is ever recorded as `PASS`.
5. **Human burden is stated and bounded.** Reading one existing Journal question per day — the
   burden this slice adds is **0 new minutes/day**, because it reuses a question the user already
   answers and adds no screen, no notification and no background process. Any graduation that adds
   a surface must restate this line before it is accepted.
6. **Stop date.** This exploration stops on **2026-11-21**. If the two-workflow evidence in (1) is
   not collected by then, the exploration is dropped or re-scoped in #76, and the result is kept
   (#94 rule 5: negative results are kept, not deleted).
7. **Drop condition.** If existing Journal + Insights can express both candidate workflows
   losslessly — the direction #46's synthetic witnesses already point — drop the separate contract
   and keep the documentation-only note. A promising exploration is still not a roadmap item.

## 8. Evidence index

Every claim above is reproducible. Base: `kvnloo/noop` `main` at head
**`2a1de7609843b095505274ad6fc811757210be80`** — pinned by `git rev-parse HEAD` in the slice
checkout, `gh api repos/kvnloo/noop/commits/main --jq .sha` and
`gh api repos/ryanbr/noop/commits/main --jq .sha` (fork `main` is the exact mirror: all three
SHAs agree).

| Claim | How it was checked |
|---|---|
| The four #76 symbols exist on current `main` | `grep -rn` over `Packages/`, `Strand/`, `android/` (paths in §4) |
| The experiment baseline still counts unlogged days | `Strand/Screens/InsightsView.swift:814`; `buildExperimentSnapshot` in `android/app/src/main/java/com/noop/ui/InsightsScreen.kt` |
| The explicit-No set exists but is unused there | `InsightModel.controls` (`InsightsScreen.kt:143-145`); `controls` cache (`InsightsView.swift:43-45`, filled at `:427`) |
| Behavior Effects holds the invariant | `EffectRanker.kt:242`; `BehaviorInsights.swift:99` — a day in neither set is skipped |
| Runner state at the time of writing | `gh api repos/kvnloo/fleet-runner/actions/runs --jq '.workflow_runs[0]'` → latest run `42351795262` (union gate, head `8e711618d5`) conclusion `failure`; the gate's own verdict file `union-20261107T013344.json` records `BLOCKED` (platform cancel), so the runner is alive but budget-cancellable — a cancel is never booked as a red suite |

This slice itself is **not** covered by any executable gate: a documentation-only change matches no
suite in `noop-channels/gates.py`'s path table, so `noop-channel gate isolate` records
**`BLOCKED`** with the note "no executable suite matches this PR's paths" (`gates.gate_isolate`
refuses to claim a verdict it could not produce). That is recorded rather than papered over, and it
is never written as `PASS`.

## 9. Not in scope

Issue #76 forbids these, and this slice does not add any of them:

- a **second Journal**;
- a **second metric store**;
- a **protocol database**;
- a **product-level experiment dashboard**.

Additional explicit non-goals carried from #76: no new score, no Bayesian optimizer, no automatic
treatment recommendation, no hosted service, no cloud telemetry, no clinical efficacy claim, no
silent P0–P2 promotion. Production code, schema/migration changes, persistence, a scheduler, result
or effect calculation, causal inference, a response model and a recommendation system are all out of
scope for this slice. Upstream implementation ownership and contributors are preserved; upstream
posting stays owner-gated through `upstream-gate/gate propose`.

## 10. What this slice asks for

Nothing but a decision recorded in #76: given §4's live invariant violation and §7's exit criteria,
does the factory work the **explicit-No invariant** as the smallest bounded product slice, or first
collect the two-workflow evidence? Both are cheap; only one may be active at a time (#94 rule 1).
