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
network, no write/control. Same names for MCP and `query`:

- `health_snapshot`
- `metric_series`
- `data_freshness`
- `sleep_summary`
- `workout_summary`
- `hr_series`
- `spo2_series`
- `skin_temp_series`
- `resp_series`
- `step_series`
- `gravity_series`
- `battery_series`
- `sleep_state_series`
- `rr_series`
- `event_series`
- `sleep_stages`

`sleep_summary` still returns `hasStages` only unless `include_motion` /
`--include-motion`, `include_sleep_state` / `--include-sleep-state`, or
`include_start_adjusted` / `--include-start-adjusted` is set. Motion and sleep
state attach bounded payloads; startTsAdjusted is included only when present.
`workout_summary` still returns `hasZones`/`hasNotes` only unless `include_zones`
/ `--include-zones` or `include_notes` / `--include-notes` is set, which attach
bounded payloads.
`data_freshness` includes last-ts for HR, RR, event, sleep, and workout when
those tables exist. One concern per PR; follow [`CLAUDE.md`](CLAUDE.md).
