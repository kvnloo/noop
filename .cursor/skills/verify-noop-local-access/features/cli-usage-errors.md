# CLI usage errors

A user who passes a bad command or duplicate flag gets exit 64, with diagnostics on stderr unless `--quiet`.

## Sub-features

- `unknown-command` exits 64.
- `duplicate-flag` exits 64 for duplicate `--pretty` or `--quiet`.
- `quiet-hides-stderr` keeps stdout JSON unchanged and omits diagnostics.

## How to get to it (user POV)

- Run `noop-local-access nope`.
- Run `noop-local-access query --list-tools --pretty --pretty`.
- Run `noop-local-access query data_freshness --db-path MISSING --quiet`.

## Driving it with nla-verify

Preconditions:

- `nla-verify doctor` passed.

- **Unknown command.** Run `Packages/NoopLocalAccess/.build/debug/noop-local-access nope`. Exit 64. Stderr is non-empty.
- **Duplicate pretty.** Run `Packages/NoopLocalAccess/.build/debug/noop-local-access query --list-tools --pretty --pretty`. Exit 64.
- **Quiet missing db.** Run `Packages/NoopLocalAccess/.build/debug/noop-local-access query data_freshness --db-path /tmp/nla-verify-missing.sqlite --quiet`. Exit 1. Stderr empty. Stdout is not a freshness object.
- **Proof.** `commands.log` records the three exits. Do not treat a test-only `NoopCLIQueryError` as this proof.

## Gotchas

- Exit 64 is usage. Exit 1 is runtime/database.
- `--quiet` after a parse failure may still parse enough to hide stderr. Assert both the exit and stderr bytes.
