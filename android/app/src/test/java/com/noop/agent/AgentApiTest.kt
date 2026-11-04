package com.noop.agent

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.InetAddress
import java.sql.Connection
import java.sql.DriverManager
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Behavioural tests for the Experimental Hermes agent API: real SQL against synthetic rows (sqlite-jdbc),
 * real MCP JSON-RPC, real HTTP over a loopback socket. No personal data; every value here is invented.
 */
class AgentApiTest {
    private lateinit var db: Connection
    private lateinit var dir: File
    private val zone: ZoneId = ZoneOffset.UTC
    // 2026-03-15 12:00 UTC
    private val now = LocalDate.of(2026, 3, 15).atTime(12, 0).toInstant(ZoneOffset.UTC).toEpochMilli()

    private val sql = AgentSql { text, args ->
        db.prepareStatement(text).use { st ->
            args.forEachIndexed { i, a -> st.setObject(i + 1, a) }
            st.executeQuery().use { rs ->
                val md = rs.metaData
                buildList {
                    while (rs.next()) add((1..md.columnCount).associate { md.getColumnLabel(it) to rs.getObject(it) })
                }
            }
        }
    }

    @Before
    fun setUp() {
        dir = createTempDir("agent-test")
        db = DriverManager.getConnection("jdbc:sqlite::memory:")
        db.createStatement().use { s ->
            s.executeUpdate("CREATE TABLE dailyMetric (deviceId TEXT, day TEXT, totalSleepMin REAL, efficiency REAL, deepMin REAL, remMin REAL, restingHr INTEGER, avgHrv REAL, recovery REAL, strain REAL, respRateBpm REAL, spo2Pct REAL, skinTempDevC REAL, steps INTEGER, PRIMARY KEY(deviceId, day))")
            s.executeUpdate("CREATE TABLE sleepSession (deviceId TEXT, startTs INTEGER, endTs INTEGER, startTsAdjusted INTEGER, efficiency REAL, stagesJSON TEXT)")
            s.executeUpdate("CREATE TABLE workout (deviceId TEXT, startTs INTEGER, endTs INTEGER, sport TEXT, durationS REAL, avgHr INTEGER, strain REAL, notes TEXT, routePolyline TEXT)")
            s.executeUpdate("CREATE TABLE hrSample (deviceId TEXT, ts INTEGER, bpm INTEGER)")
        }
        for (d in 0 until 10) {
            val day = LocalDate.of(2026, 3, 15).minusDays(d.toLong()).toString()
            // Strap-imported row (sparse) and NOOP-computed sibling (complete) for the same day.
            exec("INSERT INTO dailyMetric (deviceId, day, recovery) VALUES ('oura-AA:BB:CC:DD:EE:FF', '$day', 10)")
            exec("INSERT INTO dailyMetric VALUES ('oura-AA:BB:CC:DD:EE:FF-noop', '$day', ${400 + d}, 90, 60, 90, ${50 + d}, ${40 + d}, ${60 + d}, 8.5, 14.2, 97.0, 0.1, ${8000 + d})")
            val wake = LocalDate.of(2026, 3, 15).minusDays(d.toLong()).atTime(7, 0).toEpochSecond(ZoneOffset.UTC)
            val bed = wake - 7 * 3600
            exec("INSERT INTO sleepSession VALUES ('oura-x', $bed, $wake, NULL, 91, '[secret stages]')")
            exec("INSERT INTO sleepSession VALUES ('oura-x-noop', ${bed + 600}, ${wake - 600}, NULL, 88, NULL)") // duplicate night
        }
        val nap = LocalDate.of(2026, 3, 14).atTime(15, 0).toEpochSecond(ZoneOffset.UTC)
        exec("INSERT INTO sleepSession VALUES ('oura-x', $nap, ${nap + 1800}, NULL, 80, NULL)")
        val run = LocalDate.of(2026, 3, 13).atTime(18, 0).toEpochSecond(ZoneOffset.UTC)
        exec("INSERT INTO workout VALUES ('whoop-SERIAL123', $run, ${run + 1800}, 'Running', 1800, 150, 9.1, 'ran with Jane Doe', 'encodedpolyline')")
        exec("INSERT INTO workout VALUES ('apple-health', ${run + 20}, ${run + 1820}, 'Running', 1800, 151, NULL, NULL, NULL)") // same session
        exec("INSERT INTO hrSample VALUES ('oura-x', ${now / 1000 - 3600}, 61)")
    }

    @After
    fun tearDown() { db.close(); dir.deleteRecursively() }

    private fun exec(s: String) { db.createStatement().use { it.executeUpdate(s) } }
    private fun reader() = AgentHealthReader(sql, { now }, zone)

    // ---- policy ---------------------------------------------------------------------------------

    @Test
    fun tailnetRangesOnly() {
        assertTrue(AgentPolicy.isTailnet(InetAddress.getByName("100.64.0.1")))
        assertTrue(AgentPolicy.isTailnet(InetAddress.getByName("100.117.226.39")))
        assertFalse(AgentPolicy.isTailnet(InetAddress.getByName("100.128.0.1")))
        assertFalse(AgentPolicy.isTailnet(InetAddress.getByName("192.168.1.30")))
        assertFalse(AgentPolicy.isTailnet(InetAddress.getByName("127.0.0.1")))
        assertTrue(AgentPolicy.isTailnet(InetAddress.getByName("fd7a:115c:a1e0::dd3b:8a65")))
        assertFalse(AgentPolicy.isTailnet(InetAddress.getByName("fd00::1")))
    }

    @Test
    fun tokenDigestAndDayBounds() {
        val sha = AgentPolicy.sha256Hex("tok_abcdefghijklmnopqrstuvwxyz")
        assertTrue(AgentPolicy.tokenMatches("tok_abcdefghijklmnopqrstuvwxyz", sha))
        assertFalse(AgentPolicy.tokenMatches("tok_abcdefghijklmnopqrstuvwxyZ", sha))
        assertFalse(AgentPolicy.tokenMatches(null, sha))
        assertEquals(31, AgentPolicy.clampDays(4000, 7, 31))
        assertEquals(1, AgentPolicy.clampDays(-5, 7, 31))
        assertEquals(7, AgentPolicy.clampDays(null, 7, 31))
    }

    // ---- minimized reads ------------------------------------------------------------------------

    @Test
    fun snapshotSummaryHasAggregatesButNoRowsOrDeviceIds() {
        val out = reader().healthSnapshot(7, detail = false)
        val text = out.toString()
        assertEquals("summary", out.getString("granularity"))
        assertFalse(out.has("daily"))
        assertEquals(7, out.getInt("daysWithData"))
        val hrv = out.getJSONObject("metrics").getJSONObject("hrv")
        assertEquals(7, hrv.getInt("n"))
        assertEquals(40.0, hrv.getDouble("latest"), 0.0) // the complete NOOP-computed row won over the sparse import
        assertEquals(43.0, hrv.getDouble("mean"), 0.0)
        assertFalse("raw device ids must not leave the phone", text.contains("AA:BB") || text.contains("oura-"))
        assertTrue(out.getJSONArray("sources").toString().contains("oura (NOOP-computed)"))
        assertTrue(out.getString("note").contains("not medical advice"))
    }

    @Test
    fun snapshotDetailListsDays() {
        val out = reader().healthSnapshot(5, detail = true)
        assertEquals(5, out.getJSONArray("daily").length())
        assertEquals("2026-03-15", out.getJSONArray("daily").getJSONObject(4).getString("day"))
    }

    @Test
    fun metricSeriesSummaryWithholdsPoints() {
        val summary = reader().metricSeries("rhr", 7, detail = false)
        assertFalse(summary.has("points"))
        assertEquals(0, summary.getInt("returned"))
        val detail = reader().metricSeries("rhr", 7, detail = true)
        assertEquals(7, detail.getJSONArray("points").length())
    }

    @Test(expected = AgentToolError::class)
    fun metricSeriesRejectsUnknownKey() { reader().metricSeries("journal", 7, false) }

    @Test
    fun sleepDedupesOverlappingCopiesAndSeparatesNaps() {
        val out = reader().sleepSummary(7, detail = false)
        assertEquals(7, out.getInt("nights"))
        assertEquals(1, out.getInt("naps"))
        assertEquals(420.0, out.getJSONObject("durationMin").getDouble("mean"), 0.0)
        assertEquals("00:00", out.getString("medianBedtime"))
        assertEquals("07:00", out.getString("medianWake"))
        assertFalse(out.toString().contains("secret stages"))
        assertFalse(out.has("sessions"))
    }

    @Test
    fun workoutsNeverCarryNotesOrRoutes() {
        val out = reader().workoutSummary(7, detail = true)
        val text = out.toString()
        assertEquals(1, out.getInt("count"))
        assertEquals(30.0, out.getJSONObject("bySport").getJSONObject("running").getDouble("minutes"), 0.0)
        assertFalse(text.contains("Jane") || text.contains("polyline") || text.contains("SERIAL"))
    }

    @Test
    fun freshnessReportsAgesNotTimestampsOfRows() {
        val out = reader().dataFreshness()
        assertEquals(1.0, out.getJSONObject("latest").getDouble("heartRateSampleAgeHours"), 0.0)
        assertFalse(out.getBoolean("stale"))
        assertEquals(10, out.getInt("dailyCoverageLast30Days"))
    }

    // ---- MCP + scopes + audit -------------------------------------------------------------------

    private class FakeActions : AgentActions {
        val calls = mutableListOf<String>()
        override fun openScreen(screen: String) = JSONObject().put("screen", screen).put("mode", "in_app").put("landed", true).also { calls += "open:$screen" }
        override fun showCoachNote(title: String, body: String) = JSONObject().put("posted", true).also { calls += "note:${body.length}" }
    }

    private fun rpc(method: String, params: JSONObject? = null, id: Int = 1) =
        JSONObject().put("jsonrpc", "2.0").put("id", id).put("method", method).also { if (params != null) it.put("params", params) }.toString()

    private fun call(mcp: AgentMcp, tool: String, args: JSONObject = JSONObject()): JSONObject =
        JSONObject(mcp.handle(rpc("tools/call", JSONObject().put("name", tool).put("arguments", args)), AgentCaller("100.113.138.100", "abc123"))!!).getJSONObject("result")

    @Test
    fun mcpHandshakeAndToolCatalog() {
        val audit = AgentAudit(File(dir, "audit.jsonl")) { now }
        val mcp = AgentMcp(reader(), FakeActions(), audit, { null }, { now })
        val init = JSONObject(mcp.handle(rpc("initialize", JSONObject().put("protocolVersion", "2025-03-26")), AgentCaller("p", "d"))!!)
        assertEquals("2025-03-26", init.getJSONObject("result").getString("protocolVersion"))
        assertNull(mcp.handle("""{"jsonrpc":"2.0","method":"notifications/initialized"}""", AgentCaller("p", "d")))
        val tools = JSONObject(mcp.handle(rpc("tools/list"), AgentCaller("p", "d"))!!).getJSONObject("result").getJSONArray("tools")
        val names = (0 until tools.length()).map { tools.getJSONObject(it).getString("name") }
        assertEquals(listOf("health_snapshot", "metric_series", "data_freshness", "sleep_summary", "workout_summary", "open_screen", "show_coach_note"), names)
        assertEquals(-32601, JSONObject(mcp.handle(rpc("resources/write"), AgentCaller("p", "d"))!!).getJSONObject("error").getInt("code"))
    }

    @Test
    fun noGrantRefusesAndAudits() {
        val audit = AgentAudit(File(dir, "audit.jsonl")) { now }
        val mcp = AgentMcp(reader(), FakeActions(), audit, { null }, { now })
        val r = call(mcp, "health_snapshot")
        assertTrue(r.getBoolean("isError"))
        assertEquals("no_grant", r.getJSONObject("structuredContent").getString("error"))
        assertEquals("refused_no_grant", audit.recent().first().getString("outcome"))
    }

    @Test
    fun expiredGrantRefuses() {
        val audit = AgentAudit(File(dir, "audit.jsonl")) { now }
        val g = AgentGrant(now - 120_000, now - 1, setOf(AgentScope.READ_SUMMARY))
        assertTrue(call(AgentMcp(reader(), FakeActions(), audit, { g }, { now }), "data_freshness").getBoolean("isError"))
    }

    @Test
    fun scopesGateDetailAndActions() {
        val audit = AgentAudit(File(dir, "audit.jsonl")) { now }
        val actions = FakeActions()
        var grant = AgentGrant(now - 1, now + 60_000, setOf(AgentScope.READ_SUMMARY))
        val mcp = AgentMcp(reader(), actions, audit, { grant }, { now })

        assertFalse(call(mcp, "health_snapshot").getBoolean("isError"))
        val detail = call(mcp, "health_snapshot", JSONObject().put("granularity", "daily"))
        assertEquals("scope_required", detail.getJSONObject("structuredContent").getString("error"))
        val act = call(mcp, "open_screen", JSONObject().put("screen", "sleep"))
        assertEquals("scope_required", act.getJSONObject("structuredContent").getString("error"))
        assertTrue(actions.calls.isEmpty())

        grant = grant.copy(scopes = setOf(AgentScope.READ_SUMMARY, AgentScope.ACTIONS))
        assertEquals("invalid_params", call(mcp, "open_screen", JSONObject().put("screen", "settings")).getJSONObject("structuredContent").getString("error"))
        assertTrue(call(mcp, "open_screen", JSONObject().put("screen", "sleep")).getJSONObject("structuredContent").getBoolean("landed"))
        assertFalse(call(mcp, "show_coach_note", JSONObject().put("title", "Tonight").put("body", "Aim for lights out by 23:00.")).getBoolean("isError"))
        assertEquals("invalid_params", call(mcp, "show_coach_note", JSONObject().put("title", "x").put("body", "y".repeat(601))).getJSONObject("structuredContent").getString("error"))
        assertEquals(listOf("open:sleep", "note:28"), actions.calls)

        val log = audit.recent(20)
        val noteEntry = log.first { it.optString("tool") == "show_coach_note" && it.getString("outcome") == "ok" }
        assertEquals(28, noteEntry.getInt("chars"))
        assertFalse("the audit log never stores note text or values", File(dir, "audit.jsonl").readText().contains("lights out"))
        assertTrue(log.any { it.optString("tool") == "open_screen" && it.getString("kind") == "action" && it.getBoolean("landed") })
    }

    @Test
    fun auditIsBounded() {
        val audit = AgentAudit(File(dir, "audit.jsonl")) { now }
        repeat(AgentAudit.MAX_LINES + 25) { audit.record("read", "data_freshness", "ok") }
        assertEquals(AgentAudit.MAX_LINES, File(dir, "audit.jsonl").readLines().count { it.isNotBlank() })
    }

    @Test
    fun auditKeepsEveryEntryAcrossInstancesAndThreads() {
        val file = File(dir, "audit.jsonl")
        val threads = (0 until 4).map { i ->
            Thread { val a = AgentAudit(file) { now }; repeat(25) { a.record("read", "t$i", "ok") } }
        }
        threads.forEach { it.start() }; threads.forEach { it.join() }
        assertEquals(100, file.readLines().count { it.isNotBlank() })
    }

    // ---- pairing + broker revocation ------------------------------------------------------------

    private val token = "Tok_abcdefghijklmnopqrstuvwxyz0123456789"
    private val device = "0123456789abcdef0123456789abcdef"

    @Test
    fun pairingLinkFormatsAndStoreKeepsOnlyDigest() {
        val link = "z0live://pair?url=https%3A%2F%2Fhost.example.ts.net%3A8791&token=$token"
        assertEquals("https://host.example.ts.net:8791" to token, AgentPairing.parseLink(link))
        assertEquals("https://host.example.ts.net:8791" to token, AgentPairing.parseLink(link.replace("z0live://", "noop-agent://")))
        val store = AgentPairingStore(File(dir, "pairing.json"))
        store.save(AgentPairing("https://host.example.ts.net:8791", AgentPolicy.sha256Hex(token), device, now))
        assertFalse(File(dir, "pairing.json").readText().contains(token))
        assertEquals(device, store.load()!!.deviceId)
    }

    @Test
    fun authorizerCachesBrokerCheckAndFailsClosed() {
        var clock = now
        var brokerAnswer: String? = device
        var brokerCalls = 0
        val pairing = AgentPairing("https://b.ts.net", AgentPolicy.sha256Hex(token), device, now)
        val auth = AgentAuthorizer({ pairing }, { _, _ -> brokerCalls++; brokerAnswer }, { clock })
        assertTrue(auth.authorize(token) is AgentAuth.Allowed)
        assertTrue(auth.authorize(token) is AgentAuth.Allowed)
        assertEquals(1, brokerCalls)
        assertEquals(AgentAuth.Denied("bad_token"), auth.authorize("Tok_wrongwrongwrongwrongwrongwrong"))
        brokerAnswer = null // pairing revoked on the broker / broker unreachable
        clock += AgentPolicy.VERIFY_TTL_MS + 1
        assertEquals(AgentAuth.Denied("broker_unverified"), auth.authorize(token))
        brokerAnswer = "ffffffffffffffffffffffffffffffff"
        assertEquals(AgentAuth.Denied("broker_device_mismatch"), auth.authorize(token))
        assertEquals(AgentAuth.Denied("not_paired"), AgentAuthorizer({ null }, { _, _ -> device }).authorize(token))
    }

    // ---- HTTP transport over a real socket ------------------------------------------------------

    private fun http(port: Int, request: String): String =
        java.net.Socket(InetAddress.getLoopbackAddress(), port).use { s ->
            s.soTimeout = 5000
            s.getOutputStream().write(request.toByteArray()); s.getOutputStream().flush()
            s.getInputStream().readBytes().toString(Charsets.UTF_8)
        }

    private fun post(body: String, auth: String? = token, extra: String = ""): String =
        "POST /mcp HTTP/1.1\r\nHost: x\r\nContent-Type: application/json\r\n" +
            (if (auth != null) "Authorization: Bearer $auth\r\n" else "") + extra +
            "Content-Length: ${body.toByteArray().size}\r\n\r\n$body"

    @Test
    fun httpTransportEnforcesAuthOriginMethodAndPeer() {
        val audit = AgentAudit(File(dir, "audit.jsonl")) { now }
        val pairing = AgentPairing("https://b.ts.net", AgentPolicy.sha256Hex(token), device, now)
        val grant = AgentGrant(now - 1, now + 60_000, setOf(AgentScope.READ_SUMMARY))
        val mcp = AgentMcp(reader(), FakeActions(), audit, { grant }, { now })
        val server = AgentHttpServer(InetAddress.getLoopbackAddress(), 0, AgentAuthorizer({ pairing }, { _, _ -> device }, { now }), mcp, audit, peerAllowed = { true })
        server.start()
        try {
            val port = server.boundPort
            assertTrue(http(port, post(rpc("tools/list"), auth = null)).startsWith("HTTP/1.1 401"))
            assertTrue(http(port, post(rpc("tools/list"), extra = "Origin: http://evil.example\r\n")).startsWith("HTTP/1.1 403"))
            assertTrue(http(port, "GET /mcp HTTP/1.1\r\nAuthorization: Bearer $token\r\n\r\n").startsWith("HTTP/1.1 405"))
            assertTrue(http(port, post("""{"jsonrpc":"2.0","method":"notifications/initialized"}""")).startsWith("HTTP/1.1 202"))
            val ok = http(port, post(rpc("tools/call", JSONObject().put("name", "health_snapshot").put("arguments", JSONObject()))))
            assertTrue(ok, ok.startsWith("HTTP/1.1 200"))
            val result = JSONObject(ok.substringAfter("\r\n\r\n")).getJSONObject("result")
            assertFalse(result.getBoolean("isError"))
            assertNotNull(result.getJSONObject("structuredContent").getJSONObject("metrics").optJSONObject("rhr"))
            assertTrue(http(port, post("x".repeat(10), extra = "").let { it }).startsWith("HTTP/1.1 200")) // parse error is a JSON-RPC error body
        } finally { server.stop() }

        val closed = AgentHttpServer(InetAddress.getLoopbackAddress(), 0, AgentAuthorizer({ pairing }, { _, _ -> device }, { now }), mcp, audit, peerAllowed = AgentPolicy::isTailnet)
        closed.start()
        try {
            assertEquals("loopback is not a tailnet peer: dropped without a response", "", http(closed.boundPort, post(rpc("tools/list"))))
            assertEquals("refused_peer_not_tailnet", audit.recent().first().getString("outcome"))
        } finally { closed.stop() }
    }
}
