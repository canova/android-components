/* Any copyright is dedicated to the Public Domain.
   http://creativecommons.org/publicdomain/zero/1.0/ */

package mozilla.components.service.glean.storages

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import mozilla.components.service.glean.private.Lifetime
import mozilla.components.service.glean.private.TimeUnit
import mozilla.components.service.glean.private.TimespanMetricType
import mozilla.components.service.glean.resetGlean
import mozilla.components.service.glean.timing.TimingManager
import org.junit.After

import org.junit.Before
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.runner.RunWith
import org.mockito.Mockito.eq
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.TimeUnit as AndroidTimeUnit

@RunWith(RobolectricTestRunner::class)
class TimespansStorageEngineTest {
    @Before
    fun setUp() {
        resetGlean()
        TimespansStorageEngine.applicationContext = ApplicationProvider.getApplicationContext()
        TimespansStorageEngine.clearAllStores()
    }

    @After
    fun reset() {
        TimingManager.testResetTimeSource()
    }

    @Test
    fun `timespan deserializer should correctly parse JSONArray(s)`() {
        val expectedValue: Long = 37
        val persistedSample = mapOf(
            "store1#telemetry.invalid_bool" to false,
            "store1#telemetry.invalid_string" to "c4ff33",
            "store1#telemetry.valid" to "[${TimeUnit.Nanosecond.ordinal}, $expectedValue]"
        )

        val storageEngine = TimespansStorageEngineImplementation()

        // Create a fake application context that will be used to load our data.
        val context = mock(Context::class.java)
        val sharedPreferences = mock(SharedPreferences::class.java)
        `when`(sharedPreferences.all).thenAnswer { persistedSample }
        `when`(context.getSharedPreferences(
            eq(storageEngine::class.java.canonicalName),
            eq(Context.MODE_PRIVATE)
        )).thenReturn(sharedPreferences)
        `when`(context.getSharedPreferences(
            eq("${storageEngine::class.java.canonicalName}.PingLifetime"),
            eq(Context.MODE_PRIVATE)
        )).thenReturn(ApplicationProvider.getApplicationContext<Context>()
            .getSharedPreferences("${storageEngine::class.java.canonicalName}.PingLifetime",
                Context.MODE_PRIVATE))

        storageEngine.applicationContext = context
        val snapshot = storageEngine.getSnapshotWithTimeUnit(storeName = "store1", clearStore = true)
        assertEquals(1, snapshot!!.size)
        assertEquals(Pair("nanosecond", expectedValue), snapshot["telemetry.valid"])
    }

    @Test
    fun `a single elapsed time must be correctly recorded`() {
        val expectedTimespanNanos: Long = 37

        val metric = TimespanMetricType(
            false,
            "telemetry",
            Lifetime.Ping,
            "single_elapsed_test",
            listOf("store1"),
            timeUnit = TimeUnit.Nanosecond
        )

        // Return 0 the first time we get the time
        TimingManager.getElapsedNanos = { 0 }
        metric.start(this)

        // Return the expected time when we stop the timer.
        TimingManager.getElapsedNanos = { expectedTimespanNanos }
        metric.stopAndSum(this)

        assertTrue(metric.testHasValue())

        val snapshot = TimespansStorageEngine.getSnapshotWithTimeUnit(storeName = "store1", clearStore = false)
        assertEquals(1, snapshot!!.size)
        assertEquals(Pair("nanosecond", expectedTimespanNanos), snapshot["telemetry.single_elapsed_test"])
    }

    @Test
    fun `multiple elapsed times must be correctly accumulated`() {
        val expectedChunkNanos: Long = 37

        val metric = TimespanMetricType(
            false,
            "telemetry",
            Lifetime.Ping,
            "single_elapsed_test",
            listOf("store1"),
            timeUnit = TimeUnit.Nanosecond
        )

        // Record the time for the first chunk.
        TimingManager.getElapsedNanos = { 0 }
        metric.start(this)
        TimingManager.getElapsedNanos = { expectedChunkNanos }
        metric.stopAndSum(this)

        // Record the time for the second chunk of time.
        metric.start(this)
        TimingManager.getElapsedNanos = { expectedChunkNanos * 2 }
        metric.stopAndSum(this)

        assertTrue(metric.testHasValue())

        val snapshot = TimespansStorageEngine.getSnapshotWithTimeUnit(storeName = "store1", clearStore = false)
        assertEquals(1, snapshot!!.size)
        assertEquals(Pair("nanosecond", expectedChunkNanos * 2), snapshot["telemetry.single_elapsed_test"])
    }

    @Test
    fun `the recorded time must conform to the chosen resolution`() {
        val expectedLengthInNanos: Long = AndroidTimeUnit.DAYS.toNanos(3)
        val expectedResults = mapOf(
            TimeUnit.Nanosecond to expectedLengthInNanos,
            TimeUnit.Microsecond to AndroidTimeUnit.NANOSECONDS.toMicros(expectedLengthInNanos),
            TimeUnit.Millisecond to AndroidTimeUnit.NANOSECONDS.toMillis(expectedLengthInNanos),
            TimeUnit.Second to AndroidTimeUnit.NANOSECONDS.toSeconds(expectedLengthInNanos),
            TimeUnit.Minute to AndroidTimeUnit.NANOSECONDS.toMinutes(expectedLengthInNanos),
            TimeUnit.Hour to AndroidTimeUnit.NANOSECONDS.toHours(expectedLengthInNanos),
            TimeUnit.Day to AndroidTimeUnit.NANOSECONDS.toDays(expectedLengthInNanos)
        )

        expectedResults.forEach { (res, expectedTimespan) ->
            val metric = TimespanMetricType(
                false,
                "telemetry",
                Lifetime.Ping,
                "resolution_test",
                listOf("store1"),
                timeUnit = res
            )

            // Record the timespan in the provided resolution.
            TimingManager.getElapsedNanos = { 0 }
            metric.start(this)
            TimingManager.getElapsedNanos = { expectedLengthInNanos }
            metric.stopAndSum(this)
            assertTrue(metric.testHasValue())

            val snapshot = TimespansStorageEngine.getSnapshotWithTimeUnit(storeName = "store1", clearStore = true)
            assertEquals(1, snapshot!!.size)
            assertEquals(Pair(res.name.toLowerCase(), expectedTimespan), snapshot["telemetry.resolution_test"])
        }
    }

    @Test
    fun `accumulated short-lived timespans should not be discarded`() {
        // We'll attempt to accumulate 10 timespans with a sub-second duration.
        val steps = 10
        val expectedTimespanSeconds: Long = 1
        val timespanFraction: Long = AndroidTimeUnit.SECONDS.toNanos(expectedTimespanSeconds) / steps

        val metric = TimespanMetricType(
            false,
            "telemetry",
            Lifetime.Ping,
            "many_short_lived_test",
            listOf("store1"),
            timeUnit = TimeUnit.Second
        )

        // Accumulate enough timespans with a duration lower than the
        // expected resolution. Their sum should still be >= than our
        // resolution, and thus be reported.
        for (i in 0..steps) {
            val timespanStart: Long = i * timespanFraction
            TimingManager.getElapsedNanos = { timespanStart }
            metric.start(this)

            val timespanEnd: Long = timespanStart + timespanFraction
            TimingManager.getElapsedNanos = { timespanEnd }
            metric.stopAndSum(this)
        }

        assertTrue(metric.testHasValue())
        // Since the sum of the short-lived timespans is >= our resolution, we
        // expect the accumulated value to be in the snapshot.
        val snapshot = TimespansStorageEngine.getSnapshotWithTimeUnit(storeName = "store1", clearStore = true)
        assertEquals(1, snapshot!!.size)
        assertEquals(
            Pair("second", expectedTimespanSeconds),
            snapshot["telemetry.many_short_lived_test"]
        )
    }
}
