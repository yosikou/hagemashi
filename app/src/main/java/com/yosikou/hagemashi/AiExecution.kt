package com.yosikou.hagemashi

import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock

// All app components run in the same process. This also serializes rotating OAuth refreshes.
internal object AiExecution {
    val lock=ReentrantLock(true)
    private val control=Any()
    private var waitingManual=0
    private var automatic:Operation?=null
    fun requestManual():Closeable {
        synchronized(control){waitingManual++;automatic?.cancel()}
        val closed=AtomicBoolean()
        return Closeable{if(closed.compareAndSet(false,true))synchronized(control){waitingManual--}}
    }
    fun beginAutomatic(operation:Operation):Boolean=synchronized(control) {
        if(waitingManual>0 || !lock.tryLock())false else {automatic=operation;true}
    }
    fun endAutomatic(operation:Operation) {
        synchronized(control){if(automatic===operation)automatic=null}
        lock.unlock()
    }
}
