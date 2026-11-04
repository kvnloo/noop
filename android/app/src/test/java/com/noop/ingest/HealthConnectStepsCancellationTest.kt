package com.noop.ingest

import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.time.TimeRangeFilter
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.time.Instant
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.cancellation.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Cancellation during Health Connect's live steps top-up must not be treated as a recoverable
 * permission, read or Room error. The private paginated reader is exercised with a throwing
 * HealthConnectClient; the other catch sites are source-anchored because the JVM unit suite
 * cannot substitute the platform SDK factory and Room repository used by refreshTodaySteps.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HealthConnectStepsCancellationTest {
    private fun invokePaginatedStepsRead(failure: Exception): Any? {
        val client = Proxy.newProxyInstance(
            HealthConnectClient::class.java.classLoader,
            arrayOf(HealthConnectClient::class.java),
        ) { _, method, _ ->
            check(method.name == "readRecords") { "Unexpected Health Connect call: ${method.name}" }
            throw failure
        } as HealthConnectClient

        // No new importer API or parity-ledger declaration: exercise the existing private reader.
        val readAll = HealthConnectImporter::class.java.declaredMethods.single {
            it.name == "readAll" && it.parameterTypes.last() == Continuation::class.java
        }
        readAll.isAccessible = true
        return readAll.invoke(
            HealthConnectImporter,
            client,
            StepsRecord::class,
            TimeRangeFilter.between(
                Instant.parse("2026-11-03T00:00:00Z"),
                Instant.parse("2026-11-04T00:00:00Z"),
            ),
            "com.noop.whoop",
            { _: StepsRecord -> error("The failing reader must not emit records") },
            object : Continuation<Boolean> {
                override val context = EmptyCoroutineContext
                override fun resumeWith(result: Result<Boolean>) {
                    error("The synchronous failing client must not suspend")
                }
            },
        )
    }

    @Test
    fun cancelledStepsReadRethrowsInsteadOfReturningFalse() {
        val cancellation = CancellationException("steps refresh cancelled")
        val thrown = assertThrows(InvocationTargetException::class.java) {
            invokePaginatedStepsRead(cancellation)
        }
        assertSame(cancellation, thrown.targetException)
    }

    @Test
    fun ordinaryStepsReadFailureStillReturnsFalse() {
        assertEquals(false, invokePaginatedStepsRead(IllegalStateException("provider unavailable")))
    }

    private val importerSource: String by lazy {
        // Gradle's :app test working directory is app/, but IDE/root invocations may use android/.
        val path = "src/main/java/com/noop/ingest/HealthConnectImporter.kt"
        val file = listOf(File(path), File("app/$path")).firstOrNull { it.isFile }
            ?: error("Cannot locate production HealthConnectImporter.kt from ${File(".").absolutePath}")
        file.readText()
    }

    private fun assertCatchRethrowsBeforeFallback(start: String, end: String, fallback: String) {
        val begin = importerSource.indexOf(start)
        assertTrue("Missing production start marker: $start", begin >= 0)
        val finish = importerSource.indexOf(end, begin + start.length)
        assertTrue("Missing production end marker: $end", finish > begin)
        val actual = importerSource.substring(begin, finish).replace(Regex("\\s+"), " ")
        val expected =
            "catch (e: Exception) { if (e is kotlin.coroutines.cancellation.CancellationException) " +
                "throw e $fallback"
        assertTrue("Cancellation was swallowed before $fallback", actual.contains(expected))
    }

    @Test
    fun cancelledPermissionLookupIsNotMisclassifiedAsNoGrant() {
        assertCatchRethrowsBeforeFallback(
            "suspend fun refreshTodaySteps(context: Context, repo: WhoopRepository): Int? {",
            "if (HealthPermission.getReadPermission(StepsRecord::class) !in granted) return null",
            "return null",
        )
    }

    @Test
    fun cancelledStoredRowLookupIsNotMisclassifiedAsMissingRow() {
        assertCatchRethrowsBeforeFallback(
            "val existing = try {\n            repo.appleDaily(HC_DEVICE, dayKey, dayKey).firstOrNull()",
            "// Zero is indistinguishable from",
            "null",
        )
    }

    @Test
    fun cancelledRoomUpsertIsNotMisclassifiedAsOldSteps() {
        assertCatchRethrowsBeforeFallback(
            "repo.upsertAppleDaily(listOf(updated))",
            "return sum.toInt()",
            "return existing?.steps",
        )
    }
}
