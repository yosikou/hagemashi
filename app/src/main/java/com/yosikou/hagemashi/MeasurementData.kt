package com.yosikou.hagemashi

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.math.BigDecimal
import org.json.JSONArray
import org.json.JSONObject

internal enum class GenerationKind { MORNING, EVENING, MANUAL }
internal enum class MeasureItem { GOAL, GOAL_DATE, READ_AT, WEIGHT_PERIOD, STEP_PERIOD, WEIGHT, BODY_FAT, STEPS, LEGACY }
internal enum class MeasureState { VALUE, NO_RECORDS, UNPERMITTED, ERROR }
internal data class MeasurementRow(val item: MeasureItem, val value: String?, val unit: String, val at: String,
    val state: MeasureState = MeasureState.VALUE, val origin: String = "")
internal fun measurementRows(snapshot: HealthSnapshot, goal: String?, goalDate: String?, zone: ZoneId): List<MeasurementRow> {
    fun time(value: Instant) = DateTimeFormatter.ISO_OFFSET_DATE_TIME.withZone(zone).format(value)
    fun period(window: HealthWindow) = time(window.start) + " 〜 " + time(window.end)
    fun status(value: HealthReadStatus) = when (value) {
        HealthReadStatus.PERMISSION_REQUIRED, HealthReadStatus.ACCESS_DENIED -> MeasureState.UNPERMITTED
        HealthReadStatus.ERROR -> MeasureState.ERROR
        else -> MeasureState.NO_RECORDS
    }
    val rows = mutableListOf<MeasurementRow>()
    goal?.let { rows += MeasurementRow(MeasureItem.GOAL, it, "kg", "") }
    goalDate?.let { rows += MeasurementRow(MeasureItem.GOAL_DATE, it, "", "") }
    rows += MeasurementRow(MeasureItem.READ_AT, null, "", time(snapshot.readAt))
    rows += MeasurementRow(MeasureItem.WEIGHT_PERIOD, null, "", period(snapshot.measurementWindow))
    rows += MeasurementRow(MeasureItem.STEP_PERIOD, null, "", period(snapshot.stepsWindow))
    snapshot.results.forEach { result ->
        val item = when (result.kind) { HealthKind.WEIGHT -> MeasureItem.WEIGHT; HealthKind.BODY_FAT -> MeasureItem.BODY_FAT; HealthKind.STEPS -> MeasureItem.STEPS }
        val unit = when (result.kind) { HealthKind.WEIGHT -> "kg"; HealthKind.BODY_FAT -> "%"; HealthKind.STEPS -> "歩" }
        if (result.samples.isNotEmpty()) result.samples.sortedBy { it.measuredAt }.forEach {
            rows += MeasurementRow(item, BigDecimal.valueOf(it.value).stripTrailingZeros().toPlainString(), unit, time(it.measuredAt), origin = it.origin)
        } else if (result.days.isNotEmpty()) result.days.sortedBy { it.window.start }.forEach {
            rows += MeasurementRow(item, it.value?.count?.toString(), unit, period(it.window),
                if (it.value == null) status(it.status) else MeasureState.VALUE, it.value?.origins?.sorted()?.joinToString(", ").orEmpty())
        } else if (result.steps != null) rows += MeasurementRow(item, result.steps.count.toString(), unit, period(snapshot.stepsWindow), origin = result.steps.origins.sorted().joinToString(", "))
        else rows += MeasurementRow(item, null, unit, period(if (result.kind == HealthKind.STEPS) snapshot.stepsWindow else snapshot.measurementWindow), status(result.status))
    }
    return rows.toList()
}
internal fun encodeRows(rows: List<MeasurementRow>): JSONArray = JSONArray().also { array -> rows.forEach {
    array.put(JSONObject().put("item", it.item.name).put("value", it.value ?: JSONObject.NULL).put("unit", it.unit)
        .put("at", it.at).put("state", it.state.name).put("origin", it.origin))
} }
internal fun decodeRows(array: JSONArray): List<MeasurementRow> = (0 until array.length()).map { i -> array.getJSONObject(i).let {
    MeasurementRow(MeasureItem.valueOf(it.getString("item")), if (it.isNull("value")) null else it.getString("value"),
        it.getString("unit"), it.getString("at"), MeasureState.valueOf(it.getString("state")), it.optString("origin"))
} }
// Read-only compatibility for the known v1/v2 formatter. Never rewrite or infer missing numerical values.
internal fun legacyMeasurementRows(input: String): List<MeasurementRow> {
    val lines = input.lines(); val rows = mutableListOf<MeasurementRow>()
    val weightPeriod = lines.firstOrNull { it.startsWith("体重・体脂肪率") && it.contains("の対象期間：") }?.substringAfter("の対象期間：").orEmpty()
    val stepPeriod = lines.firstOrNull { it.startsWith("歩数（") && it.contains("の対象期間：") }?.substringAfter("の対象期間：").orEmpty()
    for (line in lines) {
        val item = when {
            line.startsWith("目標体重：") -> MeasureItem.GOAL
            line.startsWith("目標日：") -> MeasureItem.GOAL_DATE
            line.startsWith("取得時刻：") -> MeasureItem.READ_AT
            line.startsWith("体重・体脂肪率") -> MeasureItem.WEIGHT_PERIOD
            line.startsWith("歩数（") -> MeasureItem.STEP_PERIOD
            line.startsWith("体重：") -> MeasureItem.WEIGHT
            line.startsWith("体脂肪率：") -> MeasureItem.BODY_FAT
            line.startsWith("歩数：") -> MeasureItem.STEPS
            else -> null
        } ?: continue
        val content = line.substringAfter('：', "")
        if (item == MeasureItem.WEIGHT_PERIOD || item == MeasureItem.STEP_PERIOD) { rows += MeasurementRow(item, null, "", content); continue }
        if (item == MeasureItem.READ_AT) { rows += MeasurementRow(item, null, "", content); continue }
        if (item == MeasureItem.GOAL_DATE) { rows += MeasurementRow(item, content, "", ""); continue }
        val amount = content.substringBefore('／').trim()
        val number = Regex("^([0-9]+(?:\\.[0-9]+)?)\\s*(kg|%|歩)$").matchEntire(amount)
        val at = when {
            content.contains("測定日時：") -> content.substringAfter("測定日時：").substringBefore('／')
            content.contains("集計期間：") -> content.substringAfter("集計期間：").substringBefore('／')
            item == MeasureItem.STEPS -> stepPeriod
            else -> weightPeriod
        }
        val state = when {
            number != null -> MeasureState.VALUE
            amount.contains("権限") || amount.contains("拒否") -> MeasureState.UNPERMITTED
            amount.contains("失敗") -> MeasureState.ERROR
            amount.contains("記録") -> MeasureState.NO_RECORDS
            else -> MeasureState.ERROR
        }
        rows += MeasurementRow(item, number?.groupValues?.get(1), number?.groupValues?.get(2).orEmpty(), at, state,
            if (content.contains("提供元：")) content.substringAfter("提供元：") else "")
    }
    return rows.sortedWith(compareBy<MeasurementRow> { it.item.ordinal }.thenBy {
        runCatching { java.time.OffsetDateTime.parse(it.at.substringBefore(" 〜 ")).toInstant() }.getOrDefault(Instant.MIN)
    }).ifEmpty { listOf(MeasurementRow(MeasureItem.LEGACY, null, "", "", MeasureState.ERROR)) }
}
