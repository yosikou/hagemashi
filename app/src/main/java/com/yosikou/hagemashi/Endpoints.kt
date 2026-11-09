package com.yosikou.hagemashi

import java.net.URI

internal const val TOKEN_ENDPOINT = "$ISSUER/api/accounts/oauth/token"

// Only these fixed literals may enter diagnostics. Never echo an arbitrary URI host.
internal enum class TargetHost(val hostname: String) {
    AUTH("auth.openai.com"), API("api.openai.com")
}

internal fun trustedHost(uri: URI): TargetHost = TargetHost.entries.firstOrNull { it.hostname == uri.host }
    ?: throw Problem(Failure.PROTOCOL, fixedError = FixedError.INVALID_ENDPOINT)

internal fun checkedEndpoint(value: String): URI {
    try {
        if (value.any { it.isWhitespace() || it.isISOControl() }) throw IllegalArgumentException()
        val uri = URI(value)
        trustedHost(uri)
        if (uri.scheme != "https" || uri.rawUserInfo != null || uri.port !in setOf(-1, 443) || uri.rawFragment != null) throw IllegalArgumentException()
        return uri
    } catch (_: Exception) {
        throw Problem(Failure.PROTOCOL, fixedError = FixedError.INVALID_ENDPOINT)
    }
}
