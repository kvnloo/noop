package com.noop.agent

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/**
 * Minimal HTTP/1.1 endpoint for MCP "Streamable HTTP" with JSON responses (POST /mcp). One request per
 * connection, bounded headers/body, short timeouts, two worker threads. Bound to the Tailscale address
 * only; any peer outside the tailnet ranges is dropped before a byte is read.
 */
class AgentHttpServer(
    private val bindAddress: InetAddress,
    private val port: Int,
    private val authorizer: AgentAuthorizer,
    private val mcp: AgentMcp,
    private val audit: AgentAudit,
    private val peerAllowed: (InetAddress) -> Boolean = AgentPolicy::isTailnet,
) {
    private var socket: ServerSocket? = null
    private var acceptThread: Thread? = null
    private var workers: ExecutorService? = null
    val boundPort: Int get() = socket?.localPort ?: -1

    @Synchronized
    fun start() {
        check(socket == null) { "already started" }
        val server = ServerSocket()
        server.reuseAddress = true
        server.bind(InetSocketAddress(bindAddress, port), 8)
        socket = server
        val pool = Executors.newFixedThreadPool(2) { r -> Thread(r, "noop-agent-http").apply { isDaemon = true } }
        workers = pool
        acceptThread = Thread({
            while (!server.isClosed) {
                val client = try { server.accept() } catch (e: SocketException) { break } catch (e: Exception) { continue }
                try { pool.execute { serve(client) } } catch (e: RejectedExecutionException) { runCatching { client.close() } }
            }
        }, "noop-agent-accept").apply { isDaemon = true; start() }
    }

    @Synchronized
    fun stop() {
        runCatching { socket?.close() }
        workers?.shutdownNow()
        socket = null; workers = null; acceptThread = null
    }

    private fun serve(client: Socket) {
        client.use { s ->
            val peer = s.inetAddress
            if (!peerAllowed(peer)) {
                audit.record("auth", null, "refused_peer_not_tailnet")
                return
            }
            s.soTimeout = AgentPolicy.SOCKET_TIMEOUT_MS
            val response = try {
                route(readRequest(s.getInputStream()), peer.hostAddress.orEmpty())
            } catch (e: HttpError) {
                Response(e.status, """{"error":"${e.message}"}""")
            } catch (e: Exception) {
                Response(400, """{"error":"bad request"}""")
            }
            val body = response.body.toByteArray(Charsets.UTF_8)
            val head = buildString {
                append("HTTP/1.1 ${response.status} ${REASONS[response.status] ?: "Status"}\r\n")
                append("Content-Type: application/json\r\n")
                append("Content-Length: ${body.size}\r\n")
                append("Cache-Control: no-store\r\n")
                if (response.status == 405) append("Allow: POST\r\n")
                append("Connection: close\r\n\r\n")
            }
            runCatching {
                s.getOutputStream().apply { write(head.toByteArray(Charsets.US_ASCII)); write(body); flush() }
            }
        }
    }

    private fun route(request: Request, peer: String): Response {
        if (request.path != AgentPolicy.MCP_PATH) throw HttpError(404, "not found")
        // Browsers send Origin; MCP clients do not. Refusing it blocks DNS-rebinding pages on the tailnet.
        if (request.headers.containsKey("origin")) {
            audit.record("auth", null, "refused_origin", mapOf("peer" to peer))
            throw HttpError(403, "origin not allowed")
        }
        val bearer = request.headers["authorization"]?.takeIf { it.startsWith("Bearer ") }?.removePrefix("Bearer ")?.trim()
        when (val auth = authorizer.authorize(bearer)) {
            is AgentAuth.Denied -> {
                audit.record("auth", null, "refused_${auth.reason}", mapOf("peer" to peer))
                throw HttpError(401, "unauthorized")
            }
            is AgentAuth.Allowed -> {
                if (request.method != "POST") throw HttpError(405, "method not allowed")
                val type = request.headers["content-type"].orEmpty().substringBefore(';').trim().lowercase()
                if (type != "application/json") throw HttpError(415, "content-type must be application/json")
                val reply = mcp.handle(request.body, AgentCaller(peer, auth.deviceShort, auth.tokenScopes, auth.via)) ?: return Response(202, "")
                return Response(200, reply)
            }
        }
    }

    internal data class Request(val method: String, val path: String, val headers: Map<String, String>, val body: String)
    private data class Response(val status: Int, val body: String)
    private class HttpError(val status: Int, message: String) : Exception(message)

    internal companion object {
        private val REASONS = mapOf(200 to "OK", 202 to "Accepted", 400 to "Bad Request", 401 to "Unauthorized", 403 to "Forbidden",
            404 to "Not Found", 405 to "Method Not Allowed", 411 to "Length Required", 413 to "Payload Too Large", 415 to "Unsupported Media Type")

        fun readRequest(input: InputStream): Request {
            val head = ByteArrayOutputStream()
            var matched = 0
            while (matched < 4) {
                val b = input.read()
                if (b < 0) throw HttpError(400, "truncated")
                head.write(b)
                if (head.size() > AgentPolicy.MAX_HEADER_BYTES) throw HttpError(413, "headers too large")
                matched = when {
                    (matched == 0 || matched == 2) && b == '\r'.code -> matched + 1
                    (matched == 1 || matched == 3) && b == '\n'.code -> matched + 1
                    b == '\r'.code -> 1
                    else -> 0
                }
            }
            val lines = head.toString(Charsets.ISO_8859_1.name()).split("\r\n").filter { it.isNotEmpty() }
            val parts = lines.firstOrNull()?.split(" ") ?: throw HttpError(400, "no request line")
            if (parts.size != 3 || !parts[2].startsWith("HTTP/1.")) throw HttpError(400, "bad request line")
            val headers = lines.drop(1).associate { line ->
                val i = line.indexOf(':'); if (i <= 0) throw HttpError(400, "bad header")
                line.substring(0, i).trim().lowercase() to line.substring(i + 1).trim()
            }
            if (headers.containsKey("transfer-encoding")) throw HttpError(411, "chunked bodies are not supported")
            val length = headers["content-length"]?.toIntOrNull() ?: 0
            if (length < 0 || length > AgentPolicy.MAX_BODY_BYTES) throw HttpError(413, "body too large")
            val body = ByteArray(length)
            var read = 0
            while (read < length) {
                val n = input.read(body, read, length - read)
                if (n < 0) throw HttpError(400, "truncated body")
                read += n
            }
            return Request(parts[0], parts[1].substringBefore('?'), headers, String(body, Charsets.UTF_8))
        }
    }
}
