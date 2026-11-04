package com.yosikou.hagemashi

import org.json.JSONObject
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.cert.CertificateException
import java.security.cert.CertPathValidatorException
import java.util.concurrent.CancellationException
import javax.net.ssl.SSLException

// Diagnostic data is an allowlist of enums and a bounded numeric HTTP status.
// Exception messages, class names, URLs, request/response data and tokens never enter it.
internal enum class Stage {
    NONE, READ_SAVED_STATE, PREPARE_LOGIN, WAIT_CALLBACK, SAVE_REGISTRATION,
    CODE_EXCHANGE, DISCOVERY, SIGNING_KEYS, ID_TOKEN, TOKEN_FIELDS, SAVE_CREDENTIALS,
    MODELS, GENERATION, REFRESH, REVOCATION
}
internal enum class Phase { IDLE, RUNNING, SUCCEEDED, FAILED, CANCELLED }
internal enum class NetworkFailure { HTTP, DNS, TLS, TIMEOUT, CONNECTION, IO, PERMISSION }
internal enum class SavedConnection { NONE, PRESENT, UNKNOWN }
internal enum class FixedError { DNS_LOOKUP_FAILED, TLS_FAILED, TIMEOUT, CONNECTION_FAILED, IO_FAILED, PERMISSION_DENIED, HTTP_ERROR, INVALID_ENDPOINT, IDENTITY_REJECTED, STORAGE_FAILED, PROTOCOL_ERROR, OTHER_FAILURE }
internal data class Progress(val stage: Stage = Stage.NONE, val phase: Phase = Phase.IDLE, val completed: Stage = Stage.NONE)
internal data class SafeDiagnostic(val failure: Failure, val network: NetworkFailure?, val httpStatus: Int?, val host: TargetHost? = null, val errorClass: FixedError = FixedError.PROTOCOL_ERROR)

internal fun diagnosticOf(error: Exception): SafeDiagnostic {
    val problem = safeProblem(error)
    val errorClass = problem.fixedError ?: when (problem.network) {
        NetworkFailure.DNS -> FixedError.DNS_LOOKUP_FAILED
        NetworkFailure.TLS -> FixedError.TLS_FAILED
        NetworkFailure.TIMEOUT -> FixedError.TIMEOUT
        NetworkFailure.CONNECTION -> FixedError.CONNECTION_FAILED
        NetworkFailure.IO -> FixedError.IO_FAILED
        NetworkFailure.PERMISSION -> FixedError.PERMISSION_DENIED
        NetworkFailure.HTTP -> FixedError.HTTP_ERROR
        null -> when (problem.kind) {
            Failure.IDENTITY -> FixedError.IDENTITY_REJECTED
            Failure.STORAGE -> FixedError.STORAGE_FAILED
            Failure.TIMEOUT -> FixedError.TIMEOUT
            Failure.PERMISSION -> FixedError.PERMISSION_DENIED
            Failure.PROTOCOL -> FixedError.PROTOCOL_ERROR
            else -> FixedError.OTHER_FAILURE
        }
    }
    return SafeDiagnostic(problem.kind, problem.network, problem.status?.takeIf { it in 100..599 }, problem.targetHost, errorClass)
}

internal fun savedConnection(connected: Boolean?): SavedConnection = when (connected) {
    true -> SavedConnection.PRESENT
    false -> SavedConnection.NONE
    null -> SavedConnection.UNKNOWN
}

internal fun safeProblem(error: Exception): Problem {
    if (error is Problem) return error
    if (error is CancellationException) throw error
    val chain = mutableListOf<Throwable>()
    var next: Throwable? = error
    while (chain.size < 8) {
        val current = next ?: break
        if (chain.any { it === current }) break
        chain.add(current)
        next = current.cause
    }
    val network = when {
        chain.any { it is UnknownHostException } -> NetworkFailure.DNS
        chain.any { it is SocketTimeoutException } -> NetworkFailure.TIMEOUT
        chain.any { it is SSLException || it is CertificateException || it is CertPathValidatorException } -> NetworkFailure.TLS
        chain.any { it is ConnectException || it is NoRouteToHostException } -> NetworkFailure.CONNECTION
        chain.any { it is SecurityException } -> NetworkFailure.PERMISSION
        chain.any { it is IOException } -> NetworkFailure.IO
        else -> null
    }
    return Problem(when (network) {
        NetworkFailure.TIMEOUT -> Failure.TIMEOUT
        NetworkFailure.PERMISSION -> Failure.PERMISSION
        null -> Failure.PROTOCOL
        else -> Failure.NETWORK
    }, network = network)
}

// The actual post-callback pipeline is injectable for offline failure-path tests.
internal fun completeSignIn(
    operation: Operation,
    exchange: () -> JSONObject,
    discovery: () -> JSONObject,
    keys: (JSONObject) -> JSONObject,
    verify: (JSONObject, JSONObject) -> Identity,
    record: (JSONObject, Identity) -> JSONObject,
    persist: (JSONObject, Identity) -> Unit
) {
    val tokens = operation.step(Stage.CODE_EXCHANGE, exchange)
    val configuration = operation.step(Stage.DISCOVERY, discovery)
    val signingKeys = operation.step(Stage.SIGNING_KEYS) { keys(configuration) }
    val identity = operation.step(Stage.ID_TOKEN) { verify(tokens, signingKeys) }
    val session = operation.step(Stage.TOKEN_FIELDS) { record(tokens, identity) }
    operation.step(Stage.SAVE_CREDENTIALS) { persist(session, identity) }
}
