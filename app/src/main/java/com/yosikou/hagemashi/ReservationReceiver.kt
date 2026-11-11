package com.yosikou.hagemashi
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.time.LocalDate
import java.util.concurrent.Executors

internal class ReservationReceiver:BroadcastReceiver() {
    override fun onReceive(context:Context,intent:Intent) {
        // Persist entry before executor/Keystore work, so process interruption can be distinguished.
        val receivedSlot=if(intent.action=="${context.packageName}.DELIVER_RESERVATION") runCatching{NoticeSlot.valueOf(intent.getStringExtra("slot").orEmpty())}.getOrNull() else null
        ReservationDiagnostics.record(context,ReservationEvent.RECEIVER_ENTERED,receivedSlot)
        val pending=goAsync()
        try { executor.execute {
            ReservationDiagnostics.record(context,ReservationEvent.HANDLER_STARTED)
            try {
                if(intent.action=="${context.packageName}.DELIVER_RESERVATION") {
                    val day=intent.getStringExtra("day")?.let{runCatching{LocalDate.parse(it)}.getOrNull()}
                    val slot=intent.getStringExtra("slot")?.let{runCatching{NoticeSlot.valueOf(it)}.getOrNull()}
                    if(day!=null && slot!=null)ReservationSupport.deliver(context,intent.getLongExtra("version",-1),day,slot)
                    else ReservationDiagnostics.record(context,ReservationEvent.INVALID_DELIVERY)
                }else if(intent.action in setOf(Intent.ACTION_BOOT_COMPLETED,Intent.ACTION_TIME_CHANGED,Intent.ACTION_TIMEZONE_CHANGED,Intent.ACTION_MY_PACKAGE_REPLACED)) {
                    ReservationDiagnostics.record(context,ReservationEvent.SYSTEM_RESCHEDULE)
                    ReservationSupport.reschedule(context)
                }
            }catch(e:Exception){
                ReservationDiagnostics.record(context,ReservationEvent.RECEIVER_FAILED,reason=ReservationDiagnostics.failure(e))
                runCatching{ReservationSupport.reschedule(context)}
            }finally{
                ReservationDiagnostics.record(context,ReservationEvent.RECEIVER_FINISHED)
                pending.finish()
            }
        } }catch(e:Exception){
            ReservationDiagnostics.record(context,ReservationEvent.RECEIVER_FAILED,reason=ReservationDiagnostics.failure(e))
            pending.finish()
        }
    }
    companion object{private val executor=Executors.newSingleThreadExecutor{task->Thread(task,"reservation-local").apply{isDaemon=true}}}
}
