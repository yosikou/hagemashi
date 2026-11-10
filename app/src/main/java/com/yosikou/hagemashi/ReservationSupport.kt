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
    private fun cancel(context:Context,slot:NoticeSlot,version:Long) {
        if(version<0)return
        pending(context,slot,version,noCreate=true)?.let{requireNotNull(context.getSystemService(AlarmManager::class.java)).cancel(it);it.cancel()}
    }
    private fun cancelLegacy(context:Context,version:Long) {
        if(version<0)return
        val intent=Intent(context,ReservationReceiver::class.java).setAction(action(context)).setData(Uri.parse("${context.packageName}://reservation/$version"))
        PendingIntent.getBroadcast(context,42,intent,PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_NO_CREATE)?.let{
            requireNotNull(context.getSystemService(AlarmManager::class.java)).cancel(it);it.cancel()
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
            if(old.version(slot)!=current.version(slot))cancel(context,slot,old.version(slot))
            ensure(context,current,slot,old.version(slot)!=current.version(slot))
        }
    }
    private fun arm(context:Context,cfg:AppSettings,plan:ReservationPlan,json:JSONObject) {
        requireNotNull(context.getSystemService(AlarmManager::class.java)).setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,plan.at.toEpochMilli(),requireNotNull(pending(context,plan.slot,plan.version,plan)))
        json.getJSONObject("registrations").put(plan.slot.name,JSONObject().put("version",plan.version).put("day",plan.day.toString()).put("zone",ZoneId.systemDefault().id).put("trigger",plan.at.toEpochMilli()))
        state(context).write(json)
    }
    private fun ensure(context:Context,cfg:AppSettings,slot:NoticeSlot,force:Boolean) {
        val json=ledger(context);val registrations=json.getJSONObject("registrations")
        val registered=registrations.optJSONObject(slot.name)
        val oldVersion=registered?.optLong("version",-1) ?: -1
        if(oldVersion>=0 && oldVersion!=cfg.version(slot))cancel(context,slot,oldVersion)
        val today=LocalDate.now();val waiting=goalNeedsReset(cfg,today)
        val valid=cfg.slot(slot).enabled && AnswerNotifications(context).allowed() &&
            (if(waiting) reminderDays(json,cfg.goalCycle).size<3 else cfg.testAnswerId?.let{runCatching{AnswerStore(context).load(it)}.isSuccess}==true)
        if(!valid) {
            cancel(context,slot,cfg.version(slot));registrations.remove(slot.name);state(context).write(json);return
        }
        val storedDay=registered?.optString("day")?.let{runCatching{LocalDate.parse(it)}.getOrNull()}
        if(!force && oldVersion==cfg.version(slot) && storedDay!=null && storedDay>=today &&
            slotKey(storedDay,slot) !in claimedSlots(json) && (!waiting || storedDay.toString() !in allReminderDays(json)) &&
            registered?.optString("zone")==ZoneId.systemDefault().id) {
            val target=reservationAt(storedDay,cfg.slot(slot).time,ZoneId.systemDefault())
            val retryAt=if(target>Instant.now())target else Instant.now().plusSeconds(15)
            if(retryAt<slotDeadline(storedDay,slot,cfg,ZoneId.systemDefault())) {arm(context,cfg,ReservationPlan(slot,cfg.version(slot),storedDay,retryAt),json);return}
        }
        cancel(context,slot,cfg.version(slot))
        val plan=nextReservation(Instant.now(),ZoneId.systemDefault(),cfg,slot,json)
        if(plan==null){registrations.remove(slot.name);state(context).write(json)}else arm(context,cfg,plan,json)
    }
    fun reschedule(context:Context)=synchronized(lock){val cfg=SettingsStore(context).read();NoticeSlot.entries.forEach{ensure(context,cfg,it,true)}}
    fun deliver(context:Context,version:Long,day:LocalDate,slot:NoticeSlot)=synchronized(lock) {
        val cfg=SettingsStore(context).read()
        if(!cfg.slot(slot).enabled || version!=cfg.version(slot))return@synchronized
        val json=ledger(context)
        val due=reservationDue(version,day,slot,cfg,Instant.now(),ZoneId.systemDefault(),json)
        if(due && AnswerNotifications(context).allowed()) {
            val waiting=goalNeedsReset(cfg,day)
            val answer=if(waiting)null else AnswerStore(context).load(requireNotNull(cfg.testAnswerId))
            state(context).write(claimSlot(json,day,slot,if(waiting)cfg.goalCycle else null))
            val result=runCatching{if(waiting)AnswerNotifications(context).postGoalReminder(cfg.goalCycle,day)
                else AnswerNotifications(context).post(requireNotNull(answer),"reservation:$day:${slot.name}")}.getOrDefault(NoticeState.FAILED)
            state(context).write(ledger(context).put("lastResult",result.name))
        }
        // Unchanged other-slot plans stay intact; a guide claim excludes both slots for this day.
        NoticeSlot.entries.forEach{ensure(context,cfg,it,it==slot && due)}
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
                }else if(cfg.testAnswerId==null)R.string.reservation_choose_answer
                else if(runCatching{AnswerStore(context).load(cfg.testAnswerId)}.isFailure)R.string.reservation_missing_answer
                else if(slotKey(today,slot) in claimedSlots(json))R.string.reservation_claimed
                else if(json.getJSONObject("registrations").optJSONObject(slot.name)?.optLong("version",-1)==cfg.version(slot))R.string.reservation_registered
                else R.string.reservation_not_registered
            }
        }
    }
    fun status(context:Context,cfg:AppSettings):Int = if(!cfg.morning.enabled && !cfg.evening.enabled)R.string.reservation_off else R.string.reservation_test_only
    fun slotStatuses(context:Context,cfg:AppSettings):Map<NoticeSlot,Int> = NoticeSlot.entries.associateWith{slotStatus(context,cfg,it)}
}
