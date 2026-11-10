package com.yosikou.hagemashi

import android.content.Context

// Separate key and noBackupFilesDir files: disconnecting an account never replaces saved answers.
internal class AnswerStore(private val context: Context, private val directory: String = "encouragements",
    private val alias: String = "hagemashi.encouragement.v1") {
    private fun vault(id: String): CredentialVault {
        if (!isAnswerId(id)) throw Problem(Failure.STORAGE)
        return CredentialVault(context, "$directory/$id.v1", alias)
    }
    fun save(value: SavedEncouragement) {
        val target = vault(value.id)
        if (java.io.File(context.noBackupFilesDir, "$directory/${value.id}.v1").exists()) throw Problem(Failure.STORAGE)
        target.write(encodeEncouragement(value))
    }
    fun load(id: String): SavedEncouragement = decodeEncouragement(id, vault(id).read())
}
