package com.bitchat.lora.service

enum class LoRaDaemon(val unit: String, val process: String?, val port: Int?) {
    MESHTASTIC("meshtasticd.service", "meshtasticd", 4403),
    MESHCORE("meshcored.service", "meshcored", 5000),
    LEGACY_MESHCORE("meshcore.service", null, null)
}

enum class DaemonAction { START, STOP, RESET_FAILED, TERMINATE }

data class DaemonSnapshot(
    val installed: Boolean,
    val state: String,
    val hasJob: Boolean,
    val processAlive: Boolean,
    val known: Boolean = true,
    val detail: String = ""
) {
    val unitStopped: Boolean get() = !installed || (!hasJob && state in setOf("inactive", "failed"))
    val stopped: Boolean get() = known && unitStopped && !processAlive
}

data class DaemonResult(val success: Boolean, val detail: String = "")

interface LoRaDaemonBackend {
    /** Absolute monotonic deadline; every command in the operation shares this budget. */
    fun snapshot(daemon: LoRaDaemon, deadlineMs: Long = nowMs() + 5_000): DaemonSnapshot
    fun execute(daemon: LoRaDaemon, action: DaemonAction, deadlineMs: Long = nowMs() + 5_000): DaemonResult
    fun isListening(daemon: LoRaDaemon, deadlineMs: Long = nowMs() + 5_000): Boolean
    fun nowMs(): Long
    fun pause(ms: Long)
}

/**
 * A stop, including its first status read, takes at most stopTimeoutMs.
 * Start budgets each conflicting stop separately, then shares startupTimeoutMs
 * across target inspection, start commands and readiness. Stopping a stale target
 * and cleaning up a failed attempt each receive a separate stopTimeoutMs budget.
 * Thus start is bounded by (daemon count + 1) * stopTimeoutMs + startupTimeoutMs
 * (28 seconds with the defaults); direct preparation by daemon count * stopTimeoutMs.
 * These bounds rely on the backend enforcing deadlines, excluding OS scheduling delay.
 */
class LoRaDaemonController(
    private val backend: LoRaDaemonBackend,
    private val stopTimeoutMs: Long = 5_000,
    private val startupTimeoutMs: Long = 8_000,
    private val pollMs: Long = 100
) {
    init {
        require(stopTimeoutMs > 0 && startupTimeoutMs > 0 && pollMs > 0)
    }

    fun start(daemon: LoRaDaemon): DaemonResult {
        require(daemon != LoRaDaemon.LEGACY_MESHCORE)
        for (other in LoRaDaemon.entries.filter { it != daemon }) {
            val result = stop(other)
            if (!result.success) return result
        }
        var deadline = backend.nowMs() + startupTimeoutMs
        val initial = backend.snapshot(daemon, deadline)
        if (backend.nowMs() >= deadline) return startupFailure(daemon, "startup status timed out")
        if (!initial.known) return failure(daemon, initial.detail)
        if (!initial.installed) return failure(daemon, "service is not installed")
        if (initial.state == "active" && !initial.hasJob && backend.isListening(daemon, deadline) &&
            backend.nowMs() < deadline) return DaemonResult(true)
        val remainingStartupMs = deadline - backend.nowMs()
        if (remainingStartupMs <= 0) return startupFailure(daemon, "listener inspection timed out")
        val stopped = stop(daemon)
        if (!stopped.success) return stopped
        // Target teardown has its own budget; do not replenish time already spent inspecting it.
        deadline = backend.nowMs() + remainingStartupMs
        val afterStop = backend.snapshot(daemon, deadline)
        if (!afterStop.known || backend.nowMs() >= deadline) {
            return failure(daemon, "cannot inspect target after stop: ${afterStop.detail}")
        }
        // systemd can unload a healthy inactive unit between inspection and the
        // reset command. Such a unit has no failure to reset and reset-failed may
        // return "not loaded" even though start would load and launch it normally.
        val actions = if (initial.state == "failed" || afterStop.state == "failed") {
            listOf(DaemonAction.RESET_FAILED, DaemonAction.START)
        } else listOf(DaemonAction.START)
        for (action in actions) {
            val result = backend.execute(daemon, action, deadline)
            if (!result.success || backend.nowMs() >= deadline) {
                val detail = result.detail.ifBlank { "$action exceeded startup deadline" }
                // A failed/expired IPC reply does not prove that systemd rejected the start job.
                return if (action == DaemonAction.START) startupFailure(daemon, detail)
                else failure(daemon, detail)
            }
        }
        while (backend.nowMs() < deadline) {
            val state = backend.snapshot(daemon, deadline)
            if (!state.known || backend.nowMs() >= deadline) break
            if (state.state == "active" && !state.hasJob && backend.isListening(daemon, deadline) &&
                backend.nowMs() < deadline) return DaemonResult(true)
            if (state.state == "failed") break
            pauseUntil(deadline)
        }
        return startupFailure(daemon, "did not become ready")
    }

    fun stop(daemon: LoRaDaemon): DaemonResult {
        val deadline = backend.nowMs() + stopTimeoutMs
        var state = backend.snapshot(daemon, deadline)
        if (backend.nowMs() >= deadline) return failure(daemon, "stop status timed out")
        if (!state.known) return failure(daemon, state.detail)
        if (state.stopped) return DaemonResult(true)
        if (state.installed && !state.unitStopped) {
            val result = backend.execute(daemon, DaemonAction.STOP, deadline)
            if (!result.success) return failure(daemon, result.detail)
        }
        var terminated = false
        while (backend.nowMs() < deadline) {
            state = backend.snapshot(daemon, deadline)
            if (backend.nowMs() >= deadline) break
            if (!state.known) return failure(daemon, state.detail)
            if (state.stopped) return DaemonResult(true)
            // Never kill a process while systemd is still stopping its unit.
            if (state.unitStopped && state.processAlive && !terminated) {
                val result = backend.execute(daemon, DaemonAction.TERMINATE, deadline)
                if (!result.success) return failure(daemon, result.detail)
                terminated = true
            }
            pauseUntil(deadline)
        }
        return failure(daemon, "still owns the radio after stop timeout")
    }

    fun prepareDirectRadio(): DaemonResult {
        for (daemon in LoRaDaemon.entries) {
            val result = stop(daemon)
            if (!result.success) return result
        }
        return DaemonResult(true)
    }

    private fun startupFailure(daemon: LoRaDaemon, detail: String): DaemonResult {
        val cleanup = stop(daemon)
        return failure(daemon, detail + if (cleanup.success) "" else "; cleanup failed: ${cleanup.detail}")
    }

    private fun pauseUntil(deadlineMs: Long) {
        val remaining = deadlineMs - backend.nowMs()
        if (remaining > 0) backend.pause(minOf(pollMs, remaining))
    }

    private fun failure(daemon: LoRaDaemon, detail: String) =
        DaemonResult(false, "${daemon.unit}: $detail")
}
