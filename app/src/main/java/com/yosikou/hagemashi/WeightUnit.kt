package com.yosikou.hagemashi

import java.math.BigDecimal
import java.math.RoundingMode
import java.text.Normalizer

// International avoirdupois pound; persistence and Health Connect remain in kilograms.
internal enum class WeightUnit(val symbol: String) { KG("kg"), LB("lb");
    fun fromKg(value: BigDecimal): BigDecimal = if (this == KG) value else value.divide(POUND_KG, 12, RoundingMode.HALF_UP)
    fun toKg(value: BigDecimal): BigDecimal = if (this == KG) value else value.multiply(POUND_KG)
    fun display(kg: String, decimals: Int = 2): String = fromKg(BigDecimal(kg)).setScale(decimals, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
    fun input(kg: String): String = display(kg, 6)
    fun prompt(kg: String): String = fromKg(BigDecimal(kg)).stripTrailingZeros().toPlainString()
    companion object { private val POUND_KG = BigDecimal("0.45359237") }
}
// Accept plain positive decimals (including NFKC digits), up to six fractional places.
internal fun weightInputKg(text: String, unit: WeightUnit): String? {
    val normalized = Normalizer.normalize(text.trim(), Normalizer.Form.NFKC)
    if (!Regex("[0-9]+(?:\\.[0-9]{1,6})?").matches(normalized)) return null
    val canonical = canonicalGoal(normalized) ?: return null
    return canonicalGoal(unit.toKg(BigDecimal(canonical)).stripTrailingZeros().toPlainString())
}
// An unchanged draft must never write a rounded projection back to the stored kg target.
internal fun goalDraftKg(text: String, initial: String, savedKg: String?, unit: WeightUnit): String? =
    if (text == initial && savedKg != null) savedKg else weightInputKg(text, unit)

internal fun weightDisplayRow(row: MeasurementRow, unit: WeightUnit, precise: Boolean = false): MeasurementRow {
    if (row.item !in setOf(MeasureItem.GOAL, MeasureItem.WEIGHT) || row.unit !in setOf("kg", "lb")) return row
    val value = row.value?.toBigDecimalOrNull()
    val kg = value?.let { if (row.unit == "lb") WeightUnit.LB.toKg(it) else it }
    return row.copy(value = kg?.let { if (precise) unit.prompt(it.toPlainString()) else unit.display(it.toPlainString()) }, unit = unit.symbol)
}
