package com.yosikou.hagemashi
import java.time.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ReservationPlanTest {
    private val id = "11111111-1111-4111-8111-111111111111"
    private val cfg = AppSettings(personas = listOf(Persona("friend", "F", "P")), notificationEnabled = true,
        notificationTime = LocalTime.of(9, 0), testAnswerId = id, reservationVersion = 4)
    private val zone = ZoneId.of("Asia/Tokyo")
    private val now = Instant.parse("2026-11-10T00:00:00Z")
    @Test fun nextAlarmIsLocalCalendarTimeNotTwentyFourHoursFromNow() {
        val plan = nextReservation(now.minusSeconds(1), zone, cfg, emptySet())!!
        assertEquals(LocalDate.of(2026,11,10), plan.day); assertEquals(now, plan.at)
        assertEquals(LocalDate.of(2026,11,11), nextReservation(now, zone, cfg, emptySet())!!.day)
    }
    @Test fun disabledOrMissingSavedAnswerNeverProducesAReservation() {
        assertNull(nextReservation(now, zone, cfg.copy(notificationEnabled = false), emptySet()))
        assertNull(nextReservation(now, zone, cfg.copy(testAnswerId = null), emptySet()))
    }
    @Test fun oldVersionOffAndChangedTimeCannotDeliverAStaleAlarm() {
        val day = LocalDate.of(2026,11,10)
        assertFalse(reservationDue(3, day, cfg, now, zone, emptySet()))
        assertFalse(reservationDue(4, day, cfg.copy(notificationEnabled = false), now, zone, emptySet()))
        assertFalse(reservationDue(4, day, cfg.copy(notificationTime = LocalTime.NOON), now, zone, emptySet()))
    }
    @Test fun yesterdayNeverCarriesIntoTodayAndFutureDatesDoNotFireEarly() {
        val day = LocalDate.of(2026,11,10)
        assertFalse(reservationDue(4, day.minusDays(1), cfg, now, zone, emptySet()))
        assertFalse(reservationDue(4, day.plusDays(1), cfg, now, zone, emptySet()))
        assertFalse(reservationDue(4, day, cfg, now.minusSeconds(1), zone, emptySet()))
        assertTrue(reservationDue(4, day, cfg, now.plusSeconds(10), zone, emptySet()))
    }
    @Test fun persistentClaimSurvivesRetryReconfigurationAndRecreation() {
        val day = LocalDate.of(2026,11,10)
        val json = claimDay(JSONObject().put("host", "synthetic"), day)
        val restored = claimedDays(JSONObject(json.toString()))
        assertFalse(reservationDue(4, day, cfg, now, zone, restored))
        assertFalse(reservationDue(5, day, cfg.copy(reservationVersion = 5), now, zone, restored))
        assertEquals(day.plusDays(1), nextReservation(now.minusSeconds(1), zone, cfg, restored)!!.day)
        assertEquals(1, claimedDays(claimDay(json, day)).size)
    }
    @Test fun timezoneChangesAndDstUseCalendarDatesAndOneOccurrenceOnOverlap() {
        val ny = ZoneId.of("America/New_York")
        assertEquals(Instant.parse("2026-03-08T07:30:00Z"), reservationAt(LocalDate.of(2026,3,8), LocalTime.of(2,30), ny))
        val overlap = cfg.copy(notificationTime = LocalTime.of(1,30))
        val plan = nextReservation(Instant.parse("2026-11-01T05:45:00Z"), ny, overlap, emptySet())!!
        assertEquals(LocalDate.of(2026,11,2), plan.day)
        assertNotEquals(nextReservation(now.minusSeconds(1), zone, cfg, emptySet())!!.at,
            nextReservation(now.minusSeconds(1), ZoneId.of("Europe/London"), cfg, emptySet())!!.at)
    }
    @Test fun claimsAreNotForgottenAfterManyDaysOrAClockRollback() {
        var state = JSONObject().put("host", "synthetic")
        val first = LocalDate.of(2024,1,1)
        repeat(500) { state = claimDay(state, first.plusDays(it.toLong())) }
        assertTrue(first.toString() in claimedDays(JSONObject(state.toString())))
        assertFalse(reservationDue(4, first, cfg, reservationAt(first, cfg.notificationTime, zone), zone, claimedDays(state)))
    }
    @Test fun corruptLedgerNeverResetsDuplicateProtection() {
        assertThrows(IllegalArgumentException::class.java) { claimedDays(JSONObject().put("unexpected", "private")) }
        assertThrows(IllegalArgumentException::class.java) { claimedDays(JSONObject().put("schema", 2)) }
    }
}
