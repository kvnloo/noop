package com.noop.ingest

import org.junit.Assert.assertEquals
import org.junit.Test

class HealthConnectWriterSnapshotTest {
    private val previous = setOf("test.writer.alpha", "test.writer.beta")
    private val seen = setOf("test.writer.alpha", "test.writer.gamma")

    @Test
    fun completeReadReplacesPreviousWritersExactly() {
        assertEquals(seen, nextWriterSnapshot(previous, seen, allReadsOk = true))
    }

    @Test
    fun partialReadKeepsPreviousWritersWithoutAddingNewOnes() {
        assertEquals(previous, nextWriterSnapshot(previous, seen, allReadsOk = false))
    }

    @Test
    fun totalReadFailureKeepsPreviousWriters() {
        assertEquals(previous, nextWriterSnapshot(previous, emptySet(), allReadsOk = false))
    }

    @Test
    fun completeEmptyReadClearsPreviousWriters() {
        assertEquals(emptySet<String>(), nextWriterSnapshot(previous, emptySet(), allReadsOk = true))
    }
}
