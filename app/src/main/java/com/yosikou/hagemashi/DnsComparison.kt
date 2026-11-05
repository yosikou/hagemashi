package com.yosikou.hagemashi

import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.Future
import java.util.concurrent.ThreadPoolExecutor

internal enum class DnsPoint { BEFORE_BROWSER, AFTER_CALLBACK, AFTER_RESUME }
internal enum class AppVisibility { VISIBLE, HIDDEN, CHANGED }
internal data class DnsObservation(val point: DnsPoint, val visibility: AppVisibility, val resolved: Boolean, val diagnostic: SafeDiagnostic?)

// Optional experiment: the Android default resolver only. Never return/store an address or use it to connect.
internal class DnsComparison(
    private val lookup: (String) -> Unit = { host -> if (InetAddress.getAllByName(host).isEmpty()) throw UnknownHostException() },
    private val executor: ExecutorService = Executors.newFixedThreadPool(2) { task -> Thread(task, "dns-comparison").apply { isDaemon = true } },
    private val timeoutMillis: Long = 5000
) {
    fun sample(point: DnsPoint, visible: () -> Boolean): DnsObservation {
        val start = visible()
        var future: Future<Unit>? = null
        val diagnostic = try {
            future = executor.submit<Unit> { lookup(TargetHost.AUTH.hostname) }
            future.get(timeoutMillis, TimeUnit.MILLISECONDS)
            null
        }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { diagnosticOf(e).copy(host = TargetHost.AUTH) }
            finally { future?.cancel(true); (executor as? ThreadPoolExecutor)?.purge() }
        val end = visible()
        val visibility = if (start != end) AppVisibility.CHANGED else if (start) AppVisibility.VISIBLE else AppVisibility.HIDDEN
        return DnsObservation(point, visibility, diagnostic == null, diagnostic)
    }
    fun close() { executor.shutdownNow() }
}
