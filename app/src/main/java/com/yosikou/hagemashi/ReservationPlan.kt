package com.yosikou.hagemashi

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import org.json.JSONArray
import org.json.JSONObject

internal data class ReservationPlan(val version: Long, val day: LocalDate, val at: Instant)
internal fun reservationAt(day: LocalDate, time: LocalTime, zone: ZoneId): Instant = day.atTime(time).atZone(zone).toInstant()
internal fun nextReservation(now: Instant, zone: ZoneId, cfg: AppSettings, claimed: Set<String>): ReservationPlan? {
    if (!cfg.notificationEnabled || cfg.testAnswerId == null) return null
    val today = now.atZone(zone).toLocalDate()
    for (offset in 0L..(claimed.size.toLong() + 1)) {
        val day = today.plusDays(offset); val at = reservationAt(day, cfg.notificationTime, zone)
        if (at > now && day.toString() !in claimed) return ReservationPlan(cfg.reservationVersion, day, at)
    }
    return null
}
internal fun reservationDue(version: Long, day: LocalDate, cfg: AppSettings, now: Instant, zone: ZoneId, claimed: Set<String>): Boolean =
    cfg.notificationEnabled && cfg.testAnswerId != null && cfg.reservationVersion == version && day == now.atZone(zone).toLocalDate() &&
        now >= reservationAt(day, cfg.notificationTime, zone) && day.toString() !in claimed
internal fun claimedDays(json: JSONObject): Set<String> {
    if (!json.has("schema")) { require(json.keys().asSequence().toSet() == setOf("host")); return emptySet() }
    require(json.getInt("schema") == 1)
    val list = json.getJSONArray("claimed")
    return (0 until list.length()).map { LocalDate.parse(list.getString(it)).toString() }.toSet()
}
internal fun claimDay(json: JSONObject, day: LocalDate): JSONObject {
    val days = claimedDays(json).toMutableSet(); days += day.toString()
    return json.put("schema", 1).put("claimed", JSONArray(days.toList()))
}
