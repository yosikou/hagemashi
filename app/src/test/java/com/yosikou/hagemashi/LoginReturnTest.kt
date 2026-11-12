package com.yosikou.hagemashi

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class LoginReturnTest {
    private fun pipeline(operation: Operation, gate: LoginReturn, exchange: () -> Unit, discovery: () -> Unit = {}) {
        completeSignIn(operation, exchange = { exchange(); JSONObject() },
            discovery = { discovery(); JSONObject() }, keys = { JSONObject() },
            verify = { _, _ -> Identity("synthetic", "", "") }, record = { _, _ -> JSONObject() },
            persist = { _, _ -> }, beforeNetwork = { gate.await(operation) })
    }

    @Test fun browserMayRemainForegroundWithoutSpendingCodeAndReturnCompletesExactlyOnce() {
        val gate = LoginReturn()
        val waiting = CountDownLatch(1)
        val exchanges = AtomicInteger()
        val op = Operation(onProgress = { if (it.stage == Stage.WAIT_APP_RETURN && it.phase == Phase.RUNNING) waiting.countDown() })
        val executor = Executors.newSingleThreadExecutor()
        try {
            op.step(Stage.SAVE_REGISTRATION) { }
            val future = executor.submit { pipeline(op, gate, { exchanges.incrementAndGet() }) }
            assertTrue(waiting.await(2, TimeUnit.SECONDS))
            assertEquals(0, exchanges.get())
            assertFalse(future.isDone)
            gate.setResumed(true)
            future.get(2, TimeUnit.SECONDS)
            gate.setResumed(false)
            gate.setResumed(true)
            assertEquals(1, exchanges.get())
            assertEquals(Stage.SAVE_CREDENTIALS, op.progress.completed)
        } finally { op.cancel(); executor.shutdownNow() }
    }

    @Test fun leavingAgainBetweenExchangeAndDiscoveryWaitsWithoutReusingCode() {
        val gate = LoginReturn().apply { setResumed(true) }
        val waiting = CountDownLatch(1)
        val discoveries = AtomicInteger()
        val exchanges = AtomicInteger()
        val op = Operation(onProgress = { if (it.stage == Stage.WAIT_APP_RETURN && it.phase == Phase.RUNNING) waiting.countDown() })
        val executor = Executors.newSingleThreadExecutor()
        try {
            val future = executor.submit { pipeline(op, gate,
                { exchanges.incrementAndGet(); gate.setResumed(false) }, { discoveries.incrementAndGet() }) }
            assertTrue(waiting.await(2, TimeUnit.SECONDS))
            assertEquals(1, exchanges.get())
            assertEquals(0, discoveries.get())
            gate.setResumed(true)
            future.get(2, TimeUnit.SECONDS)
            assertEquals(1, exchanges.get())
            assertEquals(1, discoveries.get())
        } finally { op.cancel(); executor.shutdownNow() }
    }

    @Test fun cancellationWhileAwayNeverStartsCodeExchange() {
        val gate = LoginReturn()
        val waiting = CountDownLatch(1)
        val op = Operation(onProgress = { if (it.stage == Stage.WAIT_APP_RETURN) waiting.countDown() })
        val executor = Executors.newSingleThreadExecutor()
        try {
            val future = executor.submit { pipeline(op, gate, { fail("must not exchange a cancelled code") }) }
            assertTrue(waiting.await(2, TimeUnit.SECONDS))
            op.cancel()
            val failure = assertThrows(ExecutionException::class.java) { future.get(2, TimeUnit.SECONDS) }
            assertTrue(failure.cause is CancellationException)
            assertEquals(Phase.CANCELLED, op.progress.phase)
        } finally { op.cancel(); executor.shutdownNow() }
    }

    @Test fun waitingUsesTheExistingDeadlineAndDoesNotExchangeExpiredCode() {
        val op = Operation(100)
        try {
            val failure = assertThrows(Problem::class.java) { pipeline(op, LoginReturn(), { fail("must not exchange expired code") }) }
            assertEquals(Failure.TIMEOUT, failure.kind)
        } finally { op.cancel() }
    }

    @Test fun foregroundSignInDoesNotRequireAnExtraReturn() {
        val op = Operation()
        val exchanges = AtomicInteger()
        pipeline(op, LoginReturn().apply { setResumed(true) }, { exchanges.incrementAndGet() })
        assertEquals(1, exchanges.get())
        assertEquals(Stage.SAVE_CREDENTIALS, op.progress.completed)
    }

    @Test fun browserClosePageHasNoNavigationOrAutomaticBrowserControl() {
        val page = loginReplyPage("Close this browser tab and return to the app")
        for (forbidden in listOf("href=", "intent://", "auth-return", "<script", "window.close", "http-equiv", "127.0.0.1", "code=")) {
            assertFalse(page.contains(forbidden))
        }
    }

    @Test fun browserPageCannotInjectMarkupThroughLocalizedText() {
        val page = loginReplyPage("<script>synthetic</script>\"<img>")
        assertFalse(page.contains("<script>"))
        assertFalse(page.contains("<img>"))
        assertTrue(page.contains("&lt;script&gt;"))
        assertTrue(page.contains("&quot;&lt;img&gt;"))
    }
}
