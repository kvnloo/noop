# CLI hr series

A user can read a bounded local heart-rate series as JSON from a sqlite they point at.

## Sub-features

- `hr-query` returns points for `hr_series` from `--db-path`.
- `hr-empty` returns empty points when the table is missing, not a crash.
- `hr-bounds` clamps `--hours` and `--limit` through the same dispatcher as MCP.

## How to get to it (user POV)

- Run `noop-local-access query hr_series --hours 1 --bucket-seconds 60 --limit 50 --db-path PATH`.

## Driving it with nla-verify

Preconditions:

- `nla-verify doctor` passed.
- `--db-path` is a disposable sqlite. For a non-empty series, it needs `hrSample` rows (the package tests' `TemporaryDatabase` seeds those). Do not use the human's live DB.

- **Query.** Run `Packages/NoopLocalAccess/.build/debug/noop-local-access query hr_series --hours 1 --limit 50 --db-path "$DB"`. Exit 0. Stdout JSON includes a `points` array (name as returned; if the payload uses another list key, record the actual key and still assert an array). Save as `evidence/<run-id>/hr-series.json`.
- **Empty table.** Point at a sqlite with no `hrSample` table. Exit 0 with empty points, not exit 1.
- **Proof.** Command, stdout, and the db path used. No derived score fields.

## Gotchas

- `--from-ts` and `--to-ts` must be passed together.
- This is not daily `metric_series`. Do not accept a day-bucket payload as proof.
- Linux without Swift is blocked, not a pass.
