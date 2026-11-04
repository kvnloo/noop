package com.noop.agent

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.security.MessageDigest

/**
 * Experimental, fork-only Hermes agent access (kvnloo/noop). Default OFF.
 *
 * The owner pairs NOOP with their own z0live broker (the same `z0live://pair` credential format the
 * Oura gesture export uses), then grants a time-boxed session. While a grant is live, NOOP answers a
 * small MCP tool surface on its Tailscale address only. Nothing listens without a grant, nothing is
 * pushed anywhere, and the phone never stores the bearer token itself, only its SHA-256.
 */
enum class AgentScope(val wire: String) {
    /** Aggregates only: means, ranges, counts, freshness. Always part of a grant. */
    READ_SUMMARY("read_summary"),

    /** Per-day values and per-night/per-workout rows. Off unless the owner ticks it for this grant. */
    READ_DETAIL("read_detail"),

    /** The two safe, reversible in-app actions. Off unless the owner ticks it for this grant. */
    ACTIONS("actions"),
    ;

    companion object {
        fun parse(wire: String): AgentScope? = entries.firstOrNull { it.wire == wire }
    }
}

data class AgentGrant(val startedAtMs: Long, val expiresAtMs: Long, val scopes: Set<AgentScope>) {
    fun active(nowMs: Long): Boolean = nowMs in startedAtMs until expiresAtMs
    fun allows(scope: AgentScope): Boolean = scope in scopes
}

object AgentPolicy {
    const val PORT = 8765
    const val MCP_PATH = "/mcp"
    val GRANT_MINUTES = listOf(15, 60, 240)
    const val MAX_HEADER_BYTES = 16 * 1024
    const val MAX_BODY_BYTES = 64 * 1024
    const val MAX_RESPONSE_BYTES = 256 * 1024
    const val SOCKET_TIMEOUT_MS = 5_000
    /** A broker check is reused this long; revoking the pairing on the broker cuts access within it. */
    const val VERIFY_TTL_MS = 5 * 60_000L
    const val MAX_SUMMARY_DAYS = 31
    const val MAX_DETAIL_DAYS = 31

    /** Screens `open_screen` may navigate to. Navigation only: no screen is started or changed. */
    val SCREENS = linkedSetOf("today", "sleep", "breathe", "stress", "trends", "workouts", "coach", "insights")

    const val NOTE_TITLE_MAX = 60
    const val NOTE_BODY_MAX = 600

    /** Tailscale CGNAT IPv4 range 100.64.0.0/10 and the Tailscale ULA prefix fd7a:115c:a1e0::/48. */
    fun isTailnet(address: InetAddress): Boolean {
        val b = address.address
        return when (address) {
            is Inet4Address -> (b[0].toInt() and 0xff) == 100 && (b[1].toInt() and 0xc0) == 0x40
            is Inet6Address -> b.size == 16 &&
                (b[0].toInt() and 0xff) == 0xfd && (b[1].toInt() and 0xff) == 0x7a &&
                (b[2].toInt() and 0xff) == 0x11 && (b[3].toInt() and 0xff) == 0x5c &&
                (b[4].toInt() and 0xff) == 0xa1 && (b[5].toInt() and 0xff) == 0xe0
            else -> false
        }
    }

    fun sha256Hex(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    /** Constant-time comparison of a presented bearer token against the stored digest. */
    fun tokenMatches(presented: String?, storedSha256Hex: String?): Boolean {
        if (presented.isNullOrEmpty() || storedSha256Hex.isNullOrEmpty()) return false
        if (presented.length > 200) return false
        return MessageDigest.isEqual(
            sha256Hex(presented).toByteArray(Charsets.US_ASCII),
            storedSha256Hex.lowercase().toByteArray(Charsets.US_ASCII),
        )
    }

    fun clampDays(raw: Any?, default: Int, max: Int): Int {
        val value = when (raw) {
            null -> default
            is Number -> raw.toInt()
            is String -> raw.toIntOrNull() ?: throw AgentToolError("days must be an integer")
            else -> throw AgentToolError("days must be an integer")
        }
        return value.coerceIn(1, max)
    }
}

/** A tool-level refusal. Its message is returned to the agent, so it never carries health values. */
class AgentToolError(message: String, val code: String = "invalid_params") : Exception(message)
