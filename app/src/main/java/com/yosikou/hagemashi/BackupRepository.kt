package com.yosikou.hagemashi

import android.content.Context
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import kotlin.concurrent.withLock

internal enum class RestoreFault { NONE, AFTER_SETTINGS, AFTER_HISTORY, BEFORE_COMMIT }
internal class BackupRepository(private val context:Context) {
    fun snapshot(includeHistory:Boolean):BackupContents=synchronized(ReservationSupport.lock) {
        AppDataRoot.access(context){active->
            val cfg=SettingsStore(active).read()
            val history=if(includeHistory)AnswerStore(active).backupAll() else emptyList()
            if(history.size>BACKUP_MAX_ANSWERS)throw BackupProblem(BackupError.TOO_LARGE)
            BackupContents(cfg,history,includeHistory)
        }
    }
    fun restore(contents:BackupContents,fault:RestoreFault=RestoreFault.NONE,syncReservations:Boolean=true):AppSettings {
        // Wait for an in-flight manual request and cancel/wait for an automatic request before
        // taking the same ReservationSupport -> AppDataRoot lock order as existing writers.
        AiExecution.requestManual().use {
            return AiExecution.lock.withLock { synchronized(ReservationSupport.lock) { synchronized(AppDataRoot.lock) {
                val old=SettingsStore(context).read()
                val restored=restoredSettings(contents.settings,old)
                val generations=GenerationStore(context.noBackupFilesDir)
                try {
                    generations.transaction(prepare={source,stage->
                        copyData(source,stage)
                        val target=GenerationContext(context,stage)
                        CredentialVault(target,"app-settings.v1","hagemashi.settings.v1").write(encodeSettings(restored))
                        if(fault==RestoreFault.AFTER_SETTINGS)throw java.io.IOException()
                        if(contents.includesHistory)AnswerStore(target).mergeRestored(contents.answers)
                        if(fault==RestoreFault.AFTER_HISTORY)throw java.io.IOException()
                    },verify={stage->
                        val target=GenerationContext(context,stage)
                        val loaded=decodeSettings(CredentialVault(target,"app-settings.v1","hagemashi.settings.v1").read(),emptyList())
                        check(loaded==restored)
                        if(contents.includesHistory)contents.answers.forEach{check(AnswerStore(target).load(it.id).id==it.id)}
                    },beforeMove={if(fault==RestoreFault.BEFORE_COMMIT)throw java.io.IOException()})
                }catch(e:Exception){throw BackupProblem(BackupError.SAVE)}
                // The commit is complete. Optional OS cleanup cannot roll back the data or start
                // delivery: resumeRequired is checked on every reservation entry point.
                runCatching{generations.prune()}
                // Retire only legacy settings/history, never authentication or reservation files.
                runCatching{dataEntries(context.noBackupFilesDir).forEach{it.deleteRecursively()}}
                runCatching{saveLanguage(context,restored.language)}
                if(syncReservations)runCatching{ReservationSupport.changed(context,old,restored)}
                restored
            } } }
        }
    }
    private fun dataEntries(source:File)=source.listFiles().orEmpty().filter {
            it.name in setOf("app-settings.v1","app-settings.v1.bak","app-settings.v1.new","encouragements") ||
                it.name=="encouragements-index.v1.db" || it.name.startsWith("encouragements-index.v1.db-")
        }
    private fun copyData(source:File,target:File) {
        val entries=dataEntries(source)
        fun copy(from:File,to:File) {
            if(from.isDirectory){check(to.mkdirs() || to.isDirectory);from.listFiles().orEmpty().forEach{copy(it,File(to,it.name))}}
            else {to.parentFile?.mkdirs();FileInputStream(from).use{input->FileOutputStream(to).use{output->input.copyTo(output);output.fd.sync()}}}
        }
        entries.forEach{copy(it,File(target,it.name))}
    }
}
