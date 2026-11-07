# Flow and Distraction-Shield Experiment — Study Design Slice

**Issue:** [kvnloo/noop#85](https://github.com/kvnloo/noop/issues/85)  
**Parent RFC:** [kvnloo/noop#94](https://github.com/kvnloo/noop/issues/94) — Limitless research exploration program  
**Chain position:** #3 of 5 (#76 → #77 → **#85** → #84 → #79)  
**Governance:** One exploration at a time; bounded and opt-in; ends with a receipt  
**Burden:** 0 new minutes/day (debug ADB lease only; no background process, no Journal prompts)  
**Status:** Study design and falsification only — no production code, no validated "flow score"

---

## 1. Research Instrument → Source → Failure Mode Table

| Instrument Field | Source (PR / Component) | Failure Mode |
|------------------|-------------------------|--------------|
| **Reminder Observation Fields** | | |
| `observedEpochMillis` (UTC) | uhabits PR #3 — `FocusShieldController.logEligibleReminderOutcome` | Clock skew / NTP drift on device → misaligned join with AW buckets; mitigated by UTC anchor captured at observation time, separate from Loop's internal reminder clock |
| `reminderTime` (scheduled UTC) | uhabits PR #3 — `Reminder.scheduledAt` persisted through snooze | `scheduleAll()` overwrite on restart (fixed by `snoozeUntil` primitive in commit `16fbd72`); if persistence fails, observation missing not invented |
| `deferredUntil` (lease expiry UTC) | uhabits PR #3 — `FocusShieldController.setLeaseMinutes` → `snoozeUntil` | Lease expiry not persisted across restarts (fixed by `snoozeUntil`); if lease lost, reminder fires → recorded as **control**, not shield |
| `shieldedFlag` (boolean) | uhabits PR #3 — `FocusShieldController.isActive()` at eligibility gate | Debug broadcast receiver absent in release builds → flag always `false`; experiment only runs in debug source set, release = no-op |
| **Context Summary Fields** | | |
| `afkAggregate` (AFK/non-AFK seconds) | ActivityWatch PR #2 — `scripts/research_flow_metrics.py` AFK bucket join | Watcher not running → bucket stale → **censor: watcher-not-running** (never read as AFK); graphical-session.target inactive = clean exit 0, no retry |
| `windowAggregate` (app/window seconds, titles redacted) | ActivityWatch PR #2 — window bucket join, local privacy filter `[redacted]` | Same watcher failure mode as AFK; cross-device: Android AW v0.14 **push-only**, no desktop→phone bucket pull → coverage gap = stop condition |
| `coveredDuration` (seconds with valid telemetry) | ActivityWatch PR #2 — `observation_coverage` metric per day | Stale bucket = coverage miss, not null observation; minimum covered duration stop rule per RFC #94 rule 2 |
| `censoring` (enum: `none`, `watcher-not-running`, `dst-transition`, `malformed-interval`) | ActivityWatch PR #2 — DST split at local midnight, malformed intervals fail closed | DST 23/25-hour days handled by UTC elapsed-time arithmetic; corrupt reminder-log prefixes rejected; censored days excluded from denominator |
| **Outcome Fields** | | |
| `switchesWithin2Min` (count) | ActivityWatch PR #2 — `switches_at_reminder_boundary` (pre-10m / post-2m/10m/30m windows) | Missing pre/post boundary → censored, never invented as zero; join requires both Loop reminder event + AW window bucket |
| `fragmentationRate` (Simpson index / active hour) | ActivityWatch PR #2 — `simpson_fragmentation` proxy | Low coverage → high variance; reported with `coveredDuration` denominator, not clock hours |
| `sustainedDwell` (median dwell, sustained-context blocks ≥10min) | ActivityWatch PR #2 — `median_dwell`, `sustained_context_blocks` | Context groups user-defined; raw titles never emitted; if AW bucket missing → censored |
| `resumptionLatency` (return-to-context seconds, pre/post 10m/30m) | ActivityWatch PR #2 — `return_to_context_latency` | Requires identifiable context group; if group changes or bucket gap → censored |
| `selfRatedFlow` (optional, 1–5) | NOOP — future Journal/manual factor bridge via #70 | **Not in this slice**; explicitly opt-in, separate consent, never completes a habit |
| `taskOutcome` (optional, categorical) | NOOP — future Journal/manual factor bridge via #70 | **Not in this slice**; same as above |

---

## 2. Falsification Plan

### Primary Hypothesis
> A short (1–120 min) focus lease that defers eligible habit reminders **reduces** the primary proxy `switchesWithin2Min` after reminder eligibility, without increasing `fragmentationRate` or `resumptionLatency` in the post-window periods.

### Falsification Criteria (any one stops the exploration)
| Criterion | Measurement | Threshold |
|-----------|-------------|-----------|
| **No reduction in primary proxy** | Within-person A/B: `switchesWithin2Min_shield` vs `switchesWithin2Min_control` | 95% CI of mean difference includes 0 **or** shield mean ≥ control mean |
| **Harm on secondary proxies** | `fragmentationRate_shield` > `fragmentationRate_control` **or** `resumptionLatency_shield` > `resumptionLatency_control` | Statistically significant (within-person, p < 0.05) in wrong direction |
| **Coverage collapse** | `coveredDuration` < 4 hours/day on > 30% of randomized days | Exploration stops per RFC #94 rule 2 (stop rule written before start) |
| **Watcher silent failure undetected** | `censoring == watcher-not-running` on > 20% of days | Instrument invalid — AW is not a reliable proxy surface |
| **Carryover contamination** | Sequence effect (shield→control vs control→shield) significant in crossover | Design invalid — washout insufficient |

### What Would Make Us Stop Believing the Shield Helps
**The single cheapest observation:** A single randomized session where `switchesWithin2Min` under **shield** is **≥** the control session's value, with `coveredDuration` ≥ 4 hours and `censoring == none` for both. If the shield does not reduce the primary proxy in a clean, covered observation, the mechanism hypothesis fails.

> **Note:** This is a *within-person* crossover. The falsification is per-participant, not population-aggregate. The exploration publishes exact SHA/CI data from the debug A/B fixture run before any claim of effect (per issue acceptance criteria).

### Non-Falsifiable Elements (Explicitly Acknowledged)
- `selfRatedFlow` and `taskOutcome` are **not measured in this slice** — they require the NOOP Journal/manual factor bridge (#70) and external outcomes (#77), which are downstream gates.
- No causal claim about "flow" as a cognitive state — only behavioral proxies (switches, fragmentation, dwell, latency).
- ActivityWatch is a **proxy for computer interaction**, not ground-truth attention or cognition (per issue body).

---

## 3. No Flow Score — Explicit Statement and Offsetting Measure

> **This exploration does not ship, compute, or validate a "flow score."**

### What We Do Not Do
- No composite index combining switches, fragmentation, dwell, latency into a single number.
- No threshold labeling a session "in flow" or "out of flow."
- No dashboard gauge, no notification, no Journal auto-answer, no habit completion from shield.
- No claim that reducing `switchesWithin2Min` equals "increasing flow."

### What We DO Report (Offsetting Measures)
| Measure | Purpose | Validation |
|---------|---------|------------|
| **Per-proxy point estimates + within-person 95% CI** | Transparent effect size on each behavioral proxy | Bootstrap (10k resamples) on paired shield/control sessions |
| **Coverage report** (`coveredDuration`, `censoring` breakdown) | Denominator transparency — missing ≠ zero | Every observation day tagged; stop rule enforced |
| **Censoring audit trail** | Distinguish "user was focused" from "watcher was down" | `censor: watcher-not-running` vs `censor: none` |
| **Sequence/carryover diagnostics** | Crossover validity | Mixed-effects model with period + sequence terms |
| **Exact SHA + CI run data** | Reproducibility gate before any effect claim | Per issue acceptance: "publish exact SHA/CI data before any claim of effect" |

These measures graduate through **oss-factory #212 gates** (isolate → union → promote) — not through a "flow score" threshold.

---

## 4. Exit Criteria → oss-factory #212 Gates

| Gate | Criteria | Artifact |
|------|----------|----------|
| **GATE 1: isolate** | Docs-only PR; no executable suite matches → **BLOCKED** (verdict recorded, not claimed green) | Verdict JSON at `/mnt/zer0models/workspace/zer0/data/operations/noop-channels/runs/gates/isolate-pr<NNN>-<SHA>.json` |
| **GATE 2: union** | Throwaway union ref built from open PR heads (never merged); MBP host lane; `pushed:false` | Union verdict JSON + ref name |
| **GATE 3: promote** | Falsification criteria pre-registered; debug A/B fixture run completed on real device (ADB lease + AW export); exact SHA + CI data published as PR comment; coverage ≥ 4h on ≥ 70% of randomized days; no carryover contamination | PR comment with SHA, CI run URL, coverage table, falsification result |
| **Receipt** | Exploration ends with a receipt per RFC #94 rule 3: one Markdown file summarizing design, data, falsification outcome, and the single cheapest observation that would change the next build decision | `docs/FLOW_SHIELD_RECEIPT.md` (created at promotion) |

### Prerequisites (from upstream slices)
- #76 **Experiment Contract** (PR #176): prospective contract verbatim, unresolved fields, receipt — **DONE**
- #77 **Lagged Outcome Semantics** (PR #177): 16-field outcome object, immutable receipt/observation separation, 5th-day question (`measurement_kind`) — **DONE**
- #70 **Integration Inventory/Transport**: ADB debug path documented, aw-sync push-only limitation acknowledged, authorization gates for production transport — **CITED**

### Not In Scope (per RFC #94 and issue body)
- No release-build broadcast transport or automatic background sync
- No Android ActivityWatch desktop→phone bucket pull (v0.14 push-only)
- No causal benefit claim from a dashboard screenshot
- No second collector / background process in NOOP core
- No raw window titles, URLs, keystrokes, screenshots, OCR, medical/health identifiers
- No habit completion from deferred reminders (shield ≠ complete)

---

## 5. Synthetic Fixture Rows (for CI verification)

```json
// docs/fixtures/flow_shield_observation.jsonl
{"observedEpochMillis":1794062623000,"reminderTime":1794062620000,"deferredUntil":1794066220000,"shieldedFlag":true,"afkAggregate":120,"windowAggregate":3480,"coveredDuration":3600,"censoring":"none","switchesWithin2Min":0,"fragmentationRate":0.12,"sustainedDwell":420,"resumptionLatency":15}
{"observedEpochMillis":1794062623000,"reminderTime":1794062620000,"deferredUntil":0,"shieldedFlag":false,"afkAggregate":60,"windowAggregate":3540,"coveredDuration":3600,"censoring":"none","switchesWithin2Min":3,"fragmentationRate":0.31,"sustainedDwell":180,"resumptionLatency":45}
{"observedEpochMillis":1794149023000,"reminderTime":1794149020000,"deferredUntil":0,"shieldedFlag":false,"afkAggregate":0,"windowAggregate":0,"coveredDuration":0,"censoring":"watcher-not-running","switchesWithin2Min":null,"fragmentationRate":null,"sustainedDwell":null,"resumptionLatency":null}
```

> Fixtures are synthetic, no personal data. Used only for schema validation and join logic tests in the fork PRs (uhabits #3, ActivityWatch #2). Not imported into NOOP.

---

## 6. Reconciliation with Held Drafts and Issues

| Reference | Reconciliation |
|-----------|----------------|
| **noop#76** (PR #176) | Experiment contract provides the prospective framework; this slice operationalizes its instrument fields and adds the falsification plan |
| **noop#77** (PR #177) | `measurement_kind` (5th-day question) is the branch point: this slice uses `behavioral-proxy` kind; `self-rated-flow` and `task-outcome` kinds are downstream |
| **noop#70** | Integration inventory documents the ADB debug path (valid) and aw-sync push-only block (stop condition); no new transport added here |
| **noop#94** (RFC) | Rules 1–3 satisfied: one at a time (chain position 3), bounded (0 min/day, opt-in, stop rule), ends with receipt |
| **uhabits#3** | Provides reminder observation fields + debug ADB lease; release builds inert; content-free timestamps only |
| **activitywatch#2** | Provides context summary + outcome proxies; offline join; censorship rules; no raw titles; DST-safe |

---

## 7. Single Cheapest Observation That Changes Next Build Decision

> **A single clean randomized session (coveredDuration ≥ 4h, censoring = none) where `switchesWithin2Min_shield ≥ switchesWithin2Min_control`.**  
> If the shield does not reduce the primary proxy in one verifiable observation, the mechanism hypothesis fails and the exploration stops — no union gate, no promotion, receipt records the null result.

---

*Generated for kvnloo/noop#85 — study design and falsification slice only.  
No production code. No validated "flow score."  
Additive, reversible, privacy-safe.  
Draft PR → `kvnloo/noop` `main` on branch `fav/flow-shield-study-design-85`.*