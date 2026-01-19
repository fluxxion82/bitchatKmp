package com.bitchat.tor

import com.bitchat.domain.tor.model.TorMode
import com.bitchat.domain.tor.model.TorState
import com.bitchat.domain.tor.model.TorStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File

actual class TorManager actual constructor(
    private val dataDir: String
) {
    private val _statusFlow = MutableStateFlow(
        TorStatus(
            mode = TorMode.OFF,
            running = false,
            bootstrapPercent = 0,
            lastLogLine = "",
            state = TorState.OFF,
            socksPort = DEFAULT_SOCKS_PORT
        )
    )

    actual val statusFlow: StateFlow<TorStatus> = _statusFlow.asStateFlow()

    @Volatile
    private var initialized = false

    @Volatile
    private var currentPort = DEFAULT_SOCKS_PORT

    init {
        File(dataDir).mkdirs()

        try {
            nativeSetLogCallback(object : LogCallback {
                override fun onLogLine(message: String?) {
                    message?.let { handleLogLine(it) }
                }
            })
        } catch (e: Exception) {
            System.err.println("$TAG: Failed to set log callback: ${e.message}")
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
            println("$TAG: Initializing Arti...")
            val result = try {
                nativeInitialize(dataDir)
            } catch (e: Exception) {
                System.err.println("$TAG: Failed to initialize: ${e.message}")
                _statusFlow.update { it.copy(state = TorState.ERROR, errorMessage = e.message) }
                return
            }

            if (result != 0) {
                System.err.println("$TAG: Initialization failed: $result")
                _statusFlow.update { it.copy(state = TorState.ERROR, errorMessage = "Init failed: $result") }
                return
            }

            initialized = true
        }

        println("$TAG: Starting SOCKS proxy on port $currentPort...")
        _statusFlow.update { it.copy(mode = TorMode.ON, state = TorState.STARTING) }

        val result = try {
            nativeStartSocksProxy(currentPort)
        } catch (e: Exception) {
            System.err.println("$TAG: Failed to start proxy: ${e.message}")
            _statusFlow.update { it.copy(state = TorState.ERROR, errorMessage = e.message) }
            return
        }

        if (result != 0) {
            System.err.println("$TAG: Start proxy failed: $result")
            _statusFlow.update { it.copy(state = TorState.ERROR, errorMessage = "Start failed: $result") }
        }
    }

    actual suspend fun stop() {
        println("$TAG: Stopping Tor...")
        _statusFlow.update { it.copy(state = TorState.STOPPING) }

        try {
            nativeStop()
        } catch (e: Exception) {
            System.err.println("$TAG: Failed to stop: ${e.message}")
        }

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

    }

    private fun handleLogLine(line: String) {
        println("$TAG: Arti: $line")

        _statusFlow.update { it.copy(lastLogLine = line) }

        when {
            line.contains("AMEx: state changed to Initialized", ignoreCase = true) ||
                    line.contains("AMEx: state changed to Starting", ignoreCase = true) -> {
                _statusFlow.update { it.copy(state = TorState.STARTING) }
            }

            line.contains("Sufficiently bootstrapped; system SOCKS now functional", ignoreCase = true) -> {
                _statusFlow.update {
                    it.copy(
                        bootstrapPercent = 75,
                        state = TorState.BOOTSTRAPPING
                    )
                }
            }

            line.contains("We have found that guard [scrubbed] is usable", ignoreCase = true) -> {
                _statusFlow.update {
                    it.copy(
                        bootstrapPercent = 100,
                        state = TorState.RUNNING,
                        running = true
                    )
                }
            }

            line.contains("AMEx: state changed to Stopping", ignoreCase = true) -> {
                _statusFlow.update { it.copy(state = TorState.STOPPING, running = false) }
            }

            line.contains("AMEx: state changed to Stopped", ignoreCase = true) -> {
                _statusFlow.update {
                    it.copy(
                        state = TorState.OFF,
                        running = false,
                        bootstrapPercent = 0
                    )
                }
            }

            line.contains("ERROR", ignoreCase = true) -> {
                _statusFlow.update { it.copy(state = TorState.ERROR, errorMessage = line) }
            }
        }
    }

    interface LogCallback {
        fun onLogLine(message: String?)
    }

    companion object {
        private const val TAG = "TorManager"
        private const val DEFAULT_SOCKS_PORT = 9050

        init {
            try {
                val os = System.getProperty("os.name").lowercase()
                val libName = when {
                    os.contains("mac") || os.contains("darwin") -> "arti_desktop"
                    os.contains("linux") -> "arti_desktop"
                    os.contains("windows") -> "arti_desktop"
                    else -> "arti_desktop"
                }

                System.loadLibrary(libName)
                println("$TAG: Loaded lib$libName")
            } catch (e: UnsatisfiedLinkError) {
                System.err.println("$TAG: Failed to load native library: ${e.message}")
            }
        }

        // JNI functions
        @JvmStatic
        private external fun nativeGetVersion(): String

        @JvmStatic
        private external fun nativeSetLogCallback(callback: LogCallback)

        @JvmStatic
        private external fun nativeInitialize(dataDir: String): Int

        @JvmStatic
        private external fun nativeStartSocksProxy(port: Int): Int

        @JvmStatic
        private external fun nativeStop(): Int
    }
}
