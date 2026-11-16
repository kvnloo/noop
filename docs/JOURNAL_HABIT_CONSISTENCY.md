# Journal habit evidence (downstream implementation, 2026-10-19)

NOOP already has editable, persistent, local Journal questions. A user can create a
Boolean item under **Insights > Journal > Edit > Behaviour**, answer Yes or No each day,
and compare an explicitly tracked routine against their own observations.

This change provides a *read-only* Swift/Kotlin consistency calculation. It is
**not yet a habit-screen UI**, coaching system, score, intervention, or causal study.
The optional "small habit / clear cue / repeat" direction is inspired by general
behaviour-design ideas often associated with *Atomic Habits*; this is not an
official integration, book-text reproduction, or a claim of endorsement.

## Contracts

- **Question identity:** use the exact immutable `JournalCatalogItem.canonical`, not
  a renamed display string, and do not mix multiple questions into one streak.
- **Day identity:** use NOOP's existing local *wake-day* journal keys (`yyyy-MM-dd`),
  passed in by the caller; no UTC-second stepping across DST boundaries.
- **Truth states:** Yes, explicit No, and *no observation* are three distinct states.
  No entry must **never** be imputed as No or copied from a previous day. The Android
  implicit-copy fix is isolated in downstream PR #53.
- **Precedence:** resolve WHOOP-imported + native rows with existing
  `Repository.mergeJournal` / `mergeJournalEntries` first. Native edits win for a
  day/question. Within a selected question, the later repeated row wins.
- **Window:** the summary clamps to 1...90 civil days (default seven) and counts
  Yes, No and unanswered days separately. It excludes future rows. `currentYesStreak`
  preserves yesterday's run when *today is unlogged*, but an explicit No today
  ends that run. `longestYesStreak` examines all supplied past history.
- **Polarity:** Yes means the user answered Yes; it does **not** mean success for
  negatively phrased questions (e.g. "Did you drink alcohol?"). Future UI must use
  "Yes-streak" or require an affirmative target habit, never silently label every
  Yes a good habit.
- **Missing/invalid input:** malformed today yields no summary; invalid dates and
  rows beyond today are ignored; unpaired trailing list elements are ignored.
- **No side effects:** both `JournalConsistency` implementations are pure. They add
  no BLE messages, permissions, database tables, notifications, network activity,
  analytics uploads, changes to recovery/strain/HRV formulae, or medical claims.

## Call sites for the next vertical slice

Apple:
```swift
let rows = await repo.journalEntries()
let selected = rows.filter { $0.question == canonical }
let summary = JournalConsistency.summarize(
    dayKeys: selected.map(\.day),
    answeredYes: selected.map(\.answeredYes),
    today: Repository.localDayKey(Date())
)
```

Android:
```kotlin
val rows = mergeJournalEntries(imported, native).filter { it.question == canonical }
val summary = JournalConsistency.summarize(
    dayKeys = rows.map { it.day },
    answeredYes = rows.map { it.answeredYes },
    today = LocalDate.now().toString(),
)
```

No new Journal writes should be caused by loading either display. Show coverage
(`yesDays + noDays`) next to any streak; do not show "100% adherence" when only
one Yes has been observed. Sample questions must be user-chosen, not silently
enabled for all existing NOOP users.

## Acceptance gates for a UI release

1. Deterministic mirrored Apple/Android fixtures (missing vs No, explicit edits,
   future exclusion, leap-day continuity, historic-longest, window bounds).
2. Build/test native apps; exercise a blank Journal day, yes/no edits, revisiting
   dates, and app restart on real devices.
3. Display opt-in affirmative habits first; allow users to hide/rename/reorder
   using existing Journal catalog rather than creating a second habit database.
4. Accessibility, translations, backup/restore and privacy review.
5. Only then consider a configurable cue, two-minute entry action and lightweight
   completion affordance. Correlations with physiology remain *observational*;
   experiments need prospective registration and sufficient independent evidence.

## Limitless Protocol boundary

NOOP can become a locally trusted **data source** for user-owned tools through
existing on-device export and the downstream read-only local-access stack. A
separate companion may turn authorized observations into user-controlled routines.
It should not require a NOOP-operated server, background telemetry, cloud identity
or inferred hardware capabilities. The project's PolyForm Noncommercial license
does not authorize selling a NOOP-based commercial product.
