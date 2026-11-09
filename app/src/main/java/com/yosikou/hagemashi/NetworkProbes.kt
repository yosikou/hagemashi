package com.yosikou.hagemashi

import java.util.concurrent.CancellationException

internal enum class ProbeKind(val stage: Stage) {
    DNS(Stage.PROBE_DNS), HTTPS_GET(Stage.PROBE_GET), EMPTY_POST(Stage.PROBE_POST)
}

internal data class ProbeResult(val kind: ProbeKind, val progress: Progress, val visibility: AppVisibility,
    val diagnostic: SafeDiagnostic? = null, val httpStatus: Int? = null)

// No vault, account, authorization URL, callback, token, or user-supplied endpoint is accessible here.
internal class NetworkProbes(private val http: Http = Http(), private val dns: DnsComparison = DnsComparison()) {
    fun execute(kind: ProbeKind, operation: Operation, visible: () -> Boolean): ProbeResult {
        val start = visible()
        var status: Int? = null
        var diagnostic: SafeDiagnostic? = null
        try {
            operation.step(kind.stage) {
                if (kind == ProbeKind.DNS) {
                    val observation = dns.sample(DnsPoint.BEFORE_BROWSER, visible)
                    observation.diagnostic?.let { d -> throw Problem(d.failure, d.httpStatus, network = d.network,
                        targetHost = TargetHost.AUTH, fixedError = d.errorClass, exceptionClasses = d.exceptionClasses) }
                } else status = http.probe(kind == ProbeKind.EMPTY_POST, operation)
            }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { diagnostic = diagnosticOf(e).copy(host = TargetHost.AUTH) }
        val end = visible()
        return ProbeResult(kind, operation.progress,
            if (start != end) AppVisibility.CHANGED else if (start) AppVisibility.VISIBLE else AppVisibility.HIDDEN,
            diagnostic, status ?: diagnostic?.httpStatus)
    }
    fun close() { dns.close() }
}

// Machine-readable allowlisted schema, never concatenate arbitrary exception/request/response text.
internal fun probeReport(build: String, applicationId: String, variant: String, internet: Boolean?, results: List<ProbeResult>): String {
    fun identifier(value: String) = value.takeIf { it.matches(Regex("[A-Za-z0-9._-]{1,150}")) } ?: "UNKNOWN"
    return buildString {
        appendLine("schema=HAGEMASHI_NETWORK_V1")
        appendLine("build=${identifier(build)}")
        appendLine("applicationId=${identifier(applicationId)}")
        appendLine("variant=${identifier(variant)}")
        appendLine("internet=${internet?.toString() ?: "UNKNOWN"}")
        for (result in results) {
            appendLine("probe=${result.kind.name}")
            appendLine("host=${TargetHost.AUTH.hostname}")
            appendLine("stage=${result.progress.stage.name}")
            appendLine("phase=${result.progress.phase.name}")
            appendLine("completed=${result.progress.completed.name}")
            appendLine("visibility=${result.visibility.name}")
            appendLine("result=${result.diagnostic?.errorClass?.name ?: if (result.kind == ProbeKind.DNS) "DNS_RESOLVED" else "HTTP_RESPONSE_RECEIVED"}")
            appendLine("http=${result.httpStatus?.takeIf { it in 100..599 }?.toString() ?: "NOT_RECEIVED"}")
            appendLine("exceptions=${result.diagnostic?.exceptionClasses?.joinToString(" -> ") { it.displayName }?.ifEmpty { "NONE" } ?: "NONE"}")
        }
    }
}
