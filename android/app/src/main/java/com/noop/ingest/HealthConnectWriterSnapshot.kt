package com.noop.ingest

internal fun nextWriterSnapshot(
    previous: Set<String>,
    observedOriginPackages: Set<String>,
    allReadsOk: Boolean,
): Set<String> = if (allReadsOk) observedOriginPackages.toSet() else previous
