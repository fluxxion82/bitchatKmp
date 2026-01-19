package com.bitchat.tor

import android.util.Log
import com.bitchat.domain.tor.model.TorMode
import com.bitchat.domain.tor.model.TorState
import com.bitchat.domain.tor.model.TorStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.min
import kotlin.time.Duration.Companion.seconds

actual class TorManager actual constructor(
    private val dataDir: String
) {
    private val _statusFlow = MutableStateFlow(
        TorStatus(
            mode = TorMode.OFF,
            running = false,
            bootstrapPercent = 0,
            lastLogLine = if (libraryLoaded) "" else "Tor library not available",
            state = TorState.OFF,
            socksPort = DEFAULT_SOCKS_PORT
        )
    )

    actual val statusFlow: StateFlow<TorStatus> = _statusFlow.asStateFlow()

    @Volatile
    private var initialized = false

    @Volatile
    private var currentPort = DEFAULT_SOCKS_PORT

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private var retryJob: Job? = null
    private var retryCount = 0

    init {
        if (libraryLoaded) {
            try {
                nativeSetLogCallback(object : LogCallback {
                    override fun onLogLine(message: String?) {
                        message?.let { handleLogLine(it) }
                    }
                })
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to set log callback", e)
            }
        } else {
            Log.w(TAG, "Tor library not loaded - Tor functionality will be unavailable")
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
        if (!libraryLoaded) {
            Log.w(TAG, "Cannot start Tor - library not loaded")
            _statusFlow.update {
                it.copy(
                    state = TorState.ERROR,
                    errorMessage = "Tor library not available. Build native libraries first."
                )
            }
            return
        }

        if (!initialized) {
            Log.i(TAG, "Initializing Arti...")
            val result = withContext(Dispatchers.IO) {
                try {
                    nativeInitialize(dataDir)
                } catch (e: Throwable) {
                    Log.e(TAG, "Failed to initialize", e)
                    _statusFlow.update { it.copy(state = TorState.ERROR, errorMessage = e.message) }
                    return@withContext -1
                }
            }

            if (result != 0) {
                Log.e(TAG, "Initialization failed: $result")
                _statusFlow.update { it.copy(state = TorState.ERROR, errorMessage = "Init failed: $result") }
                return
            }

            initialized = true
        }

        Log.i(TAG, "Starting SOCKS proxy on port $currentPort...")
        _statusFlow.update { it.copy(mode = TorMode.ON, state = TorState.STARTING) }

        val result = withContext(Dispatchers.IO) {
            try {
                nativeStartSocksProxy(currentPort)
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to start proxy", e)
                _statusFlow.update { it.copy(state = TorState.ERROR, errorMessage = e.message) }
                return@withContext -1
            }
        }

        if (result != 0) {
            Log.e(TAG, "Start proxy failed: $result")
            _statusFlow.update { it.copy(state = TorState.ERROR, errorMessage = "Start failed: $result") }
        }
    }

    actual suspend fun stop() {
        Log.i(TAG, "Stopping Tor...")
        _statusFlow.update { it.copy(state = TorState.STOPPING) }

        // Cancel any pending retry
        retryJob?.cancel()
        retryJob = null
        retryCount = 0

        if (libraryLoaded) {
            withContext(Dispatchers.IO) {
                try {
                    nativeStop()
                } catch (e: Throwable) {
                    Log.e(TAG, "Failed to stop", e)
                }
            }
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
        Log.d(TAG, "Arti: $line")

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
                retryCount = 0
                retryJob?.cancel()
                retryJob = null
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
                _statusFlow.update {
                    it.copy(
                        state = TorState.ERROR,
                        errorMessage = line,
                        running = false,  // Clear running flag on error
                        bootstrapPercent = 0  // Reset bootstrap progress
                    )
                }
                scheduleRetry()
            }
        }
    }

    private fun scheduleRetry() {
        retryJob?.cancel()

        if (retryCount >= MAX_RETRY_COUNT) {
            Log.w(TAG, "Max retry count ($MAX_RETRY_COUNT) exceeded. Not retrying.")
            return
        }

        val delaySeconds = min(30 * (1 shl retryCount), 300)
        val delay = delaySeconds.seconds

        retryCount++
        Log.i(TAG, "🔄 Scheduling Tor retry attempt $retryCount/$MAX_RETRY_COUNT in $delay...")

        retryJob = scope.launch {
            delay(delay)

            if (_statusFlow.value.state == TorState.ERROR) {
                Log.i(TAG, "🔄 Retrying Tor connection (attempt $retryCount/$MAX_RETRY_COUNT)...")
                try {
                    stop()
                    start()
                } catch (e: Exception) {
                    Log.e(TAG, "Retry attempt failed", e)
                }
            } else {
                Log.d(TAG, "Tor state changed, canceling retry")
            }
        }
    }

    interface LogCallback {
        fun onLogLine(message: String?)
    }

    companion object {
        private const val TAG = "TorManager"
        private const val DEFAULT_SOCKS_PORT = 9050
        private const val MAX_RETRY_COUNT = 5

        @Volatile
        private var libraryLoaded = false

        init {
            try {
                System.loadLibrary("arti_android")
                libraryLoaded = true
                Log.i(TAG, "Loaded libarti_android.so")
            } catch (e: UnsatisfiedLinkError) {
                libraryLoaded = false
                Log.e(TAG, "Failed to load libarti_android.so - Tor will be unavailable", e)
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
