package com.yosikou.hagemashi

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.*

class ReservationOutcomeTest {
    private val day=LocalDate.of(2026,11,11)
    private val zone=ZoneId.of("Asia/Tokyo")
    private val cfg=AppSettings(personas=listOf(Persona("friend","F","P")),goal="68",automaticGeneration=true,
        morning=SlotSetting(true,LocalTime.of(12,10)),evening=SlotSetting(false,LocalTime.of(20,0)),morningVersion=16)
    private val id=scheduledAnswerId(day,NoticeSlot.MORNING,16)
    private fun empty()=reservationLedger(JSONObject().put("host","synthetic"))
    @Test fun oldDailyClaimDoesNotMoveANewReservationToTomorrow() {
        val old=claimSlot(empty(),day,NoticeSlot.MORNING)
        val at=reservationAt(day,cfg.morning.time,zone)
        assertEquals(day,nextReservation(at.minusSeconds(120),zone,cfg,NoticeSlot.MORNING,old)!!.day)
        assertTrue(reservationDue(16,day,NoticeSlot.MORNING,cfg,at,zone,old))
        assertEquals(ReservationReason.DUE,reservationDecision(16,day,NoticeSlot.MORNING,cfg,at,zone,old))
    }
    @Test fun sameDayRebookingGetsANewAnswerWhileRetriesKeepTheSameAnswer() {
        val state=recordRunOutcome(empty(),day,NoticeSlot.MORNING,16,ReservationOutcome.POSTED,id)
        val changed=nextSettings(cfg,cfg.copy(morning=cfg.morning.copy(time=LocalTime.of(12,30))),day)
        val at=reservationAt(day,changed.morning.time,zone)
        assertTrue(reservationDue(changed.morningVersion,day,NoticeSlot.MORNING,changed,at,zone,state))
        assertNotEquals(id,scheduledAnswerId(day,NoticeSlot.MORNING,changed.morningVersion))
        assertEquals(id,scheduledAnswerId(day,NoticeSlot.MORNING,16))
        assertFalse(reservationDue(16,day,NoticeSlot.MORNING,cfg,reservationAt(day,cfg.morning.time,zone),zone,state))
    }
    @Test fun savedButBlockedOrFailedNoticeCanRetryWithoutClaimingGenerationSuccess() {
        val at=reservationAt(day,cfg.morning.time,zone)
        for(outcome in listOf(ReservationOutcome.SAVED,ReservationOutcome.BLOCKED,ReservationOutcome.FAILED,ReservationOutcome.SENDING)) {
            val state=recordRunOutcome(empty(),day,NoticeSlot.MORNING,16,outcome,id)
            assertTrue(reservationDue(16,day,NoticeSlot.MORNING,cfg,at,zone,state))
            val record=state.getJSONObject("outcomes").getJSONObject(reservationRunKey(day,NoticeSlot.MORNING,16))
            assertEquals(id,record.getString("answerId"))
            assertEquals(outcome,runOutcome(reservationLedger(JSONObject(state.toString())),day,NoticeSlot.MORNING,16))
        }
    }
    @Test fun transientGenerationFailureIsNotSuccessAndCanRetryTheSameReservation() {
        val state=recordRunOutcome(empty(),day,NoticeSlot.MORNING,16,ReservationOutcome.GENERATION_RETRYING)
        assertTrue(reservationDue(16,day,NoticeSlot.MORNING,cfg,reservationAt(day,cfg.morning.time,zone),zone,state))
        assertEquals(ReservationOutcome.GENERATION_RETRYING,runOutcome(state,day,NoticeSlot.MORNING,16))
    }
    @Test fun failedOrSkippedGenerationStopsOnlyItsOwnReservation() {
        val next=cfg.copy(morningVersion=17)
        val at=reservationAt(day,cfg.morning.time,zone)
        for(outcome in listOf(ReservationOutcome.GENERATION_FAILED,ReservationOutcome.SKIPPED)) {
            val state=recordRunOutcome(empty(),day,NoticeSlot.MORNING,16,outcome)
            assertFalse(reservationDue(16,day,NoticeSlot.MORNING,cfg,at,zone,state))
            assertTrue(reservationDue(17,day,NoticeSlot.MORNING,next,at,zone,state))
        }
    }
    @Test fun cannotRecordSavedOrPostedWithoutARealAnswerReference() {
        assertThrows(IllegalArgumentException::class.java){recordRunOutcome(empty(),day,NoticeSlot.MORNING,16,ReservationOutcome.POSTED)}
        assertThrows(IllegalArgumentException::class.java){recordRunOutcome(empty(),day,NoticeSlot.MORNING,16,ReservationOutcome.SAVED,"invalid")}
    }
    @Test fun testAnswerDeliveryAlsoAllowsANewVersionOnTheSameDay() {
        val settings=cfg.copy(automaticGeneration=false,testAnswerId=id)
        val state=claimRun(empty(),day,NoticeSlot.MORNING,16)
        val at=reservationAt(day,cfg.morning.time,zone)
        assertFalse(reservationDue(16,day,NoticeSlot.MORNING,settings,at,zone,state))
        assertTrue(reservationDue(17,day,NoticeSlot.MORNING,settings.copy(morningVersion=17),at,zone,state))
    }
    @Test fun goalReminderLimitStillAppliesAcrossReservationVersions() {
        val expired=cfg.copy(goalDate=day.minusDays(1))
        val state=claimSlot(empty(),day,NoticeSlot.MORNING,expired.goalCycle)
        assertFalse(reservationDue(17,day,NoticeSlot.MORNING,expired.copy(morningVersion=17),reservationAt(day,cfg.morning.time,zone),zone,state))
    }
}
