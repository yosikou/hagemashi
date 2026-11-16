package com.yosikou.hagemashi

import java.time.LocalTime
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class DataConsentTest {
    private val personas=listOf(Persona("friend","friend","prompt"),Persona("numbers","coach","prompt"),Persona("companion","companion","prompt"))
    private val defaults=AppSettings(personas=personas)
    private fun accepted()=consentDecision(defaults,true)
    private fun automatic()=accepted().copy(automaticGeneration=true,morning=SlotSetting(true,LocalTime.of(8,0)),evening=SlotSetting(true,LocalTime.of(20,0)),morningVersion=4,eveningVersion=5)
    @Test fun freshAndExistingInstallCannotSendBeforeOpeningSettings() {
        for(cfg in listOf(defaults,defaults.copy(automaticGeneration=true,morning=SlotSetting(true,LocalTime.NOON)))) {
            GenerationKind.entries.forEach{assertFalse(canSendData(cfg,it))}
            val migrated=migrateDataConsent(cfg)
            assertFalse(migrated.automaticGeneration);assertEquals(cfg.morning.time,migrated.morning.time)
            assertFalse(hasDataConsent(migrated));assertEquals(0,migrated.consentShownVersion)
        }
    }
    @Test fun presentationIsNotConsentAndDecliningIsRememberedSeparately() {
        val shown=migrateDataConsent(defaults).copy(consentShownVersion=DATA_CONSENT_VERSION)
        assertFalse(hasDataConsent(shown))
        val declined=consentDecision(shown,false)
        val restarted=decodeSettings(encodeSettings(declined),personas)
        assertEquals(DATA_CONSENT_VERSION,restarted.consentShownVersion)
        assertFalse(hasDataConsent(restarted))
    }
    @Test fun acceptingAloneNeverEnablesAutomaticAndManualCanBeRepeated() {
        val cfg=consentDecision(defaults.copy(automaticGeneration=true),true)
        assertTrue(hasDataConsent(cfg));assertFalse(cfg.automaticGeneration)
        val gate=GenerationSendGate(Any()){decodeSettings(encodeSettings(cfg),personas)}
        var sent=0
        repeat(3){gate.write(GenerationKind.MANUAL){sent++}}
        assertEquals(3,sent)
        for(kind in listOf(GenerationKind.MORNING,GenerationKind.EVENING))assertFalse(canSendData(cfg,kind))
    }
    @Test fun automaticRequiresConsentEnabledGenerationEnabledSlotAndCurrentVersion() {
        val cfg=automatic()
        assertTrue(canSendData(cfg,GenerationKind.MORNING,4));assertTrue(canSendData(cfg,GenerationKind.EVENING,5))
        assertFalse(canSendData(cfg,GenerationKind.MORNING,3))
        assertFalse(canSendData(cfg.copy(morning=cfg.morning.copy(enabled=false)),GenerationKind.MORNING))
        assertFalse(canSendData(cfg.copy(resumeRequired=true),GenerationKind.EVENING))
        assertFalse(canSendData(cfg.copy(automaticGeneration=false),GenerationKind.EVENING))
    }
    @Test fun queuedAndAlreadyPreparedWorkRereadsConsentAtActualBodyWrite() {
        var cfg=automatic();val gate=GenerationSendGate(Any()){cfg};var bytesWritten=0
        for(kind in GenerationKind.entries) {
            cfg=automatic();gate.check(kind)
            cfg=consentDecision(cfg,false)
            assertEquals(Failure.CONSENT,assertThrows(Problem::class.java){gate.write(kind){bytesWritten++}}.kind)
        }
        assertEquals(0,bytesWritten)
    }
    @Test fun stoppingAutomaticBlocksBothQueuedSlotsButKeepsManualConsent() {
        var cfg=automatic();val gate=GenerationSendGate(Any()){cfg}
        gate.check(GenerationKind.MORNING,4);gate.check(GenerationKind.EVENING,5)
        cfg=cfg.copy(automaticGeneration=false)
        for(kind in listOf(GenerationKind.MORNING,GenerationKind.EVENING))assertThrows(Problem::class.java){gate.write(kind){fail("must not send")}}
        var sent=false;gate.write(GenerationKind.MANUAL){sent=true};assertTrue(sent)
    }
    @Test fun storageReadFailureIsClosedAtFinalWrite() {
        val gate=GenerationSendGate(Any()){throw Problem(Failure.STORAGE)}
        GenerationKind.entries.forEach{kind->assertThrows(Problem::class.java){gate.write(kind){fail("must not send")}}}
    }
    @Test fun malformedConsentVersionDoesNotGrantAccess() {
        for(raw in listOf<Any>("1",true,1.5,4294967297L)) {
            val json=encodeSettings(accepted()).put("consentAcceptedVersion",raw)
            assertFalse(hasDataConsent(decodeSettings(json,personas)))
        }
    }
    @Test fun materialPolicyChangeInvalidatesConsentAndDisablesOldAutomaticState() {
        val old=automatic().copy(consentControlsVersion=DATA_CONSENT_VERSION-1,consentAcceptedVersion=DATA_CONSENT_VERSION-1,consentShownVersion=DATA_CONSENT_VERSION-1)
        assertFalse(hasDataConsent(old));GenerationKind.entries.forEach{assertFalse(canSendData(old,it))}
        val migrated=migrateDataConsent(old)
        assertFalse(migrated.automaticGeneration);assertEquals(old.morning.time,migrated.morning.time)
        assertNotEquals(DATA_CONSENT_VERSION,migrated.consentShownVersion)
        assertTrue(hasDataConsent(consentDecision(migrated,true)))
    }
    @Test fun newAndLegacyBackupCannotTransferAnyConsentOrEnableState() {
        val cfg=automatic();val bytes=encodeBackup(BackupContents(cfg,emptyList(),false))
        val json=JSONObject(String(bytes,Charsets.UTF_8));val settings=json.getJSONObject("settings")
        for(key in listOf("consentShownVersion","consentAcceptedVersion","consentControlsVersion","automaticGeneration","morningEnabled","eveningEnabled"))assertFalse(settings.has(key))
        val legacy=JSONObject(json.toString()).also{it.getJSONObject("settings").put("automaticGeneration",true).put("morningEnabled",true).put("eveningEnabled",true)}
        for(input in listOf(bytes,legacy.toString().toByteArray())) {
            val restored=restoredSettings(decodeBackup(input).settings,cfg)
            assertEquals(cfg.morning.time,restored.morning.time);assertEquals(cfg.evening.time,restored.evening.time)
            assertFalse(hasDataConsent(restored));assertEquals(0,restored.consentShownVersion)
            assertFalse(restored.automaticGeneration);assertFalse(restored.resumeAutomatic)
            val resumed=resumeRestoredSettings(restored)
            assertFalse(resumed.automaticGeneration);assertFalse(resumed.morning.enabled);assertFalse(resumed.evening.enabled)
        }
    }
}
