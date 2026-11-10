package com.yosikou.hagemashi
import java.time.*
import java.net.URI
import org.json.JSONArray
import org.json.JSONObject

internal data class ReservationPlan(val slot:NoticeSlot,val version:Long,val day:LocalDate,val at:Instant)
internal fun reservationAt(day:LocalDate,time:LocalTime,zone:ZoneId):Instant=day.atTime(time).atZone(zone).toInstant()
internal fun slotKey(day:LocalDate,slot:NoticeSlot)="$day|${slot.name}"
internal fun reservationLedger(raw:JSONObject):JSONObject {
    if(!raw.has("schema")) {
        require(raw.keys().asSequence().toSet()==setOf("host"))
        return JSONObject().put("schema",2).put("claims",JSONArray()).put("reminders",JSONObject()).put("registrations",JSONObject())
    }
    require(raw.getInt("schema") in 1..2)
    if(raw.getInt("schema")==1) {
        val old=raw.getJSONArray("claimed")
        return JSONObject().put("schema",2).put("claims",JSONArray((0 until old.length()).map{slotKey(LocalDate.parse(old.getString(it)),NoticeSlot.MORNING)}))
            .put("reminders",JSONObject()).put("registrations",JSONObject())
    }
    val json=JSONObject(raw.toString())
    val claims=json.getJSONArray("claims")
    repeat(claims.length()) { val parts=claims.getString(it).split('|');require(parts.size==2);LocalDate.parse(parts[0]);NoticeSlot.valueOf(parts[1]) }
    val reminders=json.getJSONObject("reminders")
    reminders.keys().forEach { cycle -> require(cycle.toLong()>=0);val days=reminders.getJSONArray(cycle);repeat(days.length()){LocalDate.parse(days.getString(it))} }
    json.getJSONObject("registrations")
    return json
}
internal fun claimedSlots(json:JSONObject):Set<String> = json.getJSONArray("claims").let { a->(0 until a.length()).map{a.getString(it)}.toSet() }
internal fun reminderDays(json:JSONObject,cycle:Long):Set<String> = json.getJSONObject("reminders").optJSONArray(cycle.toString())?.let { a->(0 until a.length()).map{a.getString(it)}.toSet() }.orEmpty()
internal fun allReminderDays(json:JSONObject):Set<String> = json.getJSONObject("reminders").let { reminders ->
    reminders.keys().asSequence().flatMap { key -> reminders.getJSONArray(key).let { a -> (0 until a.length()).asSequence().map { a.getString(it) } } }.toSet()
}
internal fun claimSlot(raw:JSONObject,day:LocalDate,slot:NoticeSlot,reminderCycle:Long?=null):JSONObject {
    val json=reservationLedger(raw)
    json.put("claims",JSONArray((claimedSlots(json)+slotKey(day,slot)).toList()))
    if(reminderCycle!=null) {
        val days=reminderDays(json,reminderCycle);require(day.toString() !in allReminderDays(json) && days.size<3)
        json.getJSONObject("reminders").put(reminderCycle.toString(),JSONArray((days+day.toString()).toList()))
    }
    return json
}
internal fun slotDeadline(day:LocalDate,slot:NoticeSlot,cfg:AppSettings,zone:ZoneId):Instant =
    if(slot==NoticeSlot.MORNING && cfg.evening.enabled) reservationAt(day,cfg.evening.time,zone) else day.plusDays(1).atStartOfDay(zone).toInstant()
internal fun nextReservation(now:Instant,zone:ZoneId,cfg:AppSettings,slot:NoticeSlot,raw:JSONObject):ReservationPlan? {
    if(!cfg.slot(slot).enabled) return null
    val json=reservationLedger(raw);val claimed=claimedSlots(json)
    val waiting=goalNeedsReset(cfg,now.atZone(zone).toLocalDate())
    val reminders=allReminderDays(json)
    if(waiting && reminderDays(json,cfg.goalCycle).size>=3 || !waiting && cfg.testAnswerId==null) return null
    val today=now.atZone(zone).toLocalDate()
    for(offset in 0L..(claimed.size+reminders.size+2).toLong()) {
        val day=today.plusDays(offset);val at=reservationAt(day,cfg.slot(slot).time,zone)
        if(at>now && slotKey(day,slot) !in claimed && (!waiting || day.toString() !in reminders))
            return ReservationPlan(slot,cfg.version(slot),day,at)
    }
    return null
}
internal fun reservationDue(version:Long,day:LocalDate,slot:NoticeSlot,cfg:AppSettings,now:Instant,zone:ZoneId,raw:JSONObject):Boolean {
    val json=reservationLedger(raw)
    if(!cfg.slot(slot).enabled || version!=cfg.version(slot) || day!=now.atZone(zone).toLocalDate() ||
        now<reservationAt(day,cfg.slot(slot).time,zone) || now>=slotDeadline(day,slot,cfg,zone) || slotKey(day,slot) in claimedSlots(json)) return false
    return if(goalNeedsReset(cfg,day)) reminderDays(json,cfg.goalCycle).let{it.size<3 && day.toString() !in allReminderDays(json)} else cfg.testAnswerId!=null
}
internal fun isGoalReviewNavigation(action:String?,data:String?,applicationId:String):Boolean = runCatching {
    val uri=URI(data ?: "")
    action=="$applicationId.REVIEW_GOAL" && uri.scheme==applicationId && uri.rawAuthority=="goal-review" &&
        uri.rawQuery==null && uri.rawFragment==null && uri.rawPath.removePrefix("/").toLong()>=0
}.getOrDefault(false)
