# NoopLocalAccess

`noop-local-access` exposes bounded, read-only NOOP health data locally. It has no network or
write/control path.

Use MCP over stdio with `noop-local-access mcp`, or query one tool directly as JSON:

```sh
noop-local-access query health_snapshot --days 14
noop-local-access query metric_series --key hrv --days 90
noop-local-access query data_freshness
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
noop-local-access query sleep_stages --days 14 --limit 7 --max-points 120
noop-local-access query event_series --kind ALPHA --hours 6 --limit 50
noop-local-access query rr_series --hours 6 --limit 50
```

Set `NOOP_DB_PATH` to select a database, or pass `--db-path PATH`. Query results are written to
stdout; diagnostics are written to stderr. Query usage errors exit 64 and runtime/database errors
exit 1.

Arguments reuse the MCP defaults and bounds:

- `health_snapshot`: optional `--days` (default 14, clamped to 1...120).
- `metric_series`: required `--key`; optional `--source` (default `my-whoop`), `--days` (default 90,
  clamped to 1...4000), `--from-day`, `--to-day`, and `--limit` (default 500, clamped to 1...2000).
- `data_freshness`: no tool arguments. Same object as before, plus nullable
  `latestRrInterval`, `latestEvent`, `latestSleepSession`, and `latestWorkout`
  last-ts fields (missing tables are `null`). `latestHeartRateSample` is
  unchanged. `latestWorkout` uses the same `{ts, iso, ageSeconds}` shape.
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
- `sleep_stages`: optional `--days` (default 30, clamped to 1...4000), `--limit` sessions
  (default 14, clamped to 1...60), and `--max-points` per session (default 200, clamped to
  1...2000). `sleep_summary` still returns `hasStages` only unless `--include-motion`, `--include-sleep-state`, or `--include-start-adjusted` is set.
- `event_series`: required `--kind`; optional `--hours` (default 6, clamped to 1...24),
  `--from-ts` and `--to-ts` together, `--limit` (default 500, clamped to 1...2000), and
  `--device-id`. One kind per call. Unknown kinds and a missing `event` table return empty
  points. Stored `payloadJSON` is parsed as JSON when valid, otherwise returned as a string.
- `rr_series`: optional `--hours` (default 6, clamped to 1...24), `--from-ts` and `--to-ts`
  together, `--limit` (default 500, clamped to 1...2000), and `--device-id`. Filters match
  WhoopStore.rrIntervals (`srcChannel != spo2Ibi`, `tsSuspect != 1`; NULLs kept). A missing
  `rrInterval` table returns empty points. Suffix limit is the cap; this is not a 1Hz dump.

Dates use the existing `YYYY-MM-DD` tool contract. The CLI does not add a separate validation or
interpretation layer; it passes accepted arguments to the same bounded read-only dispatcher as MCP.
