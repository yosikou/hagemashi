package com.yosikou.hagemashi

import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.CancellationException

internal enum class HealthKind { WEIGHT, BODY_FAT, STEPS }
internal enum class HealthReadStatus { PERMISSION_REQUIRED, NO_RECORDS, HAS_RECORDS, ACCESS_DENIED, ERROR }
internal data class HealthWindow(val start: Instant, val end: Instant)
internal data class HealthSample(val value: Double, val measuredAt: Instant, val origin: String)
internal data class HealthPage(val samples: List<HealthSample>, val hasMore: Boolean = false)
internal data class HealthSteps(val count: Long, val origins: Set<String>)
internal data class HealthResult(val kind: HealthKind, val status: HealthReadStatus,
    val samples: List<HealthSample> = emptyList(), val hasMore: Boolean = false, val steps: HealthSteps? = null)
internal data class HealthSnapshot(val readAt: Instant, val measurementWindow: HealthWindow, val stepsWindow: HealthWindow,
    val results: List<HealthResult>)

internal interface HealthDataSource {
    suspend fun permissions(): Set<HealthKind>
    suspend fun samples(kind: HealthKind, window: HealthWindow): HealthPage
    suspend fun steps(window: HealthWindow): HealthSteps?
}

// Read-only domain boundary: no AI client, prompt builder, storage, logger, scheduler, or notification dependency.
internal class HealthReader(private val source: HealthDataSource) {
    suspend fun read(now: Instant, zone: ZoneId): HealthSnapshot {
        val measurements = HealthWindow(now.minus(Duration.ofDays(7)), now)
        val today = HealthWindow(now.atZone(zone).toLocalDate().atStartOfDay(zone).toInstant(), now)
        val granted = source.permissions()
        val results = HealthKind.entries.map { kind ->
            if (kind !in granted) HealthResult(kind, HealthReadStatus.PERMISSION_REQUIRED)
            else try {
                if (kind == HealthKind.STEPS) {
                    val value = source.steps(today)
                    HealthResult(kind, if (value == null) HealthReadStatus.NO_RECORDS else HealthReadStatus.HAS_RECORDS, steps = value)
                } else {
                    val page = source.samples(kind, measurements)
                    HealthResult(kind, if (page.samples.isEmpty()) HealthReadStatus.NO_RECORDS else HealthReadStatus.HAS_RECORDS,
                        samples = page.samples.sortedByDescending { it.measuredAt }.take(20), hasMore = page.hasMore || page.samples.size > 20)
                }
            } catch (e: CancellationException) { throw e }
            catch (_: SecurityException) { HealthResult(kind, HealthReadStatus.ACCESS_DENIED) }
            catch (_: Exception) { HealthResult(kind, HealthReadStatus.ERROR) }
        }
        // If permission was revoked during the reads, do not publish any now-unpermitted values.
        val finalGrants = source.permissions()
        return HealthSnapshot(now, measurements, today, results.map {
            if (it.kind !in finalGrants) HealthResult(it.kind, HealthReadStatus.PERMISSION_REQUIRED) else it
        })
    }
}
