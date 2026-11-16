package com.noop.agent

import org.json.JSONObject
import java.io.File

/**
 * On-phone audit trail of every agent request, refusal and grant change. Bounded to [MAX_LINES].
 * Entries record WHAT was asked and the outcome, never the values returned or the text of a note.
 */
class AgentAudit(private val file: File, private val nowMs: () -> Long = System::currentTimeMillis) {
    // The service, its HTTP workers and the settings screen each hold their own instance, so the
    // read-modify-write must be serialized process-wide or concurrent entries are lost.
    fun record(kind: String, tool: String? = null, outcome: String, detail: Map<String, Any?> = emptyMap()) {
        synchronized(LOCK) { append(kind, tool, outcome, detail) }
    }

    private fun append(kind: String, tool: String?, outcome: String, detail: Map<String, Any?>) {
        val entry = JSONObject().put("ts", nowMs()).put("kind", kind).put("outcome", outcome)
        if (tool != null) entry.put("tool", tool)
        detail.forEach { (k, v) -> entry.put(k, v ?: JSONObject.NULL) }
        val lines = (if (file.exists()) file.readLines() else emptyList()) + entry.toString()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(lines.takeLast(MAX_LINES).joinToString("\n", postfix = "\n"))
        tmp.setReadable(false, false); tmp.setReadable(true, true)
        check(tmp.renameTo(file))
    }

    fun recent(limit: Int = 50): List<JSONObject> = synchronized(LOCK) {
        if (!file.exists()) emptyList()
        else file.readLines().filter { it.isNotBlank() }.takeLast(limit).mapNotNull { runCatching { JSONObject(it) }.getOrNull() }.reversed()
    }

    fun clear() { synchronized(LOCK) { file.delete() } }

    companion object {
        const val MAX_LINES = 300
        private val LOCK = Any()
    }
}
