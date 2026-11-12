package com.yosikou.hagemashi

import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

// A read-only projection. Never use these rows for generation, persistence or goal evaluation.
internal data class MeasurementDisplay(val rows: List<MeasurementRow>, val combined: Boolean)
private data class MeasurementDisplayKey(val item: MeasureItem, val day: java.time.LocalDate, val value: BigDecimal, val unit: String)
private fun measurementInstant(value: String): Instant? = runCatching { java.time.OffsetDateTime.parse(value.trim()).toInstant() }.getOrNull()

internal fun measurementDisplay(rows: List<MeasurementRow>, zone: ZoneId): MeasurementDisplay {
    val groups = linkedMapOf<MeasurementDisplayKey, MutableList<MeasurementRow>>()
    fun key(row: MeasurementRow): MeasurementDisplayKey? {
        if (row.item !in setOf(MeasureItem.WEIGHT, MeasureItem.BODY_FAT) || row.state != MeasureState.VALUE) return null
        val instant = measurementInstant(row.at) ?: return null
        val value = row.value?.toBigDecimalOrNull()?.stripTrailingZeros() ?: return null
        return MeasurementDisplayKey(row.item, instant.atZone(zone).toLocalDate(), value, row.unit)
    }
    rows.forEach { row -> key(row)?.let { groups.getOrPut(it) { mutableListOf() }.add(row) } }
    val emitted = mutableSetOf<MeasurementDisplayKey>()
    val projected = rows.mapNotNull { row ->
        val groupKey = key(row) ?: return@mapNotNull row
        if (!emitted.add(groupKey)) return@mapNotNull null
        val members = groups.getValue(groupKey)
        members.maxBy { measurementInstant(it.at)!! }.copy(origin = members.map { it.origin }.filter { it.isNotBlank() }.distinct().sorted().joinToString(", "))
    }
    // Each measurement section remains in measurement-time order after choosing representatives.
    val ordered = projected.toMutableList()
    listOf(MeasureItem.WEIGHT, MeasureItem.BODY_FAT).forEach { item ->
        val indices = projected.indices.filter { projected[it].item == item && measurementInstant(projected[it].at) != null }
        val sorted = indices.map { projected[it] }.sortedBy { measurementInstant(it.at) }
        indices.zip(sorted).forEach { (index, row) -> ordered[index] = row }
    }
    return MeasurementDisplay(ordered, groups.values.any { it.size > 1 })
}

// Null means unknown/unsupported time; callers show a localized placeholder, never a raw timestamp.
internal fun measurementDisplayTime(value: String, zone: ZoneId, locale:Locale=Locale.JAPANESE): String? {
    val parts = value.split(" 〜 ")
    if (parts.size !in 1..2) return null
    val instants = parts.map { measurementInstant(it) ?: return null }
    return instants.joinToString(if(locale.language=="ja")"\n〜\n" else "\n–\n") { localizedMoment(it,zone,locale,false) }
}

internal fun answerDisplayTime(value: Instant, zone: ZoneId, locale:Locale=Locale.JAPANESE): String = localizedMoment(value,zone,locale,true)

// Only the saved answer's rows enter this function. No live Health Connect reads or inferred zeroes.
internal fun latestAnswerMeasurements(rows: List<MeasurementRow>): List<MeasurementRow> =
    listOf(MeasureItem.WEIGHT, MeasureItem.BODY_FAT, MeasureItem.STEPS).map { item ->
        val candidates = rows.filter { it.item == item }
        val values = candidates.filter { it.state == MeasureState.VALUE && it.value != null }
        val selected = values.ifEmpty { candidates }
        selected.maxByOrNull { measurementInstant(it.at.substringBefore(" 〜 ")) ?: Instant.MIN }
            ?: MeasurementRow(item, null, "", "", if (rows.any { it.item == MeasureItem.LEGACY }) MeasureState.ERROR else MeasureState.NO_RECORDS)
    }

// Daily Health Connect step windows have an exclusive end. Multi-day legacy windows stay ranges.
internal fun measurementDisplayStepDates(value: String, zone: ZoneId, locale:Locale=Locale.JAPANESE): String? {
    val parts = value.split(" 〜 ")
    if (parts.size != 2) return null
    val start = measurementInstant(parts[0]) ?: return null
    val end = measurementInstant(parts[1])?.takeIf { it > start } ?: return null
    val first = start.atZone(zone).toLocalDate()
    val last = end.minusNanos(1).atZone(zone).toLocalDate()
    return if (first == last) localizedDay(first,locale) else localizedDay(first,locale) + (if(locale.language=="ja")"\n〜\n" else "\n–\n") + localizedDay(last,locale)
}
