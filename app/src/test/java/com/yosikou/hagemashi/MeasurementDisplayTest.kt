package com.yosikou.hagemashi

import org.junit.Assert.*
import org.junit.Test
import java.time.ZoneId

class MeasurementDisplayTest {
    private val zone = ZoneId.of("Asia/Tokyo")
    private fun row(value: String = "69.8", at: String = "2026-11-10T00:01:23.123456789Z", item: MeasureItem = MeasureItem.WEIGHT) =
        MeasurementRow(item, value, if (item == MeasureItem.BODY_FAT) "%" else "kg", at)

    @Test fun localMinuteFormattingAndRanges() {
        assertEquals("2026/11/10 09:01", measurementDisplayTime(row().at, zone))
        assertEquals("2026/11/10 09:01", measurementDisplayTime("2026-11-10T01:01:59.999+01:00", zone))
        assertEquals("2026/11/10 09:01\n〜\n2026/11/11 09:01", measurementDisplayTime("2026-11-10T00:01:23Z 〜 2026-11-11T00:01:45Z", zone))
        assertNull(measurementDisplayTime("unknown", zone))
        assertNull(measurementDisplayTime("2026-11-10T09:01:00", zone))
    }
    @Test fun groupsByLocalDayAndExactNumberKeepingLatestInstantAndOrigins() {
        val early = row("69.80", "2026-11-09T23:01:23.111Z").copy(origin="a")
        val late = row("69.800", "2026-11-10T00:01:23.999Z").copy(origin="b")
        val original = listOf(early, late)
        val encoded = encodeRows(original).toString()
        val display = measurementDisplay(original, zone)
        assertTrue(display.combined)
        assertEquals(listOf(late.copy(origin="a, b")), display.rows)
        assertEquals(encoded, encodeRows(original).toString())
        assertEquals(2, original.size)
    }
    @Test fun distinctValuesDatesAndItemsRemainSeparate() {
        val input = listOf(row("69.80001"), row("69.80002"), row(at="2026-11-11T00:01:23Z"), row(item=MeasureItem.BODY_FAT))
        val display = measurementDisplay(input, zone)
        assertFalse(display.combined)
        assertEquals(4, display.rows.size)
    }
    @Test fun sameUtcDayCanBeDifferentLocalDates() {
        val input = listOf(row(at="2026-11-10T14:59:59Z"), row(at="2026-11-10T15:00:00Z"))
        assertFalse(measurementDisplay(input, zone).combined)
        assertTrue(measurementDisplay(input, ZoneId.of("UTC")).combined)
    }
    @Test fun latestUsesSubMinutePrecisionAndRowsAreOrdered() {
        val late = row(at="2026-11-10T00:01:23.999Z")
        val early = row(at="2026-11-10T00:01:23.111Z")
        val middle = row("69.9", "2026-11-10T00:01:23.500Z")
        assertEquals(listOf(middle, late), measurementDisplay(listOf(late, middle, early), zone).rows)
    }
    @Test fun missingInvalidAndStepsAreNeverCombined() {
        val cases = listOf(row().copy(state=MeasureState.NO_RECORDS), row().copy(state=MeasureState.UNPERMITTED),
            row(value="invalid"), row(at="unknown"), row(item=MeasureItem.STEPS))
        cases.forEach {
            val display = measurementDisplay(listOf(it, it), zone)
            assertFalse(display.combined)
            assertEquals(2, display.rows.size)
        }
        assertFalse(measurementDisplay(listOf(row()), zone).combined)
    }
    @Test fun oldAnswerRowsUseSameReadOnlyProjection() {
        val input = "体重：69.8 kg／測定日時：2026-11-10T00:01:00Z／提供元：a\n体重：69.80 kg／測定日時：2026-11-10T01:02:03Z／提供元：a"
        val original = legacyMeasurementRows(input)
        assertEquals(2, original.size)
        val display = measurementDisplay(original, zone)
        assertTrue(display.combined)
        assertEquals("2026/11/10 10:02", measurementDisplayTime(display.rows.single().at, zone))
        assertEquals(2, legacyMeasurementRows(input).size)
    }
}
