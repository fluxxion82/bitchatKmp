package com.bitchat.lora.bitchat.radio

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.refTo
import kotlinx.cinterop.toKString
import platform.posix.fgets
import platform.posix.pclose
import platform.posix.popen
import platform.posix.usleep

/**
 * Manages system services that conflict with BitChat's direct SPI access.
 *
 * On Linux ARM64, meshtasticd (Meshtastic daemon) may be controlling the
 * SPI LoRa radio. BitChat's direct SPI access requires exclusive access
 * to the radio, so we need to stop meshtasticd before using it.
 */
@OptIn(ExperimentalForeignApi::class)
internal object LoRaServiceManager {

    private const val MESHTASTICD_SERVICE = "meshtasticd"
    private var shouldRestoreMeshtasticd = false

    /**
     * Ensure conflicting services are stopped before BitChat uses SPI.
     *
     * Currently stops meshtasticd if it's running.
     */
    fun ensureNoConflictingServices(): Boolean {
        if (isMeshtasticdRunning()) {
            println("⚠️ meshtasticd is running - stopping it for BitChat SPI access...")
            val stopped = stopMeshtasticd()
            if (stopped) {
                shouldRestoreMeshtasticd = true
            }
            return stopped
        }
        return true
    }

    /**
     * Restore meshtasticd if BitChat stopped it earlier in this process.
     */
    fun restoreConflictingServices() {
        if (!shouldRestoreMeshtasticd) return
        println("🔄 Restoring meshtasticd service...")
        if (startMeshtasticd()) {
            println("✅ meshtasticd restored")
            shouldRestoreMeshtasticd = false
        } else {
            println("❌ Failed to restore meshtasticd")
        }
    }

    /**
     * Check if meshtasticd service is running.
     */
    fun isMeshtasticdRunning(): Boolean {
        val result = executeCommand("systemctl is-active $MESHTASTICD_SERVICE 2>/dev/null")
            .ifBlank { executeCommand("sudo -n systemctl is-active $MESHTASTICD_SERVICE 2>/dev/null") }
        return result.trim() == "active"
    }

    /**
     * Stop meshtasticd service to release SPI radio.
     */
    private fun stopMeshtasticd(): Boolean {
        println("🛑 Stopping meshtasticd service...")

        var result = executeCommand("systemctl stop $MESHTASTICD_SERVICE 2>&1")
        if (result.contains("Access denied", ignoreCase = true) || result.contains("authentication", ignoreCase = true)) {
            result = executeCommand("sudo -n systemctl stop $MESHTASTICD_SERVICE 2>&1")
        }

        // Wait briefly for service to stop
        usleep(500_000u) // 0.5 seconds

        return if (!isMeshtasticdRunning()) {
            println("✅ meshtasticd stopped - SPI radio available for BitChat")
            true
        } else {
            println("❌ Failed to stop meshtasticd: $result")
            false
        }
    }

    private fun startMeshtasticd(): Boolean {
        var result = executeCommand("systemctl start $MESHTASTICD_SERVICE 2>&1")
        if (result.contains("Access denied", ignoreCase = true) || result.contains("authentication", ignoreCase = true)) {
            result = executeCommand("sudo -n systemctl start $MESHTASTICD_SERVICE 2>&1")
        }
        usleep(500_000u)
        return isMeshtasticdRunning()
    }

    /**
     * Execute a shell command and return stdout.
     */
    private fun executeCommand(command: String): String {
        val buffer = ByteArray(4096)
        val output = StringBuilder()

        val process = popen(command, "r")
        if (process == null) {
            return ""
        }

        try {
            while (true) {
                val line = fgets(buffer.refTo(0), buffer.size, process)
                if (line == null) break
                output.append(buffer.toKString())
            }
        } finally {
            pclose(process)
        }

        return output.toString()
    }
}
