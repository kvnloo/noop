# Intervention Catalog and Low-Risk N-of-1 Recipe Backlog (design slice)

**Issue:** [kvnloo/noop#84](https://github.com/kvnloo/noop/issues/84) · **Program:** [#94](https://github.com/kvnloo/noop/issues/94) research exploration, #4 of 5 (#76 → #77 → #85 → **#84** → #79)
**Kind:** documentation + one synthetic fixture. No production code, no schema, no second catalog store, no medical claims.
**Human burden of this slice:** 0 minutes/day. **Burden of running the first recipe (§6):** about 10 s/day answering one Journal question that already exists. Opt-in, off by default.
**Base:** `main` @ `2a1de7609843b095505274ad6fc811757210be80` (fork `main` == `ryanbr/noop` `main`).

This is a **recipe registry for future trials**, not a product feature and not a health instruction set. Per #84, the older Training Facility / Enhancement Arsenal / Habit Forge plans map onto existing NOOP primitives: **Journal factors + experiment recipes + external outcome contracts**. Nothing here says an intervention works. A row says only *how it could be tested and how the test would fail*.

> **No medical benefit, causal effect or individual efficacy is inferred from a recipe being in this catalog.**

---

## 1. Primitives this slice reuses (cited, not rebuilt)

| Primitive | Where on `main` | Used for |
|---|---|---|
| Journal starter questions | `Strand/Data/JournalCatalog.swift` `starterQuestions`; Android `android/app/src/main/java/com/noop/ui/JournalLog.kt` | The **user-entered** factor. Yes / No / Skipped / unanswered stay distinct |
| Behaviour Effects | `Packages/StrandAnalytics/.../BehaviorInsights.swift` `effect(behaviorDays:controlDays:outcomeByDay:...)`; Android `analytics/EffectRanker.kt` `effect(...)` | With-vs-without contrast whose controls are **explicit No days only** (the unlogged-is-not-No repair is already in this function) |
| Experiment card | `Strand/Screens/InsightsView.swift` ~L814 | **Not usable as a control yet.** Its baseline is "behaviour-absent days", which includes unlogged days. That is the live #76 §4 / #49 violation |
| Outcome keys | Insights `Outcome.key`: `recovery`, `hrv`, `sleep_performance`, `rhr` | The only prespecified outcomes allowed in Tier ≤ 2 rows (`sleep_efficiency` and `sleep_consistency` exist in `metricSeries` but are not Insights outcomes) |
| Wind-down nudge | `Strand/System/WindDownNudge.swift` (#207, opt-in, default OFF) | Context for recipe R1; the nudge is **not** an exposure log |
| Breathing | `Strand/Screens/BreathingView.swift`, `StrandAnalytics/BreathProtocolCatalog.swift` | Recipe R5. The session→Journal link is fork PR [#51](https://github.com/kvnloo/noop/pull/51) (Android only, not on `main`) |
| Prospective contract / receipts / study design | [#176](https://github.com/kvnloo/noop/pull/176) (#76), [#177](https://github.com/kvnloo/noop/pull/177) (#77), [#178](https://github.com/kvnloo/noop/pull/178) (#85) | Locking, lagged outcome objects, falsification-plan pattern |
| Synthetic Journal pilots | fork PR [#46](https://github.com/kvnloo/noop/pull/46) | Existing witnesses for late caffeine and screen-in-bed |

Upstream [ryanbr/noop#1760](https://github.com/ryanbr/noop/issues/1760) is context only and is not a target of this slice.

---

## 2. Recipe table (required columns)

Column rules (all reviewer-checkable):
- **Intervention/context** is **user-entered**: an existing Journal question, quoted verbatim, or `PROPOSED:` plus a question that does not exist yet. NOOP never infers it from wearable data and never auto-answers it.
- **Baseline/control** is **comparable explicit-No wake-days**: the same question answered No by the same person on wake-days with similar weekday, prior-night sleep and training load. **Unanswered or Skipped days are never controls.** Rows that cannot use explicit-No say what they use instead and why.
- **Outcome + lag** is fixed before the first exposure day. Lag runs from the exposure wake-day to the outcome wake-day.
- **Owner** is the existing NOOP component or external producer that holds the data. It is never a new store.

| ID | Intervention / context (user-entered) | Baseline / control | Prespecified outcome + lag | Owner | Tier (cap) | Mode | Disconfirming result |
|---|---|---|---|---|---|---|---|
| R1 | "Did you view a screen in bed?" (exists) | explicit-No answers to the same question on comparable wake-days | `sleep_performance`, next wake-day (lag 1) | Journal + Behaviour Effects | T2 | assignable | predicted Yes < No; disconfirmed if Yes−No ≥ 0 or Welch p ≥ 0.05 at minimum coverage |
| R2 | "Did you have caffeine late in the day?" (exists; **timing only**, no amount) | explicit-No answers on comparable wake-days | `sleep_performance`, lag 1; secondary `recovery`, lag 1 | Journal ([ryanbr/noop#759](https://github.com/ryanbr/noop/issues/759)) + Behaviour Effects | T2 | assignable (abstain-only) | as R1 (predicted Yes < No) |
| R3 | "Did you eat close to bedtime?" (exists) | explicit-No answers on comparable wake-days | `sleep_performance`, lag 1 | Journal + Behaviour Effects | T2 | assignable (abstain-only) | as R1 (predicted Yes < No) |
| R4 | "Did you read before bed?" (exists) | explicit-No answers on comparable wake-days | `sleep_performance`, lag 1 | Journal + Behaviour Effects | T2 | assignable | predicted Yes > No; disconfirmed if Yes−No ≤ 0 or p ≥ 0.05 |
| R5 | `PROPOSED:` "Did you finish a breathing session this evening?" (link per #51) | explicit-No evenings (no session) on comparable wake-days; **not** "planned but skipped" evenings, because skipping depends on how the evening went | `hrv`, lag 1; within-session HRV only if the quality flag passes | Breathing + Journal (#51) | T2 | assignable | predicted Yes > No on `hrv`; disconfirmed as R1 |
| R6 | `PROPOSED:` "Did you keep your usual bedtime ±30 min?" | the person's own explicit-No nights in the trailing 14–28 wake-days | `recovery`, lag 1 | Sleep + Journal | T2 | assignable | predicted Yes > No on `recovery`; disconfirmed as R1 |
| R7 | `PROPOSED:` "Did you do a structured workout today?" | explicit-No days matched on prior sleep and day-of-week; training load is a covariate, never ignored | `hrv`, `rhr`, lag 1 (separate rows; no pooled score) | NOOP Workouts + Journal | T2 | observe-only | difference in the prespecified direction absent, or CI includes 0 |
| R8 | "Did you use a sauna?" (exists) / `PROPOSED:` cold exposure | explicit-No days, same season (stop if the trial crosses a season boundary) | `sleep_performance`, lag 1; carry-over check at lag 2 | Journal + Insights | T2 | **observe-only** | direction prespecified at lock; a lag-2 effect larger than lag-1 means carry-over, so the result is *inconclusive* |
| R9 | spaced-retrieval schedule A vs B (external course) | randomized assignment of equivalent item families | unassisted retention at 24 h / 7 d / 30 d, plus transfer | external Anki/FSRS producer through the #77 outcome contract | T2 | assignable (external) | schedule A ≤ B at the prespecified primary lag |
| R10 | AI assistance on vs off | equivalent item families, randomized order | immediate score **and** delayed unassisted transfer (both prespecified) | external benchmark through #77 | T2 | assignable (external) | assisted ≤ unassisted on delayed transfer |

**Mode.** `assignable`: the user may schedule exposure vs no-exposure days because both arms are ordinary, low-risk choices (mostly abstaining from something). `observe-only`: NOOP only records what the user already chose and never suggests or schedules the exposure. Heat/cold and training are observe-only because assigning them carries physical risk and is not NOOP's call.

**Statistic (all rows):** the existing Behaviour Effects output (mean difference, Welch p, Cohen's d). No new statistics are added in this slice.

**Minimum coverage (all rows):** at least 5 exposure and 5 explicit-No control wake-days with a valid outcome row, and at least 70% of trial wake-days covered. Below that the verdict is **inconclusive / unknown**, never "no effect" and never "works".

**Confounds named per #84:** regression to the mean (do not start a trial right after an unusually bad week; use the predeclared start date), season and DST (no window may cross a DST change or season boundary), and training load (recorded covariate; R7 is observe-only for this reason). No automatic completion from wearable data.

The machine-readable copy is `docs/fixtures/intervention_catalog_recipes.json`. It is **synthetic**, contains no personal data, and is not imported by the app.

---

## 3. Evidence tier rule

Each row has one tier label, and the label is a **ceiling**.

| Tier | Label | Minimum to *display* this tier |
|---|---|---|
| T0 | unknown | default; no outcome or no coverage |
| T1 | anecdote / idea | user-entered factor exists; no prespecified outcome or lag |
| T2 | testable hypothesis | **prespecified outcome key + lag + explicit-No baseline + disconfirming result**, all written in the row before any exposure day |
| T3 | replicated human evidence (external) | T2, plus a citation (DOI or registry ID) in the row that the reviewer has opened |
| T4 | personal N-of-1 result | T2, plus a locked #76 contract (`prediction_locked_at` before day 1) and an adjudicated #77 receipt. The label shows the verdict (`supports` / `contradicts` / `inconclusive`) for **this person only** |

**The rule.** A recipe **may not exceed T1 until it has a prespecified outcome and lag.** It may not show T3 without an opened citation, or T4 without a receipt. T3 and T4 are independent: external replication never upgrades a personal verdict, and a personal verdict is never generalised to other users. This slice includes no citations, so **every row is capped at T2**.

The four categories #84 asks to keep apart map onto this table: anecdote = T1, mechanistic hypothesis = T2, replicated human evidence = T3, personalized observation = T4. "Unknown" (T0) is a valid answer and is shown as such.

---

## 4. Low-risk gate

**Excluded outright.** A row in any of these classes is rejected in review, not softened:

1. **Supplements, medication, pharmacology.** No dose, timing-of-dose, stack, cycle, titration or interaction rows. The existing Journal question "Did you take magnesium?" (category `.supplements`) stays a **user-entered annotation**. It is not a recipe, never `assignable`, and never gets a tier above T1.
2. **Treatment recommendations.** No "if metric X then do Y". The only conditional in a row is its disconfirming result, which is a statistical stop rule, not advice.
3. **Medical claims.** No row and no copy may say an intervention *improves, treats, prevents, reduces risk* or is *clinically proven*.
4. **Neuromodulation, fasting, sleep restriction, extreme heat/cold dosing,** and anything whose no-exposure arm or exposure arm needs medical supervision.
5. **Auto-answered factors.** A wearable reading never writes a Journal Yes or No.
6. **Assigned risk.** An exposure with physical risk is `observe-only` at most (R7, R8).

**How a reviewer checks it**, with three greps on this PR's files:

```sh
# 1. no excluded vocabulary in the recipe rows or fixture (expect: no output)
grep -inE 'dose|mg\b|stack|supplement|medicat|treat|prevent|cure|clinically|improves' docs/fixtures/intervention_catalog_recipes.json
# 2. no row uses unanswered/skipped days as control (expect: no output)
grep -inE '"baseline_control": *"[^"]*(unanswered|unlogged|skipped)' docs/fixtures/intervention_catalog_recipes.json
# 3. tier ceiling: every fixture row is tier <= 2 (expect: no output)
grep -nE '"evidence_tier": *[3-9]' docs/fixtures/intervention_catalog_recipes.json
```

Prose check: every `Intervention / context` cell is either a verbatim `starterQuestions` string or starts with `PROPOSED:`.

---

## 5. Exit criteria: graduating ONE recipe to product work

A recipe moves from this backlog to product work only through the [oss-factory #212](https://github.com/kvnloo/oss-factory/issues/212) gates, all of them:

1. **Invariant first.** The Experiment-card baseline (`InsightsView.swift` ~L814 and the Android `buildExperimentSnapshot`) selects **explicit-No** days only, with matching synthetic vectors on Swift and Kotlin. Until then, recipes are read through Behaviour Effects, not the Experiment card.
2. **Locked contract.** A #76 contract with outcome, lag, comparator, minimum coverage and stop date, locked before day 1.
3. **Adjudicated receipt.** A #77 receipt with verdict `supports` / `contradicts` / `inconclusive` from the predeclared rule. Low coverage gives `inconclusive`.
4. **Two-workflow test (#76 exit 1).** Graduation is needed only if the recipe needs something existing Journal + Behaviour Effects cannot express losslessly. If it can be expressed, ship nothing and keep the doc note. That is the expected result for R1–R4.
5. **Fork gates on the exact head.** `gate isolate`, then `gate union` on a throwaway union ref that is never pushed. `BLOCKED` or `NOT_EXECUTED` is recorded as such, never as `PASS`.
6. **Burden restated.** Any graduation that adds a screen, notification or background job restates minutes/day and stays opt-in.
7. **Stop date.** This exploration stops on **2026-11-21**. Null and negative results are kept (#94).

---

## 6. Cheapest falsifiable first trial

**R1: "Did you view a screen in bed?" → `sleep_performance`, lag 1, explicit-No controls.**

Why it is the cheapest:
- The question already exists in `starterQuestions` on both platforms.
- Behaviour Effects already uses explicit-No controls, so it needs **no code and no #49 repair**.
- The outcome key is already an Insights outcome.
- Fork PR #46 already has a synthetic witness for it.
- Both arms are ordinary and abstain-only, and the burden is about 10 s/day.

R2 and R4 tie on cost. R1 goes first because its no-exposure arm is the easiest to keep. R5 needs #51 on `main`. R9 and R10 need an external producer.

**Prediction (locked before day 1):** Yes-days (screen in bed) have lower next-wake-day `sleep_performance` than explicit-No days.
**Falsified if:** with at least 5 Yes and 5 explicit-No wake-days and at least 70% coverage, Behaviour Effects reports Yes−No ≥ 0, or Welch p ≥ 0.05 (the function's own test; no new statistics). With less coverage the result is *inconclusive*.

---

## 7. Not in scope

A second Journal, metric store, catalog store or health score. Production code, migrations or a scheduler. Causal inference, recommendations or dashboards. Supplement, medication or neuromodulation content of any kind. Upstream posting, which stays owner-gated through `upstream-gate/gate propose`.

## 8. Receipt

- **Verification:** each path, symbol and question string in §1–§2 was checked by grep against `main` @ `2a1de760`. The fixture was parsed as JSON and checked against the §4 greps and the §3 T2 ceiling.
- **Gates:** this PR is docs-only. GATE 1 `isolate` matches no executable suite and is recorded as **BLOCKED (docs-only)**, not as green. The verdict path is in the PR comment.
