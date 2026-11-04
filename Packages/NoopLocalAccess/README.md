# NoopLocalAccess

`noop-local-access` exposes bounded, read-only NOOP health data locally. It has no network or
write/control path.

Use MCP over stdio with `noop-local-access mcp`, or query one tool directly as JSON:

```sh
noop-local-access query health_snapshot --days 14
noop-local-access query metric_series --key hrv --days 90
noop-local-access query data_freshness
noop-local-access query sleep_summary --days 30
noop-local-access query workout_summary --days 90
noop-local-access query rr_series --hours 6 --limit 50
```

Set `NOOP_DB_PATH` to select a database, or pass `--db-path PATH`. Query results are written to
stdout; diagnostics are written to stderr. Query usage errors exit 64 and runtime/database errors
exit 1.

Arguments reuse the MCP defaults and bounds:

- `health_snapshot`: optional `--days` (default 14, clamped to 1...120).
- `metric_series`: required `--key`; optional `--source` (default `my-whoop`), `--days` (default 90,
  clamped to 1...4000), `--from-day`, `--to-day`, and `--limit` (default 500, clamped to 1...2000).
- `data_freshness`: no tool arguments.
- `sleep_summary`: optional `--days` (default 30, clamped to 1...4000).
- `workout_summary`: optional `--days` (default 90, clamped to 1...4000).

- `rr_series`: optional `--hours` (default 6, clamped to 1...24), paired `--from-ts`
  and `--to-ts` (inclusive Unix seconds), `--limit` (default 500, clamped to 1...2000),
  and `--device-id`. The newest bounded suffix is returned in ascending time/order.
  Missing `rrInterval` tables and optional legacy columns are supported; malformed
  non-integer rows and rows labelled `spo2Ibi` or `tsSuspect=1` are excluded.
  This is a raw diagnostic-style read, not the scoring-selected `rrIntervals` stream.

Dates use the existing `YYYY-MM-DD` tool contract. The CLI does not add a separate validation or
interpretation layer; it passes accepted arguments to the same bounded read-only dispatcher as MCP.
