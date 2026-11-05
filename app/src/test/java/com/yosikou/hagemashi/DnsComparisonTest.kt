package com.yosikou.hagemashi

import org.junit.Assert.*
import org.junit.Test
import java.net.UnknownHostException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

class DnsComparisonTest {
    @Test fun lookupUsesOnlyTheFixedHostAndDoesNotReturnAddresses() {
        var host: String? = null
        val comparison = DnsComparison(lookup = { host = it })
        try {
            val result = comparison.sample(DnsPoint.BEFORE_BROWSER) { true }
            assertEquals("auth.openai.com", host)
            assertTrue(result.resolved)
            assertNull(result.diagnostic)
            assertEquals(AppVisibility.VISIBLE, result.visibility)
        } finally { comparison.close() }
    }

    @Test fun lookupFailurePreservesSafeCauseClassesWithoutMessages() {
        val secret = "synthetic-query-token-IP-not-for-diagnostics"
        val comparison = DnsComparison(lookup = { throw UnknownHostException(secret) })
        try {
            val result = comparison.sample(DnsPoint.AFTER_CALLBACK) { false }
            assertFalse(result.resolved)
            assertEquals(AppVisibility.HIDDEN, result.visibility)
            assertEquals(FixedError.DNS_LOOKUP_FAILED, result.diagnostic?.errorClass)
            assertEquals(listOf(SafeException.EXECUTION, SafeException.UNKNOWN_HOST), result.diagnostic?.exceptionClasses)
            assertFalse(result.toString().contains(secret))
        } finally { comparison.close() }
    }

    @Test fun comparisonTimeoutIsBoundedAndDoesNotRequireARealNetwork() {
        val comparison = DnsComparison(lookup = { CountDownLatch(1).await() }, timeoutMillis = 25)
        try {
            val result = comparison.sample(DnsPoint.AFTER_RESUME) { true }
            assertFalse(result.resolved)
            assertEquals(FixedError.TIMEOUT, result.diagnostic?.errorClass)
            assertEquals(listOf(SafeException.FUTURE_TIMEOUT), result.diagnostic?.exceptionClasses)
        } finally { comparison.close() }
    }

    @Test fun visibilityTransitionIsNotMisreportedAsEntirelyBackground() {
        val visibilityReads = AtomicInteger()
        val comparison = DnsComparison(lookup = {})
        try {
            val result = comparison.sample(DnsPoint.AFTER_CALLBACK) { visibilityReads.getAndIncrement() == 0 }
            assertEquals(AppVisibility.CHANGED, result.visibility)
        } finally { comparison.close() }
    }
}
