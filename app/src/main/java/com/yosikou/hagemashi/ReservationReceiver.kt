package com.yosikou.hagemashi

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.time.LocalDate
import java.util.concurrent.Executors

internal class ReservationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        executor.execute {
            try {
                if (intent.action == "${context.packageName}.DELIVER_RESERVATION") {
                    val day = intent.getStringExtra("day")?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
                    if (day != null) ReservationSupport.deliver(context, intent.getLongExtra("version", -1), day)
                } else if (intent.action in setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED, Intent.ACTION_MY_PACKAGE_REPLACED))
                    ReservationSupport.reschedule(context)
            } catch (_: Exception) {
                // Never reset a broken ledger or log private data. Best effort restoration contains no network work.
                runCatching { ReservationSupport.reschedule(context) }
            }
            finally { pending.finish() }
        }
    }
    companion object {
        private val executor = Executors.newSingleThreadExecutor { task -> Thread(task, "reservation-local").apply { isDaemon = true } }
    }
}
