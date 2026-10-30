package com.yosikou.hagemashi

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.StringReader
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.util.concurrent.CancellationException

class ProtocolTest {
    private fun rejected(kind: Failure? = null, action: () -> Unit) {
        try { action(); fail("Expected rejection") } catch (e: Problem) { if (kind != null) assertEquals(kind, e.kind) }
    }

    @Test fun pkceMatchesRfc7636() {
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"))
    }

    @Test fun newRegistrationUsesIssuedIdAndDecodesCode() {
        val result = parseCallback("/auth/callback?state=expected&code=a%2Bb&client_id=oaiapp_test", "expected", null)
        assertEquals("oaiapp_test", result.clientId)
        assertEquals("a+b", result.code)
    }

    @Test fun callbackRejectsWrongStateAndDuplicateParameters() {
        rejected(Failure.IDENTITY) { parseCallback("/auth/callback?state=wrong&code=a&client_id=oaiapp_a", "expected", null) }
        rejected { parseCallback("/auth/callback?state=expected&state=expected&code=a&client_id=oaiapp_a", "expected", null) }
    }

    @Test fun callbackRejectsAbsoluteUrlAndWrongPath() {
        rejected { parseCallback("https://example.com/auth/callback?state=a&code=b", "a", "oaiapp_a") }
        rejected { parseCallback("/callback?state=a&code=b", "a", "oaiapp_a") }
    }

    @Test fun callbackCannotChangeReturningRegistration() {
        assertEquals("oaiapp_a", parseCallback("/auth/callback?state=a&code=b", "a", "oaiapp_a").clientId)
        rejected(Failure.IDENTITY) { parseCallback("/auth/callback?state=a&code=b&client_id=oaiapp_b", "a", "oaiapp_a") }
        rejected { parseCallback("/auth/callback?state=a&code=b&client_id=dynamic_agent_client", "a", null) }
        rejected { parseCallback("/auth/callback?state=a&code=b", "a", null) }
    }

    @Test fun denialRequiresMatchingState() {
        rejected(Failure.IDENTITY) { parseCallback("/auth/callback?state=bad&error=access_denied", "a", null) }
        rejected(Failure.DENIED) { parseCallback("/auth/callback?state=a&error=access_denied", "a", null) }
    }

    private val pair by lazy { KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair() }
    private fun keys(): JSONObject {
        val pub = pair.public as RSAPublicKey
        return JSONObject().put("keys", JSONArray().put(JSONObject().put("kid", "test").put("kty", "RSA").put("alg", "RS256")
            .put("n", b64(pub.modulus.toByteArray())).put("e", b64(pub.publicExponent.toByteArray()))))
    }
    private fun claims() = JSONObject().put("iss", ISSUER).put("aud", "oaiapp_a").put("sub", "person")
        .put("exp", 2000).put("iat", 900).put("nonce", "nonce").put("email", "test@example.invalid")
    private fun token(claims: JSONObject = claims(), alg: String = "RS256"): String {
        val header = b64(JSONObject().put("alg", alg).put("kid", "test").toString().toByteArray())
        val body = b64(claims.toString().toByteArray())
        val signed = "$header.$body"
        val sig = Signature.getInstance("SHA256withRSA").apply { initSign(pair.private); update(signed.toByteArray()) }.sign()
        return "$signed.${b64(sig)}"
    }

    @Test fun verifiedIdentityComesFromSignedClaims() {
        assertEquals("person", validateIdToken(token(), "oaiapp_a", "nonce", keys(), 1000).subject)
    }

    @Test fun rejectsTamperingAndUnsignedAlgorithm() {
        val original = token().split('.')
        val tampered = original[0] + "." + b64(claims().put("sub", "attacker").toString().toByteArray()) + "." + original[2]
        rejected(Failure.IDENTITY) { validateIdToken(tampered, "oaiapp_a", "nonce", keys(), 1000) }
        rejected(Failure.IDENTITY) { validateIdToken(token(alg = "none"), "oaiapp_a", "nonce", keys(), 1000) }
    }

    @Test fun rejectsWrongIssuerAudienceNonceAndExpiredToken() {
        for (claims in listOf(claims().put("iss", "https://example.invalid"), claims().put("aud", "other"),
            claims().put("nonce", "wrong"), claims().put("exp", 999), claims().put("iat", 2000))) {
            rejected(Failure.IDENTITY) { validateIdToken(token(claims), "oaiapp_a", "nonce", keys(), 1000) }
        }
    }

    @Test fun multipleAudiencesNeedMatchingAuthorizedParty() {
        val claims = claims().put("aud", JSONArray().put("oaiapp_a").put("other"))
        rejected(Failure.IDENTITY) { validateIdToken(token(claims), "oaiapp_a", "nonce", keys(), 1000) }
        assertEquals("person", validateIdToken(token(claims.put("azp", "oaiapp_a")), "oaiapp_a", "nonce", keys(), 1000).subject)
    }

    private fun sse(vararg events: JSONObject) = events.joinToString("") { "data: $it\n\n" }
    private fun completed(text: String = "応援しています") = JSONObject().put("type", "response.completed")
        .put("response", JSONObject().put("status", "completed").put("output", JSONArray().put(JSONObject().put("content", JSONArray()
            .put(JSONObject().put("type", "output_text").put("text", text))))))
    private fun consume(text: String) = consumeSse(StringReader(text).buffered(), Operation())

    @Test fun completedEventRequiredForSuccess() {
        assertEquals("応援しています", consume(sse(completed())))
        rejected { consume("data: [DONE]\n\n") }
        rejected { consume(sse(JSONObject().put("type", "response.output_text.delta").put("delta", "partial"))) }
    }

    @Test fun failedAndIncompleteStreamsNeverSucceed() {
        val failure = JSONObject().put("type", "response.failed").put("response", JSONObject().put("error", JSONObject()
            .put("code", "subscription_sharing_usage_limit_exceeded")))
        rejected(Failure.LIMIT) { consume(sse(failure, completed())) }
        rejected { consume(sse(JSONObject().put("type", "response.incomplete"), completed())) }
        rejected { consume(sse(completed(""))) }
    }

    @Test fun multilineSseAndCommentsAreHandled() {
        val text = "{\"type\":\"response.completed\",\ndata: \"response\":" + completed().getJSONObject("response") + "}"
        assertEquals("応援しています", consume(": heartbeat\n\ndata: $text\n\n"))
    }

    @Test fun boundedInputAndUnknownErrorsDoNotLeakMessages() {
        rejected { readBoundedLine(StringReader("12345\n").buffered(), 4) }
        val error = responseProblem(403, JSONObject().put("error", JSONObject().put("code", "secret-looking-value").put("message", "private")))
        assertNull(error.code)
        assertNull(error.message)
        assertEquals(Failure.POLICY, error.kind)
    }

    @Test fun cancelledOperationsCannotCommitCredentials() {
        val operation = Operation()
        operation.cancel()
        var written = false
        try { operation.commit { written = true }; fail("Expected cancellation") } catch (_: CancellationException) { }
        assertFalse(written)
    }
}
