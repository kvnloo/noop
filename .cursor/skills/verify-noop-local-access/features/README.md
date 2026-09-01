# NoopLocalAccess verification map

Maintained source for proving user-facing CLI/MCP query behavior. Read this index, then the feature file.

## Baseline preconditions

- Swift 5.9+ on PATH (Linux or macOS). `nla-verify doctor` exit 2 is INCONCLUSIVE when Swift is missing, not PASS and not a product FAIL.
- Repo root of `kvnloo/noop`. Package is `Packages/NoopLocalAccess`.
- Build `noop-local-access` via `nla-verify doctor`.
- Use a disposable sqlite with `--db-path` for any tool that reads samples. `query --list-tools`, `tools`, `resource --list`, and `--version` do not need a DB.
- Never drive the official macOS app container unless the human named that path.
- Record every command, stdout, stderr, and exit code under `evidence/<run-id>/`.

## Driving conventions

- Start from a built debug binary. Do not call Swift test types.
- Treat command names and flags as literal.
- Prefer `--db-path` over `NOOP_DB_PATH`.
- MCP is stdio JSON-RPC one line in, one line out. Same payloads as CLI `query` / `resource`.

## Proof and skip reporting

- CLI proof is command + stdout + stderr + exit code.
- JSON proof is parseable stdout matching the named keys in the feature file.
- Mutation is not in this product. Read-only. Do not assert writes.
- If Swift is missing, report blocked, not skipped-as-pass.
- Do not report a skipped entry point as verified through XCTest.

## Features

- [CLI catalog](./cli-catalog.md) covers `--version`, `query --list-tools`, and `tools`.
- [CLI data freshness](./cli-data-freshness.md) covers `query data_freshness` and `resource noop://data/freshness`.
- [CLI resource list](./cli-resource-list.md) covers `resource --list` and known URIs.
- [CLI hr series](./cli-hr-series.md) covers `query hr_series` against a seeded sqlite.
- [CLI usage errors](./cli-usage-errors.md) covers exit 64 and quiet stderr.
