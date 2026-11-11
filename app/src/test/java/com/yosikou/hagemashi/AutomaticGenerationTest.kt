package com.yosikou.hagemashi

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject
import java.time.*
import java.util.concurrent.*

class AutomaticGenerationTest {
    private val cfg=AppSettings(personas=builtInPersonaIds.map{Persona(it,it,"P")},goal="68",modelId="synthetic-model",automaticGeneration=true,
        morning=SlotSetting(true,LocalTime.of(9,0)),evening=SlotSetting(true,LocalTime.of(20,0)))
    private val day=LocalDate.of(2026,11,11)
    private val zone=ZoneId.of("Asia/Tokyo")
    private fun empty()=reservationLedger(JSONObject().put("host","synthetic"))
    @Test fun automaticSlotsNeedNoTestAnswerButRespectLocalDayDeadlinesAndClaims() {
        val at=reservationAt(day,cfg.morning.time,zone)
        assertTrue(reservationDue(0,day,NoticeSlot.MORNING,cfg,at,zone,empty()))
        assertEquals(day,nextReservation(at.minusSeconds(1),zone,cfg,NoticeSlot.MORNING,empty())!!.day)
        assertFalse(reservationDue(0,day,NoticeSlot.MORNING,cfg,slotDeadline(day,NoticeSlot.MORNING,cfg,zone),zone,empty()))
        assertFalse(reservationDue(0,day,NoticeSlot.MORNING,cfg,at.plusSeconds(86400),zone,empty()))
        assertFalse(reservationDue(0,day,NoticeSlot.MORNING,cfg,at,zone,recordRunOutcome(empty(),day,NoticeSlot.MORNING,0,ReservationOutcome.POSTED,scheduledAnswerId(day,NoticeSlot.MORNING))))
        assertEquals(ReservationReason.DUE,reservationDecision(0,day,NoticeSlot.MORNING,cfg,at,zone,empty()))
    }
    @Test fun enablingAutomaticIsExplicitAndInvalidatesBothPreviousSlotJobs() {
        val old=cfg.copy(automaticGeneration=false)
        val decoded=decodeSettings(encodeSettings(old).apply{remove("automaticGeneration")},old.personas)
        assertFalse(decoded.automaticGeneration)
        val enabled=nextSettings(old,old.copy(automaticGeneration=true),day)
        assertTrue(enabled.automaticGeneration)
        assertEquals(old.morningVersion+1,enabled.morningVersion)
        assertEquals(old.eveningVersion+1,enabled.eveningVersion)
        assertTrue(decodeSettings(encodeSettings(enabled),enabled.personas).automaticGeneration)
    }
    @Test fun deterministicSavedRecordIdentitySurvivesRetriesButSeparatesDaysAndSlots() {
        assertEquals(scheduledAnswerId(day,NoticeSlot.MORNING),scheduledAnswerId(day,NoticeSlot.MORNING))
        assertNotEquals(scheduledAnswerId(day,NoticeSlot.MORNING),scheduledAnswerId(day,NoticeSlot.EVENING))
        assertNotEquals(scheduledAnswerId(day,NoticeSlot.MORNING),scheduledAnswerId(day.plusDays(1),NoticeSlot.MORNING))
        assertTrue(isAnswerId(scheduledAnswerId(day,NoticeSlot.MORNING)))
    }
    @Test fun unsafeOrMissingShortIsStoredAsFallbackAndUsedForDetailTitle() {
        val fallback="今日も自分のペースを大切に。できたことを認めながら、無理なく続けるあなたを応援しています。"
        for(short in listOf("", "体重69kgになりました")) {
            val text=encouragementText(JSONObject().put("short",short).put("full","synthetic-full").toString(),fallback)
            val answer=SavedEncouragement(scheduledAnswerId(day,NoticeSlot.MORNING),Instant.EPOCH,text.short,text.full,"synthetic",false)
            assertEquals(fallback,decodeEncouragement(answer.id,encodeEncouragement(answer)).short)
            assertEquals(notificationShort(answer.short,fallback),answerTitle(answer,fallback,"legacy"))
        }
    }
    @Test fun oldRecordWithoutShortRetainsItsBodyAndGetsLegacyTitle() {
        val id=scheduledAnswerId(day,NoticeSlot.MORNING)
        val json=encodeEncouragement(SavedEncouragement(id,Instant.EPOCH,"","synthetic-body","synthetic-input",false)).apply{remove("short")}
        val answer=decodeEncouragement(id,json)
        assertEquals("synthetic-body",answer.full)
        assertEquals("励ましの詳細",answerTitle(answer,"fallback","励ましの詳細"))
    }
    @Test fun foregroundRequestCancelsAnAutomaticOperationAndDefersFurtherAutomaticWork() {
        val executor=Executors.newSingleThreadExecutor()
        val entered=CountDownLatch(1);val finish=CountDownLatch(1)
        val operation=Operation()
        val task=executor.submit<Boolean>{
            assertTrue(AiExecution.beginAutomatic(operation));entered.countDown()
            try{finish.await(5,TimeUnit.SECONDS);runCatching{operation.check()}.exceptionOrNull() is java.util.concurrent.CancellationException}
            finally{AiExecution.endAutomatic(operation)}
        }
        try {
            assertTrue(entered.await(5,TimeUnit.SECONDS))
            val ticket=AiExecution.requestManual()
            try {
                finish.countDown();assertTrue(task.get(5,TimeUnit.SECONDS))
                assertFalse(AiExecution.beginAutomatic(Operation()))
            }finally{ticket.close();ticket.close()}
            val next=Operation();assertTrue(AiExecution.beginAutomatic(next));AiExecution.endAutomatic(next)
        }finally{finish.countDown();executor.shutdownNow()}
    }
    @Test fun endOfSlotShortensTheNetworkOperationDeadline() {
        val operation=Operation()
        operation.limitDuration(0)
        val error=assertThrows(Problem::class.java){operation.check()}
        assertEquals(Failure.TIMEOUT,error.kind)
    }
}
