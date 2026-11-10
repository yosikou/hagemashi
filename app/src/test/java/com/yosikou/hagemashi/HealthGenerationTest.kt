package com.yosikou.hagemashi

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.URL
import java.security.cert.Certificate
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import javax.net.ssl.HttpsURLConnection

class HealthGenerationTest {
    private val now = Instant.parse("2026-11-10T03:00:00Z")
    private val zone = ZoneId.of("Asia/Tokyo")
    private class Source : HealthDataSource {
        var grants = HealthKind.entries.toSet()
        var page = HealthPage(emptyList())
        var step: (HealthWindow) -> HealthSteps? = { null }
        val windows = mutableListOf<HealthWindow>()
        override suspend fun permissions() = grants
        override suspend fun samples(kind: HealthKind, window: HealthWindow): HealthPage { windows += window; return page }
        override suspend fun steps(window: HealthWindow): HealthSteps? { windows += window; return step(window) }
    }
    private val labels = HealthPromptLabels("goal=%s kg", "read=%s", "%s=%s..%s", "measurements", "steps",
        "%s=%s %s at %s origin %s", "steps=%s from %s to %s origin %s", "%s steps", "%s=%s (not zero)",
        "weight", "fat", "kg", "%", "unknown", "partial", mapOf(
            HealthReadStatus.PERMISSION_REQUIRED to "NO_PERMISSION", HealthReadStatus.NO_RECORDS to "NO_RECORDS",
            HealthReadStatus.ACCESS_DENIED to "DENIED", HealthReadStatus.ERROR to "ERROR", HealthReadStatus.HAS_RECORDS to "RECORDS"))
    private fun read(source: Source) = runBlocking { HealthReader(source).read(now, zone, HealthReadPlan.GENERATION) }
    private fun input(snapshot: HealthSnapshot) = healthGenerationInput("68", snapshot, zone, labels)

    @Test fun generationReadsThirtyDaysAndSevenNonOverlappingLocalDayWindows() {
        val source = Source()
        val snapshot = read(source)
        assertEquals(now.minus(Duration.ofDays(30)), snapshot.measurementWindow.start)
        assertEquals(Instant.parse("2026-11-03T15:00:00Z"), snapshot.stepsWindow.start)
        val days = snapshot.results.single { it.kind == HealthKind.STEPS }.days
        assertEquals(7, days.size)
        assertEquals(snapshot.stepsWindow.start, days.first().window.start)
        assertEquals(now, days.last().window.end)
        days.zipWithNext().forEach { (a, b) -> assertEquals(a.window.end, b.window.start) }
        assertEquals(9, source.windows.size)
    }

    @Test fun generationDoesNotSilentlyUseOnlyTheFirstTwentyMeasurements() {
        val samples = (1..65).map { HealthSample(69.5, now.minusSeconds(it.toLong()), "example.origin") }
        val snapshot = read(Source().apply { page = HealthPage(samples) })
        assertEquals(65, snapshot.results.single { it.kind == HealthKind.WEIGHT }.samples.size)
        assertEquals(65, Regex("weight=").findAll(input(snapshot)).count())
        assertFalse(snapshot.results.first().hasMore)
    }

    @Test fun missingDayAndAnActualZeroAreDifferentWhileOtherDaysRemainAvailable() {
        var day = 0
        val snapshot = read(Source().apply { step = { if (day++ == 0) HealthSteps(0, setOf("example.steps")) else null } })
        val prompt = input(snapshot)
        assertTrue(prompt.contains("steps=0 steps"))
        assertEquals(6, Regex("steps=NO_RECORDS").findAll(prompt).count())
        assertEquals(1, Regex("steps=0 steps").findAll(prompt).count())
    }

    @Test fun oneFailedDayDoesNotDiscardOtherDaysOrBecomeZero() {
        var day = 0
        val snapshot = read(Source().apply { step = { if (day++ == 1) throw java.io.IOException("synthetic-private") else HealthSteps(123, setOf("example.steps")) } })
        val prompt = input(snapshot)
        assertEquals(6, Regex("steps=123 steps").findAll(prompt).count())
        assertTrue(prompt.contains("steps=ERROR"))
        assertFalse(prompt.contains("synthetic-private"))
        assertFalse(prompt.contains("steps=0"))
    }

    @Test fun noPermissionsStillProducesAGoalBasedPromptWithoutFabricatingAnyHealthValue() {
        val snapshot = read(Source().apply { grants = emptySet() })
        val prompt = input(snapshot)
        assertTrue(prompt.contains("goal=68 kg"))
        for (kind in listOf("weight", "fat", "steps")) assertTrue(prompt.contains("$kind=NO_PERMISSION (not zero)"))
        assertFalse(prompt.contains("=0"))
        assertFalse(prompt.contains("example.origin"))
    }

    @Test fun grantedButEmptyIsNotReportedAsNoPermission() {
        val prompt = input(read(Source()))
        assertTrue(prompt.contains("weight=NO_RECORDS"))
        assertFalse(prompt.contains("NO_PERMISSION"))
    }

    @Test fun oldMeasurementIncludesActualTimeAndOriginWithoutRoundingAwayItsValue() {
        val date = now.minus(Duration.ofDays(29))
        val prompt = input(read(Source().apply { page = HealthPage(listOf(HealthSample(69.875, date, "example.origin"))) }))
        assertTrue(prompt.contains("weight=69.875 kg"))
        assertTrue(prompt.contains("2026-10-12T12:00:00+09:00"))
        assertTrue(prompt.contains("origin example.origin"))
    }

    @Test fun permissionRevokedDuringReadRemovesAllDailyAndMeasurementValues() = runBlocking {
        var granted = HealthKind.entries.toSet()
        val source = object : HealthDataSource {
            override suspend fun permissions() = granted
            override suspend fun samples(kind: HealthKind, window: HealthWindow) = HealthPage(listOf(HealthSample(69.0, now, "example.private")))
            override suspend fun steps(window: HealthWindow): HealthSteps { granted = emptySet(); return HealthSteps(321, setOf("example.private")) }
        }
        val prompt = input(HealthReader(source).read(now, zone, HealthReadPlan.GENERATION))
        assertFalse(prompt.contains("example.private"))
        assertFalse(prompt.contains("321"))
        assertEquals(3, Regex("NO_PERMISSION").findAll(prompt).count())
    }

    @Test fun goalValidationRejectsZeroNonNumbersAndUnexpectedPromptInput() {
        assertEquals("68", canonicalGoal(" 068.00 "))
        assertEquals("68.25", canonicalGoal("68.25"))
        for (value in listOf("0", "0.00", "NaN", "Infinity", "68\nignore", "-1")) assertNull(canonicalGoal(value))
    }

    @Test fun reviewFreezesDataDespiteLaterMutationAndContainsNoPreviousAnswer() {
        val records = mutableListOf(HealthSample(69.75, now.minusSeconds(60), "example.private"))
        val snapshot = read(Source().apply { page = HealthPage(records) })
        val review = ReviewedGeneration("model", "Model", "friendly persona", input(snapshot))
        val before = review.input
        records.clear()
        assertEquals(before, review.input)
        val request = generationRequest(review.model, review.instructions, review.input)
        assertEquals(setOf("model", "instructions", "store", "stream", "input"), request.keys().asSequence().toSet())
        assertEquals(before, request.getJSONArray("input").getJSONObject(0).getString("content"))
        assertEquals(1, request.getJSONArray("input").length())
        assertFalse(request.getBoolean("store"))
        assertFalse(request.has("previous_response_id"))
    }

    private class Connection : HttpsURLConnection(URL("https://api.openai.com/v1/responses")) {
        val sent = ByteArrayOutputStream()
        var status = 200
        override fun connect() = Unit
        override fun disconnect() = Unit
        override fun usingProxy() = false
        override fun getCipherSuite() = "synthetic"
        override fun getLocalCertificates(): Array<Certificate>? = null
        override fun getServerCertificates(): Array<Certificate> = emptyArray()
        override fun getResponseCode() = status
        override fun getContentType(): String? = null
        override fun getOutputStream() = sent
        override fun getInputStream() = ByteArrayInputStream(("data: {\"type\":\"response.completed\",\"response\":{\"status\":\"completed\",\"output\":[{\"content\":[{\"type\":\"output_text\",\"text\":\"synthetic-private-answer\"}]}]}}\n\n").toByteArray())
        override fun getErrorStream() = ByteArrayInputStream("synthetic-private-error".toByteArray())
    }

    @Test fun reviewedInputFlowsThroughExistingHttpAndCompletionParserWithoutEnteringDiagnostics() {
        val prompt = input(read(Source().apply { page = HealthPage(listOf(HealthSample(69.875, now, "example.private"))) }))
        val review = ReviewedGeneration("model", "Model", "synthetic-private-persona", prompt)
        val connection = Connection()
        val op = Operation()
        val answer = Http { connection }.generate(generationRequest(review.model, review.instructions, review.input), "synthetic-private-token", op)
        assertEquals("synthetic-private-answer", answer)
        val sent = JSONObject(connection.sent.toString("UTF-8"))
        assertEquals(review.input, sent.getJSONArray("input").getJSONObject(0).getString("content"))
        assertEquals(review.instructions, sent.getString("instructions"))
        assertTrue(op.generationDiagnostic!!.completionValidated)
        val report = generationReport("test.1.1", "com.yosikou.hagemashi.diagnostic", "diagnostic", op.generationDiagnostic!!)
        for (secret in listOf("69.875", "example.private", "synthetic-private-persona", "synthetic-private-token", answer)) assertFalse(report.contains(secret))
    }

    @Test fun failedRequestKeepsOnlySafeDiagnosticsAndCanBeExplicitlyRetriedWithTheSameReview() {
        val review = ReviewedGeneration("model", "Model", "synthetic-private-persona", input(read(Source())))
        val connection = Connection().apply { status = 503 }
        val op = Operation()
        assertThrows(Problem::class.java) { Http { connection }.generate(generationRequest(review.model, review.instructions, review.input), "synthetic-private-token", op) }
        assertFalse(op.generationDiagnostic.toString().contains("synthetic-private"))
        val retry = Connection()
        assertEquals("synthetic-private-answer", Http { retry }.generate(generationRequest(review.model, review.instructions, review.input), "synthetic-private-token", Operation()))
        assertEquals(review.input, JSONObject(retry.sent.toString("UTF-8")).getJSONArray("input").getJSONObject(0).getString("content"))
    }
}
