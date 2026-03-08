package com.bitchat.lora.meshtastic

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import platform.posix.pclose
import platform.posix.popen
import platform.posix.fgets
import platform.posix.usleep
import platform.posix.geteuid
import kotlinx.cinterop.refTo

/**
 * Controls the meshtasticd systemd service on Linux ARM64.
 *
 * meshtasticd is the Meshtastic Linux native daemon that controls
 * SPI LoRa radios. BitChat's direct SPI access and meshtasticd cannot
 * both control the radio simultaneously - when switching protocols,
 * we need to stop one and start the other.
 *
 * Usage:
 * - When switching TO Meshtastic protocol: stop BitChat SPI, start meshtasticd
 * - When switching TO BitChat protocol: stop meshtasticd, start BitChat SPI
 *
 * Requires: meshtasticd installed and configured (sudo apt install meshtasticd)
 * The service should be disabled from auto-start: sudo systemctl disable meshtasticd
 */
@OptIn(ExperimentalForeignApi::class)
object MeshtasticdService {

    private const val SERVICE_NAME = "meshtasticd"
    private const val SUDO_NON_INTERACTIVE = "sudo -n"

    /**
     * Start the meshtasticd service.
     *
     * @return true if service started successfully or was already running
     */
    fun start(): Boolean {
        if (!isInstalled()) {
            println("meshtasticd service unit is not installed")
            return false
        }

        // Stop meshcored first (they share the radio)
        stopMeshcore()

        if (isRunning()) {
            println("meshtasticd already running")
            return true
        }

        println("Starting meshtasticd service...")
        val result = runSystemctl("start $SERVICE_NAME")

        return if (result.success) {
            // Wait briefly for service to initialize
            usleep(1_000_000u) // 1 second
            if (isRunning()) {
                println("meshtasticd started successfully")
                true
            } else {
                val status = runSystemctl("status $SERVICE_NAME --no-pager -l")
                println("meshtasticd failed to start: ${status.output.trim()}")
                false
            }
        } else {
            println("Failed to start meshtasticd: ${result.output.trim()}")
            false
        }
    }

    /**
     * Stop the meshtasticd service.
     *
     * @return true if service stopped successfully or was not running
     */
    fun stop(): Boolean {
        if (!isInstalled()) {
            println("meshtasticd not installed; nothing to stop")
            return true
        }

        if (!isRunning()) {
            println("meshtasticd already stopped")
            return true
        }

        println("Stopping meshtasticd service...")
        val result = runSystemctl("stop $SERVICE_NAME")

        return if (result.success) {
            // Wait briefly for service to stop
            usleep(500_000u) // 0.5 seconds
            if (!isRunning()) {
                println("meshtasticd stopped successfully")
                true
            } else {
                println("meshtasticd may still be stopping...")
                true // Systemd will handle it
            }
        } else {
            println("Failed to stop meshtasticd: ${result.output.trim()}")
            false
        }
    }

    /**
     * Check if meshtasticd is currently running.
     *
     * Checks both systemd service status AND process existence,
     * since meshtasticd might be started manually (e.g., in simulation mode).
     *
     * @return true if meshtasticd is running (via systemd or manually)
     */
    fun isRunning(): Boolean {
        if (!isInstalled()) {
            return false
        }

        // First check systemd service
        val systemdResult = runSystemctl("is-active $SERVICE_NAME")
        if (systemdResult.output.trim() == "active") {
            return true
        }

        // Also check if process is running directly (e.g., started manually)
        val processResult = executeCommand("pgrep -x $SERVICE_NAME")
        return processResult.success && processResult.output.trim().isNotEmpty()
    }

    /**
     * Check if meshtasticd is installed.
     *
     * @return true if service unit exists
     */
    fun isInstalled(): Boolean {
        val result = executeCommand("systemctl list-unit-files $SERVICE_NAME.service")
        return result.output.contains(SERVICE_NAME)
    }

    /**
     * Get meshtasticd service status details.
     *
     * @return status string from systemctl
     */
    fun getStatus(): String {
        val result = runSystemctl("status $SERVICE_NAME --no-pager -l")
        return result.output
    }

    /**
     * Stop meshcored service/process if running (they can't both use the radio).
     */
    private fun stopMeshcore() {
        // Check systemd service
        val systemdResult = executeCommand("systemctl is-active meshcored")
        if (systemdResult.output.trim() == "active") {
            println("Stopping meshcored (shares radio with meshtasticd)...")
            runSystemctl("stop meshcored")
            usleep(1_000_000u)
            return
        }
        // Check for direct process
        val processResult = executeCommand("pgrep -x meshcored")
        if (processResult.success && processResult.output.trim().isNotEmpty()) {
            println("Stopping meshcored process (shares radio with meshtasticd)...")
            executeCommand("$SUDO_NON_INTERACTIVE pkill -x meshcored 2>/dev/null || true")
            usleep(1_000_000u)
        }
    }

    /**
     * Run a systemctl command with predictable non-interactive behavior.
     */
    private fun runSystemctl(args: String): CommandResult {
        val baseCommand = "systemctl $args 2>&1"

        // If process is already running as root, avoid sudo.
        if (geteuid() == 0u) {
            return executeCommand(baseCommand)
        }

        // Try without sudo first (for users with direct permissions), then sudo -n fallback.
        val direct = executeCommand(baseCommand)
        if (direct.success) {
            return direct
        }

        val withSudo = executeCommand("$SUDO_NON_INTERACTIVE $baseCommand")
        return if (withSudo.success || withSudo.output.isNotBlank()) {
            withSudo
        } else {
            CommandResult(
                success = false,
                output = "systemctl failed and non-interactive sudo unavailable"
            )
        }
    }

    /**
     * Execute a shell command and return the result.
     */
    private fun executeCommand(command: String): CommandResult {
        val buffer = ByteArray(4096)
        val output = StringBuilder()

        val process = popen(command, "r")
        if (process == null) {
            return CommandResult(false, "Failed to execute command")
        }

        try {
            while (true) {
                val line = fgets(buffer.refTo(0), buffer.size, process)
                if (line == null) break
                output.append(buffer.toKString())
            }
        } finally {
            val exitCode = pclose(process)
            // pclose returns exit status shifted left by 8 bits
            val actualExitCode = exitCode shr 8
            return CommandResult(actualExitCode == 0, output.toString())
        }
    }

    private data class CommandResult(
        val success: Boolean,
        val output: String
    )
}
