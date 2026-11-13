package com.yosikou.hagemashi

import android.content.Context
import android.util.AtomicFile
import java.io.File

internal class AnswerStore(private val source: Context, private val directory: String = "encouragements",
    private val alias: String = "hagemashi.encouragement.v1") {
    private var context:Context=source
    private fun vault(id: String): CredentialVault {
        if (!isAnswerId(id)) throw Problem(Failure.STORAGE)
        return CredentialVault(context, "$directory/$id.v1", alias)
    }
    private fun files(): List<File> = File(context.noBackupFilesDir, directory).listFiles().orEmpty()
        .map { File(it.parentFile, it.name.removeSuffix(".bak").removeSuffix(".new")) }
        .distinctBy { it.path }.filter { it.name.endsWith(".v1") && isAnswerId(it.name.removeSuffix(".v1")) }
    private fun raw(id: String) = decodeEncouragement(id, vault(id).read())
    private fun index() = HistoryIndex(context, directory, ::files, ::raw)
    private fun <T> protect(block: () -> T): T = try {
        fun run(active:Context):T = synchronized(HistoryAccess.lock) {
            val previous=context;context=active
            try{block()}finally{context=previous}
        }
        if(directory=="encouragements")AppDataRoot.access(source,::run) else synchronized(AppDataRoot.lock){run(source)}
    }
        catch (e: AnswerDeleted) { throw e } catch (_: Exception) { throw Problem(Failure.STORAGE) }
    fun save(value: SavedEncouragement) = protect {
        index().use { db ->
            val idx = index(); idx.migrate(db)
            if (idx.deleted(db, value.id) || File(context.noBackupFilesDir, "$directory/${value.id}.v1").exists()) throw Problem(Failure.STORAGE)
            vault(value.id).write(encodeEncouragement(value)); idx.insert(db, value)
        }
    }
    fun page(cursor: HistoryCursor? = null, limit: Int = 20): HistoryPage = protect {
        require(limit in 1..50)
        index().use { db ->
            val idx = index(); idx.migrate(db)
            val ids = idx.ids(db, cursor, limit + 1)
            val selected = ids.take(limit)
            HistoryPage(selected.mapNotNull { runCatching { raw(it.id) }.getOrNull() }, if (ids.size > limit) selected.lastOrNull() else null)
        }
    }
    fun latest(): SavedEncouragement? {
        var cursor: HistoryCursor? = null
        do { val page = page(cursor, 1); if (page.answers.isNotEmpty()) return page.answers.first(); cursor = page.next } while(cursor != null)
        return null
    }
    fun load(id: String): SavedEncouragement = protect {
        index().use { db -> if (index().deleted(db, id)) throw AnswerDeleted(); raw(id) }
    }
    fun loadIfPresent(id:String):SavedEncouragement? = protect {
        index().use{db->
            val idx=index()
            if(idx.deleted(db,id))throw AnswerDeleted()
            val file=File(context.noBackupFilesDir,"$directory/$id.v1")
            if(!file.exists() && !File(file.path+".bak").exists())null
            else raw(id).also{idx.insert(db,it)}
        }
    }
    fun backupAll():List<SavedEncouragement> = protect {
        index().use { db ->
            val idx=index();idx.migrate(db)
            (idx.allIds(db)+files().map{it.name.removeSuffix(".v1")}).distinct().filterNot{idx.deleted(db,it)}.take(BACKUP_MAX_ANSWERS+1).map(::raw)
        }
    }
    fun mergeRestored(values:List<SavedEncouragement>) = protect {
        index().use { db ->
            val idx=index();idx.migrate(db)
            val present=files().map{it.name.removeSuffix(".v1")}.filterNot{idx.deleted(db,it)}.toSet()
            newBackupAnswers(present,values).forEach { value ->
                vault(value.id).write(encodeEncouragement(value))
                db.delete("deleted","id=?",arrayOf(value.id))
                db.delete("answers","id=?",arrayOf(value.id))
                idx.insert(db,value)
            }
            values.filter{it.id in present}.forEach{value->val existing=raw(value.id);idx.insert(db,existing)}
        }
    }
    fun delete(id: String) = protect {
        require(isAnswerId(id))
        index().use { db -> val idx = index(); idx.migrate(db); idx.remove(db, listOf(id)); erase(id) }
    }
    fun deleteAll() = protect {
        index().use { db ->
            val idx = index(); idx.migrate(db)
            val ids = (idx.allIds(db) + files().map { it.name.removeSuffix(".v1") }).distinct()
            idx.remove(db, ids); ids.forEach(::erase)
        }
    }
    private fun erase(id: String) {
        val path = File(context.noBackupFilesDir, "$directory/$id.v1")
        AtomicFile(path).delete()
        if (path.exists() || File(path.path+".bak").exists() || File(path.path+".new").exists()) throw Problem(Failure.STORAGE)
    }
}
