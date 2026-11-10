package com.yosikou.hagemashi
import java.time.*
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test

class ReservationPlanTest {
    private val id="11111111-1111-4111-8111-111111111111"
    private val cfg=AppSettings(personas=listOf(Persona("friend","F","P")),morning=SlotSetting(true,LocalTime.of(9,0)),
        evening=SlotSetting(true,LocalTime.of(20,0)),testAnswerId=id,morningVersion=4,eveningVersion=5)
    private val zone=ZoneId.of("Asia/Tokyo")
    private val day=LocalDate.of(2026,11,10)
    private val now=reservationAt(day,cfg.morning.time,zone)
    private fun empty()=reservationLedger(JSONObject().put("host","synthetic"))
    @Test fun eachSlotUsesItsOwnLocalTimeAndEnabledState() {
        assertEquals(now,nextReservation(now.minusSeconds(1),zone,cfg,NoticeSlot.MORNING,empty())!!.at)
        assertEquals(reservationAt(day,cfg.evening.time,zone),nextReservation(now,zone,cfg,NoticeSlot.EVENING,empty())!!.at)
        assertNull(nextReservation(now,zone,cfg.copy(morning=cfg.morning.copy(enabled=false)),NoticeSlot.MORNING,empty()))
        assertNotNull(nextReservation(now,zone,cfg.copy(morning=cfg.morning.copy(enabled=false)),NoticeSlot.EVENING,empty()))
    }
    @Test fun retriesAreDeduplicatedPerDayAndSlotWithoutBlockingTheOtherSlot() {
        val claimed=claimSlot(empty(),day,NoticeSlot.MORNING)
        assertFalse(reservationDue(4,day,NoticeSlot.MORNING,cfg,now,zone,claimed))
        val evening=reservationAt(day,cfg.evening.time,zone)
        assertTrue(reservationDue(5,day,NoticeSlot.EVENING,cfg,evening,zone,claimed))
        assertFalse(reservationDue(6,day,NoticeSlot.EVENING,cfg,evening,zone,claimed))
        assertEquals(day.plusDays(1),nextReservation(now.minusSeconds(1),zone,cfg,NoticeSlot.MORNING,claimed)!!.day)
    }
    @Test fun morningWindowEndsAtTheEnabledEveningSlotAndNoOldDayCarriesOver() {
        val atNight=reservationAt(day,cfg.evening.time,zone)
        assertFalse(reservationDue(4,day,NoticeSlot.MORNING,cfg,atNight,zone,empty()))
        assertFalse(reservationDue(4,day.minusDays(1),NoticeSlot.MORNING,cfg,now,zone,empty()))
        assertTrue(reservationDue(4,day,NoticeSlot.MORNING,cfg.copy(evening=cfg.evening.copy(enabled=false)),atNight,zone,empty()))
    }
    @Test fun staleTimeOrVersionCannotRunEarlyAndOffNeverRuns() {
        assertFalse(reservationDue(3,day,NoticeSlot.MORNING,cfg,now,zone,empty()))
        assertFalse(reservationDue(4,day,NoticeSlot.MORNING,cfg.copy(morning=SlotSetting(true,LocalTime.NOON)),now,zone,empty()))
        assertFalse(reservationDue(4,day,NoticeSlot.MORNING,cfg.copy(morning=cfg.morning.copy(enabled=false)),now,zone,empty()))
    }
    @Test fun expiredGoalGuidesAtMostOncePerDayAcrossSlotsAndThreeTimesTotal() {
        val expired=cfg.copy(goal="68",goalDate=day.minusDays(1))
        var state=empty()
        repeat(3){i->
            val date=day.plusDays(i.toLong());val at=reservationAt(date,cfg.morning.time,zone)
            assertTrue(reservationDue(4,date,NoticeSlot.MORNING,expired,at,zone,state))
            state=claimSlot(state,date,NoticeSlot.MORNING,expired.goalCycle)
            assertFalse(reservationDue(5,date,NoticeSlot.EVENING,expired,reservationAt(date,cfg.evening.time,zone),zone,state))
        }
        assertNull(nextReservation(now.plusSeconds(1),zone,expired,NoticeSlot.MORNING,state))
        assertNull(nextReservation(now.plusSeconds(1),zone,expired,NoticeSlot.EVENING,state))
    }
    @Test fun completingReconfigurationResumesNormalSlotsButNeverDuplicatesASameDayGuide() {
        val expired=cfg.copy(goal="68",goalDate=day.minusDays(1))
        val state=claimSlot(empty(),day,NoticeSlot.MORNING,0)
        val renewed=nextSettings(expired,expired.copy(goalDate=day.plusDays(5)),day)
        assertFalse(goalNeedsReset(renewed,day))
        assertTrue(reservationDue(renewed.eveningVersion,day,NoticeSlot.EVENING,renewed,reservationAt(day,cfg.evening.time,zone),zone,state))
        val expiredAgain=renewed.copy(goalDate=day.minusDays(1))
        assertFalse(reservationDue(expiredAgain.eveningVersion,day,NoticeSlot.EVENING,expiredAgain,reservationAt(day,cfg.evening.time,zone),zone,state))
    }
    @Test fun legacySingleSlotClaimsMigrateToMorningWithoutErasingProtection() {
        val legacy=JSONObject().put("schema",1).put("claimed",JSONArray().put(day.toString()))
        val state=reservationLedger(legacy)
        assertTrue(slotKey(day,NoticeSlot.MORNING) in claimedSlots(state))
        assertFalse(slotKey(day,NoticeSlot.EVENING) in claimedSlots(state))
        assertEquals(state.toString(),reservationLedger(state).toString())
    }
    @Test fun dstAndClockRollbackDoNotAssumeTwentyFourHourDaysOrForgetClaims() {
        val ny=ZoneId.of("America/New_York")
        assertEquals(Instant.parse("2026-03-08T07:30:00Z"),reservationAt(LocalDate.of(2026,3,8),LocalTime.of(2,30),ny))
        var state=empty();repeat(500){state=claimSlot(state,day.plusDays(it.toLong()),NoticeSlot.MORNING)}
        assertTrue(slotKey(day,NoticeSlot.MORNING) in claimedSlots(reservationLedger(JSONObject(state.toString()))))
        assertFalse(reservationDue(4,day,NoticeSlot.MORNING,cfg,now,zone,state))
    }
    @Test fun deletedOrUnselectedTestAnswerCannotBeReplacedByLatestAndCorruptionFailsClosed() {
        assertNull(nextReservation(now,zone,cfg.copy(testAnswerId=null),NoticeSlot.MORNING,empty()))
        assertThrows(IllegalArgumentException::class.java){reservationLedger(JSONObject().put("unexpected","private"))}
        assertThrows(IllegalArgumentException::class.java){reservationLedger(JSONObject().put("schema",9))}
    }
    @Test fun guideNavigationContainsNoHealthValuesAndOnlyOpensGoalSettings() {
        val app="com.yosikou.hagemashi.diagnostic"
        assertTrue(isGoalReviewNavigation("$app.REVIEW_GOAL","$app://goal-review/0",app))
        assertFalse(isGoalReviewNavigation("other","$app://goal-review/0",app))
        assertFalse(isGoalReviewNavigation("$app.REVIEW_GOAL","$app://goal-review/0?goal=68",app))
    }
}
