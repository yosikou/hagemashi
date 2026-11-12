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
    @Test fun savedAutomaticAnswerCanBeRecoveredAfterIndexGapAndDeletionNeverRegeneratesIt() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val dir="automatic-test-${UUID.randomUUID()}";val alias="test.auto.${UUID.randomUUID()}"
        val id=scheduledAnswerId(java.time.LocalDate.of(2026,11,11),NoticeSlot.MORNING)
        val record=SavedEncouragement(id,java.time.Instant.EPOCH,"safe","synthetic-body","synthetic-input",false,kind=GenerationKind.MORNING)
        try {
            val store=AnswerStore(context,dir,alias)
            assertNull(store.loadIfPresent(id))
            // Simulate termination after the encrypted answer commit, before history-index insert.
            CredentialVault(context,"$dir/$id.v1",alias).write(encodeEncouragement(record))
            assertEquals(record.full,AnswerStore(context,dir,alias).loadIfPresent(id)!!.full)
            assertEquals(id,AnswerStore(context,dir,alias).page().answers.single().id)
            store.delete(id)
            assertThrows(AnswerDeleted::class.java){store.loadIfPresent(id)}
        }finally{
            java.io.File(context.noBackupFilesDir,dir).deleteRecursively()
            java.io.File(context.noBackupFilesDir,"$dir-index.v1.db").delete()
            java.security.KeyStore.getInstance("AndroidKeyStore").apply{load(null);deleteEntry(alias)}
        }
    }
}
