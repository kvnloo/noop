# CLI resource list

A user can list the MCP resource URIs the binary will serve, then fetch one.

## Sub-features

- `resource-list` prints a JSON array of known URIs.
- `resource-catalog` prints the tool-name list for `noop://tools/catalog`.
- `unknown-uri` exits 64.

## How to get to it (user POV)

- Run `noop-local-access resource --list`.
- Run `noop-local-access resource noop://tools/catalog`.
- Run `noop-local-access resource not-a-uri` and expect a usage error.

## Driving it with nla-verify

Preconditions:

- `nla-verify doctor` passed.
- No database for `--list` or `tools/catalog`.

- **List.** Run `Packages/NoopLocalAccess/.build/debug/noop-local-access resource --list`. Exit 0. Stdout JSON array includes `noop://tools/catalog` and `noop://data/freshness`. Save as `evidence/<run-id>/resource-list.json`.
- **Catalog.** Run `Packages/NoopLocalAccess/.build/debug/noop-local-access resource noop://tools/catalog`. Exit 0. Stdout is a JSON array of tool names, same membership as `query --list-tools`.
- **Unknown.** Run `Packages/NoopLocalAccess/.build/debug/noop-local-access resource noop://nope`. Exit 64.
- **Proof.** `commands.log` has all three exit codes. Evidence files remain after cleanup.

## Gotchas

- Short forms without `noop://` are accepted for known URIs. Unknown short forms still exit 64.
- `resource --list --pretty` is valid. Duplicate `--pretty` is a usage error.
