package com.noop.agent

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.atomic.AtomicLong

/**
 * Process-local hand-off from an agent `open_screen` (or its notification tap) to the composed NOOP
 * shell. AppRoot collects [pending], navigates, then calls [landed] so the agent gets a real receipt
 * instead of "dispatched".
 */
object AgentNav {
    data class Request(val id: Long, val screen: String)

    private val ids = AtomicLong(0)
    private val state = MutableStateFlow<Request?>(null)
    val pending: StateFlow<Request?> = state
    @Volatile private var lastLanded: Request? = null

    const val EXTRA_SCREEN = "com.noop.agent.SCREEN"

    fun currentId(): Long = ids.get()

    fun request(screen: String): Request {
        require(screen in AgentPolicy.SCREENS)
        val r = Request(ids.incrementAndGet(), screen)
        state.value = r
        return r
    }

    /** Called by the shell after it navigated for [id]. */
    fun landed(id: Long) {
        val r = state.value
        if (r?.id == id) {
            lastLanded = r
            state.value = null
        }
    }

    /** True once a request newer than [afterId] for [screen] has landed in the shell. */
    fun awaitLanded(screen: String, afterId: Long, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val l = lastLanded
            if (l != null && l.id > afterId && l.screen == screen) return true
            Thread.sleep(50)
        }
        return false
    }
}
