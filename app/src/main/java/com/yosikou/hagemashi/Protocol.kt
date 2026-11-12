package com.yosikou.hagemashi

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.Closeable
import java.io.InputStream
import java.io.ByteArrayOutputStream
import java.io.PushbackInputStream
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
internal class Problem(val kind: Failure, val status: Int? = null, val code: String? = null, val network: NetworkFailure? = null,
    val targetHost: TargetHost? = null, val fixedError: FixedError? = null, val exceptionClasses: List<SafeException> = emptyList()) : Exception()

internal fun randomSecret(): String = b64(ByteArray(32).also { SecureRandom().nextBytes(it) })
internal fun b64(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
internal fun challenge(verifier: String): String = b64(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))
internal fun sameSecret(a: String, b: String): Boolean = MessageDigest.isEqual(a.toByteArray(), b.toByteArray())
internal fun form(values: Map<String, String>): String = values.entries.joinToString("&") {
    URLEncoder.encode(it.key, "UTF-8") + "=" + URLEncoder.encode(it.value, "UTF-8")
}

private val resourceCloser = Executors.newCachedThreadPool { task -> Thread(task, "connection-cleanup").apply { isDaemon = true } }

internal class Operation(timeoutMillis: Long = 180000, private val onProgress: (Progress) -> Unit = {}) {
    private var deadline = System.nanoTime() + timeoutMillis * 1_000_000
    private var cancelled = false
    private val resources = mutableSetOf<Closeable>()
    @Volatile var progress = Progress()
        private set
    @Volatile var lastDiagnostic: SafeDiagnostic? = null
        private set
    @Volatile var generationDiagnostic: GenerationDiagnostic? = null
        private set
    fun recordGeneration(value: GenerationDiagnostic) { generationDiagnostic = value }

    fun <T> step(stage: Stage, block: () -> T): T {
        lastDiagnostic = null
        publish(Progress(stage, Phase.RUNNING, progress.completed))
        try {
            check()
            val result = block()
            check()
            publish(Progress(stage, Phase.SUCCEEDED, stage))
            return result
        } catch (e: CancellationException) {
            publish(Progress(stage, Phase.CANCELLED, progress.completed))
            throw e
        } catch (e: Exception) {
            lastDiagnostic = diagnosticOf(e)
            publish(Progress(stage, Phase.FAILED, progress.completed))
            throw safeProblem(e)
        }
    }

    private fun publish(value: Progress) { progress = value; onProgress(value) }
    @Synchronized fun wasCancelled():Boolean=cancelled
    @Synchronized fun limitDuration(millis:Long) { deadline=minOf(deadline,System.nanoTime()+maxOf(0,millis)*1_000_000) }
    @Synchronized fun check() {
        if (cancelled) throw CancellationException()
        if (System.nanoTime() > deadline) throw Problem(Failure.TIMEOUT, network = NetworkFailure.TIMEOUT)
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

// CR, LF and CRLF are SSE line endings. Do not read ahead after CR: a terminal event may arrive on an open connection.
private class SseLines(private val reader: BufferedReader, private val trace: GenerationTrace) {
    private var skipLf = false
    fun read(): String? {
        val out = StringBuilder()
        while (true) {
            val ch = reader.read()
            if (ch == -1) return out.toString().takeIf { it.isNotEmpty() }
            if (skipLf) { skipLf = false; if (ch == 10) continue }
            if (ch == 13) { skipLf = true; return out.toString() }
            if (ch == 10) return out.toString()
            if (out.length >= 65536) trace.reject(GenerationReason.INPUT_LIMIT, StreamEnd.LIMIT_EXCEEDED)
            out.append(ch.toChar())
        }
    }
}

internal fun generationRequest(model: String, instructions: String, input: String): JSONObject =
    JSONObject().put("model", model).put("instructions", instructions).put("store", false).put("stream", true)
        .put("input", JSONArray().put(JSONObject().put("role", "user").put("content", input)))

// Only a fully framed response.completed with nonblank output succeeds. Never expose a partial answer on failure.
internal fun consumeSse(reader: BufferedReader, operation: Operation, trace: GenerationTrace = GenerationTrace()): String {
    var size = 0
    var events = 0
    var firstLine = true
    var refusal = false
    val lines = SseLines(reader, trace)
    val data = StringBuilder()
    val answer = StringBuilder()
    trace.update { it.copy(step = GenerationStep.EVENTS, end = StreamEnd.READING) }
    fun dispatch(): String? {
        if (data.isEmpty()) return null
        val payload = data.toString().dropLast(1)
        data.setLength(0)
        if (payload == "[DONE]") return null
        val event = try {
            val tokener = org.json.JSONTokener(payload)
            val value = tokener.nextValue()
            if (value !is JSONObject || tokener.nextClean() != 0.toChar()) trace.reject(GenerationReason.INVALID_JSON)
            value
        } catch (_: org.json.JSONException) { trace.reject(GenerationReason.INVALID_JSON) }
        val type = event.opt("type") as? String ?: trace.reject(GenerationReason.INVALID_EVENT_DATA)
        val known = KnownStreamEvent.entries.firstOrNull { it != KnownStreamEvent.NONE && it.wireName == type }
        trace.update { if (known == null) it.copy(unknownEventSeen = true) else it.copy(lastKnown = known) }
        when (event.optString("type")) {
            "response.output_text.delta" -> {
                answer.append(event.opt("delta") as? String ?: trace.reject(GenerationReason.INVALID_EVENT_DATA))
                trace.update { it.copy(answerPresent = answer.isNotBlank()) }
            }
            "response.refusal.delta", "response.refusal.done" -> refusal = true
            "response.failed", "error" -> {
                val failed = type == "response.failed"
                trace.update { it.copy(terminal = if (failed) StreamTerminal.FAILED else StreamTerminal.ERROR,
                    end = if (failed) StreamEnd.FAILED else StreamEnd.ERROR,
                    reason = if (failed) GenerationReason.FAILED else GenerationReason.SERVER_ERROR) }
                throw responseProblem(0, event.optJSONObject("response") ?: event)
            }
            "response.incomplete" -> {
                trace.update { it.copy(terminal = StreamTerminal.INCOMPLETE) }
                trace.reject(GenerationReason.INCOMPLETE, StreamEnd.INCOMPLETE)
            }
            "response.completed" -> {
                trace.update { it.copy(step = GenerationStep.COMPLETION, terminal = StreamTerminal.COMPLETED) }
                val response = event.getJSONObject("response")
                if (response.optString("status") != "completed" || response.has("error") && !response.isNull("error")) trace.reject(GenerationReason.INVALID_COMPLETION)
                val finalText = StringBuilder()
                val output = response.optJSONArray("output") ?: JSONArray()
                for (i in 0 until output.length()) {
                    val item = output.getJSONObject(i)
                    val content = item.optJSONArray("content") ?: continue
                    for (j in 0 until content.length()) {
                        val part = content.getJSONObject(j)
                        if (part.optString("type") == "output_text") finalText.append(part.opt("text") as? String ?: trace.reject(GenerationReason.INVALID_EVENT_DATA))
                        if (part.optString("type") == "refusal") refusal = true
                    }
                }
                val text = finalText.toString().ifEmpty { answer.toString() }
                trace.update { it.copy(answerPresent = text.isNotBlank()) }
                if (text.length > 20000) trace.reject(GenerationReason.INPUT_LIMIT, StreamEnd.LIMIT_EXCEEDED)
                if (text.isBlank()) trace.reject(if (refusal) GenerationReason.REFUSAL else GenerationReason.EMPTY_ANSWER)
                trace.update { it.copy(end = StreamEnd.COMPLETED, completionValidated = true) }
                return text
            }
        }
        if (answer.length > 20000) trace.reject(GenerationReason.INPUT_LIMIT, StreamEnd.LIMIT_EXCEEDED)
        return null
    }
    try {
        while (true) {
            operation.check()
            var line = lines.read() ?: break
            trace.update { it.copy(body = BodyPresence.PRESENT) }
            if (firstLine) { line = line.removePrefix("\uFEFF"); firstLine = false }
            size += line.length + 1
            if (size > 2_000_000 || ++events > 50000) trace.reject(GenerationReason.INPUT_LIMIT, StreamEnd.LIMIT_EXCEEDED)
            if (line.isEmpty()) dispatch()?.let { return it }
            else if (line == "data") data.append('\n')
            else if (line.startsWith("data:")) data.append(line.substring(5).removePrefix(" ")).append('\n')
        }
        if (trace.value.body == BodyPresence.UNKNOWN) trace.update { it.copy(body = BodyPresence.ABSENT) }
        if (data.isNotEmpty()) trace.reject(GenerationReason.UNTERMINATED_EVENT, StreamEnd.EOF_IN_EVENT)
        trace.reject(if (trace.value.body == BodyPresence.ABSENT) GenerationReason.EMPTY_BODY else GenerationReason.NO_COMPLETION, StreamEnd.EOF_WITHOUT_COMPLETION)
    } catch (_: org.json.JSONException) {
        trace.reject(GenerationReason.INVALID_EVENT_DATA)
    } catch (e: Exception) {
        trace.interrupted(e)
        throw e
    }
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
    return Problem(kind, status.takeIf { it in 100..599 }, code, status.takeIf { it in 100..599 }?.let { NetworkFailure.HTTP })
}

internal class Http(private val open: (java.net.URL) -> HttpsURLConnection = { it.openConnection() as HttpsURLConnection }) {
    // Same connection, TLS, timeout, redirect and exception handling as OAuth; no credentials or body parsing.
    fun probe(post: Boolean, operation: Operation): Int =
        request(if (post) TOKEN_ENDPOINT else "$ISSUER/", operation, if (post) "" else null,
            "application/x-www-form-urlencoded", null, onStatus = { it }) { throw Problem(Failure.PROTOCOL) }

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

    fun generate(body: JSONObject, bearer: String, operation: Operation): String {
        val trace = GenerationTrace(operation::recordGeneration)
        try {
            return request("$RESOURCE/responses", operation, body.toString(), "application/json", bearer, trace = trace) {
                consumeSse(it.bufferedReader(Charsets.UTF_8), operation, trace)
            }
        } catch (e: Exception) {
            trace.interrupted(e)
            throw e
        }
    }

    private fun <T> request(url: String, operation: Operation, body: String?, contentType: String, bearer: String?,
        onStatus: ((Int) -> T)? = null, trace: GenerationTrace? = null, read: (InputStream) -> T): T {
        val uri = checkedEndpoint(url)
        operation.check()
        val connection = try { open(uri.toURL()) } catch (e: Exception) {
            val problem = safeProblem(e)
            throw Problem(problem.kind, problem.status, problem.code, problem.network, trustedHost(uri), problem.fixedError, problem.exceptionClasses)
        }
        val closer = Closeable { connection.disconnect() }
        operation.attach(closer)
        var receivedStatus: Int? = null
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
            receivedStatus = status.takeIf { it in 100..599 }
            trace?.update { it.copy(step = GenerationStep.CONTENT_TYPE, httpStatus = receivedStatus, media = streamMedia(connection.contentType)) }
            operation.check()
            if (onStatus != null) {
                if (receivedStatus == null) throw Problem(Failure.PROTOCOL)
                return onStatus(status)
            }
            if (status != 200) {
                trace?.update { it.copy(reason = GenerationReason.HTTP_REJECTED, end = StreamEnd.HTTP_REJECTED) }
                val errorStream = connection.errorStream
                val error = runCatching { JSONObject(readLimited(if (trace != null && errorStream != null) observeBody(errorStream, trace) else errorStream, 65536)) }.getOrDefault(JSONObject())
                throw responseProblem(status, error)
            }
            return connection.inputStream.use { stream ->
                val observed = if (trace != null) observeBody(stream, trace) else stream
                // Like the official SDK's explicit stream path, a missing header does not override stream:true.
                // Keep MISSING in diagnostics and require the same fully validated SSE completion; never parse JSON/HTML as a fallback.
                if (trace != null && trace.value.media !in setOf(StreamMedia.EVENT_STREAM, StreamMedia.MISSING)) trace.reject(GenerationReason.CONTENT_TYPE_REJECTED, StreamEnd.MEDIA_REJECTED)
                read(observed)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val problem = try { operation.check(); safeProblem(e) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (timeout: Problem) { timeout }
            throw Problem(problem.kind, problem.status ?: receivedStatus, problem.code, problem.network, trustedHost(uri), problem.fixedError, problem.exceptionClasses)
        } finally {
            operation.detach(closer)
            connection.disconnect()
        }
    }

    private fun observeBody(stream: InputStream, trace: GenerationTrace): InputStream {
        val input = PushbackInputStream(stream, 1)
        val first = input.read()
        trace.update { it.copy(body = if (first == -1) BodyPresence.ABSENT else BodyPresence.PRESENT) }
        if (first != -1) input.unread(first)
        return input
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
