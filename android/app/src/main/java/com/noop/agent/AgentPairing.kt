package com.noop.agent

import com.noop.ble.gesture.OuraGestureBroker
import org.json.JSONObject
import java.io.File

/**
 * The owner's pairing with their own z0live broker, reused as the agent's identity. Only the broker
 * origin, the SHA-256 of the token and the broker's device id are stored; the token itself is held
 * by the owner's Hermes host and presented on each request. Revoking the pairing on the broker
 * (`z0live` device revoke) cuts agent access within [AgentPolicy.VERIFY_TTL_MS].
 */
data class AgentPairing(val brokerUrl: String, val tokenSha256: String, val deviceId: String, val pairedAtMs: Long) {
    val brokerHost: String get() = java.net.URI(brokerUrl).host.orEmpty()
    val deviceShort: String get() = deviceId.takeLast(6)

    fun toJson(): JSONObject = JSONObject().put("url", brokerUrl).put("tokenSha256", tokenSha256).put("deviceId", deviceId).put("pairedAtMs", pairedAtMs)

    companion object {
        /** Accepts the existing `z0live://pair?url=…&token=…` link, or the same query under `noop-agent://pair`. */
        fun parseLink(link: String): Pair<String, String> {
            val trimmed = link.trim()
            val normalized = if (trimmed.startsWith("noop-agent://pair?")) "z0live://pair?" + trimmed.removePrefix("noop-agent://pair?") else trimmed
            return OuraGestureBroker.parsePairing(normalized)
        }

        fun fromJson(o: JSONObject): AgentPairing? = runCatching {
            AgentPairing(o.getString("url"), o.getString("tokenSha256"), o.getString("deviceId"), o.getLong("pairedAtMs"))
                .takeIf { it.tokenSha256.matches(Regex("[0-9a-f]{64}")) && it.deviceId.matches(Regex("[0-9a-f]{32}")) && it.brokerUrl.startsWith("https://") }
        }.getOrNull()
    }
}

class AgentPairingStore(private val file: File) {
    @Synchronized
    fun load(): AgentPairing? = if (!file.exists()) null else runCatching { AgentPairing.fromJson(JSONObject(file.readText())) }.getOrNull()

    @Synchronized
    fun save(pairing: AgentPairing) {
        val tmp = File(file.parentFile, file.name + ".new")
        tmp.writeText(pairing.toJson().toString())
        tmp.setReadable(false, false); tmp.setWritable(false, false)
        tmp.setReadable(true, true); tmp.setWritable(true, true)
        check(tmp.renameTo(file))
    }

    @Synchronized
    fun forget() { file.delete() }
}

sealed class AgentAuth {
    /**
     * [tokenScopes] is null for the legacy z0live pairing (the on-phone grant alone decides). For a
     * z0bridge token it is the token's scopes, and a tool needs both the grant and the token.
     */
    data class Allowed(val deviceShort: String, val tokenScopes: Set<AgentScope>? = null, val via: String = "z0live") : AgentAuth()
    data class Denied(val reason: String) : AgentAuth()
}

/**
 * Two pairings, newest first:
 *  1. z0bridge (v2): a short-lived ES256 access token for `noop@<this device>`, verified offline with
 *     the public key pinned at pairing. No network call, nothing secret on the phone.
 *  2. Legacy z0live: bearer check against the stored digest, then a cached liveness check against the
 *     owner's broker (`GET /v1/devices/self` must return the paired device id). Fails closed when the
 *     broker cannot be reached and no fresh check is cached. Kept until the owner re-pairs with z0bridge;
 *     a z0bridge pairing replaces it.
 */
class AgentAuthorizer(
    private val pairing: () -> AgentPairing?,
    private val brokerDeviceId: (brokerUrl: String, token: String) -> String?,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val trust: () -> Z0BridgeTrust? = { null },
) {
    private var verifiedSha: String? = null
    private var verifiedAtMs: Long = 0
    private var verifier: Pair<String, Z0BridgeVerifier>? = null

    @Synchronized
    fun authorize(bearer: String?): AgentAuth {
        val t = trust()
        if (t != null && bearer != null && Z0Bridge.looksLikeJwt(bearer)) {
            val v = verifier?.takeIf { it.first == t.kid + t.audience }?.second ?: Z0BridgeVerifier(t).also { verifier = (t.kid + t.audience) to it }
            val caller = v.verify(bearer, nowMs() / 1000) ?: return AgentAuth.Denied("z0bridge_token_invalid")
            return AgentAuth.Allowed(caller.credential.takeLast(6), caller.scopes, "z0bridge")
        }
        val p = pairing() ?: return AgentAuth.Denied(if (t != null) "bad_token" else "not_paired")
        if (!AgentPolicy.tokenMatches(bearer, p.tokenSha256)) return AgentAuth.Denied("bad_token")
        val now = nowMs()
        if (verifiedSha == p.tokenSha256 && now - verifiedAtMs in 0 until AgentPolicy.VERIFY_TTL_MS) return AgentAuth.Allowed(p.deviceShort)
        val live = runCatching { brokerDeviceId(p.brokerUrl, bearer!!) }.getOrNull()
        return if (live == p.deviceId) {
            verifiedSha = p.tokenSha256; verifiedAtMs = now
            AgentAuth.Allowed(p.deviceShort)
        } else {
            verifiedSha = null
            AgentAuth.Denied(if (live == null) "broker_unverified" else "broker_device_mismatch")
        }
    }

    @Synchronized
    fun reset() { verifiedSha = null; verifiedAtMs = 0; verifier = null }
}
