package com.yosikou.hagemashi
import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalTime

class AppSettingsTest {
    private val defaults = listOf(Persona("friend", "Friend", "friendly"), Persona("numbers", "Coach", "numbers"), Persona("companion", "Companion", "relaxed"))
    private fun initial() = AppSettings(personas = defaults)
    @Test fun freshDefaultsSelectFriendAndDoNotInventAGoalOrModel() {
        val settings = decodeSettings(JSONObject().put("host", "synthetic"), defaults)
        assertEquals("friend", settings.personaId); assertNull(settings.goal); assertNull(settings.modelId)
    }
    @Test fun roundTripKeepsEditsCustomPersonasSelectionModelGoalAndNotificationPreferences() {
        val edited = initial().copy(goal = "68.5", goalDate = LocalDate.of(2027, 2, 1), modelId = "chosen-model",
            personas = defaults + Persona("custom", "My persona", "my prompt"), personaId = "custom", supplement = "extra",
            morning = SlotSetting(true, LocalTime.of(15,32)))
        assertEquals(edited, decodeSettings(encodeSettings(edited), defaults))
    }
    @Test fun deletingSelectedCustomFallsBackToFriendButDeletingAnotherKeepsSelection() {
        val edited = initial().copy(personas = defaults + listOf(Persona("a", "A", "a"), Persona("b", "B", "b")), personaId = "a")
        assertEquals("friend", edited.deletePersona("a").personaId)
        assertEquals("a", edited.deletePersona("b").personaId)
        assertThrows(IllegalArgumentException::class.java) { edited.deletePersona("friend") }
    }
    @Test fun unavailableModelIsRetainedAndExplicitReselectionIsRequired() {
        val edited = initial().copy(goal = "68", modelId = "old-model")
        assertEquals("old-model", decodeSettings(encodeSettings(edited), defaults).modelId)
        assertEquals(HomeAction.SELECT_AGENT, homeAction(edited, true, true, LocalDate.now()))
    }
    @Test fun homeRoutesGoalThenConnectionThenAgentAndAllowsMissingHealth() {
        val day = LocalDate.of(2026, 11, 10)
        assertEquals(HomeAction.SET_GOAL, homeAction(initial(), false, false, day))
        val goal = initial().copy(goal = "68")
        assertEquals(HomeAction.CONNECT, homeAction(goal, false, false, day))
        assertEquals(HomeAction.SELECT_AGENT, homeAction(goal, true, false, day))
        assertEquals(HomeAction.GENERATE, homeAction(goal.copy(modelId = "selected"), true, false, day))
    }
    @Test fun expiredGoalRequiresReviewAndMaintenanceDoesNotKeepAnExpiredDeadline() {
        val day = LocalDate.of(2026, 11, 10)
        val expired = initial().copy(goal = "68", modelId = "m", goalDate = day.minusDays(1))
        assertEquals(HomeAction.RESET_GOAL, homeAction(expired, true, false, day))
        assertEquals(HomeAction.GENERATE, homeAction(expired.copy(maintaining = true, goalDate = null), true, false, day))
    }
    @Test fun onlyReservationChangesInvalidateAnOldReservation() {
        val settings = initial()
        assertEquals(0L, nextSettings(settings, settings.copy(supplement = "extra")).reservationVersion)
        assertEquals(1L, nextSettings(settings, settings.copy(morning=SlotSetting(true,settings.morning.time))).reservationVersion)
        assertEquals(1L, nextSettings(settings, settings.copy(morning=SlotSetting(false,LocalTime.NOON))).reservationVersion)
    }
    @Test fun legacySingleSlotMigratesToMorningWithoutChangingItsTimeOrEnablingEvening() {
        val json=encodeSettings(initial()).put("schema",1).put("notificationEnabled",true).put("notificationTime","11:25")
        val restored=decodeSettings(json,defaults)
        assertEquals(SlotSetting(true,LocalTime.of(11,25)),restored.morning)
        assertFalse(restored.evening.enabled)
    }
    @Test fun bothSlotsMustBeOrderedAndOnlyAffectedSlotRevisionChanges() {
        val cfg=initial().copy(morning=SlotSetting(true,LocalTime.of(8,0)),evening=SlotSetting(true,LocalTime.of(20,0)))
        assertThrows(IllegalArgumentException::class.java) { nextSettings(cfg,cfg.copy(morning=SlotSetting(true,LocalTime.of(21,0)))) }
        val changed=nextSettings(cfg,cfg.copy(evening=SlotSetting(true,LocalTime.of(21,0))))
        assertEquals(cfg.morningVersion,changed.morningVersion); assertEquals(cfg.eveningVersion+1,changed.eveningVersion)
    }
    @Test fun reminderCycleResetsOnlyWhenReconfigurationActuallyCompletes() {
        val today=LocalDate.of(2026,11,10)
        val expired=initial().copy(goal="68",goalDate=today.minusDays(1))
        assertEquals(0L,nextSettings(expired,expired.copy(goal="67"),today).goalCycle)
        assertEquals(1L,nextSettings(expired,expired.copy(goalDate=null),today).goalCycle)
    }
    @Test fun corruptSettingsNeverSilentlyResetEdits() {
        assertThrows(IllegalArgumentException::class.java) { decodeSettings(JSONObject().put("unknown", "data"), defaults) }
        assertThrows(IllegalArgumentException::class.java) { decodeSettings(encodeSettings(initial()).put("personaId", "missing"), defaults) }
    }
}
