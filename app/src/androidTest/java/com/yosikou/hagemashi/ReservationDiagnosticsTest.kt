package com.yosikou.hagemashi

import android.content.Context
import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

class ReservationDiagnosticsTest {
    @Test fun persistedOperationalEventsCanBeReadFromANewContextWithoutKeystoreOrPrivateData() {
        val base=InstrumentationRegistry.getInstrumentation().targetContext
        val directory=File(base.noBackupFilesDir,"trace-test-${UUID.randomUUID()}").apply{mkdirs()}
        fun fresh():Context=object:ContextWrapper(base){override fun getNoBackupFilesDir()=directory}
        try {
            ReservationDiagnostics.record(fresh(),ReservationEvent.REGISTER_RETURNED,NoticeSlot.MORNING,4,1000)
            ReservationDiagnostics.record(fresh(),ReservationEvent.APP_HIDDEN)
            val report=ReservationDiagnostics.report(fresh())
            assertTrue(report.contains("REGISTER_RETURNED"))
            assertTrue(report.contains("APP_HIDDEN"))
            assertTrue(report.contains("os_alarm_registration=NOT_QUERYABLE"))
            assertFalse(report.contains("RECEIVER_ENTERED"))
            assertFalse(report.contains("NOTIFY_RETURNED\""))
            assertEquals(setOf("reservation-diagnostics.v1"),directory.listFiles()!!.map{it.name}.toSet())
        }finally{directory.deleteRecursively()}
    }
}
