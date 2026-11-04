# Android contextual focus experiment

**Branch:** `fork/wip-android-ephemeral-contextual-hom-51`
**Status:** fork-only prototype, source for `channels/nightly`; do not merge into `main` or propose upstream.
**Source:** [kvnloo/noop#51](https://github.com/kvnloo/noop/pull/51), ported from its own `3a0b74b2...1156204e` diff to `main` at `2a1de760`.

## Interaction contract

- **One primary number:** existing, on-device Charge (0–100), always dated.
  Missing/invalid scores display an em dash; never invent a value.
- **Two supporting readings:** Sleep and live heart rate when streaming, otherwise Effort.
  Each opens its existing detailed screen.
- **One contextual action:** active workout, scanning, disconnected, awaiting a score, or review.
  The order is deterministic and unit tested; no AI/network inference.
- **Ephemeral suggestions:** dismiss for the current context only. They return on context
  transitions, and are never saved in the database or preferences.
- **Two-item dock:** Today and More. All advanced features remain in existing routes.
- **Escape hatch:** Full dashboard reveals the original Today. Use its Quick actions
  drawer → Return to focus to switch back, without altering layout preferences.

## Verification

```bash
git checkout fork/wip-android-ephemeral-contextual-hom-51
cd android
./gradlew --no-build-cache --rerun-tasks assembleFullDebug testFullDebugUnitTest
adb install -r app/build/outputs/apk/full/debug/app-full-debug.apk
```

The debug build has its own `com.noop.whoop.debug` application id, so it can coexist
with the normal release app. **Do not assume it shares the production app's BLE bond
or database**; pairing and local data must be tested independently.

Check fresh onboarding, empty data, active workout handoff, BLE scan and reconnect,
Today/More navigation, screen-reader labels, large fonts, and dark/light modes.
No BLE or analytics algorithms are changed; real-device testing still matters.

All nine existing Android resource locales are covered by the experiment's strings.
The original #51 supplied English, German, Spanish, French, and European Portuguese;
this current-main port adds Italian, Polish, Russian, and Chinese.
The design uses the current `NoopType`, `Palette`, and `Metrics` tokens.

**Pilot constraint:** #51 is only a UI source branch. A longitudinal Breathe →
Journal pilot must also incorporate either [#53](https://github.com/kvnloo/noop/pull/53)
(no implicit previous-answer copy) or [#58](https://github.com/kvnloo/noop/pull/58)
(explicit copy confirmation), not both without review. Neither PR is an ancestry
or build-time dependency of this UI port. No pilot or hardware validation is claimed.

## Next experiments

Consider finer contextual signals (e.g. newly synced sleep), contextual shortcut
ranking, and OS-level glance surfaces. Keep any recommendations explainable and
local-first. Do not turn this prototype into a second scoring system.
