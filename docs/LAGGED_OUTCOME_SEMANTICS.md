# Lagged outcome receipts and external learning/flow observation bridge — semantics slice

**Status: Draft design slice. Not accepted, not approved to merge, and not product work.**
This document is the written semantics, reconciliation and exit criteria requested by issue
[#77](https://github.com/kvnloo/noop/issues/77). It adds no code, no schema, no store and no UI.
Everything below is either (a) quoted from the issue it cites, or (b) an observation made against
`kvnloo/noop` `main` at the pinned SHA named in §9. A claim without its source is not made.

This slice is governed by [#94](https://github.com/kvnloo/noop/issues/94) as a *research exploration*:
one exploration active, bounded before it starts, no new platform, human in charge, one receipt at the end.

---

## 1. Purpose and provenance

The historical **epistemic firewall** asks a concrete question: what was predicted, what was
actually observed later, and how much evidence survives scrutiny? NOOP already has daily metrics,
Journal and Lab Book. The genuinely different use case is **multiple timestamped external scalar
outcomes per session/day** (learning, task timing, focus) with explicit measurement protocol.

This slice owns the **proposed semantics and exit criteria** for that bridge — not duplicate
production code. It separates an **immutable-ish experiment receipt** (the locked hypothesis,
baseline/exposure/outcome windows, source provenance, effect and uncertainty, confounders,
missingness/censoring, analysis time/version, and a verdict of `supports | contradicts |
inconclusive`) from the **external outcome observations** that reference it.

Related records:
- P0 experiment contract [#42](https://github.com/kvnloo/noop/pull/42) (held, base `staging`)
- P1 receipts [#43](https://github.com/kvnloo/noop/pull/43) (held, stacked on #42)
- P2 external observations [#44](https://github.com/kvnloo/noop/pull/44) (held, stacked on #43)
- #46 synthetic Journal witness (held)
- #49 explicit-No repair (held, closed draft)
- Existing PR ledger [#71](https://github.com/kvnloo/noop/issues/71)
- Cross-OSS inventory [#70](https://github.com/kvnloo/noop/issues/70)
- Research program [#94](https://github.com/kvnloo/noop/issues/94)
- Factory GitOps RFC-0 [#103](https://github.com/kvnloo/noop/issues/103)
- Human Capability Loop master RFC [oss-factory #212](https://github.com/kvnloo/oss-factory/issues/212)
- The #76 design slice: PR [#176](https://github.com/kvnloo/noop/pull/176) branch
  `fav/experiment-contract-design-76` (head `b3c4478bfdffffda61e02234cac7a55efcfa11db`)

---

## 2. The outcome object — field by field with validation rules

The following table defines every field in the `ExternalOutcomeObservation` object proposed by
#77, its validation rule, and what happens when the field is missing or invalid. The field order
and naming match the issue verbatim.

| # | Field | Type / format | Validation rule | Missing / invalid → behavior |
|---|-------|---------------|-----------------|------------------------------|
| 1 | `observation_id` | Stable opaque ID (UUID v4 or ULID) | Must be globally unique; regex `^[0-9a-fA-F-]{36}$` or ULID pattern; collision on ingest → **reject entire import** | **Reject** — no observation without a stable identity |
| 2 | `observed_at_utc` | Unix epoch milliseconds (UTC) | Finite integer; must not be in the future beyond a configurable clock-skew tolerance (default 5 min); `observed_at_utc <= ingested_at_utc + skew_tolerance` | **Reject** — clock skew beyond tolerance is not silently corrected |
| 3 | `ingested_at_utc` | Unix epoch milliseconds (UTC) | Finite integer; set by NOOP at ingest time; `ingested_at_utc >= observed_at_utc - skew_tolerance` | **Reject** — ingest timestamp is authoritative for ordering |
| 4 | `local_wake_day` + `timezone` | `YYYY-MM-DD` + IANA tz name (e.g. `America/Los_Angeles`) | `local_wake_day` must be the wake-day that contains `observed_at_utc` under the given timezone's midnight-to-midnight, with DST transitions handled by the IANA zone; `timezone` must resolve in the current IANA database | **Reject** — wake-day is a join key; ambiguity is not resolved by heuristic |
| 5 | `outcome_key` | Stable string key (e.g. `anki.retrievability.24h`, `activitywatch.fragmentation.index`, `pvt.reaction_time.ms`) | Must match a registered external outcome key in the *external outcome catalog* (separate from `MetricCatalog`); key format: `source.metric[.qualifier]`; no spaces, lowercase, dot-separated | **Reject** — unregistered keys cannot be joined to a receipt or analyzed |
| 6 | `value` | Finite numeric (IEEE-754 double) | `isfinite(value) == true`; `NaN`, `Infinity`, `-Infinity` → **reject** | **Reject** — non-finite values are never coerced, imputed or treated as zero |
| 7 | `unit` | Explicit unit string (UCUM or SI derived, e.g. `ms`, `s`, `ratio`, `percent`, `count`, `index`) | Must be a known unit in the unit registry; unit is **not inferred** from the key | **Reject** — a value without an explicit unit is not a measurement |
| 8 | `source_id` | Stable source identifier (e.g. `anki`, `activitywatch`, `uhabits`, `obsidian-learning-engine`, `reaction-trainer`, `freefocusgames`) | Must be a registered source in the external source catalog; source owns the measurement protocol and native record format | **Reject** — orphan observations cannot be traced to protocol or consent |
| 9 | `source_native_record_id` | Optional opaque string | If present, must be unique within the `source_id` namespace; used for idempotent re-import and duplicate detection | **Accept (optional)** — absence means deduplication falls back to `(source_id, outcome_key, observed_at_utc, local_wake_day)` composite |
| 10 | `session_id` | Optional opaque string | If present, groups observations from the same logical session (e.g. one Anki review session, one PVT block); no cross-source semantics | **Accept (optional)** — absence means no session grouping |
| 11 | `measurement_protocol` / `version` | String + semver (e.g. `fsrs-v4.1.0`, `activitywatch-bucket-v0.14`, `pvt-standard-10min.v1.2`) | Required; `protocol` must be registered for the `source_id`; `version` must parse as semver; protocol defines the measurement procedure, sampling, aggregation and uncertainty characterization | **Reject** — protocol/version is the contract for comparability across imports |
| 12 | `data_coverage` | Finite numeric in `[0, 1]` | `isfinite(data_coverage) && data_coverage >= 0 && data_coverage <= 1`; represents the fraction of the intended measurement window that was actually observed (e.g. 0.93 = 93% of the 10-min PVT block captured) | **Reject** — coverage outside `[0,1]` or non-finite is not a valid measurement |
| 13 | `sample_count` | Non-negative integer | `sample_count >= 0` and finite; the number of raw samples/epochs/trials that produced the aggregated `value` | **Reject** — negative or non-integer sample counts are invalid |
| 14 | `measurement_kind` | Enum: `measurement` \| `inference` \| `projection` | Exactly one of the three; `measurement` = directly observed; `inference` = derived by a stated model (e.g. FSRS retrievability); `projection` = forecast/extrapolation beyond observed window | **Reject** — missing or unknown kind breaks receipt verdict logic |
| 15 | `consent` | Enum: `granted` \| `revoked` \| `unknown` | Must be one of the three; `revoked` observations are **excluded from all analysis and receipts** but retained in the import log for audit; `unknown` is treated as `revoked` for analysis, `granted` for re-import idempotency | **Reject** — consent state must be explicit; no default to `granted` |
| 16 | `recipe_version` | Semver string (e.g. `1.0.0`, `2.3.1-beta.2`) | Must parse as semver; identifies the exact transformation recipe that produced this observation from the source's native export (e.g. the Anki→NOOP CSV transform script version) | **Reject** — without recipe version, re-import cannot guarantee bitwise stability |

**Validation summary:**
- **Hard rejects (entire observation dropped, logged with reason):** fields 1–8, 11, 12, 13, 14, 16
- **Soft rejects (observation accepted but flagged):** field 9, 10 (optional), 15 (`unknown` → treated as `revoked` for analysis)
- **No coercion, no imputation, no silent defaults.** Every invalid observation is a hard error in the import pipeline; the pipeline produces a rejection report, not a cleaned dataset.

---

## 3. Immutable-ish experiment receipt — separation from observations

The issue requires a clear separation between the **experiment receipt** (the locked hypothesis and its adjudication) and the **external outcome observations** that feed it.

### 3.1 What the receipt contains (immutable-ish snapshot)

| Receipt field | Source | Mutability |
|---------------|--------|------------|
| `experiment_id` | From P0 contract (§2 of #76 slice) | Immutable after `prediction_locked_at` |
| `title`, `hypothesis`, `canonical_factor_key`, `primary_outcome_metric_key` | P0 contract | Immutable after lock |
| `study_unit` (wake-day \| session) | P0 contract | Immutable after lock |
| `baseline_window`, `exposure_window`, `outcome_window` + `lag` | P0 contract | Immutable after lock |
| `predicted_direction`, `minimum_coverage`, `minimum_samples` | P0 contract | Immutable after lock |
| `predeclared_support_and_falsification_rule` | P0 contract | Immutable after lock |
| `prediction_locked_at` (Unix ms UTC) | P0 contract | Immutable; proves hypothesis existed before exposure |
| `analysis_recipe_version` | P0 contract | Immutable; identifies the exact analysis code |
| `source_identity/version` | P0 contract | Immutable; source of factor/metric data |
| `consent/export_policy` | P0 contract | Immutable |
| **Embedded outcome observations** | References to `observation_id`s from §2 | **Append-only** — new observations may be added *before* the outcome window closes; after close, the set is frozen |
| `effect_estimate` (finite numeric) | Computed at analysis time | Immutable once computed |
| `uncertainty_interval` (lower, upper, level e.g. 0.95) | Computed at analysis time | Immutable once computed |
| `confounders` (structured annotations) | Declared at lock; may be amended with timestamped addenda before analysis | Append-only addenda |
| `missingness_censoring` (structured) | Computed from coverage/sample_count of linked observations | Immutable once computed |
| `analysis_time_utc` / `analysis_version` | Set at analysis time | Immutable |
| `verdict` | Enum: `supports` \| `contradicts` \| `inconclusive` | Immutable; derived by the predeclared rule |

### 3.2 What the receipt does NOT contain

- Raw observation payloads (the receipt references `observation_id`s only)
- Any mutable derivation, UI state, or presentation logic
- A proof that the prediction existed before exposure — that requires **independent persistence** of the contract before `prediction_locked_at` (e.g. a signed hash, a write-once log, or an external timestamping service). The pure type alone cannot prove preregistration.

### 3.3 The separation invariant

> **An experiment receipt is a snapshot of a locked hypothesis plus the observations that bear on it. The observations exist independently, are ingested on their own schedule (lagged, out-of-order, possibly revised), and are referenced by stable ID. The receipt is frozen at analysis time. A receipt with insufficient observations, source ambiguity, or uncertainty exceeding the predeclared gate MUST be `inconclusive` — never auto-support a claim.**

This invariant is the direct translation of #77's acceptance gate: *"Receipts with insufficient observations, source ambiguity or uncertainty MUST be inconclusive, never auto-support a claim."*

---

## 4. External producers remain owners — protocol boundaries

The issue explicitly names the external producers and what they contribute. This slice records those boundaries as invariants.

| External producer | What it contributes (no loss representation) | What it does NOT contribute (NOOP boundary) |
|-------------------|---------------------------------------------|---------------------------------------------|
| **Anki / FSRS** (`kvnloo/anki`, `kvnloo/fsrs4anki`) | 24h/7d/30d delayed recall, retrievability, review accuracy and counts | Not cards/deck names by default; Anki remains scheduler |
| **ActivityWatch** (`kvnloo/activitywatch`) | Aggregated dwell/switch/fragmentation/coverage **proxies** | Not validated cognitive/flow labels; no titles/URLs/screenshots |
| **Loop Habit Tracker** (`kvnloo/uhabits`) | Explicit manual/automatic completion/skip/unknown distinctions | No inference from a timer |
| **Learning tools** (`kvnloo/obsidian-learning-engine`, optional reaction/flow probes) | Bounded session/outcome values | Not another NOOP learning suite |
| **Cognitive task probes** (`kvnloo/reaction-trainer`, `kvnloo/freefocusgames`) | User-initiated task result with provenance and practice-effect caveats | No cognitive-optimization claims |

**Transport contract:** All adapters live in **external local tooling** (the forks above, user dotfiles, workspace telemetry). NOOP core is unchanged until #42 semantics and the two-case evidence requirement are met (#77 acceptance gate). No sync/network connection, no private note/deck content, no medical or causal recommendation in NOOP.

---

## 5. Acceptance / evidence gate — restated as testable criteria

From #77, translated into the smallest checks that can pass or fail:

| # | Criterion | How it is verified (smallest observation) |
|---|-----------|-------------------------------------------|
| 1 | Two separate real-world research protocols whose multiple timed observations are **not representable without loss** as existing `metricSeries`, Lab Book or events | Two fixture JSON files (one per protocol) that fail a lossless round-trip through current NOOP primitives; recorded in #77 |
| 2 | UTC clock skew, DST/wake-day, multiple observations/day, duplicate source keys, replay, overlapping imports, missing coverage, invalid non-finite values, export/reimport, consent revocation | A single test fixture matrix covering all nine cases; pass = each case produces the specified reject/accept behavior from §2 |
| 3 | Receipts with insufficient observations, source ambiguity or uncertainty MUST be **inconclusive** | A receipt fixture with `< minimum_samples` linked observations → verdict `inconclusive`; a receipt with two `source_id`s for the same `outcome_key` → `inconclusive` |
| 4 | Adapters in **external local tooling**; NOOP core unchanged until #42 semantics + two-case evidence | No NOOP core file modified; adapter code lives in the named external forks only |
| 5 | No sync/network, no private content, no medical/causal recommendation | Static analysis: no network calls in adapter paths; no PII fields in observation schema; no recommendation code paths |

---

## 6. Reconciliation with held PRs #42/#43/#44 and issues #70/#71

The #76 slice (PR #176) produced the master reconciliation table. This slice extends it for the P2/P1 boundary that #77 owns.

### 6.1 Held drafts — what #77 takes, what stays held

| Draft | What #77 adopts as semantics | What remains HELD (not forwarded) |
|-------|------------------------------|-----------------------------------|
| **#42 P0** | The structural contract fields (§2 of #76 slice) — baseline/exposure/outcome windows, `predicted_direction`, coverage/sample gates, falsification rule, `prediction_locked_at`, `analysis_recipe_version`, `status` | `canonical_factor_key` resolution against current `main`; `study_unit` + wake-day/timezone; multiple exposures; multi-day outcomes; durable preregistration identity; `source_identity/version`, `consent/export_policy` — all remain unresolved per #76 §3 |
| **#43 P1** | The receipt shape: embedded P0 snapshot, source provenance, sample counts, coverage/missingness, effect/uncertainty, confounders, lag derivation, verdict restricted to `supports`/`contradicts`/`inconclusive` | Persistence/audit (cannot prove lock-before-exposure); everything #42 is missing; infrastructure deferred |
| **#44 P2** | The external observation shape (§2 of this slice): stable ID, dual timestamps, outcome key, finite value + explicit unit, source ID, optional native record/session ID, required protocol/version, coverage, sample count, measurement kind, consent, recipe version | Persistence, import transport, UI, aggregation into `metricSeries`, higher-is-better semantics — and the prerequisite: **two real experiment recipes not representable losslessly** |

### 6.2 Issues #70/#71 — cross-references honored

- **#71 (Backfill: Healthspan Insights, Rest and experiment evidence):** Lists #42/#43/#44 as **HOLD**. This slice does not change that disposition. The semantics here are the *design record* for when the hold is revisited.
- **#70 (Backfill: Atomic Habits, Journal and experimental compliance):** Documents the cross-OSS compatibility contract (Loop CSV → Journal mapping, ActivityWatch proxies, Anki/FSRS aggregates). This slice's §4 protocol boundaries are derived from #70's "Minimal, local-first compatibility contract" and do not extend it.
- The **master table** from #76 (§5 of PR #176) is the single source of truth for draft dispositions. This slice adds no new row; it only refines the P2/P1 boundary semantics that #77 owns.

### 6.3 The #46 witness and #49 repair — evidence inputs

- **#46 witness** (synthetic Journal pilots): collects the two-workflow evidence required by #76 exit criterion 1. This slice does not run the witnesses; it defines the observation schema they would emit.
- **#49 repair** (explicit-No baseline invariant): fixes the live violation on `main` (§4 of #76 slice). This slice assumes the invariant holds for any receipt that uses NOOP-native baseline days. If the invariant is not fixed, receipts that join to NOOP baselines inherit the defect.

---

## 7. Exit criteria — the smallest observation that proves a slice is worth graduating

Graduation is through [oss-factory #212](https://github.com/kvnloo/oss-factory/issues/212)'s gates
(#94 rule 6): **M2+** for learning comparisons, **M3** for context/health signals, **M4** for
adaptive policy. The smallest set that would let a slice of this contract graduate:

1. **Two-workflow evidence (M2+ gate).** Two independent genuine user workflows that cannot be
   expressed losslessly through existing Journal/Insights are demonstrated and recorded in #77,
   with the alternatives that were tried first. #46 is the synthetic collection point.
   *If both candidate recipes are expressible losslessly, the answer is to ship no contract and
   close P2.*

2. **Invariant repaired on `main` (M3 gate).** The #76 §4 violation (explicit-No baseline) is fixed
   on both platforms from the explicit-No set, with pure deterministic selection helpers and
   matching synthetic vectors on Swift and Kotlin, and cross-platform parity asserted by oracle.

3. **One bounded exploration receipt (per #94 rule 5).** One comment in #77: what was tried, what
   was observed (including "nothing"), what is now believed, and **keep / revise / drop**.

4. **Fork gates on the exact head.** `noop-channel gate isolate` on the slice's own head, then
   `gate union` on a throwaway union ref that is never pushed, never merged and never targeted. A
   suite that cannot run is `NOT_EXECUTED` and the verdict is `LIMITED`; a runner refusal is
   `BLOCKED` and names the owner action. Neither is ever recorded as `PASS`.

5. **Human burden stated and bounded.** Reading one existing Journal question per day — the
   burden this slice adds is **0 new minutes/day**, because it reuses a question the user already
   answers and adds no screen, no notification and no background process. Any graduation that adds
   a surface must restate this line before it is accepted.

6. **Stop date.** This exploration stops on **2026-11-21**. If the two-workflow evidence in (1) is
   not collected by then, the exploration is dropped or re-scoped in #77, and the result is kept
   (#94 rule 5: negative results are kept, not deleted).

7. **Drop condition.** If existing Journal + Insights can express both candidate workflows
   losslessly — the direction #46's synthetic witnesses already point — drop the separate contract
   and keep the documentation-only note. A promising exploration is still not a roadmap item.

---

## 8. The 5th-day question — which single field, if validated, most changes what we build next?

Per the task requirement, this slice must answer in the PR body:

> **`measurement_kind` (measurement \| inference \| projection).**
>
> This field is the *discriminant* that determines whether an external observation can ever
> support a causal claim. If the first real workflow that passes the two-case evidence test
> produces only `inference` (e.g. FSRS retrievability) or `projection` (e.g. next-day PVT
> forecast), the receipt verdict logic must treat them as `inconclusive` by default — and the
> entire P2/P1 bridge becomes an **observational correlation layer**, not an experiment
> adjudication layer. Conversely, if a workflow produces `measurement` (e.g. raw PVT reaction
> time, Anki review accuracy counts), the bridge can carry causal weight *if* the other gates
> pass. Validating this field on the first real workflow decides whether we build a **receipt
> adjudicator** (P1) or only an **observation ingest + correlation dashboard** (P2-only). No
> other field has this branching power.

---

## 9. Evidence index

Every claim above is reproducible. Base: `kvnloo/noop` `main` at head
**`2a1de7609843b095505274ad6fc811757210be80`** — pinned by `git rev-parse HEAD` in the slice
checkout, `gh api repos/kvnloo/noop/commits/main --jq .sha` and
`gh api repos/ryanbr/noop/commits/main --jq .sha` (fork `main` is the exact mirror: all three
SHAs agree).

| Claim | How it was checked |
|-------|-------------------|
| The #77 outcome object fields match the issue verbatim | Direct quote from issue #77 body |
| The #76 master table (PR #176) is the single reconciliation source | Read PR #176 body and `docs/EXPERIMENT_CONTRACT.md` artifact |
| Held PRs #42/#43/#44 are fork-only, stale base, stacked | `gh pr view 42,43,44 --repo kvnloo/noop` — base `staging`/`feat/...`, state OPEN |
| Issues #70/#71 document the cross-OSS contract and backfill | `gh issue view 70,71 --repo kvnloo/noop` |
| Runner state at time of writing | `gh api repos/kvnloo/fleet-runner/actions/runs --jq '.workflow_runs[0].conclusion'` → latest run conclusion (budget-cancellable, not red) |
| No executable suite matches a docs-only change | `noop-channel gate isolate` on PR #176 returned `BLOCKED` with "no executable suite matches this PR's paths" |

This slice itself is **not** covered by any executable gate: a documentation-only change matches no
suite in `noop-channels/gates.py`'s path table, so `noop-channel gate isolate` will record
**`BLOCKED`** with the note "no executable suite matches this PR's paths" (`gates.gate_isolate`
refuses to claim a verdict it could not produce). That is recorded rather than papered over, and it
is never written as `PASS`.

---

## 10. Not in scope

Issue #77 forbids these, and this slice does not add any of them:

- a **second Journal**;
- a **second metric store**;
- a **protocol database**;
- a **product-level experiment dashboard**;
- production code, schema/migration changes, persistence, a scheduler;
- result or effect calculation, causal inference, a response model, a recommendation system;
- automatic treatment recommendation, hosted service, cloud telemetry, clinical efficacy claim;
- silent P0–P2 promotion.

Upstream implementation ownership and contributors are preserved; upstream posting stays
owner-gated through `upstream-gate/gate propose`.

---

## 11. What this slice asks for

Nothing but a decision recorded in #77: given the semantics in §2–§3, the separation invariant in
§3.3, the protocol boundaries in §4, the acceptance gate in §5, the reconciliation in §6, and the
exit criteria in §7 — does the factory work the **two-workflow evidence** (criterion 1) as the
next active exploration, or first repair the **explicit-No invariant** (criterion 2)? Both are
cheap; only one may be active at a time (#94 rule 1). The 5th-day question in §8 identifies the
field that branches the build decision.