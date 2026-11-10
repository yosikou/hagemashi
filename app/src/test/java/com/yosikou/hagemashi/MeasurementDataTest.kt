package com.yosikou.hagemashi
import java.time.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class MeasurementDataTest {
    private val zone=ZoneId.of("Asia/Tokyo")
    private val now=Instant.parse("2026-11-10T03:00:00Z")
    private val today=LocalDate.of(2026,11,10).atStartOfDay(zone).toInstant()
    private class Source(val today:Instant):HealthDataSource {
        override suspend fun permissions()=HealthKind.entries.toSet()
        override suspend fun samples(kind:HealthKind,window:HealthWindow)=HealthPage(listOf(
            HealthSample(69.5,today.minusSeconds(1),"old"),HealthSample(69.0,today.plusSeconds(1),"today")))
        override suspend fun steps(window:HealthWindow)=if(window.start>=today) HealthSteps(0,setOf("today")) else null
    }
    @Test fun morningExcludesTodayWhileManualAndNightIncludeTodayEvenWhenManuallyUsedInMorning()=runBlocking {
        val source=Source(today)
        val morning=HealthReader(source).read(now,zone,HealthReadPlan.GENERATION,GenerationKind.MORNING)
        val manual=HealthReader(source).read(now,zone,HealthReadPlan.GENERATION)
        val night=HealthReader(source).read(now,zone,HealthReadPlan.GENERATION,GenerationKind.EVENING)
        assertEquals(today,morning.measurementWindow.end)
        assertTrue(morning.results.first().samples.all{it.measuredAt<today})
        assertEquals(2,manual.results.first().samples.size)
        assertEquals(manual,night)
        assertEquals(now,manual.measurementWindow.end)
        assertTrue(morning.results.last().days.all{it.window.end<=today})
    }
    @Test fun rowsKeepActualZeroAndMissingRecordsDifferentAndRoundTripImmutableSnapshot()=runBlocking {
        val snapshot=HealthReader(Source(today)).read(now,zone,HealthReadPlan.GENERATION)
        val rows=measurementRows(snapshot,"68","2027-01-01",zone)
        assertEquals(MeasureState.VALUE,rows.last{it.item==MeasureItem.STEPS}.state)
        assertEquals("0",rows.last{it.item==MeasureItem.STEPS}.value)
        assertTrue(rows.any{it.item==MeasureItem.STEPS&&it.state==MeasureState.NO_RECORDS&&it.value==null})
        assertEquals(rows,decodeRows(encodeRows(rows)))
        assertEquals("old",rows.first{it.item==MeasureItem.WEIGHT}.origin)
    }
    @Test fun legacyRowsDoNotInventMissingMeasurementsOrExposeRawJson() {
        val raw="目標体重：68 kg\n体重：69.875 kg／測定日時：2026-11-10T12:00:00+09:00／提供元：synthetic\n体脂肪率：未取得・読み取り権限なし（0ではありません）\n歩数：未取得・期間内の記録なし（0ではありません）"
        val rows=legacyMeasurementRows(raw)
        assertEquals("69.875",rows.single{it.item==MeasureItem.WEIGHT}.value)
        assertEquals(MeasureState.UNPERMITTED,rows.single{it.item==MeasureItem.BODY_FAT}.state)
        assertEquals(MeasureState.NO_RECORDS,rows.single{it.item==MeasureItem.STEPS}.state)
    }
    @Test fun goalValidationAcceptsDecimalAndPastedNumericValuesButRejectsUnitsAndInvalidValues() {
        for(value in listOf("68.5"," 68.875 ","６８．５","+68.5","1e2",".5")) assertNotNull(canonicalGoal(value))
        for(value in listOf("0","-0","-1","NaN","Infinity","68 kg","6 8","<script>","1e999999")) assertNull(canonicalGoal(value))
    }
}
