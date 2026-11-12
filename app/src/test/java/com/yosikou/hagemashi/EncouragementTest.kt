package com.yosikou.hagemashi

import java.time.Instant
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class EncouragementTest {
    private val fallback = "今日も自分のペースを大切に。できたことを認めながら、無理なく続けるあなたを応援しています。"
    private val safe = "今日もあなたのペースを大切に。できたことを認めて、焦らず心地よい習慣を続けるあなたを応援しています。"
    private val a = "11111111-1111-4111-8111-111111111111"
    private val b = "22222222-2222-4222-8222-222222222222"
    private val app = "com.yosikou.hagemashi.diagnostic"
    @Test fun fallbackIsWithinTheTargetLengthAndContainsNoNumericGlyphs() {
        assertTrue(fallback.codePointCount(0, fallback.length) in 40..60)
        assertEquals(fallback, notificationShort(fallback, "REJECTED"))
    }
    @Test fun safeJapaneseShortIsRetained() {
        assertTrue(safe.codePointCount(0, safe.length) in 40..60)
        assertEquals(safe, notificationShort(safe, fallback))
    }
    @Test fun numericVariantsNeverReachTheNotificationShort() {
        for (number in listOf("69.8", "６９．８", "٢٣", "Ⅶ", "⑦", "七十", "壱", "〇", "ななじゅう", "seventy", "ｓｅｖｅｎｔｙ", "🔟")) {
            val candidate = safe.replace("今日", number)
            assertEquals(number, fallback, notificationShort(candidate, fallback))
        }
    }
    @Test fun healthUnitsAndLabelsAreConservativelyRejectedEvenWithoutDigits() {
        for (label in listOf("体重", "体脂肪", "歩数", "キロ", "パーセント", "％"))
            assertEquals(fallback, notificationShort(safe.replace("今日", label), fallback))
    }
    @Test fun missingOutOfRangeMultilineAndHiddenControlCharactersUseFixedText() {
        for (candidate in listOf(null, "", "短い文", safe.repeat(3), safe.replace("あなた", "あ\nなた"), safe.replace("今日", "\u202E今日")))
            assertEquals(fallback, notificationShort(candidate, fallback))
    }
    @Test fun structuredAnswerHasIndependentShortAndUntruncatedFull() {
        val full = "synthetic-full: weight=69.875, measured at 2026-11-10".repeat(8)
        val text = encouragementText(JSONObject().put("short", safe).put("full", full).toString(), fallback)
        assertEquals(safe, text.short)
        assertEquals(full, text.full)
    }
    @Test fun shortMissingOrNumericDoesNotLoseTheFullAnswer() {
        for (short in listOf(null, "69.875 kg", 42)) {
            val json = JSONObject().put("full", "synthetic-private-full")
            if (short != null) json.put("short", short)
            val text = encouragementText(json.toString(), fallback)
            assertEquals(fallback, text.short)
            assertEquals("synthetic-private-full", text.full)
        }
    }
    @Test fun legacyPlainFullNeverBecomesNotificationText() {
        val text = encouragementText("weight=69.875 kg\nsynthetic-full", fallback)
        assertEquals(fallback, text.short)
        assertTrue(text.full.contains("69.875"))
    }
    @Test fun fencedJsonIsParsedButInvalidStructuredFullFailsSafely() {
        val text = encouragementText("```json\n${JSONObject().put("short", safe).put("full", "synthetic-full")}\n```", fallback)
        assertEquals("synthetic-full", text.full)
        for (bad in listOf("", "{not-json", "{\"full\":\"\"}", "{\"short\":\"synthetic-private\"}")) {
            val failure = assertThrows(Problem::class.java) { encouragementText(bad, fallback) }
            assertEquals(Failure.PROTOCOL, failure.kind)
            assertNull(failure.message)
            assertNull(failure.cause)
        }
    }
    @Test fun savedRecordRoundTripsGenerationTimeAndExactUsedDataWithoutCredentials() {
        val review = ReviewedGeneration("model", "Model", "persona-not-stored", "weight=69.875 at synthetic-time", true)
        val at = Instant.parse("2026-11-10T03:00:00Z")
        val record = newSavedEncouragement(EncouragementText(safe, "full-A"), review, at)
        val json = encodeEncouragement(record)
        val restored = decodeEncouragement(record.id, JSONObject(json.toString()))
        assertEquals(record.id, restored.id)
        assertEquals(at, restored.generatedAt)
        assertEquals(review.input, restored.usedData)
        assertEquals("full-A", restored.full)
        assertTrue(restored.hasHealthData)
        assertFalse(json.toString().contains("persona-not-stored"))
        assertEquals(setOf("schema", "id", "generatedAt", "short", "full", "usedData", "hasHealthData"), json.keys().asSequence().toSet())
    }
    @Test fun laterGenerationHasADifferentIdAndCannotChangeTheEarlierSavedValue() {
        val review = ReviewedGeneration("model", "Model", "persona", "data-A", true)
        val first = newSavedEncouragement(EncouragementText(safe, "full-A"), review, Instant.EPOCH)
        val serialized = encodeEncouragement(first).toString()
        val second = newSavedEncouragement(EncouragementText(safe, "full-B"), ReviewedGeneration("model", "Model", "persona", "data-B"), Instant.EPOCH.plusSeconds(1))
        assertNotEquals(first.id, second.id)
        assertEquals("full-A", decodeEncouragement(first.id, JSONObject(serialized)).full)
        assertEquals("data-A", first.usedData)
        assertFalse(second.hasHealthData)
    }
    @Test fun wrongRecordIdentityUnknownSchemaOrCorruptionDoesNotOpenAnotherAnswer() {
        val record = SavedEncouragement(a, Instant.EPOCH, safe, "full-A", "data-A", true)
        assertThrows(Problem::class.java) { decodeEncouragement(b, encodeEncouragement(record)) }
        assertThrows(Problem::class.java) { decodeEncouragement(a, encodeEncouragement(record).put("schema", 9)) }
        val error = assertThrows(Problem::class.java) { decodeEncouragement(a, JSONObject().put("private", "69.875")) }
        assertNull(error.message)
        assertNull(error.cause)
    }
    @Test fun tapNavigationIsUniqueToTheRecordAndCarriesOnlyItsId() {
        val navA = answerNavigation(a, app)
        val navB = answerNavigation(b, app)
        assertNotEquals(navA, navB)
        assertEquals(a, answerIdFromNavigation("$app.OPEN_ANSWER", navA, app))
        assertEquals(b, answerIdFromNavigation("$app.OPEN_ANSWER", navB, app))
        assertFalse(navA.contains("?"))
        assertFalse(navA.contains("full"))
        assertFalse(navA.contains("weight"))
    }
    @Test fun malformedExternalNavigationCannotSelectAFileOrInjectData() {
        for (uri in listOf("$app://answer/../../secrets", "$app://answer/$a?full=private", "$app://answer/$a#private", "$app://answer:42/$a", "other://answer/$a", "$app://other/$a", "$app://answer/invalid"))
            assertNull(answerIdFromNavigation("$app.OPEN_ANSWER", uri, app))
        assertNull(answerIdFromNavigation("other", answerNavigation(a, app), app))
        assertThrows(IllegalArgumentException::class.java) { answerNavigation("../../private", app) }
    }
}
