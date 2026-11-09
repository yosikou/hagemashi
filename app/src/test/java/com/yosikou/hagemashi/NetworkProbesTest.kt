package com.yosikou.hagemashi

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URL
import java.net.UnknownHostException
import java.security.cert.Certificate
import javax.net.ssl.HttpsURLConnection

class NetworkProbesTest {
    private class Connection(private val status: Int, private val failure: Exception? = null) : HttpsURLConnection(URL("https://auth.openai.com/")) {
        val output = ByteArrayOutputStream()
        override fun connect() = Unit
        override fun disconnect() = Unit
        override fun usingProxy() = false
        override fun getCipherSuite() = "synthetic"
        override fun getLocalCertificates(): Array<Certificate>? = null
        override fun getServerCertificates(): Array<Certificate> = emptyArray()
        override fun getResponseCode(): Int { failure?.let { throw it }; return status }
        override fun getOutputStream() = output
        override fun getInputStream() = throw AssertionError("Probe must not read response body")
        override fun getErrorStream() = throw AssertionError("Probe must not read error body")
    }

    @Test fun emptyPostUsesOAuthTransportWithNoCredentialsAndAccepts400() {
        val connection = Connection(400)
        var endpoint: String? = null
        val probes = NetworkProbes(Http { url -> endpoint = url.toString(); connection })
        try {
            val result = probes.execute(ProbeKind.EMPTY_POST, Operation()) { true }
            assertEquals(TOKEN_ENDPOINT, endpoint)
            assertEquals("POST", connection.requestMethod)
            assertEquals(0, connection.output.size())
            assertNull(connection.getRequestProperty("Authorization"))
            assertEquals(15000, connection.connectTimeout)
            assertEquals(45000, connection.readTimeout)
            assertFalse(connection.instanceFollowRedirects)
            assertEquals(400, result.httpStatus)
            assertNull(result.diagnostic)
            assertEquals(Progress(Stage.PROBE_POST, Phase.SUCCEEDED, Stage.PROBE_POST), result.progress)
        } finally { probes.close() }
    }

    @Test fun getDoesNotFollowRedirectOrParse403Body() {
        for (status in listOf(302, 403)) {
            val connection = Connection(status)
            var endpoint: String? = null
            val probes = NetworkProbes(Http { url -> endpoint = url.toString(); connection })
            try {
                val result = probes.execute(ProbeKind.HTTPS_GET, Operation()) { false }
                assertEquals("$ISSUER/", endpoint)
                assertEquals("GET", connection.requestMethod)
                assertFalse(connection.doOutput)
                assertEquals(status, result.httpStatus)
                assertEquals(AppVisibility.HIDDEN, result.visibility)
                assertNull(result.diagnostic)
            } finally { probes.close() }
        }
    }

    @Test fun httpFailureAndCopyKeepSafeCauseChainWithoutExceptionMessage() {
        val secret = "synthetic-private-token-url-body"
        val probes = NetworkProbes(Http { Connection(200, IOException(secret, UnknownHostException(secret))) })
        try {
            val result = probes.execute(ProbeKind.EMPTY_POST, Operation()) { true }
            assertEquals(Phase.FAILED, result.progress.phase)
            assertEquals(FixedError.DNS_LOOKUP_FAILED, result.diagnostic?.errorClass)
            assertEquals(listOf(SafeException.IO, SafeException.UNKNOWN_HOST), result.diagnostic?.exceptionClasses)
            assertNull(result.httpStatus)
            val report = probeReport("abc.12.1", "com.yosikou.hagemashi.diagnostic", "diagnostic", true, listOf(result))
            assertTrue(report.contains("stage=PROBE_POST"))
            assertTrue(report.contains("java.io.IOException -> java.net.UnknownHostException"))
            assertTrue(report.contains("http=NOT_RECEIVED"))
            assertFalse(report.contains(secret))
            assertFalse(report.contains("https://"))
        } finally { probes.close() }
    }

    @Test fun dnsDoesNotOpenHttpAndReportsResolutionSeparately() {
        var host: String? = null
        val probes = NetworkProbes(Http { throw AssertionError("DNS must not open HTTP") }, DnsComparison(lookup = { host = it }))
        try {
            val result = probes.execute(ProbeKind.DNS, Operation()) { true }
            assertEquals(TargetHost.AUTH.hostname, host)
            assertNull(result.httpStatus)
            assertNull(result.diagnostic)
            assertTrue(probeReport("abc", "id", "diagnostic", true, listOf(result)).contains("result=DNS_RESOLVED"))
        } finally { probes.close() }
    }

    @Test fun dnsFailureHasSafeCauseChainAndNoFabricatedStatus() {
        val probes = NetworkProbes(dns = DnsComparison(lookup = { throw UnknownHostException("synthetic-private") }))
        try {
            val result = probes.execute(ProbeKind.DNS, Operation()) { true }
            assertEquals(Phase.FAILED, result.progress.phase)
            assertEquals(FixedError.DNS_LOOKUP_FAILED, result.diagnostic?.errorClass)
            assertEquals(listOf(SafeException.EXECUTION, SafeException.UNKNOWN_HOST), result.diagnostic?.exceptionClasses)
            assertNull(result.httpStatus)
        } finally { probes.close() }
    }

    @Test fun copyRejectsArbitraryBuildTextAndOutOfRangeHttpStatus() {
        val result = ProbeResult(ProbeKind.HTTPS_GET, Progress(Stage.PROBE_GET, Phase.FAILED), AppVisibility.VISIBLE, httpStatus = 9999)
        val report = probeReport("synthetic-private?code=x", "id\nprivate", "debug", null, listOf(result))
        assertTrue(report.contains("build=UNKNOWN"))
        assertTrue(report.contains("applicationId=UNKNOWN"))
        assertTrue(report.contains("http=NOT_RECEIVED"))
        assertFalse(report.contains("private"))
    }
}
