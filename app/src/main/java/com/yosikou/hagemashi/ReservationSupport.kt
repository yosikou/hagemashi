package com.yosikou.hagemashi

import android.content.Context

// UI-only PR: saved preferences do not imply a scheduled delivery.
internal object ReservationSupport {
    const val available = false
    fun status(context: Context, settings: AppSettings) = R.string.reservation_unavailable
    fun changed(context: Context, old: AppSettings, current: AppSettings) = Unit
}
