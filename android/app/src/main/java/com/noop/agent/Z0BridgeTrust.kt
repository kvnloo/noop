package com.noop.agent

import org.json.JSONObject
import java.io.File
import java.math.BigInteger
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetAddress
import java.net.URI
import java.net.URL
import java.net.URLDecoder
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.util.Base64

/**
 * Pairing v2: NOOP as a z0bridge *resource*. z0bridge (the owner's single pairing and identity
 * layer, kvnloo/z0bridge) enrolls this app on this phone once and hands back its public signing
 * key. Callers such as Hermes then present short-lived ES256 access tokens that z0bridge minted for
 * the audience `noop@<this device>`, with scopes like `noop:health.read`. NOOP verifies them
 * offline: no broker round trip, no secret stored on the phone. Revoking the caller (or one of its
 * scopes, or this phone) in z0bridge stops new tokens at once, and outstanding tokens expire within
 * their lifetime (at most 5 minutes; Hermes asks for 2).
 */
data class Z0BridgeTrust(
    val identityUrl: String,
    val issuer: String,
    val kid: String,
    val x: String,
    val y: String,
    val audience: String,
    val deviceId: String,
    val pairedAtMs: Long,
) {
    val host: String get() = URI(identityUrl).host.orEmpty()
    val deviceShort: String get() = deviceId.takeLast(6)

    fun toJson(): JSONObject = JSONObject().put("v", 2).put("identityUrl", identityUrl).put("issuer", issuer).put("kid", kid)
        .put("x", x).put("y", y).put("audience", audience).put("deviceId", deviceId).put("pairedAtMs", pairedAtMs)

    companion object {
        fun fromJson(o: JSONObject): Z0BridgeTrust? = runCatching {
            Z0BridgeTrust(o.getString("identityUrl"), o.getString("issuer"), o.getString("kid"), o.getString("x"), o.getString("y"),
                o.getString("audience"), o.getString("deviceId"), o.getLong("pairedAtMs"))
                .takeIf { it.audience == "noop@${it.deviceId}" && Z0Bridge.thumbprint(it.x, it.y) == it.kid && Z0Bridge.identityUrlOk(it.identityUrl) }
        }.getOrNull()
    }
}

/** A `noop-agent://pair?v=2&url=…&code=…&kid=…&app=noop` link from `z0bridge pair --scheme noop-agent`. */
data class Z0BridgeLink(val identityUrl: String, val code: String, val kid: String) {
    override fun toString(): String = "Z0BridgeLink(${URI(identityUrl).host}, kid=$kid)" // never the code

    companion object {
        fun isV2(link: String): Boolean = link.trim().startsWith("noop-agent://pair?") && link.contains("v=2")

        fun parse(link: String): Z0BridgeLink {
            require(link.length <= 4096)
            val uri = URI(link.trim())
            require(uri.scheme == "noop-agent" && uri.rawAuthority == "pair" && uri.rawPath.isEmpty() && uri.rawFragment == null)
            val args = checkNotNull(uri.rawQuery).split("&").associate {
                val parts = it.split("=", limit = 2); require(parts.size == 2)
                parts[0] to URLDecoder.decode(parts[1], "UTF-8")
            }
            require(args.keys == setOf("v", "url", "code", "kid", "app") && args["v"] == "2" && args["app"] == "noop")
            val url = args.getValue("url").trimEnd('/')
            require(Z0Bridge.identityUrlOk(url))
            val code = args.getValue("code"); require(code.matches(Regex("z0bp_[A-Za-z0-9_-]{43}")))
            val kid = args.getValue("kid"); require(kid.matches(Regex("[A-Za-z0-9_-]{43}")))
            return Z0BridgeLink(url, code, kid)
        }
    }
}

class Z0BridgeTrustStore(private val file: File) {
    @Synchronized
    fun load(): Z0BridgeTrust? = if (!file.exists()) null else runCatching { Z0BridgeTrust.fromJson(JSONObject(file.readText())) }.getOrNull()

    @Synchronized
    fun save(trust: Z0BridgeTrust) {
        val tmp = File(file.parentFile, file.name + ".new")
        tmp.writeText(trust.toJson().toString())
        tmp.setReadable(false, false); tmp.setWritable(false, false)
        tmp.setReadable(true, true); tmp.setWritable(true, true)
        check(tmp.renameTo(file))
    }

    @Synchronized
    fun forget() { file.delete() }
}

/** A verified z0bridge access token: who called and which NOOP scopes it carries. */
data class Z0BridgeCaller(val credential: String, val device: String, val app: String, val scopes: Set<AgentScope>, val expSec: Long)

object Z0Bridge {
    const val MAX_TOKEN_BYTES = 4096
    /** z0bridge never mints longer than 300 s; allow a little clock skew. */
    const val MAX_LIFETIME_SEC = 330L
    const val SKEW_SEC = 30L

    /** z0bridge scope -> NOOP grant scope. Unknown `noop:*` scopes grant nothing. */
    val SCOPES = mapOf(
        "noop:health.read" to AgentScope.READ_SUMMARY,
        "noop:health.detail" to AgentScope.READ_DETAIL,
        "noop:actions" to AgentScope.ACTIONS,
    )

    private val b64u: Base64.Decoder = Base64.getUrlDecoder()

    fun looksLikeJwt(token: String): Boolean = token.length <= MAX_TOKEN_BYTES && token.matches(Regex("[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+"))

    /** https to any host, or plain http only to a Tailscale IPv4 literal (WireGuard already encrypts and authenticates the peer). */
    fun identityUrlOk(url: String): Boolean = runCatching {
        val u = URI(url)
        val hostOk = !u.host.isNullOrEmpty() && u.rawUserInfo == null && u.rawQuery == null && u.rawFragment == null && u.rawPath.orEmpty() in listOf("", "/")
        hostOk && when (u.scheme) {
            "https" -> true
            "http" -> u.host.matches(Regex("\\d{1,3}(\\.\\d{1,3}){3}")) && InetAddress.getByName(u.host).let { it is Inet4Address && AgentPolicy.isTailnet(it) }
            else -> false
        }
    }.getOrDefault(false)

    /** RFC 7638 JWK thumbprint of a P-256 key, as z0bridge computes its `kid`. */
    fun thumbprint(x: String, y: String): String {
        val canonical = """{"crv":"P-256","kty":"EC","x":"$x","y":"$y"}"""
        return Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8)))
    }

    fun publicKey(x: String, y: String): ECPublicKey {
        val xb = b64u.decode(x); val yb = b64u.decode(y)
        require(xb.size == 32 && yb.size == 32) { "P-256 coordinates must be 32 bytes" }
        val params = AlgorithmParameters.getInstance("EC").apply { init(ECGenParameterSpec("secp256r1")) }.getParameterSpec(ECParameterSpec::class.java)
        return KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(ECPoint(BigInteger(1, xb), BigInteger(1, yb)), params)) as ECPublicKey
    }

    /** JWS ES256 signatures are raw r||s; Java's SHA256withECDSA wants ASN.1 DER. */
    fun rawToDer(raw: ByteArray): ByteArray {
        require(raw.size == 64)
        fun int(bytes: ByteArray): ByteArray {
            var i = 0
            while (i < bytes.size - 1 && bytes[i] == 0.toByte()) i++
            val trimmed = bytes.copyOfRange(i, bytes.size)
            val body = if (trimmed[0].toInt() and 0x80 != 0) byteArrayOf(0) + trimmed else trimmed
            return byteArrayOf(0x02, body.size.toByte()) + body
        }
        val seq = int(raw.copyOfRange(0, 32)) + int(raw.copyOfRange(32, 64))
        return byteArrayOf(0x30, seq.size.toByte()) + seq
    }

    /**
     * Turn a pairing-claim response into trust, refusing anything that does not match the link: the
     * response must name this app as a resource, carry the key whose thumbprint the link pinned, and an
     * audience for the enrolled device.
     */
    fun trustFromClaim(link: Z0BridgeLink, response: JSONObject, nowMs: Long): Z0BridgeTrust {
        require(response.optString("kind") == "resource" && response.optString("app") == "noop") { "not a NOOP resource enrollment" }
        val deviceId = response.getString("deviceId"); require(deviceId.matches(Regex("dev_[a-z0-9]{10,32}")))
        require(response.getString("audience") == "noop@$deviceId") { "audience mismatch" }
        require(response.getString("kid") == link.kid) { "key differs from the one the link pinned" }
        val keys = response.getJSONObject("jwks").getJSONArray("keys")
        val jwk = (0 until keys.length()).map { keys.getJSONObject(it) }.firstOrNull { it.optString("kid") == link.kid } ?: error("pinned key missing")
        require(jwk.optString("kty") == "EC" && jwk.optString("crv") == "P-256")
        val x = jwk.getString("x"); val y = jwk.getString("y")
        require(thumbprint(x, y) == link.kid) { "thumbprint mismatch" }
        publicKey(x, y) // must parse as a point on P-256
        val issuer = response.getString("issuer"); require(issuer.isNotEmpty() && issuer.length <= 200)
        return Z0BridgeTrust(link.identityUrl, issuer, link.kid, x, y, "noop@$deviceId", deviceId, nowMs)
    }

    /** `POST {url}/v1/pair/claim {code}` over the tailnet. Returns the parsed JSON body or throws. */
    fun claim(link: Z0BridgeLink): JSONObject {
        val conn = URL("${link.identityUrl}/v1/pair/claim").openConnection() as HttpURLConnection
        conn.instanceFollowRedirects = false
        conn.connectTimeout = 4000; conn.readTimeout = 6000
        conn.requestMethod = "POST"; conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        return try {
            conn.outputStream.use { it.write(JSONObject().put("code", link.code).toString().toByteArray(Charsets.UTF_8)) }
            check(conn.responseCode == 201) { "z0bridge refused the pairing (HTTP ${conn.responseCode})" }
            val out = java.io.ByteArrayOutputStream(); val buf = ByteArray(2048)
            conn.inputStream.use { input -> while (true) { val n = input.read(buf); if (n < 0) break; check(out.size() + n <= 16_384); out.write(buf, 0, n) } }
            JSONObject(String(out.toByteArray(), Charsets.UTF_8))
        } finally { conn.disconnect() }
    }
}

/** Offline verifier for z0bridge access tokens addressed to this phone's NOOP. */
class Z0BridgeVerifier(private val trust: Z0BridgeTrust) {
    private val key: ECPublicKey = Z0Bridge.publicKey(trust.x, trust.y)
    private val b64u: Base64.Decoder = Base64.getUrlDecoder()

    fun verify(token: String, nowSec: Long): Z0BridgeCaller? = runCatching {
        if (!Z0Bridge.looksLikeJwt(token)) return null
        val (h, b, s) = token.split(".")
        val header = JSONObject(String(b64u.decode(h), Charsets.UTF_8))
        if (header.optString("alg") != "ES256" || header.optString("typ") != "at+jwt" || header.optString("kid") != trust.kid) return null
        val raw = b64u.decode(s)
        if (raw.size != 64) return null
        val sig = Signature.getInstance("SHA256withECDSA").apply { initVerify(key); update("$h.$b".toByteArray(Charsets.US_ASCII)) }
        if (!sig.verify(Z0Bridge.rawToDer(raw))) return null
        val claims = JSONObject(String(b64u.decode(b), Charsets.UTF_8))
        val exp = claims.getLong("exp"); val nbf = claims.getLong("nbf"); val iat = claims.getLong("iat")
        if (claims.getString("iss") != trust.issuer || claims.getString("aud") != trust.audience) return null
        if (nowSec >= exp || nowSec + Z0Bridge.SKEW_SEC < nbf || exp - iat > Z0Bridge.MAX_LIFETIME_SEC || iat > nowSec + Z0Bridge.SKEW_SEC) return null
        val scopes = claims.getString("scope").split(" ").mapNotNull { Z0Bridge.SCOPES[it] }.toSet()
        if (scopes.isEmpty()) return null
        Z0BridgeCaller(claims.getString("sub"), claims.getString("dev"), claims.getString("app"), scopes, exp)
    }.getOrNull()
}
