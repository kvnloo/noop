# NoopLocalAccess

`noop-local-access` exposes bounded, read-only NOOP health data locally. It has no network or
write/control path. Same names for MCP and `query`.

Seventeen tools: `health_snapshot`, `metric_series`, `data_freshness`, `sleep_summary`,
`workout_summary`, `hr_series`, `rr_series`, `event_series`, `event_kinds`, `sleep_stages`, `spo2_series`,
`skin_temp_series`, `resp_series`, `step_series`, `gravity_series`, `battery_series`,
`sleep_state_series`.

Optional flags: `include_zones` and `include_notes` on `workout_summary`; `include_motion`,
`include_sleep_state`, and `include_start_adjusted` on `sleep_summary` (CLI: `--include-*`).

`data_freshness` last-ts keys (missing tables stay `null`): `latestHeartRateSample`,
`latestRrInterval`, `latestEvent`, `latestSleepSession`, `latestWorkout`, `latestBattery`,
`latestStep`, `latestResp`, `latestSkinTemp`, `latestSpo2`, `latestGravity`,
`latestSleepState`.

MCP resource `noop://tools/catalog` returns the dispatcher `toolNames` list as JSON.
MCP resource `noop://data/freshness` returns the same JSON as tool `data_freshness`.
`noop-local-access query --list-tools` and `noop-local-access tools` print that same JSON array.

`noop-local-access resource <uri>` prints the same JSON as MCP `resourcePayload` for
`noop://tools/catalog`, `noop://data/freshness`, `noop://health/snapshot`,
`noop://metrics/catalog`, and `noop://sources` (short forms without `noop://` are accepted).
Unknown URIs exit 64. `noop-local-access resource --list` prints those known URIs as a JSON array.
`noop-local-access --version` and `-V` print the non-empty product version (`noopLocalAccessServerVersion`).
Optional `--pretty` pretty-prints `query` and `resource` JSON; the default stays compact one-line.
Optional `--quiet` suppresses stderr diagnostics; stdout JSON is unchanged.

Use MCP over stdio with `noop-local-access mcp`, or query one tool directly as JSON:

```sh
noop-local-access --version
noop-local-access query --list-tools
noop-local-access tools
noop-local-access resource --list
noop-local-access resource noop://tools/catalog
noop-local-access resource data/freshness
noop-local-access query health_snapshot --days 14
noop-local-access query health_snapshot --days 14 --pretty
noop-local-access resource --list --pretty
noop-local-access query health_snapshot --days 14 --quiet
noop-local-access query metric_series --key hrv --days 90
noop-local-access query data_freshness
noop-local-access query data_freshness --db-path PATH
noop-local-access query sleep_summary --days 30
noop-local-access query sleep_summary --days 30 --include-motion
noop-local-access query sleep_summary --days 30 --include-sleep-state
noop-local-access query sleep_summary --days 30 --include-start-adjusted
noop-local-access query workout_summary --days 90
noop-local-access query workout_summary --days 90 --include-zones
noop-local-access query workout_summary --days 90 --include-notes
noop-local-access query hr_series --hours 1 --bucket-seconds 60 --limit 50
noop-local-access query spo2_series --hours 1 --bucket-seconds 60 --limit 50
noop-local-access query skin_temp_series --hours 1 --bucket-seconds 60 --limit 50
noop-local-access query resp_series --hours 1 --bucket-seconds 60 --limit 50
noop-local-access query step_series --hours 1 --bucket-seconds 60 --limit 50
noop-local-access query gravity_series --hours 1 --bucket-seconds 60 --limit 50
noop-local-access query battery_series --hours 1 --bucket-seconds 60 --limit 50
noop-local-access query sleep_state_series --hours 1 --bucket-seconds 60 --limit 50
noop-local-access query sleep_stages --days 14 --limit 7 --max-points 120
noop-local-access query event_series --kind ALPHA --hours 6 --limit 50
noop-local-access query event_kinds --limit 100
noop-local-access query rr_series --hours 6 --limit 50
```

Set `NOOP_DB_PATH` to select a database, or pass `--db-path PATH`. For a strapless local `query data_freshness` demo, point `--db-path` at a read-only sqlite that already has a few `hrSample` rows; `TemporaryDatabase` in the package tests already seeds those (no extra fixture). Query results are written to
stdout; diagnostics are written to stderr. Query usage errors exit 64 and runtime/database errors
exit 1. Pass `--pretty` on `query` or `resource` to indent JSON; omit it for one compact line. Pass `--quiet` to suppress stderr diagnostics without changing stdout JSON.

Arguments reuse the MCP defaults and bounds:

- `health_snapshot`: optional `--days` (default 14, clamped to 1...120).
- `metric_series`: required `--key`; optional `--source` (default `my-whoop`), `--days` (default 90,
  clamped to 1...4000), `--from-day`, `--to-day`, and `--limit` (default 500, clamped to 1...2000).
- `data_freshness`: no tool arguments. Same object as before, plus nullable
  `latestRrInterval`, `latestEvent`, `latestSleepSession`, `latestWorkout`,
  `latestBattery`, `latestStep`, `latestResp`, `latestSkinTemp`, `latestSpo2`,
  `latestGravity`, and `latestSleepState` last-ts fields (missing tables are `null`).
  `latestHeartRateSample` is unchanged. New last-ts keys use the same
  `{ts, iso, ageSeconds}` shape as the other last-ts keys.
- `sleep_summary`: optional `--days` (default 30, clamped to 1...4000), `--include-motion` (default off), `--include-sleep-state` (default off), and `--include-start-adjusted` (default off). Default rows still return `hasStages` only. When `--include-motion` is set, each row gets a bounded `motion` object (`payload` plus `truncated`); oversized objects/arrays keep 32 entries; strings keep 2048 characters. When `--include-sleep-state` is set, each row gets a bounded `sleepState` object (`payload` plus `truncated`); oversized objects/arrays keep 32 entries; strings keep 2048 characters. When `--include-start-adjusted` is set, a row includes `startTsAdjusted` only if that column is present and non-null (pre-v14 tables omit the key).
- `workout_summary`: optional `--days` (default 90, clamped to 1...4000), `--include-zones` (default off), and `--include-notes` (default off). Default rows still return `hasZones`/`hasNotes` only. When `--include-zones` is set, each row gets a bounded `zones` object (`payload` plus `truncated`); oversized objects/arrays keep 32 entries. When `--include-notes` is set, each row gets a bounded `notes` object (`payload` plus `truncated`); strings keep 2048 characters.
- `hr_series`: optional `--hours` (default 6, clamped to 1...24), `--from-ts` and `--to-ts`
  together, `--bucket-seconds` (default 60, clamped to 1...3600), `--limit` (default 500,
  clamped to 1...2000), and `--device-id`.
- `spo2_series`: optional `--hours` (default 6, clamped to 1...24), `--from-ts` and `--to-ts`
  together, `--bucket-seconds` (default 60, clamped to 1...3600), `--limit` (default 500,
  clamped to 1...2000), and `--device-id`. Buckets average stored `spo2Sample.red` and
  `spo2Sample.ir`. A missing `spo2Sample` table returns empty points. Suffix limit plus
  `truncated`. Not a daily `metric_series`.
- `skin_temp_series`: optional `--hours` (default 6, clamped to 1...24), `--from-ts` and `--to-ts`
  together, `--bucket-seconds` (default 60, clamped to 1...3600), `--limit` (default 500,
  clamped to 1...2000), and `--device-id`. Buckets average stored `skinTempSample.raw`. A missing
  `skinTempSample` table returns empty points. Suffix limit plus `truncated`. Not a daily
  `metric_series`.
- `resp_series`: optional `--hours` (default 6, clamped to 1...24), `--from-ts` and `--to-ts`
  together, `--bucket-seconds` (default 60, clamped to 1...3600), `--limit` (default 500,
  clamped to 1...2000), and `--device-id`. Buckets average stored `respSample.raw`. A missing
  `respSample` table returns empty points. Suffix limit plus `truncated`. Not a daily
  `metric_series`.
- `step_series`: optional `--hours` (default 6, clamped to 1...24), `--from-ts` and `--to-ts`
  together, `--bucket-seconds` (default 60, clamped to 1...3600), `--limit` (default 500,
  clamped to 1...2000), and `--device-id`. Buckets average stored `stepSample.counter`. A missing
  `stepSample` table returns empty points. Suffix limit plus `truncated`. Not a daily
  `metric_series`.
- `gravity_series`: optional `--hours` (default 6, clamped to 1...24), `--from-ts` and `--to-ts`
  together, `--bucket-seconds` (default 60, clamped to 1...3600), `--limit` (default 500,
  clamped to 1...2000), and `--device-id`. Buckets average stored `gravitySample.x`,
  `gravitySample.y`, and `gravitySample.z`. A missing `gravitySample` table returns empty points.
  Suffix limit plus `truncated`. Not a daily `metric_series`.
- `battery_series`: optional `--hours` (default 6, clamped to 1...24), `--from-ts` and `--to-ts`
  together, `--bucket-seconds` (default 60, clamped to 1...3600), `--limit` (default 500,
  clamped to 1...2000), and `--device-id`. Buckets average stored `battery.soc` and
  `battery.mv`. A missing `battery` table returns empty points. Suffix limit plus
  `truncated`. Not a daily `metric_series`.
- `sleep_state_series`: optional `--hours` (default 6, clamped to 1...24), `--from-ts` and `--to-ts`
  together, `--bucket-seconds` (default 60, clamped to 1...3600), `--limit` (default 500,
  clamped to 1...2000), and `--device-id`. Buckets average stored `sleepStateSample.state`.
  A missing `sleepStateSample` table returns empty points. Suffix limit plus `truncated`.
  Not a daily `metric_series`. Does not dump PPG waveform.
- `sleep_stages`: optional `--days` (default 30, clamped to 1...4000), `--limit` sessions
  (default 14, clamped to 1...60), and `--max-points` per session (default 200, clamped to
  1...2000). `sleep_summary` still returns `hasStages` only unless `--include-motion`, `--include-sleep-state`, or `--include-start-adjusted` is set.
- `event_series`: required `--kind`; optional `--hours` (default 6, clamped to 1...24),
  `--from-ts` and `--to-ts` together, `--limit` (default 500, clamped to 1...2000), and
  `--device-id`. One kind per call. Unknown kinds and a missing `event` table return empty
  points. Stored `payloadJSON` is parsed as JSON when valid, otherwise returned as a string.
- `event_kinds`: optional `--limit` (default 100, clamped to 1...500) and `--device-id`.
  Distinct `event.kind` values for one device, ordered by kind. A missing `event` table
  returns empty kinds. Does not dump payloads or event rows.
- `rr_series`: optional `--hours` (default 6, clamped to 1...24), `--from-ts` and `--to-ts`
  together, `--limit` (default 500, clamped to 1...2000), and `--device-id`. Filters match
  WhoopStore.rrIntervals (`srcChannel != spo2Ibi`, `tsSuspect != 1`; NULLs kept). A missing
  `rrInterval` table returns empty points. Suffix limit is the cap; this is not a 1Hz dump.

Dates use the existing `YYYY-MM-DD` tool contract. The CLI does not add a separate validation or
interpretation layer; it passes accepted arguments to the same bounded read-only dispatcher as MCP.
