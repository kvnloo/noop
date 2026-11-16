# Wearable OSS reference map (downstream research, October 2026)

NOOP's north star remains WHOOP. This note maps optional Apple Watch and Wear OS expansion
to independent projects without assuming that vendor-app imports amount to direct hardware pairing.
Source links credit the original projects and maintainers. No third-party source is copied by this wave.

## What NOOP already has

- Apple Watch: native `NOOPWatch/` watchOS app (live foreground HR, workout session, glance/complications),
  `Strand/Data/AppleWatchDevice.swift` registry, and `StrandiOS/Health/HealthKitBridge.swift` import.
- Wear OS / Galaxy Watch / Pixel Watch: phone-side Health Connect import in
  `android/app/src/main/java/com/noop/ingest/HealthConnectImporter.kt`. A data writer app is **not**
  proof of any particular watch. Live Wear OS watch pairing is separate work.
- WHOOP BLE and scoring must remain unchanged by external source-discovery features.

## Credited OSS references

| Original project | Relevance | License / integration policy |
| --- | --- | --- |
| [aninda-ghosh/openFitness](https://github.com/aninda-ghosh/openFitness) | Apple Health recovery, strain, sleep, ECG viewer, Energy Bank UX | README claims MIT; repository has no detected LICENSE file. Ideas only pending license confirmation. |
| [eugenity92/bearing-ios](https://github.com/eugenity92/bearing-ios) | Pure-Swift readiness core, partial HealthKit permissions, overlapping sleep and DST test cases | README claims MIT; no detected LICENSE file. Ideas only pending confirmation. |
| [DanielJamesTronca/SleepChartKit](https://github.com/DanielJamesTronca/SleepChartKit) | Testable SwiftUI sleep-stage timeline and HealthKit conversion | MIT; attribution/notice if reused. |
| [kvs-coder/HealthKitReporter](https://github.com/kvs-coder/HealthKitReporter) | HealthKit anchored/query and source-data access patterns | MIT; NOOP already has a bridge, avoid a redundant dependency. |
| [piyush140104/galaxy-health-bridge](https://github.com/piyush140104/galaxy-health-bridge) | Direct local Galaxy Watch BLE relay to HealthKit; source provenance | MIT; one Galaxy Watch 4 + iPhone 14 verified by its author; not a general device-certification claim. |
| [StanleyMasinde/HrmApp](https://github.com/StanleyMasinde/HrmApp) | Wear OS Health Services -> standard BLE heart-rate service 0x180D | MIT; early stage, hardware validation required. |
| [chrisdfennell/HRPeripheral](https://github.com/chrisdfennell/HRPeripheral) | Wear OS BLE HR peripheral and companion | MIT; early implementation, evaluate rather than assume compatibility. |
| [android/health-samples](https://github.com/android/health-samples) | Official Health Connect, passive monitoring and ExerciseClient examples | Apache-2.0; attribute source if derived. |
| [android/wear-os-samples](https://github.com/android/wear-os-samples) | Official Wear OS Data Layer, complications, tiles, phone relay | Apache-2.0; attribute source if derived. |
| [the-momentum/open-wearables](https://github.com/the-momentum/open-wearables) | Canonical multi-provider schema, attribution, per-metric priorities | MIT; its cloud/OAuth/server architecture conflicts with NOOP offline-by-default. Study data design only. |
| [the-momentum/open_wearables_health_sdk](https://github.com/the-momentum/open_wearables_health_sdk) | Background HealthKit / Health Connect incremental sync examples | MIT; backend export is out of scope. |
| [Luraxx/pulse](https://github.com/Luraxx/pulse) | Pure scoring/fixtures for Fitbit Air via Google Health API | Apache-2.0; account/OAuth is NOT a silent default NOOP dependency. |
| [adidshaft/atria](https://github.com/adidshaft/atria) | Explicit confidence and validation gates for sensor-derived claims | MIT; study validation methodology and credit original authors. |
| [xoselourido/health-data-bridge](https://github.com/xoselourido/health-data-bridge) | Apple Health coverage checks and duplicate avoidance | MIT; WHOOP cloud dependency incompatible with NOOP core. |
| [OpenVitals-MTU/android-app](https://github.com/OpenVitals-MTU/android-app) | Local Health Connect, experimental Wear OS recorder, offline source choices | AGPL-3.0; architectural reference only, DO NOT copy into NOOP. |
| [OpenStrap/edge](https://github.com/OpenStrap/edge) | Recovery/strain and local-right-to-repair user experience | AGPL-3.0; conceptual comparison only, DO NOT copy. |
| [soma-intelligence/open-health-sync](https://github.com/soma-intelligence/open-health-sync) | Canonical provenance, unit normalization, explicit consent | AGPL-3.0, self-hosted backend; concepts only, DO NOT copy. |
| [skulptapp/skulpt](https://github.com/skulptapp/skulpt) | Offline-first workout app with a vendor-neutral AI-tool protocol | GPL-3.0; concepts only, DO NOT copy. |

Commercial design benchmarks, **not open source**: [Bevel](https://www.bevel.health/) (Recovery,
Sleep, Strain, watch complications; basic tier free as of July 2026) and
[Athlytic](https://athlyticapp.com/) (Recovery, Exertion, sleep debt). Do not reproduce proprietary code,
assets, branding, or infer their private formulas.

## Preferred integration trade route

1. **Already useful now:** Apple Watch -> HealthKit -> existing NOOP iOS bridge; Galaxy/Pixel ->
   vendor phone app -> Health Connect -> existing NOOP Android importer. Permissions stay opt-in.
2. **Origin visibility (this patch):** keep distinct writing-app package names from Health Connect;
   show known human-readable app labels in Data Sources. Labels are only app provenance, never
   a claim of Samsung/Garmin/Fitbit *hardware* detection.
3. **Next: per-sample attribution:** preserve `originApp`, optional `originDevice`, metric, unit,
   event timestamp and observation window before daily aggregation. Retain per-metric source priority
   so phone steps and watch steps don't double count. Do not migrate or rewrite stored user data
   without a reversible plan and Swift/Kotlin parity tests.
4. **Watch app parity:** use watchOS workout sessions for active high-frequency HR and Wear OS
   `ExerciseClient` for recording; passive Wear OS collection is batched, and `MeasureClient`
   is not intended as a 24/7 recording loophole. Battery budgets and hardware tests required.
5. **Source trust:** score only available, comparable signals; no fabricated HRV/SpO2/skin temperature,
   no commercial-algorithm claims, no default cloud network or credentials.

## P0 acceptance checks

- WHOOP BLE, source priority and all existing recovery equations unchanged.
- Normal Health Connect import (including empty imports) stores writer-app names locally and
  refreshes the Data Sources card; denied/revoked permissions do not clear the last successful run.
- Unknown writer package remains visible; no guessed watch model; sorting and dedup deterministic.
- Self-authored Health Connect records remain excluded, and steps/calories retain existing cross-writer
  de-overlap behavior.
- JVM unit coverage and a real Galaxy Watch/Samsung Health -> Health Connect phone test are separate
  evidence gates. A Kotlin compile is **not** hardware validation.

References: [Google Health Connect provenance](https://developer.android.com/health-and-fitness/health-connect/ui/data),
[Samsung Health -> Health Connect FAQ](https://developer.samsung.com/health/health-connect-faq.html),
[Apple workout session guidance](https://developer.apple.com/documentation/healthkit/running-workout-sessions),
[Wear OS Health Services](https://developer.android.com/health-and-fitness/health-services).
