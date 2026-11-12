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
internal data class HealthStepDay(val window: HealthWindow, val status: HealthReadStatus, val value: HealthSteps?)
internal enum class HealthReadPlan { INSPECTION, GENERATION }
internal data class HealthResult(val kind: HealthKind, val status: HealthReadStatus,
    val samples: List<HealthSample> = emptyList(), val hasMore: Boolean = false, val steps: HealthSteps? = null,
    val days: List<HealthStepDay> = emptyList())
internal data class HealthSnapshot(val readAt: Instant, val measurementWindow: HealthWindow, val stepsWindow: HealthWindow,
    val results: List<HealthResult>)

internal interface HealthDataSource {
    suspend fun permissions(): Set<HealthKind>
    suspend fun samples(kind: HealthKind, window: HealthWindow): HealthPage
    suspend fun steps(window: HealthWindow): HealthSteps?
}

// Read-only domain boundary: no AI client, prompt builder, storage, logger, scheduler, or notification dependency.
internal class HealthReader(private val source: HealthDataSource) {
    suspend fun read(now: Instant, zone: ZoneId, plan: HealthReadPlan = HealthReadPlan.INSPECTION, kind: GenerationKind = GenerationKind.MANUAL): HealthSnapshot {
        val generation = plan == HealthReadPlan.GENERATION
        val localDate = now.atZone(zone).toLocalDate()
        val cutoff = if (generation && kind == GenerationKind.MORNING) localDate.atStartOfDay(zone).toInstant() else now
        val measurements = HealthWindow(cutoff.minus(Duration.ofDays(if (generation) 30 else 7)), cutoff)
        val date = if (generation && kind == GenerationKind.MORNING) localDate.minusDays(1) else localDate
        val today = HealthWindow(date.minusDays(if (generation) 6 else 0).atStartOfDay(zone).toInstant(), cutoff)
        val granted = source.permissions()
        val results = HealthKind.entries.map { kind ->
            if (kind !in granted) HealthResult(kind, HealthReadStatus.PERMISSION_REQUIRED)
            else try {
                if (kind == HealthKind.STEPS) {
                    if (generation) {
                        val days = (6L downTo 0L).map { offset ->
                            val start = date.minusDays(offset).atStartOfDay(zone).toInstant()
                            val end = if (offset == 0L) cutoff else date.minusDays(offset - 1).atStartOfDay(zone).toInstant()
                            val window = HealthWindow(start, end)
                            try {
                                val value = if (start < end) source.steps(window) else null
                                HealthStepDay(window, if (value == null) HealthReadStatus.NO_RECORDS else HealthReadStatus.HAS_RECORDS, value)
                            } catch (e: CancellationException) { throw e }
                            catch (_: SecurityException) { HealthStepDay(window, HealthReadStatus.ACCESS_DENIED, null) }
                            catch (_: Exception) { HealthStepDay(window, HealthReadStatus.ERROR, null) }
                        }
                        val status = when {
                            days.any { it.value != null } -> HealthReadStatus.HAS_RECORDS
                            days.any { it.status == HealthReadStatus.ACCESS_DENIED } -> HealthReadStatus.ACCESS_DENIED
                            days.any { it.status == HealthReadStatus.ERROR } -> HealthReadStatus.ERROR
                            else -> HealthReadStatus.NO_RECORDS
                        }
                        HealthResult(kind, status, days = days)
                    } else {
                        val value = source.steps(today)
                        HealthResult(kind, if (value == null) HealthReadStatus.NO_RECORDS else HealthReadStatus.HAS_RECORDS, steps = value)
                    }
                } else {
                    val page = source.samples(kind, measurements)
                    val filtered = page.samples.filter { it.measuredAt >= measurements.start && it.measuredAt < measurements.end }
                    HealthResult(kind, if (filtered.isEmpty()) HealthReadStatus.NO_RECORDS else HealthReadStatus.HAS_RECORDS,
                        samples = filtered.sortedByDescending { it.measuredAt }.let { if (generation) it else it.take(20) },
                        hasMore = page.hasMore || (!generation && filtered.size > 20))
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
