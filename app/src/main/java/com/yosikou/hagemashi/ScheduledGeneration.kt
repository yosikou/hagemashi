package com.yosikou.hagemashi

import android.app.job.*
import android.content.ComponentName
import android.content.Context
import android.os.PersistableBundle
import java.time.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

internal object ScheduledGeneration {
    private fun id(slot:NoticeSlot)=70+slot.ordinal
    fun schedule(context:Context,plan:ReservationPlan) {
        val scheduler=requireNotNull(context.getSystemService(JobScheduler::class.java))
        val old=scheduler.getPendingJob(id(plan.slot))
        if(old!=null && old.extras.getLong("version",-1)==plan.version && old.extras.getString("day")==plan.day.toString())return
        val extras=PersistableBundle().apply{putString("slot",plan.slot.name);putString("day",plan.day.toString());putLong("version",plan.version)}
        val job=JobInfo.Builder(id(plan.slot),ComponentName(context,ScheduledGenerationService::class.java))
            .setExtras(extras).setPersisted(true).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setMinimumLatency(maxOf(0,Duration.between(Instant.now(),plan.at).toMillis()))
            .setBackoffCriteria(300000,JobInfo.BACKOFF_POLICY_LINEAR).build()
        val accepted=scheduler.schedule(job)==JobScheduler.RESULT_SUCCESS
        ReservationDiagnostics.record(context,if(accepted)ReservationEvent.JOB_ENQUEUED else ReservationEvent.JOB_ENQUEUE_FAILED,plan.slot,plan.version,plan.at.toEpochMilli())
        check(accepted)
    }
    fun cancel(context:Context,slot:NoticeSlot,version:Long) {
        val scheduler=context.getSystemService(JobScheduler::class.java) ?: return
        if(scheduler.getPendingJob(id(slot))?.extras?.getLong("version",-1)==version)scheduler.cancel(id(slot))
    }
}

class ScheduledGenerationService:JobService() {
    private data class Task(val operation:Operation,val stopped:AtomicBoolean=AtomicBoolean())
    private val tasks=ConcurrentHashMap<Int,Task>()
    private val executor=Executors.newCachedThreadPool()
    override fun onStartJob(params:JobParameters):Boolean {
        val slot=runCatching{NoticeSlot.valueOf(params.extras.getString("slot").orEmpty())}.getOrNull() ?: return false
        val day=runCatching{LocalDate.parse(params.extras.getString("day"))}.getOrNull() ?: return false
        val version=params.extras.getLong("version",-1)
        val task=Task(Operation())
        tasks[params.jobId]=task
        ReservationDiagnostics.record(this,ReservationEvent.JOB_STARTED,slot,version)
        executor.execute {
            val retry=try{executeAutomatic(this,slot,version,day,task.operation)}catch(_:Exception){ReservationDiagnostics.record(this,ReservationEvent.JOB_FAILED,slot,version);day==LocalDate.now()}
            if(!task.stopped.get() && tasks.remove(params.jobId,task)) {
                jobFinished(params,retry)
                if(!retry)runCatching{ReservationSupport.reschedule(this)}
            }
        }
        return true
    }
    override fun onStopJob(params:JobParameters):Boolean {
        tasks.remove(params.jobId)?.let{it.stopped.set(true);it.operation.cancel()}
        ReservationDiagnostics.record(this,ReservationEvent.JOB_STOPPED)
        return true // Any later execution rechecks local date, slot version and deadline before reading/sending.
    }
    override fun onDestroy(){tasks.values.forEach{it.stopped.set(true);it.operation.cancel()};tasks.clear();executor.shutdownNow();super.onDestroy()}
}

private fun automaticDue(context:Context,slot:NoticeSlot,version:Long,day:LocalDate):Boolean=synchronized(ReservationSupport.lock) {
    val cfg=SettingsStore(context).read()
    cfg.automaticGeneration && cfg.goal!=null && cfg.modelId!=null && !goalNeedsReset(cfg,LocalDate.now()) &&
        reservationDue(version,day,slot,cfg,Instant.now(),ZoneId.systemDefault(),ReservationSupport.automaticState(context))
}
// Return true only to retry within the still-valid local slot. Re-entry always checks the window again.
private fun executeAutomatic(context:Context,slot:NoticeSlot,version:Long,day:LocalDate,operation:Operation):Boolean {
    var phase=ReservationEvent.JOB_STARTED
    fun event(e:ReservationEvent,reason:ReservationReason=ReservationReason.NONE){phase=e;ReservationDiagnostics.record(context,e,slot,version,reason=reason)}
    if(day!=LocalDate.now()){event(ReservationEvent.JOB_SKIPPED,ReservationReason.WRONG_LOCAL_DAY);return false}
    if(!automaticDue(context,slot,version,day)){event(ReservationEvent.JOB_SKIPPED);return false}
    if(!AiExecution.beginAutomatic(operation)){event(ReservationEvent.AUTO_BUSY);return automaticDue(context,slot,version,day)}
    val answerId=scheduledAnswerId(day,slot,version)
    try {
        operation.check()
        val cfg=SettingsStore(context).read() // Freeze settings once, at this attempt's actual start.
        operation.limitDuration(Duration.between(Instant.now(),slotDeadline(day,slot,cfg,ZoneId.systemDefault())).toMillis())
        operation.check()
        val store=AnswerStore(context)
        val existing=store.loadIfPresent(answerId)
        val answer=if(existing!=null){event(ReservationEvent.AUTO_REUSED);existing}else {
            val slug=cfg.modelId ?: run{event(ReservationEvent.JOB_SKIPPED,ReservationReason.MISSING_SETTINGS);return false}
            event(ReservationEvent.HEALTH_STARTED)
            val prepared=runBlocking { withTimeout(60000){prepareGeneration(context,cfg,Model(slug,slug),if(slot==NoticeSlot.MORNING)GenerationKind.MORNING else GenerationKind.EVENING,background=true)} }
            event(prepared.health)
            operation.check()
            if(!automaticDue(context,slot,version,day))return false
            event(ReservationEvent.CHATGPT_STARTED)
            val raw=ConnectionRepository(context).generate(slug,prepared.review.instructions,prepared.review.input,operation){if(!automaticDue(context,slot,version,day))throw java.util.concurrent.CancellationException()}
            event(ReservationEvent.CHATGPT_COMPLETED)
            operation.check()
            if(!automaticDue(context,slot,version,day))return false
            val text=encouragementText(raw,context.getString(R.string.notification_fallback))
            val saved=SavedEncouragement(answerId,Instant.now(),text.short,text.full,prepared.review.input,prepared.review.hasHealthData,
                prepared.review.personaName,prepared.review.rows,prepared.review.kind)
            try{operation.commit{store.save(saved)};synchronized(ReservationSupport.lock){ReservationSupport.saveOutcome(context,day,slot,version,ReservationOutcome.SAVED,answerId)};event(ReservationEvent.AUTO_SAVED)}catch(e:Exception){event(ReservationEvent.AUTO_SAVE_FAILED);throw e}
            saved
        }
        operation.check()
        // Confirm the saved record and repair its index before any notification is attempted.
        store.loadIfPresent(answerId) ?: throw Problem(Failure.STORAGE)
        synchronized(ReservationSupport.lock) {
            if(!automaticDue(context,slot,version,day))return false
            val prior=runOutcome(ReservationSupport.automaticState(context),day,slot,version)
            val tag="reservation:$day:${slot.name}:$version"
            if(prior==ReservationOutcome.SENDING) {
                val active=context.getSystemService(android.app.NotificationManager::class.java).activeNotifications.any{it.tag==tag}
                ReservationSupport.saveOutcome(context,day,slot,version,if(active)ReservationOutcome.POSTED else ReservationOutcome.UNKNOWN,answerId)
                return false
            }
            ReservationSupport.saveOutcome(context,day,slot,version,ReservationOutcome.SAVED,answerId)
            if(!AnswerNotifications(context).allowed()){
                ReservationSupport.saveOutcome(context,day,slot,version,ReservationOutcome.BLOCKED,answerId)
                event(ReservationEvent.JOB_RETRY,ReservationReason.NOTIFICATIONS_BLOCKED);return true
            }
            ReservationSupport.saveOutcome(context,day,slot,version,ReservationOutcome.SENDING,answerId)
            event(ReservationEvent.NOTIFY_STARTED)
            val result=try{AnswerNotifications(context).post(answer,tag)}catch(e:Exception){
                ReservationSupport.saveOutcome(context,day,slot,version,ReservationOutcome.FAILED,answerId)
                throw e
            }
            ReservationSupport.saveOutcome(context,day,slot,version,if(result==NoticeState.POSTED)ReservationOutcome.POSTED else ReservationOutcome.BLOCKED,answerId)
            event(ReservationEvent.NOTIFY_RETURNED,if(result==NoticeState.POSTED)ReservationReason.POSTED else ReservationReason.FAILED)
            if(result==NoticeState.POSTED)ReservationDiagnostics.notificationActive(context,tag,slot,version)
            else return true
        }
        return false
    }catch(_:AnswerDeleted){
        synchronized(ReservationSupport.lock){ReservationSupport.saveOutcome(context,day,slot,version,ReservationOutcome.SKIPPED)}
        event(ReservationEvent.JOB_SKIPPED);return false
    }catch(e:Exception){
        val kind=(e as? Problem)?.kind
        val reason=if(operation.wasCancelled())ReservationReason.CANCELLED else when(kind){
            Failure.EXPIRED,Failure.PERMISSION,Failure.IDENTITY,Failure.DENIED->ReservationReason.AUTH_REQUIRED
            Failure.POLICY->ReservationReason.MODEL_UNAVAILABLE
            Failure.NETWORK,Failure.TIMEOUT->ReservationReason.NETWORK_ERROR
            Failure.LIMIT->ReservationReason.RATE_LIMIT
            Failure.PROTOCOL->ReservationReason.PROTOCOL_ERROR
            else->if(e is java.util.concurrent.CancellationException)ReservationReason.CANCELLED else ReservationReason.OTHER_ERROR
        }
        event(when(phase) {
            ReservationEvent.CHATGPT_STARTED,ReservationEvent.CHATGPT_COMPLETED->ReservationEvent.CHATGPT_FAILED
            ReservationEvent.HEALTH_STARTED->ReservationEvent.HEALTH_FAILED
            ReservationEvent.AUTO_SAVE_FAILED->ReservationEvent.AUTO_SAVE_FAILED
            ReservationEvent.NOTIFY_STARTED->ReservationEvent.NOTIFY_FAILED
            else->ReservationEvent.JOB_FAILED
        },reason)
        // Never retry an unavailable model/account silently or switch models/charging methods.
        if(!operation.wasCancelled() && kind in setOf(Failure.EXPIRED,Failure.PERMISSION,Failure.IDENTITY,Failure.DENIED,Failure.POLICY)) {
            synchronized(ReservationSupport.lock){ReservationSupport.saveOutcome(context,day,slot,version,ReservationOutcome.GENERATION_FAILED)}
            return false
        }
        val retry=runCatching{automaticDue(context,slot,version,day)}.getOrDefault(false)
        if(retry) {
            if(runCatching{AnswerStore(context).loadIfPresent(answerId)}.getOrNull()==null)
                synchronized(ReservationSupport.lock){ReservationSupport.saveOutcome(context,day,slot,version,ReservationOutcome.GENERATION_RETRYING)}
            event(ReservationEvent.JOB_RETRY,reason)
        }
        return retry
    }finally{operation.cancel();AiExecution.endAutomatic(operation)}
}
