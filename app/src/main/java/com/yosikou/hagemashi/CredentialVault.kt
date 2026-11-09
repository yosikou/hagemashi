package com.yosikou.hagemashi

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal class CredentialVault(context: Context, fileName: String = "chatgpt-connection.v1", private val alias: String = "hagemashi.chatgpt.v1") {
    private val file = AtomicFile(File(context.noBackupFilesDir, fileName))

    fun read(): JSONObject = protect {
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return@protect JSONObject().put("host", "urn:uuid:${UUID.randomUUID()}")
        val bytes = file.readFully()
        if (bytes.size !in 29..262144 || bytes[0].toInt() != 1) throw Problem(Failure.STORAGE)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(false), GCMParameterSpec(128, bytes.copyOfRange(1, 13)))
        cipher.updateAAD(alias.toByteArray(Charsets.UTF_8))
        JSONObject(String(cipher.doFinal(bytes.copyOfRange(13, bytes.size)), Charsets.UTF_8))
    }

    fun write(value: JSONObject) = protect {
        val plain = value.toString().toByteArray(Charsets.UTF_8)
        if (plain.size > 200000) throw Problem(Failure.STORAGE)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(true))
        cipher.updateAAD(alias.toByteArray(Charsets.UTF_8))
        val bytes = byteArrayOf(1) + cipher.iv + cipher.doFinal(plain)
        val output = file.startWrite()
        try {
            output.write(bytes)
            file.finishWrite(output)
        } catch (e: Exception) {
            file.failWrite(output)
            throw e
        }
    }

    fun eraseUnrecoverable() = protect {
        file.delete()
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        store.deleteEntry(alias)
    }

    private fun key(create: Boolean): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        if (!create) throw Problem(Failure.STORAGE)
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build())
        return generator.generateKey()
    }

    private fun <T> protect(block: () -> T): T = try { block() } catch (_: Exception) { throw Problem(Failure.STORAGE) }
}
