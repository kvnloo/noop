package com.noop.ingest

import com.noop.data.HrSample
import com.noop.data.SleepSession
import com.noop.data.WhoopDao
import com.noop.data.WhoopRepository
import java.lang.reflect.Proxy
import java.time.Instant
import java.util.TimeZone
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class HealthConnectHeartRateExportTest {
    private val savedZone = TimeZone.getDefault()

    @Before fun setUtc() { TimeZone.setDefault(TimeZone.getTimeZone("UTC")) }
    @After fun restoreZone() { TimeZone.setDefault(savedZone) }

    @Test fun stagedNightKeepsItsWindowWithoutDecimatingADisjointRawNap() = runBlocking {
        val deviceId = "my-whoop"
        val nightStart = Instant.parse("2026-06-14T00:00:00Z").epochSecond
        val nightEnd = nightStart + 8 * 3600
        val napStart = nightStart + 14 * 3600
        val napEnd = napStart + 1800
        val rawNight = SleepSession(deviceId, nightStart, nightEnd + 120)
        val rawNap = SleepSession(deviceId, napStart, napEnd)
        val computedNight = SleepSession(
            "$deviceId-noop", nightStart, nightEnd,
            stagesJSON = """[{"start":$nightStart,"end":$nightEnd,"stage":"light"}]""",
        )
        val nightPoints = nightEnd - 119..nightEnd
        val rawNightTail = nightEnd + 1..rawNight.endTs
        val napPoints = napStart until napEnd
        val hr = (nightPoints + rawNightTail + napPoints).map { HrSample(deviceId, it, 65) }
        val dao = Proxy.newProxyInstance(WhoopDao::class.java.classLoader, arrayOf(WhoopDao::class.java)) {
            _, method, args ->
            when (method.name) {
                "hrSamples" -> if (args[0] == deviceId) hr else emptyList<HrSample>()
                "sleepSessions" -> when (args[0]) {
                    deviceId -> listOf(rawNight, rawNap)
                    computedNight.deviceId -> listOf(computedNight)
                    else -> emptyList()
                }
                "workouts" -> emptyList<Nothing>()
                else -> error("Unexpected DAO read: ${method.name}")
            }
        } as WhoopDao

        val plan = HealthConnectWriter.planHeartRate(
            WhoopRepository(dao), deviceId,
            floor = nightStart - 86_400, frontier = nightStart - 60, now = napEnd + 3600,
        )
        val exported = plan.chunks.flatMap { chunk -> chunk.points.map { it.tsSec } }
        assertEquals(nightPoints.toList(), exported.filter { it in nightPoints })
        assertEquals("the overlapping raw night must not extend the computed window",
            listOf(nightEnd + 30, nightEnd + 60, nightEnd + 90, nightEnd + 120),
            exported.filter { it in rawNightTail })
        assertEquals("raw nap HR sample count", 1800, exported.count { it in napPoints })
        assertEquals(napPoints.toList(), exported.filter { it in napPoints })
    }
}
