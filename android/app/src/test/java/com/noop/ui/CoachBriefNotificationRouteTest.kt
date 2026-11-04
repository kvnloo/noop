package com.noop.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #2087: tapping a Coach brief must open Coach, without hijacking other
 * notifications or bypassing the Coach master switch. The route rule is pure;
 * source tripwires pin the Android Activity/PendingIntent/Compose handoff.
 */
class CoachBriefNotificationRouteTest {
    private fun source(path: String): String {
        val dir = File(System.getProperty("user.dir") ?: ".")
        val root = listOf(dir, File(dir, ".."), File(dir, "../.."))
            .firstOrNull { File(it, "Strand/AI/AICoach.swift").isFile }
            ?: error("Could not find NOOP repository root from " + dir.absolutePath)
        return File(root, path).readText()
    }

    @Test
    fun `only enabled Coach accepts the brief route`() {
        assertEquals(Destination.Coach.route, coachNotificationDestination("coach", true))
        assertNull(coachNotificationDestination(null, true))
        assertNull(coachNotificationDestination("settings", true))
        assertNull(coachNotificationDestination("coach", false))
    }

    @Test
    fun `only Coach notifications get a destination extra`() {
        val brief = source("android/app/src/main/java/com/noop/ui/CoachBriefScheduler.kt")
        assertTrue(brief.contains("context, 5, coachBriefLaunchIntent(context),"))
        assertTrue(brief.contains("context, 6, coachBriefLaunchIntent(context),"))
        val generic = source("android/app/src/main/java/com/noop/notif/ScheduledReportNotifier.kt")
        assertTrue(generic.contains("appLaunchIntent(context)"))
        val activity = source("android/app/src/main/java/com/noop/ui/MainActivity.kt")
        assertTrue(activity.contains("appLaunchIntent(context).putExtra(EXTRA_NOTIFICATION_ROUTE, Destination.Coach.route)"))
    }

    @Test
    fun `cold and warm taps reach the same single-use navigation request`() {
        val activity = source("android/app/src/main/java/com/noop/ui/MainActivity.kt")
        val cold = activity.substringAfter("override fun onCreate(savedInstanceState: Bundle?)")
            .substringBefore("WindowCompat.setDecorFitsSystemWindows")
        assertTrue(cold.contains("captureNotificationRoute(intent)"))
        val warm = activity.substringAfter("override fun onNewIntent(intent: Intent)")
            .substringBefore("\n    }")
        assertTrue(warm.contains("captureNotificationRoute(intent)"))
        assertTrue(activity.contains("from?.removeExtra(EXTRA_NOTIFICATION_ROUTE)"))
        assertTrue(activity.contains("onNotificationRouteHandled = { pendingNotificationRoute = null }"))

        val root = source("android/app/src/main/java/com/noop/ui/AppRoot.kt")
        val effect = root.substringAfter("LaunchedEffect(notificationRoute)")
            .substringBefore("\n    }")
        assertTrue(effect.contains("coachNotificationDestination(notificationRoute, BottomBarStyleStore.coachEnabled)"))
        assertTrue(effect.contains("nav.navigateTopLevel(target)"))
        assertTrue(effect.contains("onNotificationRouteHandled()"))
    }
}
