package com.yosikou.hagemashi

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.Closeable
import java.io.InputStream
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.RSAPublicKeySpec
import java.math.BigInteger
import java.util.Base64
import java.util.concurrent.CancellationException
import java.util.concurrent.Executors
import javax.net.ssl.HttpsURLConnection

internal const val ISSUER = "https://auth.openai.com"
internal const val RESOURCE = "https://api.openai.com/v1"
internal const val DIRECT_SCOPE = "chatgpt.tokens.use.direct"
internal const val SCOPES = "openid profile email offline_access resource.invoke chatgpt.tokens.use.direct"

internal enum class Failure { NETWORK, PROTOCOL, IDENTITY, STORAGE, DENIED, EXPIRED, PERMISSION, LIMIT, POLICY, SERVER, TIMEOUT }

// Never attach a raw response, URL, exception message or token to a diagnostic.
internal class Problem(val kind: Failure, val status: Int? = null, val code: String? = null) : Exception()

internal fun randomSecret(): String = b64(ByteArray(32).also { SecureRandom().nextBytes(it) })
internal fun b64(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
internal fun challenge(verifier: String): String = b64(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))
internal fun sameSecret(a: String, b: String): Boolean = MessageDigest.isEqual(a.toByteArray(), b.toByteArray())
internal fun form(values: Map<String, String>): String = values.entries.joinToString("&") {
    URLEncoder.encode(it.key, "UTF-8") + "=" + URLEncoder.encode(it.value, "UTF-8")
}

private val resourceCloser = Executors.newCachedThreadPool { task -> Thread(task, "connection-cleanup").apply { isDaemon = true } }

internal class Operation(timeoutMillis: Long = 180000) {
    private val deadline = System.nanoTime() + timeoutMillis * 1_000_000
    private var cancelled = false
    private val resources = mutableSetOf<Closeable>()
    @Synchronized fun check() {
        if (cancelled) throw CancellationException()
        if (System.nanoTime() > deadline) throw Problem(Failure.TIMEOUT)
    }
    @Synchronized fun <T> commit(block: () -> T): T { check(); return block() }
    @Synchronized fun attach(resource: Closeable) {
        try { check(); resources.add(resource) } catch (e: Exception) { runCatching { resource.close() }; throw e }
    }
    @Synchronized fun detach(resource: Closeable) { resources.remove(resource) }
    @Synchronized fun cancel() {
        cancelled = true
        resources.toList().forEach { resource -> resourceCloser.execute { runCatching { resource.close() } } }
        resources.clear()
    }
}

internal data class Callback(val code: String, val clientId: String)

internal fun parseCallback(target: String, state: String, returningClient: String?): Callback {
    val uri = URI(target)
    if (uri.isAbsolute || uri.rawAuthority != null || uri.rawPath != "/auth/callback" || uri.rawFragment != null) throw Problem(Failure.PROTOCOL)
    val pairs = uri.rawQuery.orEmpty().split('&').filter { it.isNotEmpty() }.map {
        val parts = it.split('=', limit = 2)
        URLDecoder.decode(parts[0], "UTF-8") to URLDecoder.decode(parts.getOrElse(1) { "" }, "UTF-8")
    }
    if (pairs.map { it.first }.distinct().size != pairs.size) throw Problem(Failure.PROTOCOL)
    val query = pairs.toMap()
    if (!sameSecret(state, query["state"].orEmpty())) throw Problem(Failure.IDENTITY)
    if (query.containsKey("error")) throw Problem(Failure.DENIED)
    val client = query["client_id"] ?: returningClient ?: throw Problem(Failure.PROTOCOL)
    if (!client.matches(Regex("[A-Za-z0-9_-]{1,200}")) || client == "dynamic_agent_client" ||
        (returningClient != null && client != returningClient)) throw Problem(Failure.IDENTITY)
    val code = query["code"]?.takeIf { it.isNotBlank() } ?: throw Problem(Failure.PROTOCOL)
    return Callback(code, client)
}

internal data class Identity(val subject: String, val name: String, val email: String)

internal fun validateIdToken(token: String, clientId: String, nonce: String?, keys: JSONObject, now: Long): Identity {
    try {
        if (token.length > 32768) throw Problem(Failure.IDENTITY)
        val parts = token.split('.')
        if (parts.size != 3) throw Problem(Failure.IDENTITY)
        fun json(part: String) = JSONObject(String(Base64.getUrlDecoder().decode(part), Charsets.UTF_8))
        val header = json(parts[0])
        if (header.getString("alg") != "RS256" || header.has("crit")) throw Problem(Failure.IDENTITY)
        val kid = header.getString("kid")
        if (kid.isBlank()) throw Problem(Failure.IDENTITY)
        val jwks = keys.getJSONArray("keys")
        val matches = (0 until jwks.length()).map { jwks.getJSONObject(it) }.filter {
            it.optString("kid") == kid && it.optString("kty") == "RSA" &&
                (!it.has("use") || it.optString("use") == "sig") && (!it.has("alg") || it.optString("alg") == "RS256")
        }
        if (matches.size != 1) throw Problem(Failure.IDENTITY)
        val key = matches.single()
        if (BigInteger(1, Base64.getUrlDecoder().decode(key.getString("n"))).bitLength() < 2048) throw Problem(Failure.IDENTITY)
        val publicKey = KeyFactory.getInstance("RSA").generatePublic(RSAPublicKeySpec(
            BigInteger(1, Base64.getUrlDecoder().decode(key.getString("n"))),
            BigInteger(1, Base64.getUrlDecoder().decode(key.getString("e")))
        ))
        val signature = Signature.getInstance("SHA256withRSA")
        signature.initVerify(publicKey)
        signature.update("${parts[0]}.${parts[1]}".toByteArray(Charsets.US_ASCII))
        if (!signature.verify(Base64.getUrlDecoder().decode(parts[2]))) throw Problem(Failure.IDENTITY)
        val claims = json(parts[1])
        val audience = claims.get("aud")
        val audiences = if (audience is JSONArray) (0 until audience.length()).map { audience.getString(it) } else listOf(audience as String)
        if (claims.getString("iss") != ISSUER || clientId !in audiences ||
            (audiences.size > 1 && claims.optString("azp") != clientId) ||
            (claims.has("azp") && claims.getString("azp") != clientId) ||
            claims.getLong("exp") <= now || claims.getLong("iat") > now + 60 ||
            (claims.has("nbf") && claims.getLong("nbf") > now + 60) ||
            (nonce != null && !sameSecret(nonce, claims.optString("nonce")))) throw Problem(Failure.IDENTITY)
        val subject = (claims.opt("sub") as? String)?.takeIf { it.isNotBlank() } ?: throw Problem(Failure.IDENTITY)
        return Identity(subject, (claims.opt("name") as? String).orEmpty().take(200), (claims.opt("email") as? String).orEmpty().take(254))
    } catch (e: Problem) { throw e } catch (_: Exception) { throw Problem(Failure.IDENTITY) }
}

internal fun readBoundedLine(reader: BufferedReader, limit: Int = 65536): String? {
    val out = StringBuilder()
    while (true) {
        val ch = reader.read()
        if (ch == -1) return if (out.isEmpty()) null else out.toString().removeSuffix("\r")
        if (ch == 10) return out.toString().removeSuffix("\r")
        if (out.length >= limit) throw Problem(Failure.PROTOCOL)
        out.append(ch.toChar())
    }
}

// Ignore [DONE] as a success signal. Only a completed Responses event succeeds.
internal fun consumeSse(reader: BufferedReader, operation: Operation): String {
    var size = 0
    var events = 0
    val data = StringBuilder()
    val answer = StringBuilder()
    fun dispatch(): String? {
        if (data.isEmpty()) return null
        val payload = data.toString().trimEnd('\n')
        data.setLength(0)
        if (payload == "[DONE]") return null
        val event = JSONObject(payload)
        when (event.optString("type")) {
            "response.output_text.delta" -> answer.append(event.getString("delta"))
            "response.failed", "error" -> throw responseProblem(0, event.optJSONObject("response") ?: event)
            "response.incomplete" -> throw Problem(Failure.PROTOCOL)
            "response.completed" -> {
                val response = event.getJSONObject("response")
                if (response.optString("status") != "completed" || response.has("error") && !response.isNull("error")) throw Problem(Failure.PROTOCOL)
                val finalText = StringBuilder()
                val output = response.optJSONArray("output") ?: JSONArray()
                for (i in 0 until output.length()) {
                    val item = output.getJSONObject(i)
                    val content = item.optJSONArray("content") ?: continue
                    for (j in 0 until content.length()) {
                        val part = content.getJSONObject(j)
                        if (part.optString("type") == "output_text") finalText.append(part.getString("text"))
                    }
                }
                val text = finalText.toString().ifEmpty { answer.toString() }
                if (text.isBlank() || text.length > 20000) throw Problem(Failure.PROTOCOL)
                return text
            }
        }
        if (answer.length > 20000) throw Problem(Failure.PROTOCOL)
        return null
    }
    while (true) {
        operation.check()
        val line = readBoundedLine(reader) ?: break
        size += line.length
        if (size > 2_000_000 || ++events > 50000) throw Problem(Failure.PROTOCOL)
        if (line.isEmpty()) dispatch()?.let { return it }
        else if (line.startsWith("data:")) data.append(line.substring(5).removePrefix(" ")).append('\n')
    }
    return dispatch() ?: throw Problem(Failure.PROTOCOL)
}

private val knownCodes = setOf(
    "invalid_grant", "invalid_refresh_token", "token_expired", "refresh_token_expired", "refresh_token_invalidated", "refresh_token_reused",
    "subscription_sharing_usage_limit_exceeded", "subscription_sharing_user_not_eligible", "subscription_sharing_invalid_user",
    "subscription_sharing_usage_unavailable", "subscription_sharing_user_unavailable", "subscription_sharing_unsupported_capability",
    "subscription_sharing_route_not_supported", "chatpass_v2_scope_not_authorized", "chatpass_v2_invalid_authorization_context", "invalid_client"
)

internal fun responseProblem(status: Int, body: JSONObject): Problem {
    val raw = body.optJSONObject("error")?.optString("code") ?: (body.opt("error") as? String).orEmpty()
    val code = raw.takeIf { it in knownCodes }
    val kind = when {
        code in setOf("invalid_grant", "invalid_refresh_token", "token_expired", "refresh_token_expired", "refresh_token_invalidated", "refresh_token_reused") -> Failure.EXPIRED
        code == "subscription_sharing_usage_limit_exceeded" || status == 429 -> Failure.LIMIT
        code in setOf("subscription_sharing_user_not_eligible", "subscription_sharing_route_not_supported", "chatpass_v2_scope_not_authorized", "chatpass_v2_invalid_authorization_context") -> Failure.POLICY
        code in setOf("subscription_sharing_usage_unavailable", "subscription_sharing_user_unavailable") -> Failure.SERVER
        code == "subscription_sharing_invalid_user" -> Failure.PERMISSION
        status == 401 -> Failure.PERMISSION
        status == 403 -> Failure.POLICY
        status >= 500 -> Failure.SERVER
        else -> Failure.PROTOCOL
    }
    return Problem(kind, status.takeIf { it > 0 }, code)
}

internal class Http {
    fun revoke(url: String, fields: Map<String, String>, operation: Operation) {
        request(url, operation, form(fields), "application/x-www-form-urlencoded", null) {
            if (readLimited(it, 65536).isNotBlank()) throw Problem(Failure.PROTOCOL)
        }
    }

    fun json(url: String, operation: Operation, fields: Map<String, String>? = null, bearer: String? = null): JSONObject =
        request(url, operation, fields?.let { form(it) }, "application/x-www-form-urlencoded", bearer) { stream ->
            val text = readLimited(stream, 1_000_000)
            if (text.isBlank()) JSONObject() else JSONObject(text)
        }

    fun generate(body: JSONObject, bearer: String, operation: Operation): String =
        request("$RESOURCE/responses", operation, body.toString(), "application/json", bearer) {
            consumeSse(it.bufferedReader(Charsets.UTF_8), operation)
        }

    private fun <T> request(url: String, operation: Operation, body: String?, contentType: String, bearer: String?, read: (InputStream) -> T): T {
        val uri = URI(url)
        if (uri.scheme != "https" || uri.host !in setOf("auth.openai.com", "api.openai.com") || uri.userInfo != null || uri.port !in setOf(-1, 443)) throw Problem(Failure.PROTOCOL)
        operation.check()
        val connection = uri.toURL().openConnection() as HttpsURLConnection
        val closer = Closeable { connection.disconnect() }
        operation.attach(closer)
        try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 15000
            connection.readTimeout = 45000
            connection.setRequestProperty("Accept", if (url.endsWith("/responses")) "text/event-stream" else "application/json")
            if (bearer != null) connection.setRequestProperty("Authorization", "Bearer $bearer")
            if (body != null) {
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", contentType)
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val status = connection.responseCode
            operation.check()
            if (status != 200) {
                val error = runCatching { JSONObject(readLimited(connection.errorStream, 65536)) }.getOrDefault(JSONObject())
                throw responseProblem(status, error)
            }
            if (url.endsWith("/responses") && !connection.contentType.orEmpty().startsWith("text/event-stream", ignoreCase = true)) throw Problem(Failure.PROTOCOL)
            return connection.inputStream.use(read)
        } finally {
            operation.detach(closer)
            connection.disconnect()
        }
    }

    private fun readLimited(stream: InputStream?, limit: Int): String {
        if (stream == null) return ""
        val bytes = stream.use {
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(4096)
            while (out.size() <= limit) {
                val count = it.read(buffer, 0, minOf(buffer.size, limit + 1 - out.size()))
                if (count == -1) break
                out.write(buffer, 0, count)
            }
            out.toByteArray()
        }
        if (bytes.size > limit) throw Problem(Failure.PROTOCOL)
        return bytes.toString(Charsets.UTF_8)
    }
}
