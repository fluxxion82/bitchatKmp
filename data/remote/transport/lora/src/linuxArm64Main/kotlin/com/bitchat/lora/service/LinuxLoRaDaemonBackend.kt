package com.bitchat.lora.service

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.refTo
import kotlinx.cinterop.toKString
import platform.posix.fgets
import platform.posix.geteuid
import platform.posix.pclose
import platform.posix.popen
import platform.posix.usleep
import kotlin.time.TimeSource

/** Fixed commands only. timeout bounds blocking libc calls, including an unresponsive systemd bus. */
@OptIn(ExperimentalForeignApi::class)
class LinuxLoRaDaemonBackend : LoRaDaemonBackend {
    private val epoch = TimeSource.Monotonic.markNow()
    override fun nowMs() = epoch.elapsedNow().inWholeMilliseconds
    override fun pause(ms: Long) { usleep((ms * 1_000).toUInt()) }

    override fun snapshot(daemon: LoRaDaemon, deadlineMs: Long): DaemonSnapshot {
        val unit = command("systemctl show -p LoadState -p ActiveState -p Job ${daemon.unit}", deadlineMs)
        val fields = unit.output.lineSequence().filter { '=' in it }
            .associate { it.substringBefore('=') to it.substringAfter('=') }
        val loaded = fields["LoadState"]
        val process = daemon.process?.let { command("pgrep -x $it", deadlineMs) }
        val known = nowMs() < deadlineMs && loaded in setOf("loaded", "not-found", "masked") &&
            (unit.code == 0 || loaded == "not-found") &&
            (process == null || process.code in 0..1) &&
            (loaded == "not-found" || fields["ActiveState"] in setOf(
                "active", "inactive", "failed", "activating", "deactivating", "reloading", "refreshing"
            )) && "Job" in fields
        return DaemonSnapshot(
            installed = loaded != "not-found",
            state = fields["ActiveState"] ?: "unknown",
            hasJob = !fields["Job"].isNullOrBlank(),
            processAlive = process?.code == 0,
            known = known,
            detail = if (known) "" else "cannot establish service/process ownership (systemctl=${unit.code}, pgrep=${process?.code})"
        )
    }

    override fun execute(daemon: LoRaDaemon, action: DaemonAction, deadlineMs: Long): DaemonResult {
        val actionCommand = when (action) {
            DaemonAction.STOP -> "systemctl stop --no-block ${daemon.unit}"
            DaemonAction.START -> "systemctl start --no-block ${daemon.unit}"
            DaemonAction.RESET_FAILED -> "systemctl reset-failed ${daemon.unit}"
            DaemonAction.TERMINATE -> daemon.process?.let { "pkill -x $it" }
                ?: return DaemonResult(false, "no known standalone process")
        }
        val result = command((if (geteuid() == 0u) "" else "sudo -n ") + actionCommand, deadlineMs)
        // pkill's no-match exit can race with a normal exit; the controller still verifies absence.
        return DaemonResult(result.code == 0 || (action == DaemonAction.TERMINATE && result.code == 1),
            "$action exited ${result.code}: ${result.output.trim().take(512)}")
    }

    override fun isListening(daemon: LoRaDaemon, deadlineMs: Long): Boolean {
        val port = daemon.port ?: return false
        // Opening a probe connection would displace MeshCore's single app client.
        val table = command("cat /proc/net/tcp /proc/net/tcp6", deadlineMs)
        if (table.code != 0) return false
        val hex = port.toString(16).padStart(4, '0')
        return table.output.lineSequence().any { line ->
            val fields = line.trim().split(Regex("\\s+"))
            fields.size > 3 && fields[3] == "0A" &&
                fields[1].substringAfterLast(':').equals(hex, ignoreCase = true)
        }
    }

    private data class CommandResult(val code: Int, val output: String)
    private fun command(command: String, deadlineMs: Long): CommandResult {
        val budgetMs = minOf(5_000L, deadlineMs - nowMs())
        if (budgetMs <= 0) return CommandResult(124, "operation deadline exhausted")
        val graceMs = minOf(1_000L, budgetMs / 2)
        val termMs = budgetMs - graceMs
        val duration = "${termMs / 1_000}.${(termMs % 1_000).toString().padStart(3, '0')}s"
        val grace = "${graceMs / 1_000}.${(graceMs % 1_000).toString().padStart(3, '0')}s"
        // Reserve the TERM-to-KILL grace inside the remaining budget, not after
        // it. TERM also lets sudo forward cancellation to its command/PTY.
        val flags = if (graceMs > 0) "--kill-after=$grace" else "--signal=KILL"
        val process = popen("/usr/bin/timeout $flags $duration $command 2>&1", "r")
            ?: return CommandResult(-1, "could not launch command")
        val buffer = ByteArray(1024)
        val output = StringBuilder()
        var status: Int
        try {
            while (fgets(buffer.refTo(0), buffer.size, process) != null) {
                if (output.length < 65_536) output.append(buffer.toKString())
            }
        } finally { status = pclose(process) }
        val code = when {
            status < 0 -> -1
            status and 0x7f == 0 -> (status shr 8) and 0xff
            else -> 128 + (status and 0x7f)
        }
        return CommandResult(code, output.toString())
    }
}

object LinuxLoRaServices {
    val backend = LinuxLoRaDaemonBackend()
    val controller = LoRaDaemonController(backend)
    fun report(result: DaemonResult): Boolean {
        if (!result.success) println("LoRa service error: ${result.detail}")
        return result.success
    }
}
