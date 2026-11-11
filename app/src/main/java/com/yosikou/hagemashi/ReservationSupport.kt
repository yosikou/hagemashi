package com.yosikou.hagemashi
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import java.time.*
import org.json.JSONObject

internal object ReservationSupport {
    const val available=true
    val lock=Any()
    private fun state(context:Context)=CredentialVault(context,"reservation-state.v1","hagemashi.reservation.v1")
    private fun action(context:Context)="${context.packageName}.DELIVER_RESERVATION"
    private fun pending(context:Context,slot:NoticeSlot,version:Long,plan:ReservationPlan?=null,noCreate:Boolean=false):PendingIntent? {
        val intent=Intent(context,ReservationReceiver::class.java).setAction(action(context))
            .setData(Uri.parse("${context.packageName}://reservation/${slot.name}/$version"))
        if(plan!=null) intent.putExtra("slot",slot.name).putExtra("version",version).putExtra("day",plan.day.toString())
        return PendingIntent.getBroadcast(context,42+slot.ordinal,intent,PendingIntent.FLAG_IMMUTABLE or if(noCreate)PendingIntent.FLAG_NO_CREATE else PendingIntent.FLAG_UPDATE_CURRENT)
    }
    private fun cancel(context:Context,slot:NoticeSlot,version:Long,reason:ReservationReason) {
        if(version<0)return
        ScheduledGeneration.cancel(context,slot,version)
        pending(context,slot,version,noCreate=true)?.let{
            ReservationDiagnostics.record(context,ReservationEvent.CANCEL_STARTED,slot,version,reason=reason)
            try {requireNotNull(context.getSystemService(AlarmManager::class.java)).cancel(it);it.cancel()
                ReservationDiagnostics.record(context,ReservationEvent.CANCEL_RETURNED,slot,version,reason=reason)
            } catch(e:Exception){ReservationDiagnostics.record(context,ReservationEvent.CANCEL_FAILED,slot,version,reason=ReservationDiagnostics.failure(e));throw e}
        }
    }
    private fun cancelLegacy(context:Context,version:Long) {
        if(version<0)return
        val intent=Intent(context,ReservationReceiver::class.java).setAction(action(context)).setData(Uri.parse("${context.packageName}://reservation/$version"))
        PendingIntent.getBroadcast(context,42,intent,PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_NO_CREATE)?.let{
            ReservationDiagnostics.record(context,ReservationEvent.CANCEL_STARTED,version=version,reason=ReservationReason.LEGACY)
            requireNotNull(context.getSystemService(AlarmManager::class.java)).cancel(it);it.cancel()
            ReservationDiagnostics.record(context,ReservationEvent.CANCEL_RETURNED,version=version,reason=ReservationReason.LEGACY)
        }
    }
    private fun ledger(context:Context):JSONObject {
        val raw=state(context).read()
        if(raw.optInt("schema")==1)cancelLegacy(context,raw.optLong("registeredVersion",-1))
        return reservationLedger(raw)
    }
    fun changed(context:Context,old:AppSettings,current:AppSettings)=synchronized(lock) {
        cancelLegacy(context,old.reservationVersion)
        NoticeSlot.entries.forEach { slot ->
            if(old.version(slot)!=current.version(slot))cancel(context,slot,old.version(slot),ReservationReason.VERSION_CHANGED)
            ensure(context,current,slot,old.version(slot)!=current.version(slot))
        }
    }
    private fun arm(context:Context,cfg:AppSettings,plan:ReservationPlan,json:JSONObject) {
        ReservationDiagnostics.record(context,ReservationEvent.REGISTER_STARTED,plan.slot,plan.version,plan.at.toEpochMilli())
        try { requireNotNull(context.getSystemService(AlarmManager::class.java)).setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,plan.at.toEpochMilli(),requireNotNull(pending(context,plan.slot,plan.version,plan)))
        } catch(e:Exception){ReservationDiagnostics.record(context,ReservationEvent.REGISTER_FAILED,plan.slot,plan.version,reason=ReservationDiagnostics.failure(e));throw e}
        ReservationDiagnostics.record(context,ReservationEvent.REGISTER_RETURNED,plan.slot,plan.version,plan.at.toEpochMilli())
        if(cfg.automaticGeneration && !goalNeedsReset(cfg,plan.day))ScheduledGeneration.schedule(context,plan)
        json.getJSONObject("registrations").put(plan.slot.name,JSONObject().put("version",plan.version).put("day",plan.day.toString()).put("zone",ZoneId.systemDefault().id).put("trigger",plan.at.toEpochMilli()))
        try {state(context).write(json);ReservationDiagnostics.record(context,ReservationEvent.LEDGER_SAVED,plan.slot,plan.version,plan.at.toEpochMilli())}
        catch(e:Exception){ReservationDiagnostics.record(context,ReservationEvent.LEDGER_FAILED,plan.slot,plan.version,reason=ReservationDiagnostics.failure(e));throw e}
    }
    // Called before the first startup refresh can repair/re-arm alarms. NO_CREATE never registers one.
    fun captureTokensBeforeRefresh(context:Context,cfg:AppSettings) { NoticeSlot.entries.forEach { slot ->
        runCatching {
            ReservationDiagnostics.record(context,if(pending(context,slot,cfg.version(slot),noCreate=true)!=null)
                ReservationEvent.TOKEN_PRESENT_BEFORE_REFRESH else ReservationEvent.TOKEN_ABSENT_BEFORE_REFRESH,slot,cfg.version(slot))
        }.onFailure{ReservationDiagnostics.record(context,ReservationEvent.TOKEN_CHECK_FAILED,slot,cfg.version(slot))}
    } }
    private fun ensure(context:Context,cfg:AppSettings,slot:NoticeSlot,force:Boolean) {
        val json=ledger(context);val registrations=json.getJSONObject("registrations")
        val registered=registrations.optJSONObject(slot.name)
        val oldVersion=registered?.optLong("version",-1) ?: -1
        if(oldVersion>=0 && oldVersion!=cfg.version(slot))cancel(context,slot,oldVersion,ReservationReason.VERSION_CHANGED)
        val today=LocalDate.now();val waiting=goalNeedsReset(cfg,today)
        val valid=cfg.slot(slot).enabled && AnswerNotifications(context).allowed() &&
            (if(waiting) reminderDays(json,cfg.goalCycle).size<3 else if(cfg.automaticGeneration)cfg.goal!=null && cfg.modelId!=null else cfg.testAnswerId?.let{runCatching{AnswerStore(context).load(it)}.isSuccess}==true)
        if(!valid) {
            ReservationDiagnostics.record(context,ReservationEvent.REGISTER_SKIPPED,slot,cfg.version(slot),reason=when {
                !cfg.slot(slot).enabled->ReservationReason.SLOT_DISABLED
                !AnswerNotifications(context).allowed()->ReservationReason.NOTIFICATIONS_BLOCKED
                waiting->ReservationReason.REMINDER_LIMIT
                cfg.testAnswerId==null->ReservationReason.NO_TARGET
                else->ReservationReason.TARGET_UNREADABLE
            })
            cancel(context,slot,cfg.version(slot),ReservationReason.DISABLED_OR_UNAVAILABLE);registrations.remove(slot.name);state(context).write(json);return
        }
        val storedDay=registered?.optString("day")?.let{runCatching{LocalDate.parse(it)}.getOrNull()}
        if(!force && oldVersion==cfg.version(slot) && storedDay!=null && storedDay>=today &&
            slotKey(storedDay,slot) !in claimedSlots(json) && (!waiting || storedDay.toString() !in allReminderDays(json)) &&
            registered?.optString("zone")==ZoneId.systemDefault().id) {
            val target=reservationAt(storedDay,cfg.slot(slot).time,ZoneId.systemDefault())
            val retryAt=if(target>Instant.now())target else Instant.now().plusSeconds(15)
            if(retryAt<slotDeadline(storedDay,slot,cfg,ZoneId.systemDefault())) {arm(context,cfg,ReservationPlan(slot,cfg.version(slot),storedDay,retryAt),json);return}
        }
        cancel(context,slot,cfg.version(slot),ReservationReason.REPLAN)
        val plan=nextReservation(Instant.now(),ZoneId.systemDefault(),cfg,slot,json)
        if(plan==null){ReservationDiagnostics.record(context,ReservationEvent.REGISTER_SKIPPED,slot,cfg.version(slot),reason=ReservationReason.NO_FUTURE_PLAN);registrations.remove(slot.name);state(context).write(json)}else arm(context,cfg,plan,json)
    }
    fun reschedule(context:Context)=synchronized(lock){val cfg=SettingsStore(context).read();NoticeSlot.entries.forEach{ensure(context,cfg,it,!cfg.automaticGeneration)}}
    fun deliver(context:Context,version:Long,day:LocalDate,slot:NoticeSlot)=synchronized(lock) {
        ReservationDiagnostics.record(context,ReservationEvent.DELIVERY_STARTED,slot,version)
        val cfg=SettingsStore(context).read()
        ReservationDiagnostics.record(context,ReservationEvent.SETTINGS_LOADED,slot,version)
        if(!cfg.slot(slot).enabled || version!=cfg.version(slot)) {
            ReservationDiagnostics.record(context,ReservationEvent.DELIVERY_SKIPPED,slot,version,reason=if(!cfg.slot(slot).enabled)ReservationReason.SLOT_DISABLED else ReservationReason.VERSION_MISMATCH)
            return@synchronized
        }
        if(cfg.automaticGeneration && !goalNeedsReset(cfg,LocalDate.now())) {
            val json=ledger(context);val now=Instant.now();val zone=ZoneId.systemDefault()
            if(reservationDue(version,day,slot,cfg,now,zone,json))ScheduledGeneration.schedule(context,ReservationPlan(slot,version,day,now))
            else ReservationDiagnostics.record(context,ReservationEvent.DELIVERY_SKIPPED,slot,version,reason=reservationDecision(version,day,slot,cfg,now,zone,json))
            return@synchronized
        }
        val json=ledger(context)
        ReservationDiagnostics.record(context,ReservationEvent.LEDGER_LOADED,slot,version)
        val now=Instant.now();val zone=ZoneId.systemDefault()
        val due=reservationDue(version,day,slot,cfg,now,zone,json)
        if(!due || !AnswerNotifications(context).allowed()) ReservationDiagnostics.record(context,ReservationEvent.DELIVERY_SKIPPED,slot,version,
            reason=if(!due)reservationDecision(version,day,slot,cfg,now,zone,json) else ReservationReason.NOTIFICATIONS_BLOCKED)
        if(due && AnswerNotifications(context).allowed()) {
            val waiting=goalNeedsReset(cfg,day)
            ReservationDiagnostics.record(context,ReservationEvent.ANSWER_LOAD_STARTED,slot,version)
            val answer=if(waiting)null else AnswerStore(context).load(requireNotNull(cfg.testAnswerId))
            ReservationDiagnostics.record(context,ReservationEvent.ANSWER_LOADED,slot,version)
            ReservationDiagnostics.record(context,ReservationEvent.CLAIM_STARTED,slot,version)
            state(context).write(claimSlot(json,day,slot,if(waiting)cfg.goalCycle else null))
            ReservationDiagnostics.record(context,ReservationEvent.CLAIM_SAVED,slot,version)
            ReservationDiagnostics.record(context,ReservationEvent.NOTIFY_STARTED,slot,version)
            val result=runCatching{if(waiting)AnswerNotifications(context).postGoalReminder(cfg.goalCycle,day)
                else AnswerNotifications(context).post(requireNotNull(answer),"reservation:$day:${slot.name}")}
                .onFailure{ReservationDiagnostics.record(context,ReservationEvent.NOTIFY_FAILED,slot,version,reason=if(it is Exception)ReservationDiagnostics.failure(it)else ReservationReason.OTHER_ERROR)}
                .getOrDefault(NoticeState.FAILED)
            ReservationDiagnostics.record(context,ReservationEvent.NOTIFY_RETURNED,slot,version,reason=when(result) {
                NoticeState.POSTED->ReservationReason.POSTED;NoticeState.PERMISSION_DENIED->ReservationReason.PERMISSION_DENIED
                NoticeState.DISABLED->ReservationReason.DISABLED;else->ReservationReason.FAILED
            })
            if(result==NoticeState.POSTED)ReservationDiagnostics.notificationActive(context,if(waiting)"goal-guide:$day" else "reservation:$day:${slot.name}",slot,version)
            state(context).write(ledger(context).put("lastResult",result.name))
        }
        // Unchanged other-slot plans stay intact; a guide claim excludes both slots for this day.
        NoticeSlot.entries.forEach{ensure(context,cfg,it,it==slot && due)}
    }
    fun automaticState(context:Context):JSONObject=ledger(context)
    fun claimAutomatic(context:Context,day:LocalDate,slot:NoticeSlot) { state(context).write(claimSlot(ledger(context),day,slot)) }
    fun failAutomatic(context:Context,day:LocalDate,slot:NoticeSlot,reason:ReservationReason) {
        val json=claimSlot(ledger(context),day,slot)
        val statuses=json.optJSONObject("automaticStatus") ?: JSONObject()
        statuses.put(slot.name,JSONObject().put("day",day.toString()).put("reason",reason.name))
        state(context).write(json.put("automaticStatus",statuses))
    }
    fun slotStatus(context:Context,cfg:AppSettings,slot:NoticeSlot):Int=synchronized(lock) {
        when {
            !cfg.slot(slot).enabled -> R.string.reservation_slot_off
            !AnswerNotifications(context).allowed()->R.string.reservation_permission
            else->{
                val json=ledger(context);val today=LocalDate.now()
                if(goalNeedsReset(cfg,today)) {
                    val days=reminderDays(json,cfg.goalCycle)
                    if(days.size>=3)R.string.goal_guide_limit else if(today.toString() in allReminderDays(json))R.string.goal_guide_done else R.string.goal_guide_only
                }else if(cfg.automaticGeneration) {
                    val failure=json.optJSONObject("automaticStatus")?.optJSONObject(slot.name)
                    if(cfg.goal==null || cfg.modelId==null)R.string.automatic_missing_settings
                    else if(failure?.optString("day")==today.toString())R.string.automatic_action_required
                    else if(slotKey(today,slot) in claimedSlots(json))R.string.reservation_claimed else R.string.automatic_registered
                }
                else if(cfg.testAnswerId==null)R.string.reservation_choose_answer
                else if(runCatching{AnswerStore(context).load(cfg.testAnswerId)}.isFailure)R.string.reservation_missing_answer
                else if(slotKey(today,slot) in claimedSlots(json))R.string.reservation_claimed
                else if(json.getJSONObject("registrations").optJSONObject(slot.name)?.optLong("version",-1)==cfg.version(slot))R.string.reservation_registered
                else R.string.reservation_not_registered
            }
        }
    }
    fun status(context:Context,cfg:AppSettings):Int = if(!cfg.morning.enabled && !cfg.evening.enabled)R.string.reservation_off else if(cfg.automaticGeneration)R.string.automatic_notice else R.string.reservation_test_only
    fun slotStatuses(context:Context,cfg:AppSettings):Map<NoticeSlot,Int> = NoticeSlot.entries.associateWith{slotStatus(context,cfg,it)}
}
