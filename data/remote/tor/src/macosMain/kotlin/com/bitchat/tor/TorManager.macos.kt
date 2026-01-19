package com.bitchat.tor

import com.bitchat.domain.tor.model.TorMode
import com.bitchat.domain.tor.model.TorState
import com.bitchat.domain.tor.model.TorStatus
import com.bitchat.tor.native.arti_initialize
import com.bitchat.tor.native.arti_set_log_callback
import com.bitchat.tor.native.arti_start_socks_proxy
import com.bitchat.tor.native.arti_stop
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CFunction
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.toKString
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import platform.Foundation.NSLog
import kotlin.concurrent.Volatile

@OptIn(ExperimentalForeignApi::class)
actual class TorManager actual constructor(
    private val dataDir: String
) {
    // Initialize with STARTING state to avoid showing "disconnected" before native Tor reports actual status
    // This prevents the race condition where UI checks status before native logs arrive
    private val _statusFlow = MutableStateFlow(
        TorStatus(
            mode = TorMode.ON,  // Assume Tor is starting until confirmed otherwise
            running = false,  // Not confirmed running yet
            bootstrapPercent = 0,
            lastLogLine = "Waiting for Tor status...",
            state = TorState.STARTING,  // Show as starting/initializing
            socksPort = DEFAULT_SOCKS_PORT
        )
    )

    actual val statusFlow: StateFlow<TorStatus> = _statusFlow.asStateFlow()

    @Volatile
    private var initialized = false

    @Volatile
    private var currentPort = DEFAULT_SOCKS_PORT

    init {
        currentInstance = this

        try {
            arti_set_log_callback(logCallback)
            NSLog("$TAG: Log callback set")
        } catch (e: Exception) {
            NSLog("$TAG: Failed to set log callback: ${e.message}")
        }
    }

    actual fun getSocksProxyAddress(): Pair<String, Int>? {
        return if (isProxyReady()) {
            Pair("127.0.0.1", currentPort)
        } else {
            null
        }
    }

    actual fun isProxyReady(): Boolean {
        val status = _statusFlow.value
        return status.mode != TorMode.OFF &&
                status.running &&
                status.bootstrapPercent >= 100 &&
                status.state == TorState.RUNNING
    }

    actual suspend fun start() {
        if (!initialized) {
            NSLog("$TAG: Initializing Arti...")
            val result = arti_initialize(dataDir)

            if (result != 0) {
                NSLog("$TAG: Initialization failed: $result")
                _statusFlow.update { it.copy(state = TorState.ERROR, errorMessage = "Init failed: $result") }
                return
            }

            initialized = true
        }

        NSLog("$TAG: Starting SOCKS proxy on port $currentPort...")
        _statusFlow.update { it.copy(mode = TorMode.ON, state = TorState.STARTING) }

        val result = arti_start_socks_proxy(currentPort)

        if (result != 0) {
            NSLog("$TAG: Start proxy failed: $result")
            _statusFlow.update { it.copy(state = TorState.ERROR, errorMessage = "Start failed: $result") }
        }
    }

    actual suspend fun stop() {
        NSLog("$TAG: Stopping Tor...")
        _statusFlow.update { it.copy(state = TorState.STOPPING) }

        arti_stop()

        _statusFlow.update {
            it.copy(
                mode = TorMode.OFF,
                running = false,
                bootstrapPercent = 0,
                state = TorState.OFF
            )
        }
    }

    actual fun destroy() {
        currentInstance = null
    }

    private fun handleLogLine(line: String) {
        NSLog("$TAG: Arti: $line")

        _statusFlow.update { it.copy(lastLogLine = line) }

        when {
            line.contains("AMEx: state changed to Initialized", ignoreCase = true) ||
                    line.contains("AMEx: state changed to Starting", ignoreCase = true) -> {
                NSLog("🔵 $TAG: Tor state -> STARTING")
                _statusFlow.update { it.copy(state = TorState.STARTING) }
            }

            line.contains("Sufficiently bootstrapped; system SOCKS now functional", ignoreCase = true) -> {
                NSLog("🟡 $TAG: Tor bootstrap -> 75% (SOCKS functional)")
                _statusFlow.update {
                    it.copy(
                        bootstrapPercent = 75,
                        state = TorState.BOOTSTRAPPING
                    )
                }
            }

            line.contains("We have found that guard [scrubbed] is usable", ignoreCase = true) -> {
                NSLog("🟢 $TAG: Tor bootstrap -> 100% (RUNNING)")
                _statusFlow.update {
                    it.copy(
                        bootstrapPercent = 100,
                        state = TorState.RUNNING,
                        running = true
                    )
                }
            }

            line.contains("AMEx: state changed to Stopping", ignoreCase = true) -> {
                NSLog("🟠 $TAG: Tor state -> STOPPING")
                _statusFlow.update { it.copy(state = TorState.STOPPING, running = false) }
            }

            line.contains("AMEx: state changed to Stopped", ignoreCase = true) -> {
                NSLog("🔴 $TAG: Tor state -> OFF (stopped)")
                _statusFlow.update {
                    it.copy(
                        mode = TorMode.OFF,
                        state = TorState.OFF,
                        running = false,
                        bootstrapPercent = 0
                    )
                }
            }

            line.contains("ERROR", ignoreCase = true) -> {
                NSLog("❌ $TAG: Tor ERROR: $line")
                _statusFlow.update { it.copy(state = TorState.ERROR, errorMessage = line) }
            }
        }
    }

    companion object {
        private const val TAG = "TorManager"
        private const val DEFAULT_SOCKS_PORT = 9050

        private val logCallback: CPointer<CFunction<(CPointer<ByteVar>?) -> Unit>> =
            staticCFunction { messagePtr: CPointer<ByteVar>? ->
                if (messagePtr != null) {
                    val message = messagePtr.toKString()
                    currentInstance?.handleLogLine(message)
                }
            }.reinterpret()

        private var currentInstance: TorManager? = null
    }
}
