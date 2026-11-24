package com.noop.ui

import com.noop.data.ImportSummary

/** Run a due foreground import, advancing its throttle only after a completed scan. */
internal suspend fun syncHealthConnectImportIfStale(
    force: Boolean,
    last: Long,
    now: Long,
    intervalMs: Long,
    import: suspend () -> ImportSummary?,
    stamp: () -> Unit,
) {
    if (!force && last != 0L && now - last < intervalMs) return
    val summary = import()
    if (summary?.succeeded == true) stamp()
}
