package com.yosikou.hagemashi

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.*

class ReservationTraceTest {
    private fun event(n:Long)=ReservationTraceEvent(ReservationEvent.REGISTER_RETURNED,ReservationReason.NONE,n,n,42,"synthetic.1",NoticeSlot.MORNING,4,1000)
    @Test fun boundedTraceSurvivesJsonRoundTripInChronologicalAppendOrder() {
        var trace=JSONArray()
        repeat(140){trace=appendReservationTrace(trace,event(it.toLong()))}
        val restored=sanitizeReservationTrace(JSONArray(trace.toString()))
        assertEquals(128,restored.length())
        assertEquals(12,restored.getJSONObject(0).getLong("at"))
        assertEquals(139,restored.getJSONObject(127).getLong("at"))
    }
    @Test fun unsafeExtraFieldsUnknownEventsAndBadBuildIdsNeverEnterACopiedReport() {
        val extra=traceJson(event(1)).put("token","SYNTHETIC_SECRET").put("health","SYNTHETIC_HEALTH").put("answerId","SYNTHETIC_ID").put("message","SYNTHETIC_MESSAGE")
        val bad=traceJson(event(2)).put("event","UNRECOGNIZED_SYNTHETIC_CONTENT")
        val build=traceJson(event(3)).put("build","invalid\nSYNTHETIC_SECRET")
        val clean=sanitizeReservationTrace(JSONArray().put(extra).put(bad).put(build))
        assertEquals(1,clean.length())
        assertEquals(traceJson(event(1)).keys().asSequence().toSet(),clean.getJSONObject(0).keys().asSequence().toSet())
        assertFalse(clean.toString().contains("SYNTHETIC"))
    }
    @Test fun partialExecutionAndReopenRemainDistinctWithoutOverwritingPreviousOutcome() {
        val events=listOf(ReservationEvent.REGISTER_RETURNED,ReservationEvent.APP_HIDDEN,ReservationEvent.RECEIVER_ENTERED,
            ReservationEvent.HANDLER_STARTED,ReservationEvent.NOTIFY_STARTED,ReservationEvent.APP_OPEN,ReservationEvent.REGISTER_RETURNED)
        var trace=JSONArray()
        events.forEachIndexed{i,e->trace=appendReservationTrace(trace,event(i.toLong()).copy(event=e))}
        assertEquals(events.map{it.name},(0 until trace.length()).map{trace.getJSONObject(it).getString("event")})
        assertFalse(trace.toString().contains("NOTIFY_RETURNED"))
    }
    @Test fun fixedSkipReasonsAgreeWithExistingDeliveryDecisionIncludingBoundaries() {
        val cfg=AppSettings(personas=listOf(Persona("friend","F","P")),morning=SlotSetting(true,LocalTime.of(9,0)),
            evening=SlotSetting(true,LocalTime.of(20,0)),testAnswerId="11111111-1111-4111-8111-111111111111",morningVersion=4)
        val day=LocalDate.of(2026,11,10);val zone=ZoneId.of("Asia/Tokyo")
        val empty=reservationLedger(JSONObject().put("host","synthetic"))
        val start=reservationAt(day,cfg.morning.time,zone)
        for(hour in -24..36) for(version in listOf(3L,4L)) for(enabled in listOf(true,false)) for(claimed in listOf(true,false)) {
            val value=cfg.copy(morning=cfg.morning.copy(enabled=enabled));val at=start.plusSeconds(hour*3600L)
            val ledger=if(claimed)claimSlot(empty,day,NoticeSlot.MORNING)else empty
            assertEquals(reservationDue(version,day,NoticeSlot.MORNING,value,at,zone,ledger),
                reservationDecision(version,day,NoticeSlot.MORNING,value,at,zone,ledger)==ReservationReason.DUE)
        }
        assertEquals(ReservationReason.EARLY,reservationDecision(4,day,NoticeSlot.MORNING,cfg,start.minusSeconds(1),zone,empty))
        assertEquals(ReservationReason.WINDOW_ENDED,reservationDecision(4,day,NoticeSlot.MORNING,cfg,slotDeadline(day,NoticeSlot.MORNING,cfg,zone),zone,empty))
        assertEquals(ReservationReason.NO_TARGET,reservationDecision(4,day,NoticeSlot.MORNING,cfg.copy(testAnswerId=null),start,zone,empty))
        val expired=cfg.copy(goal="68",goalDate=day.minusDays(1))
        val guided=claimSlot(empty,day,NoticeSlot.EVENING,expired.goalCycle)
        assertEquals(ReservationReason.REMINDER_LIMIT,reservationDecision(4,day,NoticeSlot.MORNING,expired,start,zone,guided))
    }
}
