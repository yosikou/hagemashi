package com.yosikou.hagemashi

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.CancellationException
import kotlinx.coroutines.runBlocking

class HealthReadTest {
    private val now = Instant.parse("2026-11-10T03:00:00Z")
    private val zone = ZoneId.of("Asia/Tokyo")
    private class Source : HealthDataSource {
        var granted = HealthKind.entries.toMutableSet()
        val calls = mutableListOf<HealthKind>()
        val windows = mutableMapOf<HealthKind, HealthWindow>()
        var page = HealthPage(emptyList())
        var stepValue: HealthSteps? = null
        var failure: Exception? = null
        var revokeWeightDuringRead = false
        override suspend fun permissions() = granted.toSet()
        override suspend fun samples(kind: HealthKind, window: HealthWindow): HealthPage {
            calls.add(kind); windows[kind] = window
            if (kind == HealthKind.WEIGHT) {
                failure?.let { throw it }
                if (revokeWeightDuringRead) granted.remove(kind)
            }
            return page
        }
        override suspend fun steps(window: HealthWindow): HealthSteps? {
            calls.add(HealthKind.STEPS); windows[HealthKind.STEPS] = window
            return stepValue
        }
    }
    private fun read(source: Source) = runBlocking { HealthReader(source).read(now, zone) }
    private fun HealthSnapshot.result(kind: HealthKind) = results.single { it.kind == kind }

    @Test fun missingPermissionsNeverReadRecordsOrFabricateZeroValues() {
        val source = Source().apply { granted.clear() }
        val snapshot = read(source)
        assertTrue(source.calls.isEmpty())
        assertTrue(snapshot.results.all { it.status == HealthReadStatus.PERMISSION_REQUIRED && it.samples.isEmpty() && it.steps == null })
    }

    @Test fun grantedButNoRecordsIsDifferentFromPermissionDenied() {
        val snapshot = read(Source())
        assertTrue(snapshot.results.all { it.status == HealthReadStatus.NO_RECORDS })
        assertNull(snapshot.result(HealthKind.STEPS).steps)
        assertTrue(snapshot.result(HealthKind.WEIGHT).samples.isEmpty())
    }

    @Test fun actualZeroStepsIsAValueWithItsOriginRatherThanAMissingRecord() {
        val snapshot = read(Source().apply { stepValue = HealthSteps(0, setOf("example.steps")) })
        val steps = snapshot.result(HealthKind.STEPS)
        assertEquals(HealthReadStatus.HAS_RECORDS, steps.status)
        assertEquals(0L, steps.steps!!.count)
        assertEquals(setOf("example.steps"), steps.steps.origins)
    }

    @Test fun partialPermissionDoesNotBlockOtherTypes() {
        val source = Source().apply { granted.remove(HealthKind.BODY_FAT); stepValue = HealthSteps(123, setOf("example.steps")) }
        val snapshot = read(source)
        assertFalse(source.calls.contains(HealthKind.BODY_FAT))
        assertEquals(HealthReadStatus.PERMISSION_REQUIRED, snapshot.result(HealthKind.BODY_FAT).status)
        assertEquals(HealthReadStatus.HAS_RECORDS, snapshot.result(HealthKind.STEPS).status)
    }

    @Test fun newestRecordsKeepTheMeasuredInstantAndActualProvider() {
        val older = HealthSample(70.25, now.minusSeconds(60), "example.first")
        val latest = HealthSample(69.85, now.minusSeconds(1), "example.second")
        val snapshot = read(Source().apply { page = HealthPage(listOf(older, latest), true) })
        val weight = snapshot.result(HealthKind.WEIGHT)
        assertEquals(listOf(latest, older), weight.samples)
        assertEquals(now.minusSeconds(1), weight.samples.first().measuredAt)
        assertEquals("example.second", weight.samples.first().origin)
        assertTrue(weight.hasMore)
    }

    @Test fun measurementsUseSevenDaysAndStepsUseTheLocalCalendarDay() {
        val source = Source()
        val snapshot = read(source)
        assertEquals(HealthWindow(now.minus(Duration.ofDays(7)), now), snapshot.measurementWindow)
        assertEquals(Instant.parse("2026-11-09T15:00:00Z"), snapshot.stepsWindow.start)
        assertEquals(now, snapshot.stepsWindow.end)
        assertEquals(snapshot.measurementWindow, source.windows[HealthKind.WEIGHT])
        assertEquals(snapshot.stepsWindow, source.windows[HealthKind.STEPS])
    }

    @Test fun localDayHandlesDstWithoutAssumingTwentyFourHours() = runBlocking {
        val snapshot = HealthReader(Source()).read(Instant.parse("2026-03-09T03:00:00Z"), ZoneId.of("America/New_York"))
        assertEquals(Instant.parse("2026-03-08T05:00:00Z"), snapshot.stepsWindow.start)
        assertEquals(Duration.ofHours(22), Duration.between(snapshot.stepsWindow.start, snapshot.stepsWindow.end))
    }

    @Test fun failedReadDoesNotBecomeNoRecordsOrRetainExceptionText() {
        val privateText = "synthetic-private-health-error"
        val snapshot = read(Source().apply { failure = IOException(privateText) })
        assertEquals(HealthReadStatus.ERROR, snapshot.result(HealthKind.WEIGHT).status)
        assertEquals(HealthReadStatus.NO_RECORDS, snapshot.result(HealthKind.BODY_FAT).status)
        assertFalse(snapshot.toString().contains(privateText))
    }

    @Test fun accessDeniedIsDifferentFromNoRecords() {
        val snapshot = read(Source().apply { failure = SecurityException("synthetic-private") })
        assertEquals(HealthReadStatus.ACCESS_DENIED, snapshot.result(HealthKind.WEIGHT).status)
        assertTrue(snapshot.result(HealthKind.WEIGHT).samples.isEmpty())
    }

    @Test fun revocationDuringReadRemovesPreviouslyFetchedValues() {
        val snapshot = read(Source().apply {
            page = HealthPage(listOf(HealthSample(68.0, now.minusSeconds(1), "example.writer")))
            revokeWeightDuringRead = true
        })
        assertEquals(HealthReadStatus.PERMISSION_REQUIRED, snapshot.result(HealthKind.WEIGHT).status)
        assertTrue(snapshot.result(HealthKind.WEIGHT).samples.isEmpty())
    }

    @Test fun cancellationIsNotReportedAsEmptyData() {
        assertThrows(CancellationException::class.java) { read(Source().apply { failure = CancellationException() }) }
    }
}
