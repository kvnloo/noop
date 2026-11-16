package com.noop.agent

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.URLEncoder

/**
 * z0bridge pairing v2 on the phone. The fixture was produced by z0bridge's own signer
 * (kvnloo/z0bridge `test/fixtures/verifier-fixture.ts`, throwaway key), so these tests check
 * cross-implementation compatibility, not a re-implementation agreeing with itself.
 */
class Z0BridgeTrustTest {
    private val fx = JSONObject(javaClass.classLoader!!.getResourceAsStream("z0bridge_verifier_fixture.json")!!.readBytes().toString(Charsets.UTF_8))
    private val jwk = fx.getJSONObject("jwk")
    private val deviceId = fx.getString("audience").removePrefix("noop@")
    private val trust = Z0BridgeTrust("http://100.113.138.100:4788", fx.getString("issuer"), jwk.getString("kid"), jwk.getString("x"), jwk.getString("y"),
        fx.getString("audience"), deviceId, 0)
    private val now = fx.getLong("now")

    @Test
    fun thumbprintMatchesZ0bridgeKid() {
        assertEquals(jwk.getString("kid"), Z0Bridge.thumbprint(jwk.getString("x"), jwk.getString("y")))
        assertNotNull(Z0BridgeTrust.fromJson(trust.toJson()))
    }

    @Test
    fun verifiesZ0bridgeSignedTokensOffline() {
        val caller = Z0BridgeVerifier(trust).verify(fx.getString("good"), now)
        assertNotNull(caller)
        assertEquals(setOf(AgentScope.READ_SUMMARY, AgentScope.ACTIONS), caller!!.scopes)
        assertEquals("cred_0123456789abcdef", caller.credential)
    }

    @Test
    fun refusesExpiredTamperedForeignAudienceAndForeignIssuer() {
        val v = Z0BridgeVerifier(trust)
        assertNull(v.verify(fx.getString("good"), fx.getLong("expiredNow")))
        assertNull(v.verify(fx.getString("tampered"), now))
        assertNull(v.verify(fx.getString("otherAud"), now))
        assertNull(Z0BridgeVerifier(trust.copy(issuer = "z0bridge:other")).verify(fx.getString("good"), now))
        assertNull(v.verify("not.a.jwt", now))
        assertNull(v.verify("x".repeat(5000), now))
    }

    private fun link(url: String = "http://100.113.138.100:4788", code: String = "z0bp_" + "A".repeat(43), kid: String = jwk.getString("kid"), extra: String = "") =
        "noop-agent://pair?v=2&url=${URLEncoder.encode(url, "UTF-8")}&code=$code&kid=$kid&app=noop$extra"

    @Test
    fun parsesOnlyWellFormedV2Links() {
        val parsed = Z0BridgeLink.parse(link())
        assertEquals("http://100.113.138.100:4788", parsed.identityUrl)
        assertFalse(parsed.toString().contains("z0bp_"))
        assertTrue(Z0BridgeLink.isV2(link()))
        assertTrue(runCatching { Z0BridgeLink.parse(link(url = "https://0.taila30dc.ts.net:4788")) }.isSuccess)
        for (bad in listOf(link(url = "http://192.168.1.5:4788"), link(url = "http://evil.example:4788"), link(code = "short"), link(kid = "x"), link(extra = "&more=1"))) {
            assertTrue(bad, runCatching { Z0BridgeLink.parse(bad) }.isFailure)
        }
    }

    private fun claimResponse(kid: String = jwk.getString("kid"), audience: String = "noop@$deviceId", kind: String = "resource") = JSONObject()
        .put("deviceId", deviceId).put("credentialId", "cred_0000000000000001").put("app", "noop").put("kind", kind).put("audience", audience)
        .put("issuer", fx.getString("issuer")).put("kid", kid).put("jwks", JSONObject().put("keys", JSONArray().put(jwk)))

    @Test
    fun trustFromClaimPinsTheLinkedKey() {
        val parsed = Z0BridgeLink.parse(link())
        val t = Z0Bridge.trustFromClaim(parsed, claimResponse(), 42)
        assertEquals("noop@$deviceId", t.audience)
        assertNotNull(Z0BridgeVerifier(t).verify(fx.getString("good"), now))
        val otherKid = "B".repeat(43)
        assertTrue(runCatching { Z0Bridge.trustFromClaim(Z0BridgeLink.parse(link(kid = otherKid)), claimResponse(kid = otherKid), 0) }.isFailure)
        assertTrue(runCatching { Z0Bridge.trustFromClaim(parsed, claimResponse(audience = "noop@dev_1111111111111111"), 0) }.isFailure)
        assertTrue(runCatching { Z0Bridge.trustFromClaim(parsed, claimResponse(kind = "client"), 0) }.isFailure)
    }

    @Test
    fun authorizerPrefersZ0bridgeAndRefusesLegacyTokensOnceReplaced() {
        var brokerCalls = 0
        val auth = AgentAuthorizer({ null }, { _, _ -> brokerCalls++; null }, { now * 1000 }, { trust })
        val ok = auth.authorize(fx.getString("good"))
        assertTrue(ok is AgentAuth.Allowed)
        assertEquals("z0bridge", (ok as AgentAuth.Allowed).via)
        assertEquals(setOf(AgentScope.READ_SUMMARY, AgentScope.ACTIONS), ok.tokenScopes)
        assertEquals(AgentAuth.Denied("z0bridge_token_invalid"), auth.authorize(fx.getString("tampered")))
        assertEquals(AgentAuth.Denied("bad_token"), auth.authorize("legacy-z0live-token-abcdefghijkl"))
        assertEquals(0, brokerCalls)
    }

    @Test
    fun aToolNeedsBothTheGrantAndTheTokenScope() {
        val dir = createTempDir("z0bridge-mcp")
        try {
            val audit = AgentAudit(File(dir, "audit.jsonl")) { 0 }
            val grant = AgentGrant(0, Long.MAX_VALUE, setOf(AgentScope.READ_SUMMARY, AgentScope.ACTIONS))
            val actions = object : AgentActions {
                var opened = 0
                override fun openScreen(screen: String) = JSONObject().put("screen", screen).put("landed", true).also { opened++ }
                override fun showCoachNote(title: String, body: String) = JSONObject().put("posted", true)
            }
            val reader = AgentHealthReader({ _, _ -> emptyList() }, { 0 }, java.time.ZoneOffset.UTC)
            val mcp = AgentMcp(reader, actions, audit, { grant }, { 1 })
            val body = """{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"open_screen","arguments":{"screen":"sleep"}}}"""
            val readOnly = JSONObject(mcp.handle(body, AgentCaller("p", "d", setOf(AgentScope.READ_SUMMARY), "z0bridge"))!!).getJSONObject("result")
            assertTrue(readOnly.getBoolean("isError"))
            assertTrue(readOnly.toString().contains("token_scope_required"))
            assertEquals(0, actions.opened)
            val full = JSONObject(mcp.handle(body, AgentCaller("p", "d", setOf(AgentScope.ACTIONS), "z0bridge"))!!).getJSONObject("result")
            assertFalse(full.getBoolean("isError"))
            assertEquals(1, actions.opened)
            assertTrue(audit.recent(5).any { it.optString("via") == "z0bridge" })
        } finally { dir.deleteRecursively() }
    }
}
