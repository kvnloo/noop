---
name: verify-noop-local-access
description: Drive the NoopLocalAccess CLI and MCP stdio on kvnloo/noop the way a user does. Use when proving query, tools, resource, --version, or MCP catalog/freshness behavior.
---

# Verify NoopLocalAccess

Primary surface is the `noop-local-access` CLI. MCP is the same dispatcher over stdio (`noop-local-access mcp`). The iOS/Android apps are out of scope.

macOS 13+ with Swift 5.9 is required (`Package.swift` platforms `.macOS(.v13)`). This skill is inconclusive on Linux without Swift. Do not invent a passing result.

## Launch

From the repo root of `kvnloo/noop` (the checkout under test):

```sh
swift build --package-path Packages/NoopLocalAccess --product noop-local-access
```

Ready when `Packages/NoopLocalAccess/.build/debug/noop-local-access` exists and `noop-local-access --version` prints a non-empty line and exits 0.

There is no long-lived server for CLI. Each drive is a new process. For MCP, start `noop-local-access mcp` on stdio with `NOOP_DB_PATH` or `--db-path` set in `codex-config`; do not attach to a user's live strap DB.

Teardown: nothing to keep alive. Delete only temp sqlite files this run created. Leave evidence files in place.

## Doctor

Run from repo root:

```sh
.cursor/skills/verify-noop-local-access/bin/nla-verify doctor
```

Pass only if all of these hold:

- `swift --version` succeeds
- the debug binary exists after build
- `--version` stdout is non-empty and exit 0
- `query --list-tools` exits 0 and stdout parses as a JSON array

Verdicts:

- PASS (exit 0): all four checks above.
- INCONCLUSIVE (exit 2): `swift` is missing or the host is not macOS 13+. This is not a pass and not a product fail. Do not drive. Do not invent a Linux binary. Wait for a macOS 13+ Swift 5.9 host.
- FAIL (exit 1): Swift is present but the package does not build, the binary is missing, or `--version` is empty.

## Drive

Harness is the built CLI, not XCTest internals and not a mocked dispatcher.

```sh
BIN=Packages/NoopLocalAccess/.build/debug/noop-local-access
$BIN query --list-tools
$BIN tools
$BIN resource --list
$BIN query data_freshness --db-path "$DB"
$BIN resource noop://data/freshness --db-path "$DB"
$BIN query hr_series --hours 1 --db-path "$DB"
```

Stable handles are command names and flags from `main.swift` help: `query`, `tools`, `resource`, `--list-tools`, `--pretty`, `--quiet`, `--db-path`, `--version`. Tool names come from the dispatcher (`health_snapshot`, `data_freshness`, `hr_series`, and the rest listed in `Packages/NoopLocalAccess/README.md`).

Prefer `--db-path` over `NOOP_DB_PATH` so two runs do not share env. Point `--db-path` at a throwaway sqlite, never at the official app container unless the human named that file.

Usage errors must exit 64. Missing DB must exit 1 with stderr `NOOP database is unavailable` unless `--quiet` is set (then stderr empty, stdout still not JSON success).

## Evidence

Write under `.cursor/skills/verify-noop-local-access/evidence/<run-id>/`. Keep:

- `commands.log` with each command, exit code, stdout, stderr
- raw stdout files (`list-tools.json`, `freshness.json`, …)
- for MCP, the JSON-RPC request line and the response line

Proof standards:

- Exercise the real binary, not `NoopCLIQuery` from a test target
- Capture the command and the resulting stdout/stderr/exit, not only the last file
- Side effects: a `--db-path` file this run created must still be readable after the query; do not write into WhoopStore
- Do not treat XCTest green as CLI proof
- Dry-run does not exist; every command hits the real parser and dispatcher

Cleanup must not delete this evidence directory.

## Cleanup

```sh
.cursor/skills/verify-noop-local-access/bin/nla-verify cleanup "$RUN_ID"
```

Removes only `/tmp/nla-verify-$RUN_ID` (or the db path the helper created). Does not `killall`. Does not delete `evidence/`.

## Helpers

All helper commands are:

```sh
.cursor/skills/verify-noop-local-access/bin/nla-verify doctor
.cursor/skills/verify-noop-local-access/bin/nla-verify list-tools
.cursor/skills/verify-noop-local-access/bin/nla-verify data-freshness --db-path PATH
.cursor/skills/verify-noop-local-access/bin/nla-verify cleanup RUN_ID
```

`nla-verify` builds if needed, then execs the debug binary. Read that script; do not guess flags.

## Isolation

Two CLI processes may share a read-only sqlite. Do not share a writable DB. Default app-container path is shared with the human's NOOP install; refuse to use it unless the human passed it explicitly.
