package com.bitchat.lora.meshcore

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.refTo
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.toKString
import platform.posix.AF_INET
import platform.posix.F_OK
import platform.posix.SOCK_STREAM
import platform.posix.access
import platform.posix.close
import platform.posix.connect
import platform.posix.fgets
import platform.posix.geteuid
import platform.posix.htonl
import platform.posix.htons
import platform.posix.pclose
import platform.posix.popen
import platform.posix.sockaddr_in
import platform.posix.socket
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
     * Whether this process has already restarted a wedged daemon.
     *
     * The recovery is worth one attempt, not one attempt per call: [start] runs on every protocol
     * switch, and a daemon that cannot initialise its radio will never come back, so retrying each
     * time just kills and relaunches it forever.
     */
    private var recoveryAttempted = false

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
            if (isListening()) {
                println("meshcored already running")
                return true
            }
            // Alive but serving nothing: almost always a failed radio init, which halts the daemon
            // before it binds. Restarting is worth one attempt -- the radio may have been busy the
            // first time -- but if it comes back still deaf, say so instead of handing the caller a
            // daemon it will spend fifteen seconds failing to reach.
            if (recoveryAttempted) {
                println(
                    "meshcored is up but still not listening on ${MeshCoreConstants.DEFAULT_PORT}; " +
                        "already tried restarting it once this run, not looping"
                )
                return false
            }
            recoveryAttempted = true
            println(
                "meshcored is running but not listening on ${MeshCoreConstants.DEFAULT_PORT}; " +
                    "restarting it once"
            )
            executeCommand("$SUDO_NON_INTERACTIVE pkill -x $SERVICE_NAME")
            usleep(500_000u)
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

        return if (isRunning() && isListening()) {
            println("meshcored started successfully")
            true
        } else if (isRunning()) {
            // The specific failure worth naming: the daemon is up and deaf. radio_init() has no
            // logging of its own, so this line is the only signal anyone gets. Check that the
            // spidev and pin numbers in /etc/meshcored/meshcored.ini match how the radio is wired.
            // Deliberately not asserting a cause. Connection refused says the port is not open;
            // it does not say why. A failed radio init halting the daemon before it binds is the
            // likeliest reason (companion_radio/main.cpp halts on !radio_init(), before
            // serial_interface.begin(TCP_PORT)), but a failed bind or listen looks identical from
            // here. LinuxSX1276::std_init does print "ERROR: radio init failed: <status>" through
            // Serial, so that output is where the real answer is.
            println(
                "meshcored is running but never opened ${MeshCoreConstants.DEFAULT_PORT}. Its radio " +
                    "init or its TCP setup failed; check the daemon's Serial output for " +
                    "\"radio init failed\", and the spidev and lora pins in " +
                    "/etc/meshcored/meshcored.ini against the wiring."
            )
            false
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
     * Whether the daemon is actually serving the companion protocol.
     *
     * A live process is not the same thing as a working daemon. `companion_radio/main.cpp` halts on
     * a failed radio init -- `if (!radio_init()) { halt(); }`, and `halt()` is a bare `while (1)` --
     * which happens *before* `serial_interface.begin(TCP_PORT)`. The process then sits there
     * spinning: systemd calls it active, `pgrep` finds it, and it holds no sockets at all. Measured
     * on the device with the radio misconfigured: pid alive, `ss -lnt` empty for 5000, zero entries
     * in `/proc/<pid>/fd`, and one line in the journal for the whole boot.
     *
     * So readiness is a connect to the port, not a look at the process table.
     */
    fun isListening(): Boolean = memScoped {
        // DESTRUCTIVE IF A CLIENT IS ATTACHED. LinuxTcpInterface.cpp accepts one client at a time
        // and closes the previous one on a new accept ("Disconnect existing client"), so probing
        // while our transport holds a session would drop that session. Only call this before the
        // transport connects, or after it has closed -- never as a periodic health check.
        val fd = socket(AF_INET, SOCK_STREAM, 0)
        if (fd < 0) return@memScoped false

        val addr = alloc<sockaddr_in>()
        addr.sin_family = AF_INET.convert()
        addr.sin_port = htons(MeshCoreConstants.DEFAULT_PORT.toUShort())
        addr.sin_addr.s_addr = htonl(0x7F000001u)  // 127.0.0.1

        val connected = connect(fd, addr.ptr.reinterpret(), sizeOf<sockaddr_in>().convert()) == 0
        close(fd)
        connected
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
