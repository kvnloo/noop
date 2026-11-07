# Flow Shield Study Design — NOOP #85

**Status:** Research exploration — governed by #94 (one at a time, bounded, opt-in, ends with receipt). Not product work until graduated through oss-factory #212 gates.

**Scope:** Study design and falsification only. No production code, no modifications to uhabits PR #3 or ActivityWatch PR #2, no NOOP app changes.

**Branch:** `fav/flow-shield-study-design-85` (docs-only, based on `main` @ `2a1de760`)

---

## 1. Research Instrument — Field Provenance & Failure Modes

| Instrument Field | Source | Failure Mode |
|------------------|--------|--------------|
| `observedEpochMillis` (UTC observation timestamp) | uhabits PR #3 — captured separately from Loop's internal reminder clock at deferral/allow time | Clock skew between phone and AW export host; missed capture if broadcast receiver not invoked; timezone/DST misattribution if stored as local time |
| `reminderTime` (scheduled reminder UTC epoch) | uhabits PR #3 — Loop's due reminder timestamp | Loop's computed reminder time may drift after app restart; `scheduleAll()` overwrite risk (mitigated by `snoozeUntil` primitive in PR) |
| `deferredUntil` (lease expiry UTC epoch) | uhabits PR #3 — `snoozeUntil` persisted value | Lease expiry not persisted across phone reboot if snooze storage corrupted; `deferredUntil` == `reminderTime` indistinguishable from no-lease control |
| `shieldedFlag` (control=0 / shield=1) | uhabits PR #3 — experiment condition at observation time | Flag flipped by later preference change calling `reshowAll()` before snooze expires (mitigated by `NotificationTray.active` removal); debug-only receiver absent in release builds |
| `afkAggregate` (AFK/window aggregate seconds) | ActivityWatch PR #2 — `research_flow_metrics.py` offline export | AW Android v0.14 push-only sync: desktop AFK buckets never reach phone; AFK gaps censored as missing, not zero |
| `windowAggregate` (active window/context group duration) | ActivityWatch PR #2 — user-defined context groups from AW export | No raw window titles/URLs exported; context groups user-defined and coarse; desktop buckets not on phone via aw-sync |
| `coveredDuration` (telemetry coverage seconds in analysis window) | ActivityWatch PR #2 — observation coverage metric | Gaps from AW crash, permission loss, or phone-off censored; coverage < 100% → outcomes marked censored, not zero-switch |
| `censoringFlag` (coverage gap / missing boundary) | ActivityWatch PR #2 — explicit censoring in intervention analyzer | Unknown = missing, not "no switch"; if telemetry absent, result is *missing*, not a zero-switch victory |
| `switchesWithin2min` (context switches in 2-min post-reminder window) | ActivityWatch PR #2 — `research_flow_intervention_analysis.py` pre-10m/post-2m join | Switch definition depends on context-group granularity; false low count if groups too coarse; false high if AW bucket split artifacts |
| `fragmentationRate` (Simpson fragmentation index / switches per active hour) | ActivityWatch PR #2 — behavioral proxy, not flow score | Simpson index sensitive to context-group cardinality; not comparable across users with different group definitions |
| `sustainedDwell` (median/max sustained-context block duration) | ActivityWatch PR #2 — sustained-context blocks from AW intervals | AW interval splitting at local midnight (UTC elapsed-time arithmetic) may fragment true blocks across DST boundaries |
| `resumptionLatency` (return-to-context latency after interruption) | ActivityWatch PR #2 — return-to-context latency metric | Requires detectable return to *same* context group; fails if user switches to new task (not a return) or context group undefined |
| `selfRatedFlow` (optional 1–5 / VAS self-report) | NOOP #77 / #85 — external learning/flow observation bridge | Opt-in, sparse, recall-biased; not synchronized to reminder timestamp; no validated flow score constructed |
| `taskOutcome` (optional self-rated task completion/quality) | NOOP #77 — lagged outcome receipts | Same sparsity/recall issues; not causally linked to shield condition without within-person randomization |

---

## 2. Falsification Plan

**Core claim to falsify:** *A short (1–120 min) focus lease that defers eligible habit reminders reduces interruption/fragmentation without materially harming habit adherence.*

### Falsification Criteria (any one sufficient to stop believing the shield helps)

| # | Observation | Why It Falsifies |
|---|-------------|------------------|
| F1 | **Primary endpoint null/harm:** In the within-person A/B (randomized shield/control per eligible work block), `switchesWithin2min` **does not decrease** under shield vs control (95% CI includes zero or positive effect), **AND** `fragmentationRate` shows no reduction. | The intervention's direct mechanism (fewer immediate context switches after a deferred reminder) is absent. |
| F2 | **Habit adherence harm:** `YES_MANUAL` rate (explicit manual completions) **decreases** under shield vs control by >5 pp (within-person, 95% CI excludes zero), **OR** `SKIP`/`NO` rate increases correspondingly. | Deferral converts reminders into missed habits — the shield trades interruption for non-adherence. |
| F3 | **No dose-response:** `switchesWithin2min` reduction (if any) **does not correlate** with lease duration (1–120 min) or `coveredDuration` (telemetry coverage). | Effect is not driven by the lease mechanism; could be placebo, Hawthorne, or confounding. |
| F4 | **Carryover contamination:** In crossover sequences, shield→control periods show **persistently lower** switches vs control→shield (sequence effect > treatment effect). | The "shield" effect is actually a learning/habituation artifact, not a causal lease effect. |
| F5 | **Coverage-censored dominance:** >30% of shield-period reminder events are **censored** (missing AW telemetry), and the uncensored subset shows no effect. | The apparent effect (or null) is driven by selection bias — missing not at random. |
| F6 | **Self-report divergence:** `selfRatedFlow` and `taskOutcome` (optional, sparse) show **no improvement** or **worsening** under shield despite behavioral proxy improvement. | Behavioral proxies (switches, fragmentation) decouple from subjective experience — the shield "works" on metrics but not on the human outcome it purports to serve. |

### Non-Falsifiable → Explicitly Acknowledged

If the study **cannot** be run (no physical device, no consent, AW export unavailable, Loop export unavailable), the result is **inconclusive** — not evidence for or against. The design documents this as a censored trial, not a failed one.

---

## 3. No Flow Score — Explicit Statement & Offsetting Measure

> **We do not ship, compute, or validate a "flow score."**

> All metrics reported are **behavioral proxies** derived from low-content ActivityWatch telemetry and content-free Loop reminder observations:
> - `switchesWithin2min`, `fragmentationRate`, `sustainedDwell`, `resumptionLatency` — event-based, observable, censored when telemetry missing
> - `selfRatedFlow`, `taskOutcome` — optional, sparse, self-reported, **not** combined into an index

> **No aggregation, weighting, normalization, or thresholding** across these fields produces a single "flow score." Any dashboard or summary showing a composite number is explicitly out of scope for this exploration and would require a separate validation study (clinical/causal, not observational).

### Offsetting Measure We DO Report

**Within-person randomized A/B effect estimates with censoring-aware confidence intervals** for each proxy separately:
- Primary: `switchesWithin2min` (shield vs control, per eligible reminder event)
- Secondary: `fragmentationRate`, `sustainedDwell`, `resumptionLatency` (same design)
- Exploratory: `selfRatedFlow`, `taskOutcome` (separately collected, not joined to reminder events)
- Coverage & censoring rates reported **per condition** (shield/control) and **per analysis window** (pre-10m, post-2m, 10m, 30m)

This satisfies the "offsetting measure" requirement: we report **causal-effect estimates on observable proxies** with honest uncertainty, not a validated latent construct.

---

## 4. Exit Criteria → oss-factory #212 Gates

| Gate | Criterion | Evidence Required | #212 Gate Mapping |
|------|-----------|-------------------|-------------------|
| **G1: Instrument Integrity** | All 14 instrument fields have documented provenance (table above) and failure modes; synthetic fixture rows exercise each field's happy path + each failure mode | `docs/FLOW_SHIELD_STUDY_DESIGN.md` (this file) + synthetic fixtures in `docs/fixtures/` | Gate 1 `isolate` — docs-only, no executable suite → **BLOCKED** (honest verdict) |
| **G2: Falsification Pre-registration** | F1–F6 criteria declared before any data collection; analysis script (`research_flow_intervention_analysis.py`) version-pinned to AW PR #2 head | AW PR #2 `scripts/research_flow_intervention_analysis.py` + this doc's falsification table | Gate 1 `isolate` — same as G1 |
| **G3: Coverage & Censoring Honesty** | Analysis outputs `censored` flag per event; summary reports coverage % per condition; no imputation of missing as zero | AW PR #2 intervention analyzer contract (censors unknown, fails closed on malformed) | Gate 2 `union` — requires real AW export + Loop export fixture |
| **G4: Within-Person Randomization** | Eligible work blocks predeclared; shield/control randomized per block; sequence balanced; carryover tested | Protocol in uhabits PR #3 `docs/FOCUS_SHIELD_EXPERIMENT.md` + this doc | Gate 2 `union` — requires physical device + consent |
| **G5: No Flow Score Shipment** | Explicit "no flow score" statement (Section 3) in this doc; no composite index in any analysis output | This document + AW PR #2 output schema (no composite field) | Gate 1 `isolate` — docs-only |
| **G6: Receipt & Graduation** | Study ends with a receipt: head SHA, isolate verdict, union verdict (or BLOCKED reason), single cheapest observation that would change next build decision | PR comment on this card with: Draft PR URL, head SHA, branch, isolate verdict path, cheapest observation line | Gate 3 `promote` — only after G1–G5 satisfied |

> **Governance compliance (#94):**
> - One exploration at a time: this is #3 in chain (#76 → #77 → **#85** → #84 → #79); #76 and #77 Draft PRs (#176, #177) are done with isolate verdicts recorded.
> - Bounded: **0 new minutes/day** human burden (debug ADB lease only, opt-in, no background sync, no release-build receiver).
> - Ends with a receipt: see G6 above.

---

## 5. Synthetic Fixture Rows (docs-only)

```
docs/fixtures/
├── reminder_observation.csv       # observedEpochMillis, reminderTime, deferredUntil, shieldedFlag
├── context_summary.csv            # afkAggregate, windowAggregate, coveredDuration, censoringFlag
├── outcomes.csv                   # switchesWithin2min, fragmentationRate, sustainedDwell, resumptionLatency, selfRatedFlow, taskOutcome
└── coverage_censoring.csv         # condition, window, coverage_pct, censored_count, total_count
```

Each fixture contains 3 rows: (happy path, failure mode A, failure mode B) per the failure mode column in Section 1. No PII, no real timestamps, no health data.

---

## 6. Reconciliation with Upstream Slices

| Reference | Reconciliation |
|-----------|----------------|
| **noop#76** (PR #176) | Experiment contract provides the prospective framework; this slice operationalizes its instrument fields and adds the falsification plan |
| **noop#77** (PR #177) | `measurement_kind` (5th-day question) is the branch point: this slice uses `behavioral-proxy` kind; `self-rated-flow` and `task-outcome` kinds are downstream |
| **noop#70** | Integration inventory documents the ADB debug path (valid) and aw-sync push-only block (stop condition); no new transport added here |
| **noop#94** (RFC) | Rules 1–3 satisfied: one at a time (chain position 3), bounded (0 min/day, opt-in, stop rule), ends with receipt |
| **uhabits#3** | Provides reminder observation fields + debug ADB lease; release builds inert; content-free timestamps only |
| **activitywatch#2** | Provides context summary + outcome proxies; offline join; censorship rules; no raw titles; DST-safe |

---

## 7. Cheapest Observation That Would Change Next Build Decision

> **If F1 is observed (primary endpoint null/harm in a within-person A/B with adequate coverage), we stop building the distraction shield and reallocate to the next exploration (#84).**

This is the single cheapest observation: one completed within-person A/B cycle (≈10–20 eligible reminders per condition) with >70% telemetry coverage, analyzed by the existing AW PR #2 intervention analyzer. No new code, no new transport, no NOOP integration required.