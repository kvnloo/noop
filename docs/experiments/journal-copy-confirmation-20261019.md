# Android Journal: confirmation-gated carry-forward

## Defect

On pinned `ryanbr/noop@8e4b0c1`, entering Insights with no native journal answers for the current day *automatically inserted yesterday's answers into today's table*. Those inserted rows became genuine Yes/No evidence in behavior correlations and the personal experiment feature without an explicit observation. The copy also made it look as if the user had answered today.

## Narrow fix

- On today's blank Journal, offer **Copy previous answers** but **do not write** any entry during screen load.
- Persist only when the user taps the copy button; preserve both Yes and No semantics exactly.
- Before writing, re-read today's native rows and copy only currently missing questions. A manual answer arriving after the suggestion cannot be overwritten by the copy action.
- Never offer a previous-day copy for Yesterday, older backfills, or Tomorrow.
- No changes to imported WHOOP rows, database schema, effect math, automatic scores or other screens.
- Nine Android JUnit regression cases guard copy visibility, imported/native collisions, numeric and hidden question exclusion, and overwrite avoidance. iOS/macOS already read native answers without writing yesterday's answers on view load; the Android-only fix restores this behavior. On-device interaction testing remains for review.

Provenance: built on the existing NOOP Journal implementation, not a new feature claim. PolyForm Noncommercial 1.0.0 terms unchanged.

- Imported WHOOP records count as already-present day evidence: never suggest a blanket copy over them, and check imports again when confirming.
- A midnight rollover invalidates the on-screen previous-day suggestion instead of writing it into the new date.
