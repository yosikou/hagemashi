package com.yosikou.hagemashi

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.URL
import java.security.cert.Certificate
import java.util.concurrent.CancellationException
import javax.net.ssl.HttpsURLConnection

class GenerationTest {
    private val secret = "synthetic-private-token-prompt-answer-response"
    private fun completed(text: String = secret, status: String = "completed") = JSONObject().put("type", "response.completed")
        .put("response", JSONObject().put("status", status).put("output", JSONArray().put(JSONObject().put("content",
            JSONArray().put(JSONObject().put("type", "output_text").put("text", text))))))
    private fun event(type: String) = JSONObject().put("type", type)
    private fun sse(vararg items: JSONObject) = items.joinToString("") { "data: $it\n\n" }
    private class Connection(val input: InputStream, private val media: String? = "text/event-stream", private val status: Int = 200) : HttpsURLConnection(URL("https://api.openai.com/v1/responses")) {
        val output = ByteArrayOutputStream()
        override fun connect() = Unit
        override fun disconnect() = Unit
        override fun usingProxy() = false
        override fun getCipherSuite() = "synthetic"
        override fun getLocalCertificates(): Array<Certificate>? = null
        override fun getServerCertificates(): Array<Certificate> = emptyArray()
        override fun getResponseCode() = status
        override fun getContentType() = media
        override fun getOutputStream() = output
        override fun getInputStream() = input
        override fun getErrorStream() = input
    }
    private fun connection(payload: String, media: String? = "text/event-stream", status: Int = 200) =
        Connection(ByteArrayInputStream(payload.toByteArray()), media, status)
    private fun generate(connection: Connection, op: Operation): String = Http { connection }.generate(generationRequest("gpt-6.1-sol", secret, secret), secret, op)
    private fun rejected(connection: Connection): GenerationDiagnostic {
        val op = Operation()
        val error = assertThrows(Problem::class.java) { generate(connection, op) }
        assertNull(error.message)
        assertNull(error.cause)
        val diagnostic = op.generationDiagnostic!!
        assertFalse(diagnostic.answerInUi)
        assertFalse(generationReport("abc.1.1", "com.yosikou.hagemashi.diagnostic", "diagnostic", diagnostic).contains(secret))
        return diagnostic
    }

    @Test fun requestUsesTheOfficialSiwcJsonContractWithoutUnsupportedFields() {
        val connection = connection(sse(completed()))
        val op = Operation()
        assertEquals(secret, generate(connection, op))
        assertEquals("POST", connection.requestMethod)
        assertEquals("application/json", connection.getRequestProperty("Content-Type"))
        assertEquals("text/event-stream", connection.getRequestProperty("Accept"))
        assertEquals("Bearer $secret", connection.getRequestProperty("Authorization"))
        val request = JSONObject(connection.output.toString("UTF-8"))
        assertEquals(setOf("model", "instructions", "store", "stream", "input"), request.keys().asSequence().toSet())
        assertEquals("gpt-6.1-sol", request.getString("model"))
        assertEquals(secret, request.getString("instructions"))
        assertFalse(request.getBoolean("store"))
        assertTrue(request.getBoolean("stream"))
        val input = request.getJSONArray("input")
        assertEquals(1, input.length())
        assertEquals("user", input.getJSONObject(0).getString("role"))
        assertEquals(secret, input.getJSONObject(0).getString("content"))
        assertFalse(op.generationDiagnostic.toString().contains(secret))
    }

    @Test fun completionIsValidatedBeforeAnAnswerCanBeAssignedToTheUi() {
        val op = Operation()
        assertEquals(secret, generate(connection(sse(event("response.created"), completed())), op))
        val d = op.generationDiagnostic!!
        assertEquals(200, d.httpStatus)
        assertEquals(StreamMedia.EVENT_STREAM, d.media)
        assertEquals(BodyPresence.PRESENT, d.body)
        assertEquals(KnownStreamEvent.COMPLETED, d.lastKnown)
        assertEquals(StreamTerminal.COMPLETED, d.terminal)
        assertEquals(StreamEnd.COMPLETED, d.end)
        assertTrue(d.completionValidated)
        assertTrue(d.answerPresent)
        assertFalse(d.answerInUi) // The network parser cannot claim that the UI displayed anything.
    }

    @Test fun mediaTypeIsExactCaseInsensitiveAndAllowsParameters() {
        for (type in listOf("text/event-stream", "Text/Event-Stream; charset=utf-8", " text/event-stream ; charset=UTF-8 ")) {
            val op = Operation()
            assertEquals(secret, generate(connection(sse(completed()), type), op))
        }
        for ((type, expected) in listOf("application/json" to StreamMedia.JSON,
            "text/html" to StreamMedia.HTML, "text/event-streaming" to StreamMedia.OTHER, secret to StreamMedia.OTHER)) {
            val d = rejected(connection(sse(completed()), type))
            assertEquals(expected, d.media)
            assertEquals(BodyPresence.PRESENT, d.body)
            assertEquals(GenerationReason.CONTENT_TYPE_REJECTED, d.reason)
            assertEquals(StreamEnd.MEDIA_REJECTED, d.end)
        }
    }

    @Test fun missingContentTypeUsesTheExplicitStreamRequestButStillRequiresValidCompletion() {
        for (media in listOf(null, "", "   ")) {
            val op = Operation()
            assertEquals(secret, generate(connection(sse(event("response.created"), completed()), media), op))
            val d = op.generationDiagnostic!!
            assertEquals(StreamMedia.MISSING, d.media)
            assertEquals(200, d.httpStatus)
            assertEquals(KnownStreamEvent.COMPLETED, d.lastKnown)
            assertEquals(StreamTerminal.COMPLETED, d.terminal)
            assertEquals(StreamEnd.COMPLETED, d.end)
            assertTrue(d.completionValidated)
            assertTrue(d.answerPresent)
            assertFalse(d.answerInUi)
        }
    }

    @Test fun missingContentTypeDoesNotMakeJsonHtmlOrPartialTextSuccessful() {
        val partial = sse(event("response.output_text.delta").put("delta", secret)) + "data: [DONE]\n\n"
        for (payload in listOf(completed().toString(), "<html>$secret</html>", partial, "")) {
            val d = rejected(connection(payload, null))
            assertEquals(StreamMedia.MISSING, d.media)
            assertEquals(200, d.httpStatus)
            assertEquals(StreamTerminal.NONE, d.terminal)
            assertEquals(StreamEnd.EOF_WITHOUT_COMPLETION, d.end)
            assertFalse(d.completionValidated)
        }
    }

    @Test fun headerlessStreamedFailureRemainsAFailureAfterHttp200() {
        val op = Operation()
        val failure = event("response.failed").put("response", JSONObject().put("error", JSONObject()
            .put("code", "subscription_sharing_usage_limit_exceeded").put("message", secret)))
        val error = assertThrows(Problem::class.java) { generate(connection(sse(failure), null), op) }
        assertEquals(Failure.LIMIT, error.kind)
        assertEquals(StreamMedia.MISSING, op.generationDiagnostic?.media)
        assertEquals(StreamTerminal.FAILED, op.generationDiagnostic?.terminal)
        assertFalse(op.generationDiagnostic!!.completionValidated)
    }

    @Test fun rejectedContentTypeChecksBodyPresenceWithoutReadingTheBody() {
        var reads = 0
        val input = object : InputStream() {
            override fun read(): Int { reads++; if (reads > 1) throw AssertionError("must not collect rejected response body"); return 65 }
        }
        val d = rejected(Connection(input, "application/json"))
        assertEquals(1, reads)
        assertEquals(GenerationReason.CONTENT_TYPE_REJECTED, d.reason)
    }

    @Test fun emptyBodyIsDifferentFromAStreamEndingWithoutCompletion() {
        val empty = rejected(connection(""))
        assertEquals(BodyPresence.ABSENT, empty.body)
        assertEquals(GenerationReason.EMPTY_BODY, empty.reason)
        val partial = rejected(connection(sse(event("response.output_text.delta").put("delta", secret)) + "data: [DONE]\n\n"))
        assertEquals(BodyPresence.PRESENT, partial.body)
        assertEquals(KnownStreamEvent.TEXT_DELTA, partial.lastKnown)
        assertEquals(StreamTerminal.NONE, partial.terminal)
        assertEquals(StreamEnd.EOF_WITHOUT_COMPLETION, partial.end)
        assertEquals(GenerationReason.NO_COMPLETION, partial.reason)
        assertTrue(partial.answerPresent)
        assertFalse(partial.completionValidated)
    }

    @Test fun eofInAnEventDoesNotClaimToHaveReceivedACompletion() {
        val d = rejected(connection(sse(event("response.created")) + "data: ${completed()}"))
        assertEquals(KnownStreamEvent.CREATED, d.lastKnown)
        assertEquals(StreamTerminal.NONE, d.terminal)
        assertEquals(StreamEnd.EOF_IN_EVENT, d.end)
        assertEquals(GenerationReason.UNTERMINATED_EVENT, d.reason)
    }

    @Test fun incompleteIsDistinctFromFailedAndFromInterruptedTransport() {
        val d = rejected(connection(sse(event("response.incomplete").put("response", JSONObject().put("private", secret)))))
        assertEquals(KnownStreamEvent.INCOMPLETE, d.lastKnown)
        assertEquals(StreamTerminal.INCOMPLETE, d.terminal)
        assertEquals(StreamEnd.INCOMPLETE, d.end)
        assertEquals(GenerationReason.INCOMPLETE, d.reason)
    }

    @Test fun streamedUsageLimitKeepsHttp200ButNeverReportsSuccess() {
        val op = Operation()
        val failure = event("response.failed").put("response", JSONObject().put("error", JSONObject()
            .put("code", "subscription_sharing_usage_limit_exceeded").put("message", secret)))
        val error = assertThrows(Problem::class.java) { generate(connection(sse(failure)), op) }
        assertEquals(Failure.LIMIT, error.kind)
        assertEquals(200, error.status)
        assertEquals(StreamTerminal.FAILED, op.generationDiagnostic?.terminal)
        assertEquals(StreamEnd.FAILED, op.generationDiagnostic?.end)
        assertFalse(op.generationDiagnostic!!.completionValidated)
        assertFalse(op.generationDiagnostic.toString().contains(secret))
    }

    @Test fun explicitErrorDoesNotExposeItsCodeOrMessage() {
        val d = rejected(connection(sse(event("error").put("code", secret).put("message", secret))))
        assertEquals(StreamTerminal.ERROR, d.terminal)
        assertEquals(GenerationReason.SERVER_ERROR, d.reason)
    }

    private fun brokenAfter(prefix: String, error: IOException): InputStream = object : InputStream() {
        val bytes = prefix.toByteArray()
        var offset = 0
        override fun read(): Int = if (offset < bytes.size) bytes[offset++].toInt() and 255 else throw error
    }

    @Test fun ioInterruptionKeepsTheLastKnownEventAndReceivedHttpStatus() {
        val d = rejected(Connection(brokenAfter(sse(event("response.created")), IOException(secret))))
        assertEquals(200, d.httpStatus)
        assertEquals(KnownStreamEvent.CREATED, d.lastKnown)
        assertEquals(StreamEnd.IO_INTERRUPTED, d.end)
        assertEquals(GenerationReason.TRANSPORT_ERROR, d.reason)
    }

    @Test fun socketTimeoutIsDifferentFromEofOrIncomplete() {
        val d = rejected(Connection(brokenAfter(sse(event("response.in_progress")), SocketTimeoutException(secret))))
        assertEquals(KnownStreamEvent.IN_PROGRESS, d.lastKnown)
        assertEquals(StreamEnd.TIMED_OUT, d.end)
        assertEquals(GenerationReason.TIMEOUT, d.reason)
    }

    @Test fun malformedJsonAndEventDataHaveDifferentFixedReasons() {
        for (payload in listOf("not-json $secret", "{} trailing $secret")) {
            assertEquals(GenerationReason.INVALID_JSON, rejected(connection("data: $payload\n\n")).reason)
        }
        assertEquals(GenerationReason.INVALID_EVENT_DATA, rejected(connection(sse(JSONObject().put("private", secret)))).reason)
        assertEquals(GenerationReason.INVALID_EVENT_DATA, rejected(connection(sse(event("response.output_text.delta")))).reason)
    }

    @Test fun unknownEventNamesAreNotReflectedAndDoNotReplaceTheLastKnownEvent() {
        val d = rejected(connection(sse(event("response.created"), event(secret))))
        assertEquals(KnownStreamEvent.CREATED, d.lastKnown)
        assertTrue(d.unknownEventSeen)
        assertFalse(d.toString().contains(secret))
    }

    @Test fun emptyAndRefusedAnswersAreNotSuccessfulCompletions() {
        val empty = rejected(connection(sse(completed(" "))))
        assertEquals(GenerationReason.EMPTY_ANSWER, empty.reason)
        assertEquals(StreamTerminal.COMPLETED, empty.terminal)
        assertFalse(empty.completionValidated)
        val refused = completed("")
        refused.getJSONObject("response").getJSONArray("output").getJSONObject(0).put("content", JSONArray()
            .put(JSONObject().put("type", "refusal").put("refusal", secret)))
        assertEquals(GenerationReason.REFUSAL, rejected(connection(sse(refused))).reason)
    }

    @Test fun invalidCompletedStatusDoesNotPassTheCompletionCheck() {
        val d = rejected(connection(sse(completed(status = "in_progress"))))
        assertEquals(StreamTerminal.COMPLETED, d.terminal)
        assertEquals(GenerationReason.INVALID_COMPLETION, d.reason)
        assertFalse(d.completionValidated)
    }

    @Test fun boundedStreamReportsLimitsWithoutRetainingItsPayload() {
        val d = rejected(connection("data: " + "x".repeat(65537)))
        assertEquals(GenerationReason.INPUT_LIMIT, d.reason)
        assertEquals(StreamEnd.LIMIT_EXCEEDED, d.end)
    }

    @Test fun cancelledGenerationDoesNotOpenARequestOrClaimCompletion() {
        val op = Operation().apply { cancel() }
        assertThrows(CancellationException::class.java) { Http { throw AssertionError("must not connect") }.generate(JSONObject(), secret, op) }
        assertEquals(StreamEnd.CANCELLED, op.generationDiagnostic?.end)
        assertFalse(op.generationDiagnostic!!.completionValidated)
    }

    @Test fun copiedReportIsAnAllowlistEvenWhenBuildTextLooksLikeASecret() {
        val state = GenerationDiagnostic(httpStatus = 9999, lastKnown = KnownStreamEvent.COMPLETED, answerPresent = true,
            completionValidated = true, answerInUi = true)
        val report = generationReport("$secret?query=x", "bad\n$secret", "diagnostic", state)
        assertTrue(report.contains("build=UNKNOWN"))
        assertTrue(report.contains("applicationId=UNKNOWN"))
        assertTrue(report.contains("http=NOT_RECEIVED"))
        assertTrue(report.contains("answer_in_ui=true"))
        assertFalse(report.contains(secret))
        assertFalse(report.contains("https://"))
    }
}
