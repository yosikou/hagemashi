package com.yosikou.hagemashi

import java.math.BigDecimal
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

// Strings are frozen at review time. No health object, previous answer, or mutable UI is read at send time.
internal class ReviewedGeneration(val model: String, val modelName: String, val instructions: String, val input: String, val hasHealthData: Boolean = false, val personaName: String? = null, val rows: List<MeasurementRow> = emptyList(), val kind: GenerationKind = GenerationKind.MANUAL)
internal data class HealthPromptLabels(
    val goal: String, val readAt: String, val period: String, val measurements: String, val steps: String,
    val sample: String, val day: String, val stepCount: String, val missing: String, val weight: String, val bodyFat: String,
    val kg: String, val percent: String, val originsUnknown: String, val partial: String,
    val statuses: Map<HealthReadStatus, String>
)
internal fun canonicalGoal(text: String): String? {
    val value = java.text.Normalizer.normalize(text.trim(), java.text.Normalizer.Form.NFKC)
    if(value.isEmpty() || value.length > 64) return null
    return try {
        val number = BigDecimal(value)
        if(number.signum() <= 0 || kotlin.math.abs(number.scale().toLong()) > 64 || number.precision() > 64) null
        else number.stripTrailingZeros().toPlainString().takeIf { it.length <= 128 }
    } catch (_: NumberFormatException) { null }
}
internal fun healthGenerationInput(goal: String, snapshot: HealthSnapshot, zone: ZoneId, text: HealthPromptLabels): String {
    require(canonicalGoal(goal) == goal)
    fun format(template: String, vararg args: Any) = String.format(Locale.ROOT, template, *args)
    fun time(value: java.time.Instant) = DateTimeFormatter.ISO_OFFSET_DATE_TIME.withZone(zone).format(value)
    fun number(value: Double) = BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()
    val lines = mutableListOf(format(text.goal, goal), format(text.readAt, time(snapshot.readAt)),
        format(text.period, text.measurements, time(snapshot.measurementWindow.start), time(snapshot.measurementWindow.end)),
        format(text.period, text.steps, time(snapshot.stepsWindow.start), time(snapshot.stepsWindow.end)))
    for (kind in HealthKind.entries) {
        val result = snapshot.results.single { it.kind == kind }
        val label = when (kind) { HealthKind.WEIGHT -> text.weight; HealthKind.BODY_FAT -> text.bodyFat; HealthKind.STEPS -> text.steps }
        if (kind == HealthKind.STEPS && result.days.isNotEmpty()) {
            result.days.forEach { day ->
                val value = day.value
                val count = if (value == null) text.statuses.getValue(day.status) else format(text.stepCount, value.count.toString())
                lines += format(text.day, count, time(day.window.start), time(day.window.end),
                    value?.origins?.sorted()?.joinToString(", ")?.ifBlank { text.originsUnknown } ?: text.originsUnknown)
            }
        } else if (result.samples.isNotEmpty()) {
            result.samples.forEach { sample -> lines += format(text.sample, label, number(sample.value),
                if (kind == HealthKind.WEIGHT) text.kg else text.percent, time(sample.measuredAt), sample.origin.ifBlank { text.originsUnknown }) }
        } else {
            lines += format(text.missing, label, text.statuses.getValue(result.status))
        }
        if (result.hasMore) lines += text.partial
    }
    return lines.joinToString("\n")
}
