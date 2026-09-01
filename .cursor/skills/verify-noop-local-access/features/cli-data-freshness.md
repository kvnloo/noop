# CLI data freshness

A user can ask when local samples last arrived, as JSON, from either `query` or the MCP resource URI.

## Sub-features

- `query-freshness` returns the `data_freshness` object on stdout.
- `resource-freshness` returns the same JSON for `noop://data/freshness`.
- `missing-db` exits 1 when the sqlite path does not exist.

## How to get to it (user POV)

- Run `noop-local-access query data_freshness --db-path PATH`.
- Run `noop-local-access resource noop://data/freshness --db-path PATH`.
- Short URI `data/freshness` is accepted.

## Driving it with nla-verify

Preconditions:

- `nla-verify doctor` passed.
- `--db-path` points at a real NOOP sqlite or a throwaway copy that has the expected tables. `TemporaryDatabase` in tests seeds `hrSample` rows. An empty missing file is the missing-db case, not success.

- **Query.** Run `.cursor/skills/verify-noop-local-access/bin/nla-verify data-freshness --db-path "$DB"`. Exit 0. Stdout JSON has key `latestHeartRateSample` (object or null). Save as `evidence/<run-id>/freshness.query.json`.
- **Resource.** Run `Packages/NoopLocalAccess/.build/debug/noop-local-access resource noop://data/freshness --db-path "$DB"`. Exit 0. Stdout JSON equals the query file for the same keys. Save as `evidence/<run-id>/freshness.resource.json`.
- **Missing DB.** Run the query against a path that does not exist. Exit 1. Stderr contains `NOOP database is unavailable`. Stdout is not a freshness object.
- **Proof.** Both JSON files parse. No score, readiness, or NZT fields are present.

## Gotchas

- Missing tables are `null` last-ts keys, not an error.
- `--quiet` hides the missing-db stderr. Assert exit 1, not the message, when `--quiet` is on.
- Compact JSON is one line unless `--pretty` is passed.
