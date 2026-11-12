package com.yosikou.hagemashi

import android.app.ActivityManager
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.util.AtomicFile
import java.io.File
import org.json.JSONArray

internal object ReservationDiagnostics {
    private val lock=Any()
    private fun file(context:Context)=AtomicFile(File(context.noBackupFilesDir,"reservation-diagnostics.v1"))
    @Volatile private var writeFailed=false
    private fun read(context:Context):JSONArray {
        val file=file(context)
        if(!file.baseFile.exists() && !File(file.baseFile.path+".bak").exists())return JSONArray()
        val bytes=file.readFully();require(bytes.size<=131072)
        return JSONArray(bytes.toString(Charsets.UTF_8))
    }
    // Best effort: diagnostics must never cancel an alarm, suppress a notification or hide a real failure.
    fun record(context:Context,event:ReservationEvent,slot:NoticeSlot?=null,version:Long?=null,trigger:Long?=null,
        reason:ReservationReason=ReservationReason.NONE) { synchronized(lock) {
        runCatching {
            val prior=runCatching{read(context)}.getOrElse { writeFailed=true;JSONArray() }
            val data=appendReservationTrace(prior,ReservationTraceEvent(event,reason,System.currentTimeMillis(),SystemClock.elapsedRealtime(),Process.myPid(),BuildConfig.BUILD_IDENTIFIER,slot,version,trigger))
            val file=file(context);val stream=file.startWrite()
            try{stream.write(data.toString().toByteArray(Charsets.UTF_8));file.finishWrite(stream)}catch(e:Exception){file.failWrite(stream);throw e}
        }.onFailure { writeFailed=true }
    } }
    fun failure(error:Exception)=when(error) {
        is SecurityException->ReservationReason.SECURITY_ERROR
        is java.io.IOException->ReservationReason.IO_ERROR
        is Problem->ReservationReason.STORAGE_ERROR
        else->ReservationReason.OTHER_ERROR
    }
    fun notificationActive(context:Context,tag:String,slot:NoticeSlot,version:Long) {
        runCatching { requireNotNull(context.getSystemService(NotificationManager::class.java)).activeNotifications.any{it.tag==tag} }
            .onSuccess{record(context,if(it)ReservationEvent.NOTIFICATION_ACTIVE else ReservationEvent.NOTIFICATION_NOT_ACTIVE,slot,version)}
            .onFailure{record(context,ReservationEvent.NOTIFICATION_CHECK_FAILED,slot,version)}
    }
    fun report(context:Context):String=synchronized(lock) {
        buildString {
            appendLine("schema=HAGEMASHI_RESERVATION_V1")
            appendLine("build=${BuildConfig.BUILD_IDENTIFIER}")
            appendLine("applicationId=${BuildConfig.APPLICATION_ID}")
            appendLine("sdk=${Build.VERSION.SDK_INT}")
            appendLine("alarm_api=SET_AND_ALLOW_WHILE_IDLE_INEXACT")
            appendLine("os_alarm_registration=NOT_QUERYABLE")
            appendLine("pending_token_is_not_alarm_registration=true")
            appendLine("notify_return_is_not_visible_delivery=true")
            appendLine("trace_write_failed=$writeFailed")
            val activity=context.getSystemService(ActivityManager::class.java)
            val power=context.getSystemService(PowerManager::class.java)
            appendLine("background_restricted="+if(Build.VERSION.SDK_INT>=28)runCatching{activity?.isBackgroundRestricted?.toString() ?: "UNKNOWN"}.getOrDefault("UNKNOWN") else "UNSUPPORTED")
            appendLine("battery_optimization_exempt="+runCatching{power?.isIgnoringBatteryOptimizations(context.packageName)?.toString() ?: "UNKNOWN"}.getOrDefault("UNKNOWN"))
            appendLine("device_idle="+runCatching{power?.isDeviceIdleMode?.toString() ?: "UNKNOWN"}.getOrDefault("UNKNOWN"))
            appendLine("notifications_allowed="+runCatching{AnswerNotifications(context).allowed().toString()}.getOrDefault("UNKNOWN"))
            // No descriptions, traces, process names, notification contents or Intent extras.
            if(Build.VERSION.SDK_INT>=30)runCatching {
                val exits=activity?.getHistoricalProcessExitReasons(context.packageName,0,5).orEmpty()
                if(exits.isEmpty())appendLine("process_exit=NONE_REPORTED")
                exits.forEach{appendLine("process_exit_at=${it.timestamp} reason_code=${it.reason} importance=${it.importance}")}
            }.onFailure{appendLine("process_exit=UNAVAILABLE")}
            else appendLine("process_exit=UNSUPPORTED")
            runCatching{read(context)}.onSuccess{events->
                // Filter even persisted records before display/copy.
                val safe=sanitizeReservationTrace(events)
                for(i in 0 until safe.length())appendLine(safe.getJSONObject(i).toString())
            }.onFailure{appendLine("trace_read=FAILED")}
        }
    }
}
