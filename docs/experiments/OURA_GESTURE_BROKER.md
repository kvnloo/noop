# Owner broker gesture export (downstream experiment)

An optional password-field pairing link in Oura gesture setup enables one-way event export to the owner's HTTPS z0live broker. Use NOOP's own distinct pairing, never extract Lawnchair's credentials. Android Keystore AES-GCM encrypts the origin/token in noBackupFilesDir; credentials never enter logs or backups. Forget broker disables export. Default remains OFF/unconfigured.

The cooperative listener lease is claimed before arming existing 120s ACM. Three calibration double taps stay local. Subsequent double taps send `double_tap`, triple taps send `triple_tap`; envelope includes only source_device, Unix ts and UUID nonce. Broker mode never also sends the older PendingIntent (avoids double toggles). Network/lease errors fail closed; no automatic replay/new nonce retry or hidden local microphone. Stop/expiry/disconnect still use existing BLE OFF. There is no scanner/reset/rekey change, raw-motion/audio export or hosted receiver in NOOP.

Enable Lawnchair Standby visibly first. Full contract, routing, documented assistant exemptions and owner acceptance steps:
https://github.com/kvnloo/z0live/blob/feat/oura-context-routing/docs/GESTURES.md

This Android-only shell integration intentionally has no Swift twin. It does not change shared analytics/protocol/storage semantics. Experimental UI text currently uses English fallback pending translations. Exact-head CI proves compilation/tests/artifact integrity only; physical Gen3/S25/Android16 acceptance is NOT RUN. No phone installs or ADB were performed.
