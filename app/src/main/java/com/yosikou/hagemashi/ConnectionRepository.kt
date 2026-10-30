package com.yosikou.hagemashi

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException

internal data class Account(val id: String, val label: String)
internal data class ConnectionSnapshot(val connected: Boolean, val permitted: Boolean, val name: String, val email: String, val accounts: List<Account>, val welcome: Boolean)
internal data class Model(val slug: String, val name: String)

internal class LoginAttempt(val socket: ServerSocket, val state: String, val nonce: String, val verifier: String,
    val client: String?, val subject: String?, val url: String, val redirect: String)

internal class ConnectionRepository(context: Context) {
    private val vault = CredentialVault(context)
    private val http = Http()

    fun snapshot(): ConnectionSnapshot {
        val root = vault.read()
        val session = root.optJSONObject("session")
        val accounts = root.optJSONArray("accounts") ?: JSONArray()
        return ConnectionSnapshot(session != null, session?.let { canGenerate(it) } ?: false,
            session?.optString("name").orEmpty(), session?.optString("email").orEmpty(),
            (0 until accounts.length()).map { index ->
                val a = accounts.getJSONObject(index)
                Account(a.getString("client"), "${index + 1}: ${a.optString("email").ifEmpty { a.optString("name") }}")
            }, session != null && canGenerate(session) && !root.optBoolean("welcomed"))
    }

    fun dismissWelcome(operation: Operation) {
        val root = vault.read().put("welcomed", true)
        operation.commit { vault.write(root) }
    }

    fun prepareLogin(selected: String?, appName: String, operation: Operation, forceNew: Boolean = false): LoginAttempt {
        val root = vault.read()
        if (root.has("session")) throw Problem(Failure.PROTOCOL)
        if (forceNew && selected == null) root.remove("pending_client")
        val accounts = root.optJSONArray("accounts") ?: JSONArray()
        val account = (0 until accounts.length()).map { accounts.getJSONObject(it) }.find { it.optString("client") == selected }
        if (selected != null && account == null) throw Problem(Failure.IDENTITY)
        val client = account?.getString("client") ?: root.optString("pending_client").takeIf { it.isNotEmpty() }
        operation.commit { vault.write(root) } // Persist this host before opening the browser.
        val socket = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
        operation.attach(socket)
        socket.soTimeout = 1000
        val state = randomSecret()
        val nonce = randomSecret()
        val verifier = randomSecret()
        val redirect = "http://127.0.0.1:${socket.localPort}/auth/callback"
        val fields = mutableMapOf("client_id" to (client ?: "dynamic_agent_client"), "ext_agent_host_id" to root.getString("host"),
            "response_type" to "code", "redirect_uri" to redirect, "scope" to SCOPES, "resource" to RESOURCE,
            "state" to state, "nonce" to nonce, "code_challenge_method" to "S256", "code_challenge" to challenge(verifier))
        if (client == null) fields["agent_name_hint"] = appName
        // No ID-token hint is kept after sign-out; browser URLs never contain our tokens.
        return LoginAttempt(socket, state, nonce, verifier, client, account?.getString("subject"),
            "$ISSUER/api/accounts/authorize?${form(fields)}", redirect)
    }

    fun finishLogin(attempt: LoginAttempt, operation: Operation, browserReply: String) {
        try {
            var callback: Callback? = null
            while (callback == null) {
                operation.check()
                val connection = try { attempt.socket.accept() } catch (_: SocketTimeoutException) { continue }
                connection.use { socket ->
                    val closer = Closeable { socket.close() }
                    operation.attach(closer)
                    try {
                        socket.soTimeout = 3000
                        val request = readBoundedLine(socket.getInputStream().bufferedReader(Charsets.US_ASCII), 8192).orEmpty().split(' ')
                        if (request.size == 3 && request[0] == "GET") {
                            val candidate = runCatching { parseCallback(request[1], attempt.state, attempt.client) }
                            // A forged local request must not consume a valid pending authorization.
                            val error = candidate.exceptionOrNull()
                            if (error is Problem && error.kind == Failure.DENIED) throw error
                            callback = candidate.getOrNull()
                        }
                        val reply = browserReply.toByteArray(Charsets.UTF_8)
                        runCatching { socket.getOutputStream().write(("HTTP/1.1 ${if (callback != null) "200 OK" else "400 Bad Request"}\r\n" +
                            "Content-Type: text/plain; charset=utf-8\r\nContent-Length: ${reply.size}\r\n" +
                            "Cache-Control: no-store\r\nReferrer-Policy: no-referrer\r\nConnection: close\r\n\r\n").toByteArray(Charsets.US_ASCII) + reply) }
                    } catch (_: SocketTimeoutException) { /* Ignore a stalled local connection; the overall attempt is bounded. */ }
                    finally { operation.detach(closer) }
                }
            }
            val result = callback ?: throw Problem(Failure.PROTOCOL)
            val root = vault.read()
            if (attempt.client == null || attempt.subject == null) {
                root.put("pending_client", result.clientId)
                operation.commit { vault.write(root) }
            }
            val tokens = http.json("$ISSUER/api/accounts/oauth/token", operation, mapOf(
                "grant_type" to "authorization_code", "client_id" to result.clientId, "code" to result.code,
                "code_verifier" to attempt.verifier, "redirect_uri" to attempt.redirect, "resource" to RESOURCE))
            val identity = validateIdToken(tokens.getString("id_token"), result.clientId, attempt.nonce, jwks(operation), now())
            if (attempt.subject != null && identity.subject != attempt.subject) throw Problem(Failure.IDENTITY)
            val session = tokenRecord(tokens, result.clientId, identity, null)
            val accounts = root.optJSONArray("accounts") ?: JSONArray()
            val known = (0 until accounts.length()).map { accounts.getJSONObject(it) }.find { it.optString("client") == result.clientId }
            if (known != null && known.getString("subject") != identity.subject) throw Problem(Failure.IDENTITY)
            val already = (0 until accounts.length()).any { accounts.getJSONObject(it).optString("client") == result.clientId }
            if (!already) accounts.put(JSONObject().put("client", result.clientId).put("subject", identity.subject).put("name", identity.name).put("email", identity.email))
            root.put("accounts", accounts).put("session", session).remove("pending_client")
            operation.commit { vault.write(root) }
        } finally {
            operation.detach(attempt.socket)
            attempt.socket.close()
        }
    }

    fun models(operation: Operation): List<Model> {
        val session = usableSession(operation)
        val items = http.json("$RESOURCE/models", operation, bearer = session.getString("access")).getJSONArray("models")
        return (0 until items.length()).map { items.getJSONObject(it) }.filter { it.optString("visibility") == "list" }
            .map { Model(it.getString("slug"), it.getString("display_name")) }.take(100)
    }

    fun generate(model: String, instructions: String, dummy: String, operation: Operation): String {
        // Re-fetch account-specific availability for each manual request; never fall back to a hardcoded slug.
        if (models(operation).none { it.slug == model }) throw Problem(Failure.POLICY)
        val session = usableSession(operation)
        val body = JSONObject().put("model", model).put("instructions", instructions).put("store", false).put("stream", true)
            .put("input", JSONArray().put(JSONObject().put("role", "user").put("content", dummy)))
        return http.generate(body, session.getString("access"), operation)
    }

    fun disconnect(operation: Operation): Boolean {
        val root = vault.read()
        val session = root.optJSONObject("session")
        var confirmed = false
        if (session != null && session.optString("refresh").isNotEmpty()) {
            // One bounded, user-initiated attempt. Do not retain tokens for background retries.
            confirmed = runCatching {
                val endpoint = discovery(operation).getString("revocation_endpoint")
                if (!endpoint.startsWith("$ISSUER/")) throw Problem(Failure.PROTOCOL)
                http.revoke(endpoint, mapOf("token" to session.getString("refresh"), "token_type_hint" to "refresh_token", "client_id" to session.getString("client")), operation)
            }.isSuccess
        }
        root.remove("session")
        // Even if revocation fails, finish local sign-out and report that distinction.
        vault.write(root)
        return confirmed
    }

    fun clearExpired() {
        val root = vault.read()
        root.remove("session")
        vault.write(root)
    }

    fun eraseUnrecoverable() = vault.eraseUnrecoverable()

    private fun usableSession(operation: Operation): JSONObject {
        val root = vault.read()
        val session = root.optJSONObject("session") ?: throw Problem(Failure.EXPIRED)
        if (!canGenerate(session)) throw Problem(Failure.PERMISSION)
        if (session.getLong("expires") > now() + 60) return session
        if (session.optString("refresh").isEmpty()) throw Problem(Failure.EXPIRED)
        val tokens = http.json("$ISSUER/api/accounts/oauth/token", operation, mapOf("grant_type" to "refresh_token", "client_id" to session.getString("client"),
            "refresh_token" to session.getString("refresh"), "resource" to RESOURCE))
        val identity = if (tokens.has("id_token")) validateIdToken(tokens.getString("id_token"), session.getString("client"), null, jwks(operation), now())
            else Identity(session.getString("subject"), session.optString("name"), session.optString("email"))
        if (identity.subject != session.getString("subject")) throw Problem(Failure.IDENTITY)
        val replacement = tokenRecord(tokens, session.getString("client"), identity, session)
        root.put("session", replacement)
        operation.commit { vault.write(root) }
        if (!canGenerate(replacement)) throw Problem(Failure.PERMISSION)
        return replacement
    }

    private fun tokenRecord(tokens: JSONObject, client: String, identity: Identity, previous: JSONObject?): JSONObject {
        if (!(tokens.opt("token_type") as? String).orEmpty().equals("Bearer", ignoreCase = true)) throw Problem(Failure.PROTOCOL)
        val expires = tokens.getLong("expires_in")
        val access = tokens.opt("access_token") as? String ?: throw Problem(Failure.PROTOCOL)
        if (access.isBlank() || access.contains('\r') || access.contains('\n') || expires !in 1..86400) throw Problem(Failure.PROTOCOL)
        val scope = if (tokens.has("scope")) (tokens.opt("scope") as? String ?: throw Problem(Failure.PROTOCOL)) else previous?.getString("scope") ?: throw Problem(Failure.PROTOCOL)
        val refresh = (tokens.opt("refresh_token") as? String).orEmpty()
        if ("offline_access" in scope.split(' ') && refresh.isEmpty()) throw Problem(Failure.PROTOCOL)
        return JSONObject().put("client", client).put("subject", identity.subject).put("name", identity.name).put("email", identity.email)
            .put("access", access).put("refresh", refresh).put("id", tokens.optString("id_token", previous?.optString("id").orEmpty()))
            .put("scope", scope).put("expires", now() + expires)
    }

    private fun canGenerate(session: JSONObject): Boolean = session.getString("scope").split(' ').containsAll(listOf(DIRECT_SCOPE, "resource.invoke"))
    private fun now() = System.currentTimeMillis() / 1000
    private fun discovery(operation: Operation): JSONObject = http.json("$ISSUER/.well-known/openid-configuration", operation).also {
        if (it.getString("issuer") != ISSUER) throw Problem(Failure.IDENTITY)
    }
    private fun jwks(operation: Operation): JSONObject {
        val uri = discovery(operation).getString("jwks_uri")
        if (!uri.startsWith("$ISSUER/")) throw Problem(Failure.IDENTITY)
        return http.json(uri, operation)
    }
}
