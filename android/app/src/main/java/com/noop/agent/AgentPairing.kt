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
    data class Allowed(val deviceShort: String) : AgentAuth()
    data class Denied(val reason: String) : AgentAuth()
}

/**
 * Bearer check against the stored digest, then a cached liveness check against the owner's broker
 * (`GET /v1/devices/self` must return the paired device id). Fails closed when the broker cannot be
 * reached and no fresh check is cached.
 */
class AgentAuthorizer(
    private val pairing: () -> AgentPairing?,
    private val brokerDeviceId: (brokerUrl: String, token: String) -> String?,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private var verifiedSha: String? = null
    private var verifiedAtMs: Long = 0

    @Synchronized
    fun authorize(bearer: String?): AgentAuth {
        val p = pairing() ?: return AgentAuth.Denied("not_paired")
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
    fun reset() { verifiedSha = null; verifiedAtMs = 0 }
}
