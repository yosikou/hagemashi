package com.yosikou.hagemashi

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.json.JSONArray
import org.json.JSONObject

// Local alarms and saved answers only. Never call the AI client or read Health Connect here.
internal object ReservationSupport {
    const val available = true
    val lock = Any()
    private fun state(context: Context) = CredentialVault(context, "reservation-state.v1", "hagemashi.reservation.v1")
    private fun action(context: Context) = "${context.packageName}.DELIVER_RESERVATION"
    private fun pending(context: Context, version: Long, plan: ReservationPlan? = null, noCreate: Boolean = false): PendingIntent? {
        val intent = Intent(context, ReservationReceiver::class.java).setAction(action(context))
            .setData(Uri.parse("${context.packageName}://reservation/$version"))
        if (plan != null) intent.putExtra("version", version).putExtra("day", plan.day.toString())
        return PendingIntent.getBroadcast(context, 42, intent, PendingIntent.FLAG_IMMUTABLE or
            if (noCreate) PendingIntent.FLAG_NO_CREATE else PendingIntent.FLAG_UPDATE_CURRENT)
    }
    private fun cancel(context: Context, version: Long) {
        pending(context, version, noCreate = true)?.let { requireNotNull(context.getSystemService(AlarmManager::class.java)).cancel(it); it.cancel() }
    }
    private fun emptyState(context: Context): JSONObject {
        val json = state(context).read(); val claimed = claimedDays(json)
        return json.put("schema", 1).put("claimed", JSONArray(claimed.toList())).put("registeredVersion", -1)
    }
    fun changed(context: Context, old: AppSettings, current: AppSettings) = synchronized(lock) {
        if (old.reservationVersion != current.reservationVersion) cancel(context, old.reservationVersion)
        ensure(context, current, old.reservationVersion != current.reservationVersion)
    }
    private fun ensure(context: Context, cfg: AppSettings, force: Boolean) {
        val raw = state(context).read(); val claimed = claimedDays(raw)
        val today = LocalDate.now()
        val registered = raw.optLong("registeredVersion", -1)
        if (registered >= 0 && registered != cfg.reservationVersion) cancel(context, registered)
        val storedDay = raw.optString("day").let { runCatching { LocalDate.parse(it) }.getOrNull() }
        val valid = cfg.notificationEnabled && cfg.testAnswerId != null &&
            AnswerNotifications(context).allowed() && runCatching { AnswerStore(context).load(cfg.testAnswerId) }.isSuccess
        if (!valid) {
            cancel(context, cfg.reservationVersion)
            state(context).write(emptyState(context)); return
        }
        if (!force && raw.optLong("registeredVersion", -1) == cfg.reservationVersion && storedDay != null && storedDay >= today &&
            storedDay.toString() !in claimed && raw.optString("zone") == ZoneId.systemDefault().id) {
            val at = reservationAt(storedDay, cfg.notificationTime, ZoneId.systemDefault())
            val plan = ReservationPlan(cfg.reservationVersion, storedDay, if (at > Instant.now()) at else Instant.now().plusSeconds(15))
            requireNotNull(context.getSystemService(AlarmManager::class.java)).setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,
                plan.at.toEpochMilli(), requireNotNull(pending(context, cfg.reservationVersion, plan)))
            state(context).write(raw.put("trigger", plan.at.toEpochMilli()))
            return
        }
        cancel(context, cfg.reservationVersion)
        val plan = nextReservation(Instant.now(), ZoneId.systemDefault(), cfg, claimed)
        if (plan == null) { state(context).write(emptyState(context)); return }
        val alarm = requireNotNull(context.getSystemService(AlarmManager::class.java))
        alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, plan.at.toEpochMilli(), requireNotNull(pending(context, cfg.reservationVersion, plan)))
        state(context).write(raw.put("schema", 1).put("claimed", JSONArray(claimed.toList())).put("registeredVersion", cfg.reservationVersion)
            .put("day", plan.day.toString()).put("zone", ZoneId.systemDefault().id).put("trigger", plan.at.toEpochMilli()))
    }
    fun reschedule(context: Context) = synchronized(lock) { val cfg = SettingsStore(context).read(); ensure(context, cfg, true) }
    fun deliver(context: Context, version: Long, day: LocalDate) = synchronized(lock) {
        val cfg = SettingsStore(context).read()
        if (!cfg.notificationEnabled || cfg.reservationVersion != version) return@synchronized
        val raw = state(context).read()
        val eligible = reservationDue(version, day, cfg, Instant.now(), ZoneId.systemDefault(), claimedDays(raw))
        if (eligible && AnswerNotifications(context).allowed()) {
            val answer = AnswerStore(context).load(requireNotNull(cfg.testAnswerId))
            // Durable claim BEFORE notifying: a crash must not cause duplicate same-day delivery.
            state(context).write(claimDay(raw, day).put("registeredVersion", -1))
            val result = runCatching { AnswerNotifications(context).post(answer, "reservation:$day") }.getOrDefault(NoticeState.FAILED)
            val after = state(context).read(); state(context).write(after.put("lastResult", result.name))
        }
        ensure(context, cfg, eligible)
    }
    fun status(context: Context, cfg: AppSettings): Int = synchronized(lock) {
        when {
            !cfg.notificationEnabled -> R.string.reservation_off
            cfg.testAnswerId == null -> R.string.reservation_choose_answer
            !AnswerNotifications(context).allowed() -> R.string.reservation_permission
            runCatching { AnswerStore(context).load(cfg.testAnswerId) }.isFailure -> R.string.reservation_missing_answer
            else -> {
                val raw = state(context).read()
                if (LocalDate.now().toString() in claimedDays(raw)) R.string.reservation_claimed
                else if (raw.optLong("registeredVersion", -1) == cfg.reservationVersion) R.string.reservation_registered
                else R.string.reservation_not_registered
            }
        }
    }
}
