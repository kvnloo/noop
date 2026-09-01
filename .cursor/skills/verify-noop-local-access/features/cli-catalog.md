# CLI catalog

A user can print the product version and the same tool-name list MCP exposes, without opening a database.

## Sub-features

- `version` prints a non-empty product version on stdout and exits 0.
- `query-list-tools` prints a JSON array of dispatcher tool names.
- `tools` prints that same JSON array.

## How to get to it (user POV)

- Run `noop-local-access --version` or `-V`.
- Run `noop-local-access query --list-tools`.
- Run `noop-local-access tools`.

## Driving it with nla-verify

Preconditions:

- `nla-verify doctor` passed.
- No database path is required.

- **Version.** Run `.cursor/skills/verify-noop-local-access/bin/nla-verify version`. Exit 0. Stdout is one non-empty line. Save it as `evidence/<run-id>/version.txt`.
- **List tools via query.** Run `.cursor/skills/verify-noop-local-access/bin/nla-verify list-tools`. Exit 0. Stdout is a JSON array that includes `health_snapshot` and `data_freshness`. Save as `evidence/<run-id>/list-tools.json`.
- **List tools via tools.** Run `Packages/NoopLocalAccess/.build/debug/noop-local-access tools`. Exit 0. Stdout JSON equals `list-tools.json` (same strings, order from dispatcher).
- **Proof.** Keep both JSON files and `commands.log`. Array length is at least the seventeen tools named in `Packages/NoopLocalAccess/README.md`.

## Gotchas

- `--version` is parsed before the subcommand. `query --version` is not this feature.
- `tools` does not take `--pretty`. Extra flags exit 64.
- Do not treat an XCTest that calls `listToolsPayload()` as this proof.
