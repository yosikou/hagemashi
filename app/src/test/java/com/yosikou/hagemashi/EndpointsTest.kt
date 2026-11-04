package com.yosikou.hagemashi

import org.junit.Assert.*
import org.junit.Test
import java.net.UnknownHostException

class EndpointsTest {
    @Test fun tokenEndpointMatchesTheOfficialContractExactly() {
        val uri = checkedEndpoint(TOKEN_ENDPOINT)
        assertEquals("https", uri.scheme)
        assertEquals("auth.openai.com", uri.host)
        assertEquals("/api/accounts/oauth/token", uri.rawPath)
        assertNull(uri.rawQuery)
        assertNull(uri.rawUserInfo)
        assertFalse(TOKEN_ENDPOINT.any { it.isWhitespace() || it.isISOControl() })
        assertEquals(TargetHost.AUTH, trustedHost(uri))
    }

    @Test fun malformedHostsAreRejectedBeforeAnyNameResolution() {
        val invalid = listOf(
            "https://auth.openai.com /test", "https:// auth.openai.com/test", "https://auth.openai.com\t/test",
            " https://auth.openai.com/test", "https://auth.openai.com/test\r\n",
            "https://auth.openai.com\u200b/test", "https://auth.openai.com\u00a0/test",
            "https://auth.openai.com%20/test", "https://auth.openai.com.evil.invalid/test",
            "https://private-secret@auth.openai.com/test", "http://auth.openai.com/test", "https://127.0.0.1/test"
        )
        for (value in invalid) {
            var opened = false
            val error = assertThrows(Problem::class.java) {
                Http { opened = true; throw AssertionError("must not connect") }.json(value, Operation())
            }
            assertFalse(opened)
            assertEquals(FixedError.INVALID_ENDPOINT, diagnosticOf(error).errorClass)
            assertNull(diagnosticOf(error).host)
            assertNull(error.message)
            assertNull(error.cause)
        }
    }

    @Test fun dnsDiagnosticsContainOnlyAnAllowlistedHostAndFixedClass() {
        val secret = "synthetic-private-query-code-token-message"
        val error = assertThrows(Problem::class.java) {
            Http { throw UnknownHostException(secret) }.json("https://auth.openai.com/test?code=$secret", Operation())
        }
        val diagnostic = diagnosticOf(error)
        assertEquals("auth.openai.com", diagnostic.host?.hostname)
        assertEquals(FixedError.DNS_LOOKUP_FAILED, diagnostic.errorClass)
        assertNull(diagnostic.httpStatus)
        assertFalse(diagnostic.toString().contains(secret))
        assertFalse(diagnostic.toString().contains("https://"))
        assertNull(error.message)
        assertNull(error.cause)
    }

    @Test fun apiHostUsesADifferentFixedValue() {
        assertEquals(TargetHost.API, trustedHost(checkedEndpoint("https://api.openai.com/v1/models")))
        val error = assertThrows(Problem::class.java) {
            Http { throw UnknownHostException("synthetic") }.json("https://api.openai.com/v1/models", Operation())
        }
        assertEquals("api.openai.com", diagnosticOf(error).host?.hostname)
    }
}
