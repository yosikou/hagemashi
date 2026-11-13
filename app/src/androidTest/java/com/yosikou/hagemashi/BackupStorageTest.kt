package com.yosikou.hagemashi

import android.content.Context
import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.time.Instant
import java.time.LocalTime
import java.util.UUID

class BackupStorageTest {
    private fun isolated(base:Context,name:String):Context {
        val directory=File(base.cacheDir,name).apply{mkdirs()}
        return object:ContextWrapper(base){
            override fun getNoBackupFilesDir()=directory
            override fun getSharedPreferences(n:String,m:Int)=base.getSharedPreferences(name+"-"+n,m)
        }
    }
    private fun answer(id:String,full:String)=SavedEncouragement(id,Instant.EPOCH,"safe short",full,"used data",false)
    @Test fun settingsOnlyAndHistoryRestoreAreAtomicIdempotentAndUseFreshStorage() {
        val base=InstrumentationRegistry.getInstrumentation().targetContext
        val name="backup-test-"+UUID.randomUUID()
        val source=isolated(base,name+"-source");val target=isolated(base,name+"-target")
        try {
            val defaults=defaultPersonas(languageContext(source,AppLanguage.JA))
            val sourceCfg=AppSettings(goal="68.123456",personas=defaults,language=AppLanguage.JA,weightUnit=WeightUnit.LB,
                morning=SlotSetting(true,LocalTime.of(8,30)),automaticGeneration=true,modelId="synthetic-model")
            SettingsStore(source).write(sourceCfg)
            SettingsStore(target).write(AppSettings(goal="75",personas=defaults))
            val id="00000000-0000-0000-0000-000000000009"
            val remote=answer(id,"foreign full")
            AnswerStore(source).save(remote)
            val local=answer("00000000-0000-0000-0000-000000000008","local full")
            val existingStore=AnswerStore(target);existingStore.save(local)
            // Settings-only restore leaves local history untouched and is visible to old store instances.
            BackupRepository(target).restore(BackupRepository(source).snapshot(false),syncReservations=false)
            assertEquals(listOf(local.id),existingStore.backupAll().map{it.id})
            val portable=BackupRepository(source).snapshot(true)
            val bytes=BackupCrypto.encrypt(encodeBackup(portable),"test portable passphrase".toCharArray())
            val decoded=decodeBackup(BackupCrypto.decrypt(bytes,"test portable passphrase".toCharArray()))
            repeat(2){BackupRepository(target).restore(decoded,syncReservations=false)}
            assertEquals(setOf(local.id,remote.id),existingStore.backupAll().map{it.id}.toSet())
            assertEquals("foreign full",AnswerStore(target).load(remote.id).full)
            val loaded=SettingsStore(target).read()
            assertEquals(sourceCfg.goal,loaded.goal);assertEquals(WeightUnit.LB,loaded.weightUnit)
            assertTrue(loaded.resumeRequired);assertFalse(loaded.morning.enabled);assertFalse(loaded.automaticGeneration)
            // A duplicate stable ID never replaces an existing response.
            val changed=decoded.copy(answers=listOf(answer(remote.id,"conflicting text")))
            BackupRepository(target).restore(changed,syncReservations=false)
            assertEquals("foreign full",existingStore.load(remote.id).full)
            val beforeCfg=encodeSettings(SettingsStore(target).read()).toString()
            val beforeHistory=existingStore.backupAll().map{encodeEncouragement(it).toString()}.sorted()
            for(fault in RestoreFault.entries.filter{it!=RestoreFault.NONE}) {
                val attempt=decoded.copy(settings=decoded.settings.copy(goal="60"),answers=decoded.answers+answer("00000000-0000-0000-0000-000000000007","must not appear"))
                assertThrows(BackupProblem::class.java){BackupRepository(target).restore(attempt,fault,syncReservations=false)}
                assertEquals(beforeCfg,encodeSettings(SettingsStore(target).read()).toString())
                assertEquals(beforeHistory,AnswerStore(target).backupAll().map{encodeEncouragement(it).toString()}.sorted())
            }
        }finally {
            source.noBackupFilesDir.deleteRecursively();target.noBackupFilesDir.deleteRecursively()
            source.getSharedPreferences("app-language",Context.MODE_PRIVATE).edit().clear().commit()
            target.getSharedPreferences("app-language",Context.MODE_PRIVATE).edit().clear().commit()
        }
    }
}
