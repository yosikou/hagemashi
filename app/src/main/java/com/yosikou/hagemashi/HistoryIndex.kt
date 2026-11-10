package com.yosikou.hagemashi

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.io.File
import java.time.Instant

internal class AnswerDeleted : Exception()
internal data class HistoryCursor(val seconds: Long, val nanos: Int, val id: String)
internal data class HistoryPage(val answers: List<SavedEncouragement>, val next: HistoryCursor?)
internal object HistoryAccess { val lock = Any() }
// Only random IDs and generation times are indexed. All answer/persona/measurement text stays encrypted.
internal class HistoryIndex(context: Context, directory: String, private val files: () -> List<File>, private val read: (String) -> SavedEncouragement) {
    private val helper = object : SQLiteOpenHelper(context, File(context.noBackupFilesDir, "$directory-index.v1.db").path, null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE answers(id TEXT PRIMARY KEY, seconds INTEGER NOT NULL, nanos INTEGER NOT NULL)")
            db.execSQL("CREATE INDEX answers_newest ON answers(seconds DESC,nanos DESC,id DESC)")
            db.execSQL("CREATE TABLE deleted(id TEXT PRIMARY KEY)")
            db.execSQL("CREATE TABLE meta(scanned INTEGER NOT NULL)")
        }
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    }
    fun <T> use(block: (SQLiteDatabase) -> T): T {
        try { return block(helper.writableDatabase) } finally { helper.close() }
    }
    fun deleted(db: SQLiteDatabase, id: String): Boolean = db.rawQuery("SELECT id FROM deleted WHERE id=?", arrayOf(id)).use { it.moveToFirst() }
    fun insert(db: SQLiteDatabase, answer: SavedEncouragement) {
        db.execSQL("INSERT OR IGNORE INTO answers(id,seconds,nanos) VALUES(?,?,?)", arrayOf(answer.id, answer.generatedAt.epochSecond, answer.generatedAt.nano))
    }
    fun migrate(db: SQLiteDatabase) {
        if (db.rawQuery("SELECT scanned FROM meta LIMIT 1", null).use { it.moveToFirst() }) return
        db.beginTransaction()
        try {
            files().forEach { file ->
                val id = file.name.removeSuffix(".v1")
                if (!deleted(db, id)) runCatching { read(id) }.getOrNull()?.let { insert(db, it) }
            }
            db.execSQL("INSERT INTO meta(scanned) VALUES(1)"); db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
    fun ids(db: SQLiteDatabase, cursor: HistoryCursor?, limit: Int): List<HistoryCursor> {
        val where = if (cursor == null) "" else "WHERE seconds < ? OR (seconds = ? AND nanos < ?) OR (seconds = ? AND nanos = ? AND id < ?)"
        val args = cursor?.let { arrayOf(it.seconds.toString(),it.seconds.toString(),it.nanos.toString(),it.seconds.toString(),it.nanos.toString(),it.id) }
        return db.rawQuery("SELECT seconds,nanos,id FROM answers $where ORDER BY seconds DESC,nanos DESC,id DESC LIMIT $limit", args).use { c ->
            buildList { while (c.moveToNext()) add(HistoryCursor(c.getLong(0),c.getInt(1),c.getString(2))) }
        }
    }
    fun remove(db: SQLiteDatabase, ids: List<String>) {
        db.beginTransaction()
        try {
            ids.forEach { id -> db.execSQL("INSERT OR IGNORE INTO deleted(id) VALUES(?)", arrayOf(id)); db.delete("answers", "id=?", arrayOf(id)) }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
    fun allIds(db: SQLiteDatabase): List<String> = db.rawQuery("SELECT id FROM answers", null).use { c -> buildList { while(c.moveToNext()) add(c.getString(0)) } }
}
