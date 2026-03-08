package com.bitchat.lora.meshcore

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.refTo
import kotlinx.cinterop.toKString
import platform.posix.F_OK
import platform.posix.access
import platform.posix.fgets
import platform.posix.geteuid
import platform.posix.pclose
import platform.posix.popen
import platform.posix.usleep

/**
 * Manages the meshcored systemd service on Linux.
 *
 * meshcored is the MeshCore C++ companion radio daemon compiled from
 * the MeshCore firmware with RadioLib + portduino for Linux SPI/GPIO.
 * It controls the SX1276/RFM95W radio and exposes the MeshCore companion
 * protocol over TCP port 5000.
 *
 * The meshcore daemon and meshtasticd cannot both use the SPI radio
 * simultaneously - when switching protocols, one must be stopped first.
 */
@OptIn(ExperimentalForeignApi::class)
object MeshCoreService {

    private const val SERVICE_NAME = "meshcored"
    private const val MESHCORE_BINARY = "/usr/local/bin/meshcored"
    private const val SUDO_NON_INTERACTIVE = "sudo -n"

    /**
     * Start the meshcore service.
     *
     * Stops meshtasticd first since they share the same SPI radio.
     *
     * @return true if service started successfully or was already running
     */
    fun start(): Boolean {
        if (!isInstalled()) {
            println("meshcored binary not found at $MESHCORE_BINARY")
            return false
        }

        // Stop meshtasticd first (they share the radio)
        stopMeshtasticd()

        if (isRunning()) {
            println("meshcored already running")
            return true
        }

        println("Starting meshcored...")

        // Try systemd first
        val hasService = executeCommand("systemctl list-unit-files $SERVICE_NAME.service")
            .output.contains(SERVICE_NAME)

        val result = if (hasService) {
            runSystemctl("start $SERVICE_NAME")
        } else {
            // No systemd service - launch binary directly
            println("No systemd service found, launching meshcored directly...")
            executeCommand("$SUDO_NON_INTERACTIVE $MESHCORE_BINARY &")
        }

        // Wait for startup
        usleep(2_000_000u) // 2 seconds for radio init

        return if (isRunning()) {
            println("meshcored started successfully")
            true
        } else {
            val status = if (hasService) {
                runSystemctl("status $SERVICE_NAME --no-pager -l").output.trim()
            } else {
                "direct launch may have failed"
            }
            println("meshcored failed to start: $status")
            false
        }
    }

    /**
     * Stop the meshcore service.
     *
     * @return true if service stopped successfully or was not running
     */
    fun stop(): Boolean {
        if (!isRunning()) {
            println("meshcored already stopped")
            return true
        }

        println("Stopping meshcored...")

        // Try systemd first
        val hasService = executeCommand("systemctl list-unit-files $SERVICE_NAME.service")
            .output.contains(SERVICE_NAME)

        if (hasService) {
            runSystemctl("stop $SERVICE_NAME")
        }

        // Also kill any direct process (covers manual launches)
        executeCommand("$SUDO_NON_INTERACTIVE pkill -x meshcored 2>/dev/null || true")

        usleep(500_000u) // 0.5 seconds
        return if (!isRunning()) {
            println("meshcored stopped successfully")
            true
        } else {
            println("meshcored may still be stopping...")
            true
        }
    }

    /**
     * Check if meshcore is currently running.
     *
     * Checks both systemd service status AND process existence,
     * since meshcore might be started manually.
     *
     * @return true if meshcore is running (via systemd or manually)
     */
    fun isRunning(): Boolean {
        // Check systemd service first
        val systemdResult = runSystemctl("is-active $SERVICE_NAME")
        if (systemdResult.output.trim() == "active") {
            return true
        }

        // Also check if process is running directly (e.g., started manually)
        val processResult = executeCommand("pgrep -x meshcored")
        return processResult.success && processResult.output.trim().isNotEmpty()
    }

    /**
     * Check if meshcore is installed.
     *
     * @return true if service unit exists or binary is present
     */
    fun isInstalled(): Boolean {
        // Check if the systemd service exists
        val result = executeCommand("systemctl list-unit-files $SERVICE_NAME.service")
        if (result.output.contains(SERVICE_NAME)) {
            return true
        }
        // Fallback: check if the binary itself exists
        return access(MESHCORE_BINARY, F_OK) == 0
    }

    /**
     * Get meshcore service status details.
     *
     * @return status string from systemctl
     */
    fun getStatus(): String {
        val result = runSystemctl("status $SERVICE_NAME --no-pager -l")
        return result.output
    }

    /**
     * Stop meshtasticd service if running (they can't both use the radio).
     */
    private fun stopMeshtasticd() {
        val meshtasticResult = executeCommand("systemctl is-active meshtasticd")
        if (meshtasticResult.output.trim() == "active") {
            println("Stopping meshtasticd (shares radio with meshcored)...")
            runSystemctl("stop meshtasticd")
            usleep(1_000_000u) // Wait 1 second for clean shutdown
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
