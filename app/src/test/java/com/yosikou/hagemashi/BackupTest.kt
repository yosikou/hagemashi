package com.yosikou.hagemashi

import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.time.LocalTime
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class BackupTest {
    private val personas=listOf(Persona("friend","edited friend","edited prompt\nkeep",false),Persona("numbers","coach","prompt",true),Persona("companion","companion","prompt",true),Persona("custom","personnage","français\nहिन्दी",false))
    private val cfg=AppSettings(goal="68.123456",goalDate=java.time.LocalDate.of(2027,1,2),personas=personas,personaId="custom",supplement="original 補足",modelId="model",language=AppLanguage.FR,weightUnit=WeightUnit.LB,
        morning=SlotSetting(true,LocalTime.of(8,30)),evening=SlotSetting(true,LocalTime.of(20,15)),automaticGeneration=true,morningVersion=77,eveningVersion=88,reservationVersion=99,testAnswerId="00000000-0000-0000-0000-000000000001")
    private fun answer(id:String="00000000-0000-0000-0000-000000000001")=SavedEncouragement(id,Instant.parse("2026-11-10T00:01:02Z"),"original short","original full","original used",true,"original persona",listOf(MeasurementRow(MeasureItem.WEIGHT,"69.123456","kg","2026-11-09T00:00:00Z")),GenerationKind.MORNING)
    @Test fun portableSettingsAndOptionalHistoryPreserveValuesButExcludeDeviceState() {
        for(include in listOf(false,true)) {
            val input=BackupContents(cfg,if(include)listOf(answer()) else emptyList(),include)
            val bytes=encodeBackup(input);val output=decodeBackup(bytes)
            assertEquals(portableSettings(cfg).toString(),portableSettings(output.settings).toString())
            assertEquals(cfg.goal,output.settings.goal);assertEquals(cfg.personas,output.settings.personas)
            assertEquals(cfg.personaId,output.settings.personaId);assertEquals(include,output.includesHistory)
            assertEquals(if(include)1 else 0,output.answers.size)
            if(include)assertEquals(encodeEncouragement(answer()).toString(),encodeEncouragement(output.answers.single()).toString())
            val settings=JSONObject(String(bytes,Charsets.UTF_8)).getJSONObject("settings")
            for(key in listOf("testAnswerId","host","token","key","permissions","morningVersion","eveningVersion","reservationVersion","goalCycle","resumeRequired"))assertFalse(key,settings.has(key))
        }
    }
    @Test fun passphraseFileCanBeDecryptedIndependentlyAndDetectsWrongPassphraseAndTampering() {
        val plain=encodeBackup(BackupContents(cfg,listOf(answer()),true))
        val password="portable 日本語 passphrase".toCharArray()
        val first=BackupCrypto.encrypt(plain,password)
        val second=BackupCrypto.encrypt(plain,password)
        assertFalse(first.contentEquals(second))
        assertArrayEquals(plain,BackupCrypto.decrypt(first,"portable 日本語 passphrase".toCharArray()))
        assertFalse(String(first,Charsets.ISO_8859_1).contains("original full"))
        val bad=assertThrows(BackupProblem::class.java){BackupCrypto.decrypt(first,"different passphrase".toCharArray())}
        assertEquals(BackupError.AUTH_OR_DAMAGE,bad.reason);assertNull(bad.message)
        val changed=first.copyOf().apply{this[lastIndex]=(this[lastIndex].toInt() xor 1).toByte()}
        assertEquals(BackupError.AUTH_OR_DAMAGE,assertThrows(BackupProblem::class.java){BackupCrypto.decrypt(changed,password)}.reason)
        val version=first.copyOf().apply{this[11]=2}
        assertEquals(BackupError.VERSION,assertThrows(BackupProblem::class.java){BackupCrypto.decrypt(version,password)}.reason)
        assertEquals(BackupError.PASSPHRASE,assertThrows(BackupProblem::class.java){BackupCrypto.encrypt(plain,"short".toCharArray())}.reason)
    }
    @Test fun restoringDisablesEverySlotUntilExplicitResumeAndCreatesFreshLocalVersions() {
        val restored=restoredSettings(cfg,AppSettings(personas=personas,morningVersion=5,eveningVersion=6))
        assertTrue(restored.resumeRequired);assertFalse(restored.morning.enabled);assertFalse(restored.evening.enabled);assertFalse(restored.automaticGeneration);assertNull(restored.testAnswerId)
        assertTrue(restored.morningVersion>6);assertTrue(restored.eveningVersion>6)
        assertEquals(cfg.morning.time,restored.morning.time);assertEquals(cfg.evening.time,restored.evening.time)
        val restarted=decodeSettings(encodeSettings(restored),personas)
        assertTrue(restarted.resumeRequired);assertFalse(restarted.morning.enabled)
        val forced=restarted.copy(morning=restarted.morning.copy(enabled=true),automaticGeneration=true)
        val now=Instant.parse("2026-11-10T09:00:00Z");val zone=java.time.ZoneId.of("UTC")
        val ledger=reservationLedger(JSONObject().put("host","synthetic"))
        assertNull(nextReservation(now,zone,forced,NoticeSlot.MORNING,ledger))
        assertFalse(reservationDue(forced.morningVersion,now.atZone(zone).toLocalDate(),NoticeSlot.MORNING,forced,now,zone,ledger))
        val resumed=resumeRestoredSettings(restarted)
        assertFalse(resumed.resumeRequired);assertTrue(resumed.morning.enabled);assertTrue(resumed.evening.enabled);assertTrue(resumed.automaticGeneration)
        assertEquals(portableSettings(cfg).toString(),portableSettings(restarted).toString())
    }
    @Test fun everyFailureBeforeTheCommitLeavesTheOriginalGenerationActive() {
        for(fault in listOf("settings","history","verify","publish")) {
            val root=Files.createTempDirectory("hagemashi-atomic").toFile()
            try {
                File(root,"settings").writeText("old settings");File(root,"history").writeText("old history")
                val store=GenerationStore(root)
                assertThrows(java.io.IOException::class.java){store.transaction(prepare={_,stage->
                    File(stage,"settings").writeText("new settings")
                    if(fault=="settings")throw java.io.IOException()
                    File(stage,"history").writeText("new history")
                    if(fault=="history")throw java.io.IOException()
                },verify={if(fault=="verify")throw java.io.IOException()},beforeMove={if(fault=="publish")throw java.io.IOException()})}
                val active=GenerationStore(root).active()
                assertEquals("old settings",File(active,"settings").readText());assertEquals("old history",File(active,"history").readText())
            }finally{root.deleteRecursively()}
        }
    }
    @Test fun settingsAndHistorySwitchTogetherAndRepeatedRestoreUsesStableIds() {
        val root=Files.createTempDirectory("hagemashi-generation").toFile()
        try {
            File(root,"settings").writeText("old")
            File(root,"history").writeText("id-a")
            val store=GenerationStore(root)
            repeat(2){
                store.transaction(prepare={previous,stage->
                    File(stage,"settings").writeText("new")
                    val ids=(File(previous,"history").readLines()+listOf("id-a","id-b")).distinct()
                    File(stage,"history").writeText(ids.joinToString("\n"))
                },verify={stage->assertTrue(File(stage,"settings").exists());assertTrue(File(stage,"history").exists())})
                assertEquals("new",File(GenerationStore(root).active(),"settings").readText())
                assertEquals(listOf("id-a","id-b"),File(store.active(),"history").readLines())
            }
            store.prune();assertEquals(1,File(root,"data-generations").listFiles()!!.size)
            // An abandoned staging directory does not become active after a new store instance.
            val abandoned=store.stage();File(abandoned,"settings").writeText("partial")
            assertEquals("new",File(GenerationStore(root).active(),"settings").readText())
        }finally{root.deleteRecursively()}
    }
    @Test fun malformedDataAndOverLimitsAreRejectedBeforeAnyApply() {
        assertEquals(BackupError.VERSION,assertThrows(BackupProblem::class.java){decodeBackup("{}".toByteArray())}.reason)
        val json=JSONObject(String(encodeBackup(BackupContents(cfg,listOf(answer()),true)),Charsets.UTF_8))
        for(version in listOf("2","4294967297","1.0","\"1\"")) {
            // Keep the input spelling: JSONObject serialization normalizes 1.0 to 1.
            val wrong=json.toString().replace("\"formatVersion\":1","\"formatVersion\":"+version)
            assertEquals(BackupError.VERSION,assertThrows(BackupProblem::class.java){decodeBackup(wrong.toByteArray())}.reason)
        }
        json.getJSONArray("history").put(encodeEncouragement(answer()))
        assertEquals(BackupError.FORMAT,assertThrows(BackupProblem::class.java){decodeBackup(json.toString().toByteArray())}.reason)
        assertEquals(BackupError.TOO_LARGE,assertThrows(BackupProblem::class.java){decodeBackup(ByteArray(BACKUP_MAX_BYTES+1))}.reason)
    }

    @Test fun repeatedHistoryPlanningUsesStableIdsAndKeepsExistingPayloads() {
        val local=answer()
        val sameId=SavedEncouragement(local.id,Instant.EPOCH,"different short","must not replace","different",false)
        val remote=answer("00000000-0000-0000-0000-000000000002")
        val first=newBackupAnswers(setOf(local.id),listOf(sameId,remote))
        assertEquals(listOf(remote),first)
        assertTrue(newBackupAnswers((listOf(local)+first).map{it.id}.toSet(),listOf(sameId,remote)).isEmpty())
        assertEquals("original full",local.full)
    }
    @Test fun decryptsAnIndependentPythonHashlibAndAesGcmFixture() {
        // Public synthetic vector: hashlib.pbkdf2_hmac SHA-256 + cryptography AESGCM.
        val fixture="SEdNQkFDSyEAAAABAAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaG0p/Q5gOKXKSlF4HWnuRF/4HvO6qHcvIGdnX9WEwSxaC58ot2R9ZR/kWyJC097Jj5ISiMzVTsQ=="
        val plaintext=BackupCrypto.decrypt(java.util.Base64.getDecoder().decode(fixture),"public 日本語 fixture passphrase".toCharArray())
        assertEquals("cross-provider backup test / 日本語",String(plaintext,Charsets.UTF_8))
    }

    @Test fun postMoveSyncFailureKeepsBothCompleteGenerationsWithoutReportingAPartialApply() {
        val root=Files.createTempDirectory("hagemashi-sync").toFile()
        try {
            File(root,"settings").writeText("old");File(root,"history").writeText("old")
            var rootSyncs=0
            val store=GenerationStore(root){directory->if(directory.canonicalFile==root.canonicalFile && ++rootSyncs==2)throw java.io.IOException()}
            store.transaction(prepare={_,stage->File(stage,"settings").writeText("new");File(stage,"history").writeText("new")},verify={})
            assertFalse(store.committedDurably)
            assertEquals("new",File(store.active(),"settings").readText());assertEquals("new",File(store.active(),"history").readText())
            store.prune()
            assertEquals("old",File(root,"settings").readText());assertEquals("old",File(root,"history").readText())
        }finally{root.deleteRecursively()}
    }
}
