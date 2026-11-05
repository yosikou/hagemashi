package com.yosikou.hagemashi

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class CredentialVaultTest {
    @Test fun encryptedRoundTripAndTamperRejection() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val vault = CredentialVault(context, "test-vault", "hagemashi.test.vault")
        val file = File(context.noBackupFilesDir, "test-vault")
        try {
            vault.eraseUnrecoverable()
            vault.write(JSONObject().put("test_value", "synthetic-test-credential"))
            assertFalse(file.readBytes().toString(Charsets.UTF_8).contains("synthetic-test-credential"))
            val reopened = CredentialVault(context, "test-vault", "hagemashi.test.vault")
            assertEquals("synthetic-test-credential", reopened.read().getString("test_value"))
            val changed = file.readBytes()
            changed[changed.lastIndex] = (changed.last().toInt() xor 1).toByte()
            file.writeBytes(changed)
            try { reopened.read(); fail("Tampering must fail closed") } catch (e: Problem) { assertEquals(Failure.STORAGE, e.kind) }
        } finally { vault.eraseUnrecoverable() }
    }
}
