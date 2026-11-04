package com.noop.ble.gesture

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import org.json.JSONObject
import java.net.URI
import java.net.URL
import java.net.URLDecoder
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Downstream-only, explicit one-way gesture export to the owner's paired broker. No BLE changes. */
class OuraGestureBroker(context: Context) {
    private val file = java.io.File(context.noBackupFilesDir, "oura-gesture-broker")
    private var device: String? = null
    val configured get() = file.exists()
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return (store.getKey(ALIAS, null) as? SecretKey) ?: KeyGenerator.getInstance("AES", "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
            generateKey()
        }
    }
    fun pair(link: String) {
        val pair = parsePairing(link)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val raw = JSONObject().put("url", pair.first).put("token", pair.second).toString().toByteArray()
        val bytes = cipher.iv + cipher.doFinal(raw)
        val pending = java.io.File(file.parentFile, file.name + ".new")
        pending.outputStream().use { it.write(bytes) }
        pending.setReadable(false, false); pending.setWritable(false, false)
        pending.setReadable(true, true); pending.setWritable(true, true)
        check(pending.renameTo(file))
        device = null
    }
    fun forget() { file.delete(); device = null }
    private fun credentials(): JSONObject {
        val bytes = file.readBytes()
        require(bytes.size in 29..8192)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12))) }
        return JSONObject(String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8))
    }
    private fun request(path: String, body: JSONObject? = null): JSONObject {
        val pair = credentials()
        val conn = URL(pair.getString("url") + path).openConnection() as javax.net.ssl.HttpsURLConnection
        conn.instanceFollowRedirects = false
        conn.connectTimeout = 1500; conn.readTimeout = 1500
        conn.setRequestProperty("Authorization", "Bearer " + pair.getString("token"))
        conn.setRequestProperty("Content-Type", "application/json")
        try {
            if (body != null) {
                conn.requestMethod = "POST"; conn.doOutput = true
                conn.outputStream.use { it.write(body.toString().toByteArray()) }
            }
            check(conn.responseCode == 200)
            val raw = conn.inputStream.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    require(out.size() + count <= 32768)
                    out.write(buffer, 0, count)
                }
                out.toByteArray()
            }
            require(raw.size <= 32768)
            return JSONObject(String(raw, Charsets.UTF_8))
        } finally { conn.disconnect() }
    }
    fun claim(seconds: Int) {
        require(seconds in 1..300)
        val id = request("/v1/devices/self").getString("device_id")
        require(id.matches(Regex("[0-9a-f]{32}")))
        check(request("/v1/gesture/listener", JSONObject().put("source_device", id).put("enabled", true).put("ttl_seconds", seconds)).getBoolean("ok"))
        device = id
    }
    fun release() {
        val id = device ?: return
        device = null
        request("/v1/gesture/listener", JSONObject().put("source_device", id).put("enabled", false).put("ttl_seconds", 1))
    }
    fun send(gesture: String): Boolean {
        require(gesture == "double_tap" || gesture == "triple_tap")
        val id = checkNotNull(device)
        return request("/v1/gesture", JSONObject().put("gesture", gesture).put("source_device", id)
            .put("ts", System.currentTimeMillis() / 1000.0).put("nonce", UUID.randomUUID().toString())).optBoolean("ok")
    }
    companion object {
        private const val ALIAS = "noop-oura-gesture-broker"
        fun parsePairing(link: String): Pair<String, String> {
            require(link.length <= 8192)
            val uri = URI(link)
            require(uri.scheme == "z0live" && uri.rawAuthority == "pair" && uri.rawPath.isEmpty() && uri.rawFragment == null)
            val entries = checkNotNull(uri.rawQuery).split("&").map {
                val parts = it.split("=", limit = 2); require(parts.size == 2)
                parts[0] to URLDecoder.decode(parts[1], "UTF-8")
            }
            require(entries.size == 2 && entries.map { it.first }.toSet() == setOf("url", "token"))
            val args = entries.toMap(); val origin = URI(args.getValue("url"))
            require(origin.scheme == "https" && !origin.host.isNullOrEmpty() && origin.rawUserInfo == null && origin.rawQuery == null && origin.rawFragment == null && origin.rawPath in listOf("", "/"))
            val token = args.getValue("token"); require(token.matches(Regex("[A-Za-z0-9_-]{20,200}")))
            return origin.toASCIIString().trimEnd('/') to token
        }
    }
}
