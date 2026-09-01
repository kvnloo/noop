# AGENTS.md

Automated-agent entry. Full working notes live in [`CLAUDE.md`](CLAUDE.md)
(architecture, hard scope, BLE, parity, PR conventions, CI table). This file
adds no policy.

## Pointers

- [`CLAUDE.md`](CLAUDE.md), [`docs/CONTRIBUTING.md`](docs/CONTRIBUTING.md), [`docs/BUILD.md`](docs/BUILD.md), [`docs/SCOPE.md`](docs/SCOPE.md), [`docs/IOS.md`](docs/IOS.md)

## CI facts

- `android.yml` is **active**.
- `app-build.yml` is **disabled_manually** (app-target Swift is not default CI).
- `swift-packages.yml` covers `Packages/NoopLocalAccess`.

## NoopLocalAccess tools

`Packages/NoopLocalAccess` (`noop-local-access` MCP/CLI): bounded, read-only, no
network, no write/control. Same names for MCP and `query`. Sixteen tools:

- `health_snapshot`
- `metric_series`
- `data_freshness`
- `sleep_summary`
- `workout_summary`
- `hr_series`
- `rr_series`
- `event_series`
- `sleep_stages`
- `spo2_series`
- `skin_temp_series`
- `resp_series`
- `step_series`
- `gravity_series`
- `battery_series`
- `sleep_state_series`

Optional flags (MCP names; CLI uses `--include-*`):

- `include_zones` / `--include-zones` on `workout_summary`
- `include_notes` / `--include-notes` on `workout_summary`
- `include_motion` / `--include-motion` on `sleep_summary`
- `include_sleep_state` / `--include-sleep-state` on `sleep_summary`
- `include_start_adjusted` / `--include-start-adjusted` on `sleep_summary`

`data_freshness` last-ts keys (missing tables stay `null`):
`latestHeartRateSample`, `latestRrInterval`, `latestEvent`,
`latestSleepSession`, `latestWorkout`, `latestBattery`,
`latestStep`, `latestResp`, `latestSkinTemp`, `latestSpo2`, `latestGravity`.

`sleep_summary` still returns `hasStages` only unless `include_motion`,
`include_sleep_state`, or `include_start_adjusted` is set. Motion and sleep
state attach bounded payloads; startTsAdjusted is included only when present.
`workout_summary` still returns `hasZones`/`hasNotes` only unless `include_zones`
or `include_notes` is set, which attach bounded payloads.
One concern per PR; follow [`CLAUDE.md`](CLAUDE.md).
