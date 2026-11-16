package com.yosikou.hagemashi

import android.content.Context

internal class DataSending(private val context:Context) {
    private val gate=GenerationSendGate(AppDataRoot.lock){SettingsStore(context).read()}
    fun check(kind:GenerationKind,expectedVersion:Long?=null) = gate.check(kind,expectedVersion)
    fun write(kind:GenerationKind,operation:Operation,expectedVersion:Long?=null,write:()->Unit) {
        // The data lock serializes the final body write with consent changes, automatic-off,
        // and the atomic restore pointer. Do not hold the reservation lock during network IO.
        gate.write(kind,expectedVersion) { operation.check();write() }
    }
}
