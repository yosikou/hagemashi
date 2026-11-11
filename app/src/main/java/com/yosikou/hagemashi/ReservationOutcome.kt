package com.yosikou.hagemashi

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

internal enum class ReservationOutcome { SAVED, SENDING, POSTED, BLOCKED, FAILED, GENERATION_RETRYING, GENERATION_FAILED, SKIPPED, UNKNOWN }
internal fun reservationRunKey(day:LocalDate,slot:NoticeSlot,version:Long)="${slotKey(day,slot)}|$version"
internal fun runOutcome(json:JSONObject,day:LocalDate,slot:NoticeSlot,version:Long):ReservationOutcome? =
    json.optJSONObject("outcomes")?.optJSONObject(reservationRunKey(day,slot,version))?.getString("state")?.let(ReservationOutcome::valueOf)
internal fun recordRunOutcome(raw:JSONObject,day:LocalDate,slot:NoticeSlot,version:Long,outcome:ReservationOutcome,answerId:String?=null):JSONObject {
    require(version>=0);require(answerId==null || isAnswerId(answerId))
    if(outcome in setOf(ReservationOutcome.SAVED,ReservationOutcome.SENDING,ReservationOutcome.POSTED,ReservationOutcome.BLOCKED,ReservationOutcome.FAILED,ReservationOutcome.UNKNOWN))require(answerId!=null)
    val json=reservationLedger(raw);val outcomes=json.optJSONObject("outcomes") ?: JSONObject()
    outcomes.put(reservationRunKey(day,slot,version),JSONObject().put("state",outcome.name).put("answerId",answerId ?: JSONObject.NULL))
    return json.put("outcomes",outcomes)
}
internal fun runClaimed(json:JSONObject,day:LocalDate,slot:NoticeSlot,cfg:AppSettings):Boolean {
    if(goalNeedsReset(cfg,day))return slotKey(day,slot) in claimedSlots(json)
    val key=reservationRunKey(day,slot,cfg.version(slot))
    val claims=json.optJSONArray("runClaims") ?: JSONArray()
    return (0 until claims.length()).any{claims.getString(it)==key} ||
        (cfg.automaticGeneration && runOutcome(json,day,slot,cfg.version(slot)) in setOf(ReservationOutcome.POSTED,ReservationOutcome.GENERATION_FAILED,ReservationOutcome.SKIPPED,ReservationOutcome.UNKNOWN))
}
internal fun claimRun(raw:JSONObject,day:LocalDate,slot:NoticeSlot,version:Long):JSONObject {
    val json=reservationLedger(raw);val claims=json.optJSONArray("runClaims") ?: JSONArray()
    val key=reservationRunKey(day,slot,version)
    if((0 until claims.length()).none{claims.getString(it)==key})claims.put(key)
    return json.put("runClaims",claims)
}
