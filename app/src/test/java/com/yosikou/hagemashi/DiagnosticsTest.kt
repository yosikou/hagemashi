package com.yosikou.hagemashi

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.security.cert.Certificate
import java.util.concurrent.CancellationException
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLHandshakeException

class DiagnosticsTest {
    private val privateText = "synthetic-private-code-token-url-body"

    @Test fun networkFailuresAreTypedWithoutExceptionMessages() {
        val cases = listOf(
            UnknownHostException(privateText) to NetworkFailure.DNS,
            SSLHandshakeException(privateText) to NetworkFailure.TLS,
            SocketTimeoutException(privateText) to NetworkFailure.TIMEOUT,
            ConnectException(privateText) to NetworkFailure.CONNECTION,
            IOException(privateText) to NetworkFailure.IO,
            InterruptedIOException(privateText) to NetworkFailure.IO,
            SecurityException(privateText) to NetworkFailure.PERMISSION,
            IOException(privateText, UnknownHostException(privateText)) to NetworkFailure.DNS,
            SSLHandshakeException(privateText).also { it.initCause(SocketTimeoutException(privateText)) } to NetworkFailure.TIMEOUT
        )
        for ((error, category) in cases) {
            val diagnostic = diagnosticOf(error)
            assertEquals(category, diagnostic.network)
            assertNull(diagnostic.httpStatus)
            assertFalse(diagnostic.toString().contains(privateText))
            val safe = safeProblem(error)
            assertNull(safe.message)
            assertNull(safe.cause)
        }
    }

    private fun pipeline(operation: Operation, failAt: Stage? = null, failure: Exception = IOException(privateText)) {
        fun before(stage: Stage) { if (stage == failAt) throw failure }
        completeSignIn(operation,
            exchange = { before(Stage.CODE_EXCHANGE); JSONObject().put("private", privateText) },
            discovery = { before(Stage.DISCOVERY); JSONObject() },
            keys = { before(Stage.SIGNING_KEYS); JSONObject() },
            verify = { _, _ -> before(Stage.ID_TOKEN); Identity("synthetic-subject", "", "") },
            record = { _, _ -> before(Stage.TOKEN_FIELDS); JSONObject().put("private", privateText) },
            persist = { _, _ -> before(Stage.SAVE_CREDENTIALS) })
    }

    @Test fun successfulPipelineReportsEveryCompletedStageWithoutPayloads() {
        val events = mutableListOf<Progress>()
        val operation = Operation(onProgress = events::add)
        pipeline(operation)
        val expected = listOf(Stage.CODE_EXCHANGE, Stage.DISCOVERY, Stage.SIGNING_KEYS, Stage.ID_TOKEN, Stage.TOKEN_FIELDS, Stage.SAVE_CREDENTIALS)
        assertEquals(expected, events.filter { it.phase == Phase.SUCCEEDED }.map { it.stage })
        assertEquals(Progress(Stage.SAVE_CREDENTIALS, Phase.SUCCEEDED, Stage.SAVE_CREDENTIALS), operation.progress)
        assertFalse(events.toString().contains(privateText))
    }

    @Test fun eachPostCallbackFailureKeepsTheExactStageAndStopsLaterSteps() {
        val stages = listOf(Stage.CODE_EXCHANGE, Stage.DISCOVERY, Stage.SIGNING_KEYS, Stage.ID_TOKEN, Stage.TOKEN_FIELDS, Stage.SAVE_CREDENTIALS)
        for ((index, stage) in stages.withIndex()) {
            val events = mutableListOf<Progress>()
            val operation = Operation(onProgress = events::add)
            operation.step(Stage.SAVE_REGISTRATION) { }
            val failure = when (stage) {
                Stage.ID_TOKEN -> Problem(Failure.IDENTITY)
                Stage.SAVE_CREDENTIALS -> Problem(Failure.STORAGE)
                else -> UnknownHostException(privateText)
            }
            val error = assertThrows(Problem::class.java) { pipeline(operation, stage, failure) }
            assertEquals(stage, operation.progress.stage)
            assertEquals(Phase.FAILED, operation.progress.phase)
            assertEquals(if (index == 0) Stage.SAVE_REGISTRATION else stages[index - 1], operation.progress.completed)
            assertEquals(stages.take(index + 1), events.filter { it.phase == Phase.RUNNING && it.stage != Stage.SAVE_REGISTRATION }.map { it.stage })
            assertEquals(failure.kindOrNetwork(), diagnosticOf(error).failure)
            assertFalse(operation.lastDiagnostic.toString().contains(privateText))
            assertNull(error.message)
        }
    }

    private fun Exception.kindOrNetwork() = if (this is Problem) kind else Failure.NETWORK

    @Test fun cancellationBeforeKeysNeverPersistsCredentials() {
        val operation = Operation()
        var saved = false
        assertThrows(CancellationException::class.java) {
            completeSignIn(operation,
                exchange = { operation.cancel(); JSONObject() }, discovery = { fail("must not fetch discovery"); JSONObject() },
                keys = { JSONObject() }, verify = { _, _ -> Identity("synthetic", "", "") },
                record = { _, _ -> JSONObject() }, persist = { _, _ -> saved = true })
        }
        assertFalse(saved)
        assertEquals(Phase.CANCELLED, operation.progress.phase)
    }

    @Test fun completedStorageIsNotReportedIfEncryptedPersistenceFails() {
        val operation = Operation()
        assertThrows(Problem::class.java) { pipeline(operation, Stage.SAVE_CREDENTIALS, Problem(Failure.STORAGE)) }
        assertEquals(Stage.TOKEN_FIELDS, operation.progress.completed)
        assertEquals(Stage.SAVE_CREDENTIALS, operation.progress.stage)
        assertEquals(Failure.STORAGE, operation.lastDiagnostic?.failure)
        assertNull(operation.lastDiagnostic?.network)
    }

    private class FakeConnection(
        private val status: Int = 200,
        private val body: String = "{}",
        private val failure: Exception? = null,
        private val readFailure: Exception? = null
    ) : HttpsURLConnection(URL("https://auth.openai.com/test")) {
        override fun connect() = Unit
        override fun disconnect() = Unit
        override fun usingProxy() = false
        override fun getCipherSuite() = "synthetic"
        override fun getLocalCertificates(): Array<Certificate>? = null
        override fun getServerCertificates(): Array<Certificate> = emptyArray()
        override fun getResponseCode(): Int { failure?.let { throw it }; return status }
        override fun getOutputStream() = ByteArrayOutputStream()
        override fun getInputStream(): ByteArrayInputStream {
            readFailure?.let { throw it }
            return ByteArrayInputStream(body.toByteArray())
        }
        override fun getErrorStream() = ByteArrayInputStream(body.toByteArray())
    }

    private fun httpFailure(connection: FakeConnection): Problem {
        val operation = Operation()
        return assertThrows(Problem::class.java) {
            operation.step(Stage.CODE_EXCHANGE) {
                Http { connection }.json("https://auth.openai.com/test", operation, mapOf("code" to privateText))
            }
        }
    }

    @Test fun httpStatusIsPreservedButResponseCodeAndBodyNeverEnterDiagnostic() {
        val error = httpFailure(FakeConnection(400, JSONObject().put("error", "invalid_grant").put("message", privateText).toString()))
        assertEquals(Failure.EXPIRED, error.kind)
        val diagnostic = diagnosticOf(error)
        assertEquals(400, diagnostic.httpStatus)
        assertEquals(NetworkFailure.HTTP, diagnostic.network)
        assertFalse(diagnostic.toString().contains("invalid_grant"))
        assertFalse(diagnostic.toString().contains(privateText))
        assertNull(error.message)
        assertNull(error.cause)
    }

    @Test fun unexpectedSuccessfulHttpBodyKeepsStatusWithoutExposingBody() {
        val error = httpFailure(FakeConnection(200, "invalid $privateText"))
        assertEquals(Failure.PROTOCOL, error.kind)
        assertEquals(200, diagnosticOf(error).httpStatus)
        assertFalse(diagnosticOf(error).toString().contains(privateText))
    }

    @Test fun transportFailureBeforeResponseHasNoFabricatedHttpStatus() {
        val error = httpFailure(FakeConnection(failure = UnknownHostException(privateText)))
        assertEquals(NetworkFailure.DNS, diagnosticOf(error).network)
        assertNull(diagnosticOf(error).httpStatus)
    }

    @Test fun timeoutAfterResponseKeepsTheReceivedStatus() {
        val error = httpFailure(FakeConnection(200, readFailure = SocketTimeoutException(privateText)))
        assertEquals(NetworkFailure.TIMEOUT, diagnosticOf(error).network)
        assertEquals(200, diagnosticOf(error).httpStatus)
        assertNull(error.message)
    }

    @Test fun invalidHttpStatusCannotReachDisplay() {
        assertNull(diagnosticOf(Problem(Failure.SERVER, 9999)).httpStatus)
    }

    @Test fun savedStateDistinguishesNoSessionFromUnreadableState() {
        assertEquals(SavedConnection.NONE, savedConnection(false))
        assertEquals(SavedConnection.PRESENT, savedConnection(true))
        assertEquals(SavedConnection.UNKNOWN, savedConnection(null))
    }
}
