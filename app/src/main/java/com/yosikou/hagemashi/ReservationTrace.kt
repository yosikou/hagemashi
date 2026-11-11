package com.yosikou.hagemashi

import org.json.JSONArray
import org.json.JSONObject

// Only allowlisted operational metadata. Never accept messages, intents, IDs or response bodies.
internal enum class ReservationEvent {
    JOB_ENQUEUED, JOB_ENQUEUE_FAILED, JOB_STARTED, JOB_FAILED, JOB_STOPPED, JOB_SKIPPED, JOB_RETRY, AUTO_BUSY,
    HEALTH_STARTED, HEALTH_READ, HEALTH_EMPTY, HEALTH_UNPERMITTED, HEALTH_UNSUPPORTED, HEALTH_FAILED,
    CHATGPT_STARTED, CHATGPT_COMPLETED, CHATGPT_FAILED, AUTO_SAVED, AUTO_REUSED, AUTO_SAVE_FAILED,
    APP_OPEN, APP_VISIBLE, APP_HIDDEN, TOKEN_PRESENT_BEFORE_REFRESH, TOKEN_ABSENT_BEFORE_REFRESH, TOKEN_CHECK_FAILED,
    REGISTER_STARTED, REGISTER_SKIPPED, REGISTER_RETURNED, REGISTER_FAILED, LEDGER_SAVED, LEDGER_FAILED,
    CANCEL_STARTED, CANCEL_RETURNED, CANCEL_FAILED, RECEIVER_ENTERED, HANDLER_STARTED,
    RECEIVER_FINISHED, RECEIVER_FAILED, INVALID_DELIVERY, SYSTEM_RESCHEDULE,
    DELIVERY_STARTED, SETTINGS_LOADED, LEDGER_LOADED, DELIVERY_SKIPPED, ANSWER_LOAD_STARTED, ANSWER_LOADED, CLAIM_STARTED, CLAIM_SAVED,
    NOTIFY_STARTED, NOTIFY_FAILED, NOTIFY_RETURNED, NOTIFICATION_ACTIVE, NOTIFICATION_NOT_ACTIVE, NOTIFICATION_CHECK_FAILED
}
internal enum class ReservationReason {
    MISSING_SETTINGS, AUTH_REQUIRED, MODEL_UNAVAILABLE, NETWORK_ERROR, RATE_LIMIT, PROTOCOL_ERROR, CANCELLED,
    NONE, VERSION_CHANGED, LEGACY, REPLAN, DISABLED_OR_UNAVAILABLE, DUE, SLOT_DISABLED,
    TARGET_UNREADABLE, NO_FUTURE_PLAN, VERSION_MISMATCH, WRONG_LOCAL_DAY, EARLY, WINDOW_ENDED, ALREADY_CLAIMED, NO_TARGET,
    REMINDER_LIMIT, NOTIFICATIONS_BLOCKED, POSTED, PERMISSION_DENIED, DISABLED, FAILED,
    SECURITY_ERROR, IO_ERROR, STORAGE_ERROR, OTHER_ERROR
}
internal data class ReservationTraceEvent(val event: ReservationEvent, val reason: ReservationReason,
    val at: Long, val elapsed: Long, val pid: Int, val build: String, val slot: NoticeSlot? = null,
    val version: Long? = null, val trigger: Long? = null)
internal fun traceJson(event: ReservationTraceEvent): JSONObject = JSONObject()
    .put("event",event.event.name).put("reason",event.reason.name).put("at",event.at)
    .put("elapsed",event.elapsed).put("pid",event.pid).put("build",event.build)
    .put("slot",event.slot?.name ?: JSONObject.NULL).put("version",event.version ?: JSONObject.NULL)
    .put("trigger",event.trigger ?: JSONObject.NULL)
internal fun sanitizeReservationTrace(raw: JSONArray, limit: Int = 128): JSONArray {
    val safe = JSONArray()
    // Reconstruct existing entries through the same allowlist; do not copy arbitrary JSON fields.
    for (i in maxOf(0,raw.length()-limit) until raw.length()) runCatching {
        val item=raw.getJSONObject(i)
        val build=item.getString("build").also { require(it.matches(Regex("[A-Za-z0-9._-]{1,100}"))) }
        safe.put(traceJson(ReservationTraceEvent(ReservationEvent.valueOf(item.getString("event")),
            ReservationReason.valueOf(item.getString("reason")),item.getLong("at"),item.getLong("elapsed"),item.getInt("pid"),build,
            if(item.isNull("slot"))null else NoticeSlot.valueOf(item.getString("slot")),
            if(item.isNull("version"))null else item.getLong("version"),if(item.isNull("trigger"))null else item.getLong("trigger"))))
    }
    return safe
}
internal fun appendReservationTrace(raw: JSONArray, event: ReservationTraceEvent): JSONArray {
    require(event.build.matches(Regex("[A-Za-z0-9._-]{1,100}")))
    return sanitizeReservationTrace(raw,127).put(traceJson(event))
}
internal fun reservationDecision(version:Long,day:java.time.LocalDate,slot:NoticeSlot,cfg:AppSettings,
    now:java.time.Instant,zone:java.time.ZoneId,json:JSONObject):ReservationReason = when {
    !cfg.slot(slot).enabled -> ReservationReason.SLOT_DISABLED
    version!=cfg.version(slot) -> ReservationReason.VERSION_MISMATCH
    day!=now.atZone(zone).toLocalDate() -> ReservationReason.WRONG_LOCAL_DAY
    now<reservationAt(day,cfg.slot(slot).time,zone) -> ReservationReason.EARLY
    now>=slotDeadline(day,slot,cfg,zone) -> ReservationReason.WINDOW_ENDED
    slotKey(day,slot) in claimedSlots(json) -> ReservationReason.ALREADY_CLAIMED
    goalNeedsReset(cfg,day) -> if(reminderDays(json,cfg.goalCycle).size>=3 || day.toString() in allReminderDays(json)) ReservationReason.REMINDER_LIMIT else ReservationReason.DUE
    !cfg.automaticGeneration && cfg.testAnswerId==null -> ReservationReason.NO_TARGET
    else -> ReservationReason.DUE
}
