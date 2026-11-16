package com.noop.agent

import org.json.JSONArray
import org.json.JSONObject

/** The two in-app actions an agent may request. Both are navigation/notification only. */
interface AgentActions {
    /** Navigate NOOP to [screen] (one of [AgentPolicy.SCREENS]). Returns an honest landing receipt. */
    fun openScreen(screen: String): JSONObject

    /** Post a local "Hermes coach" notification. Nothing is written to the health database. */
    fun showCoachNote(title: String, body: String): JSONObject
}

/** Facts about the caller the HTTP layer has already verified. */
data class AgentCaller(val peer: String, val deviceShort: String)

/**
 * MCP (JSON-RPC 2.0) handler for the phone's tool surface. Pure: transport, auth and Android live
 * elsewhere, so the protocol and the scope gates are unit-tested on the JVM.
 */
class AgentMcp(
    private val reader: AgentHealthReader,
    private val actions: AgentActions,
    private val audit: AgentAudit,
    private val grant: () -> AgentGrant?,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    /** Returns the JSON-RPC response, or null for a notification (HTTP 202, no body). */
    fun handle(body: String, caller: AgentCaller): String? {
        val request = try {
            JSONObject(body)
        } catch (e: Exception) {
            return error(JSONObject.NULL, -32700, "parse error").toString()
        }
        val id = request.opt("id")
        val method = request.optString("method")
        if (request.optString("jsonrpc") != "2.0" || method.isEmpty()) return error(id ?: JSONObject.NULL, -32600, "invalid request").toString()
        if (id == null) return null // notifications/initialized, notifications/cancelled, ...
        val result: JSONObject = when (method) {
            "initialize" -> initialize(request.optJSONObject("params"))
            "ping" -> JSONObject()
            "tools/list" -> JSONObject().put("tools", toolList())
            "tools/call" -> return call(id, request.optJSONObject("params") ?: JSONObject(), caller).toString()
            else -> return error(id, -32601, "method not found").toString()
        }
        return JSONObject().put("jsonrpc", "2.0").put("id", id).put("result", result).toString()
    }

    private fun initialize(params: JSONObject?): JSONObject {
        val requested = params?.optString("protocolVersion").orEmpty()
        return JSONObject()
            .put("protocolVersion", if (requested in PROTOCOLS) requested else PROTOCOLS.first())
            .put("capabilities", JSONObject().put("tools", JSONObject().put("listChanged", false)))
            .put("serverInfo", JSONObject().put("name", "noop-agent").put("version", VERSION))
            .put("instructions", INSTRUCTIONS)
    }

    private fun call(id: Any, params: JSONObject, caller: AgentCaller): JSONObject {
        val name = params.optString("name")
        val args = params.optJSONObject("arguments") ?: JSONObject()
        val g = grant()
        val audited = mapOf("peer" to caller.peer, "device" to caller.deviceShort, "args" to JSONArray(args.keys().asSequence().toList().sorted()))
        if (g == null || !g.active(nowMs())) {
            audit.record("read", name, "refused_no_grant", audited)
            return toolResult(id, JSONObject().put("error", "no_grant").put("message", "The owner has not granted access right now."), true)
        }
        return try {
            val tool = TOOLS[name] ?: throw AgentToolError("unknown tool", "unknown_tool")
            if (!g.allows(tool.scope)) throw AgentToolError("this grant does not include ${tool.scope.wire}", "scope_required")
            val detail = args.optString("granularity") in DETAIL_GRANULARITY
            if (detail && !g.allows(AgentScope.READ_DETAIL)) throw AgentToolError("per-day values need the read_detail scope; ask the owner or use the summary", "scope_required")
            val days = { default: Int -> AgentPolicy.clampDays(args.opt("days").takeUnless { it == JSONObject.NULL }, default, if (detail) AgentPolicy.MAX_DETAIL_DAYS else AgentPolicy.MAX_SUMMARY_DAYS) }
            val payload = when (name) {
                "health_snapshot" -> reader.healthSnapshot(days(7), detail)
                "metric_series" -> reader.metricSeries(args.optString("key"), days(14), detail)
                "data_freshness" -> reader.dataFreshness()
                "sleep_summary" -> reader.sleepSummary(days(7), detail)
                "workout_summary" -> reader.workoutSummary(days(14), detail)
                "open_screen" -> {
                    val screen = args.optString("screen")
                    if (screen !in AgentPolicy.SCREENS) throw AgentToolError("screen must be one of ${AgentPolicy.SCREENS.joinToString()}")
                    actions.openScreen(screen)
                }
                "show_coach_note" -> {
                    val title = args.optString("title").trim()
                    val text = args.optString("body").trim()
                    if (title.isEmpty() || title.length > AgentPolicy.NOTE_TITLE_MAX) throw AgentToolError("title must be 1..${AgentPolicy.NOTE_TITLE_MAX} characters")
                    if (text.isEmpty() || text.length > AgentPolicy.NOTE_BODY_MAX) throw AgentToolError("body must be 1..${AgentPolicy.NOTE_BODY_MAX} characters")
                    if (text.any { it.isISOControl() && it != '\n' } || title.any { it.isISOControl() }) throw AgentToolError("control characters are not allowed")
                    actions.showCoachNote(title, text)
                }
                else -> throw AgentToolError("unknown tool", "unknown_tool")
            }
            val text = payload.toString()
            if (text.length > AgentPolicy.MAX_RESPONSE_BYTES) throw AgentToolError("result too large; ask for fewer days", "too_large")
            val extra = mutableMapOf<String, Any?>("granularity" to if (detail) "detail" else "summary", "bytes" to text.length)
            if (name == "open_screen") { extra["screen"] = args.optString("screen"); extra["landed"] = payload.opt("landed") }
            if (name == "show_coach_note") { extra["chars"] = args.optString("body").length; extra["posted"] = payload.opt("posted") }
            audit.record(if (tool.scope == AgentScope.ACTIONS) "action" else "read", name, "ok", audited + extra)
            toolResult(id, payload, false)
        } catch (e: AgentToolError) {
            audit.record(if (TOOLS[name]?.scope == AgentScope.ACTIONS) "action" else "read", name, "refused_${e.code}", audited)
            toolResult(id, JSONObject().put("error", e.code).put("message", e.message), true)
        } catch (e: Exception) {
            audit.record("read", name, "failed", audited + ("error" to e.javaClass.simpleName))
            toolResult(id, JSONObject().put("error", "internal").put("message", "NOOP could not answer (${e.javaClass.simpleName})."), true)
        }
    }

    private fun toolResult(id: Any, payload: JSONObject, isError: Boolean): JSONObject =
        JSONObject().put("jsonrpc", "2.0").put("id", id).put(
            "result",
            JSONObject()
                .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", payload.toString())))
                .put("structuredContent", payload)
                .put("isError", isError),
        )

    private fun error(id: Any, code: Int, message: String) =
        JSONObject().put("jsonrpc", "2.0").put("id", id).put("error", JSONObject().put("code", code).put("message", message))

    private fun toolList(): JSONArray = JSONArray(TOOLS.map { (name, tool) ->
        JSONObject().put("name", name).put("description", tool.description).put("inputSchema", tool.schema())
            .put("annotations", JSONObject().put("readOnlyHint", tool.scope != AgentScope.ACTIONS).put("destructiveHint", false).put("idempotentHint", tool.scope != AgentScope.ACTIONS).put("openWorldHint", false))
    })

    private class Tool(val scope: AgentScope, val description: String, val props: JSONObject = JSONObject(), val required: List<String> = emptyList()) {
        fun schema(): JSONObject = JSONObject().put("type", "object").put("properties", props).put("required", JSONArray(required)).put("additionalProperties", false)
    }

    companion object {
        const val VERSION = "0.1.0"
        val PROTOCOLS = listOf("2025-06-18", "2025-03-26", "2024-11-05")
        private val DETAIL_GRANULARITY = setOf("daily", "detail", "nightly", "per_workout")
        const val INSTRUCTIONS = "NOOP on the owner's phone. Bounded, read-only wearable aggregates (default summary; " +
            "per-day values only with the read_detail scope) and two safe actions (open a NOOP screen, post a coach note). " +
            "Every call is logged on the phone. Values are wearable estimates: never diagnose; cite the numbers you used; " +
            "say when data is stale or sparse."

        private fun days(default: Int) = JSONObject().put("type", "integer").put("minimum", 1).put("maximum", AgentPolicy.MAX_SUMMARY_DAYS).put("default", default)
        private fun granularity() = JSONObject().put("type", "string").put("enum", JSONArray(listOf("summary", "daily"))).put("default", "summary")

        private val TOOLS: Map<String, Tool> = linkedMapOf(
            "health_snapshot" to Tool(AgentScope.READ_SUMMARY, "Recovery, HRV, resting HR, sleep, strain, respiration, SpO2, skin temperature and steps over the last N days: mean/min/max/latest per metric, sources by kind and freshness.",
                JSONObject().put("days", days(7)).put("granularity", granularity())),
            "metric_series" to Tool(AgentScope.READ_SUMMARY, "One daily metric over N days. Summary stats by default; per-day points with granularity=daily (needs read_detail).",
                JSONObject().put("key", JSONObject().put("type", "string").put("enum", JSONArray(AgentHealthReader.METRICS.keys.toList()))).put("days", days(14)).put("granularity", granularity()), listOf("key")),
            "data_freshness" to Tool(AgentScope.READ_SUMMARY, "How old the newest heart-rate sample, sleep session, workout and daily metric are, and daily coverage over 30 days."),
            "sleep_summary" to Tool(AgentScope.READ_SUMMARY, "Main-sleep nights over N days: mean/min/max duration, efficiency, median bedtime and wake, bedtime spread and nap count. granularity=daily lists nights (needs read_detail).",
                JSONObject().put("days", days(7)).put("granularity", granularity())),
            "workout_summary" to Tool(AgentScope.READ_SUMMARY, "Workouts over N days: count, minutes, per-sport totals, mean strain. granularity=daily lists workouts without notes or routes (needs read_detail).",
                JSONObject().put("days", days(14)).put("granularity", granularity())),
            "open_screen" to Tool(AgentScope.ACTIONS, "Navigate NOOP to a screen. If NOOP is not on screen, the owner gets a notification to tap instead. Navigation only; nothing is started or changed.",
                JSONObject().put("screen", JSONObject().put("type", "string").put("enum", JSONArray(AgentPolicy.SCREENS.toList()))), listOf("screen")),
            "show_coach_note" to Tool(AgentScope.ACTIONS, "Post a short local notification from Hermes coach on the phone. Not stored in NOOP's database.",
                JSONObject().put("title", JSONObject().put("type", "string").put("maxLength", AgentPolicy.NOTE_TITLE_MAX)).put("body", JSONObject().put("type", "string").put("maxLength", AgentPolicy.NOTE_BODY_MAX)), listOf("title", "body")),
        )
    }
}
