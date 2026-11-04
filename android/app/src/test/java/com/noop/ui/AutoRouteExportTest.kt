package com.noop.ui

import com.noop.analytics.RouteMath
import com.noop.data.WorkoutRow
import com.noop.ingest.RouteExport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** #2682: recorded routes and privacy gates, without needing an Android device or SAF mock. */
class AutoRouteExportTest {
    private val twoPoints = RouteMath.encode(
        listOf(RouteMath.LatLng(41.90000, -87.65000), RouteMath.LatLng(41.90010, -87.64990)),
    )

    private fun row(polyline: String? = twoPoints) = WorkoutRow(
        deviceId = "my-whoop", startTs = 1_700_000_000L, endTs = 1_700_000_120L,
        sport = "Running", source = "manual", durationS = 120.0, routePolyline = polyline,
    )

    @Test fun defaultOffEvenForRealTwoPointTrack() {
        assertNull(AutoRouteExportPolicy.plan(row(), destinationSelected = false, writeGranted = true))
    }

    @Test fun revokedWriteGrantDoesNotExport() {
        assertNull(AutoRouteExportPolicy.plan(row(), destinationSelected = true, writeGranted = false))
    }

    @Test fun refusesMissingOnePointCorruptAndOutOfRangeTracks() {
        assertNull(AutoRouteExportPolicy.plan(row(null), true, true))
        assertNull(AutoRouteExportPolicy.plan(row(""), true, true))
        assertNull(AutoRouteExportPolicy.plan(row(RouteMath.encode(listOf(RouteMath.LatLng(41.9, -87.65)))), true, true))
        assertNull(AutoRouteExportPolicy.plan(row("?"), true, true))
        assertNull(AutoRouteExportPolicy.plan(row(RouteMath.encode(
            listOf(RouteMath.LatLng(95.0, 0.0), RouteMath.LatLng(95.1, 0.1)),
        )), true, true))
    }

    @Test fun refusesInvalidWorkoutWindow() {
        assertNull(AutoRouteExportPolicy.plan(row().copy(startTs = 0), true, true))
        assertNull(AutoRouteExportPolicy.plan(row().copy(endTs = 1_700_000_000L), true, true))
        assertNull(AutoRouteExportPolicy.plan(row().copy(endTs = 1_699_999_999L), true, true))
    }

    @Test fun optInProducesStableNameAndRealGpxPoints() {
        val plan = AutoRouteExportPolicy.plan(row(), destinationSelected = true, writeGranted = true)
        assertNotNull(plan)
        assertEquals("noop-route-1700000000.gpx", plan!!.fileName)
        assertEquals(2, plan.points.size)
        assertEquals(41.90000, plan.points.first().lat, 1e-5)
        assertEquals(-87.64990, plan.points.last().lon, 1e-5)
        val gpx = RouteExport.render(
            RouteExport.Format.GPX, plan.points, 1_700_000_000L, 1_700_000_120L, "Running",
        ).toString(Charsets.UTF_8)
        assertTrue(gpx.contains("<gpx version=\"1.1\""))
        assertEquals(2, gpx.split("<trkpt lat=").size - 1)
    }
}
