package com.yosikou.hagemashi
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.KeyStore
import java.time.Instant
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HistoryStoreTest {
    @Test fun pagesAreNewestFirstAndDeletionSurvivesReopenWithoutTouchingOtherStores() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val dir="history-test-${UUID.randomUUID()}"; val alias="hagemashi.test.history.${UUID.randomUUID()}"
        val store=AnswerStore(context,dir,alias)
        val records=(0..24).map { i -> SavedEncouragement(UUID.randomUUID().toString(),Instant.EPOCH.plusSeconds(i.toLong()),"safe short","synthetic-full-$i","synthetic-data-$i",true,"persona-$i") }
        try {
            records.forEach(store::save)
            val first=store.page(limit=7)
            assertEquals(records.reversed().take(7).map{it.id},first.answers.map{it.id})
            assertNotNull(first.next)
            val second=AnswerStore(context,dir,alias).page(first.next,7)
            assertEquals(records.reversed().drop(7).take(7).map{it.id},second.answers.map{it.id})
            store.delete(records.last().id)
            assertThrows(AnswerDeleted::class.java){AnswerStore(context,dir,alias).load(records.last().id)}
            store.deleteAll()
            assertTrue(store.page().answers.isEmpty())
            assertThrows(AnswerDeleted::class.java){AnswerStore(context,dir,alias).load(records.first().id)}
        } finally {
            File(context.noBackupFilesDir,dir).deleteRecursively()
            listOf("","-journal","-wal","-shm").forEach{File(context.noBackupFilesDir,"$dir-index.v1.db$it").delete()}
            KeyStore.getInstance("AndroidKeyStore").apply{load(null);deleteEntry(alias)}
        }
    }
}
