package com.bitchat.tor

import com.bitchat.domain.tor.model.TorMode
import com.bitchat.domain.tor.model.TorState
import com.bitchat.domain.tor.model.TorStatus
import com.bitchat.tor.native.arti_set_status_callback
import com.bitchat.tor.native.arti_set_log_callback
import com.bitchat.tor.native.arti_start
import com.bitchat.tor.native.arti_stop_generation
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.concurrent.Volatile

@Volatile
private var activeTorManager: TorManager? = null

@OptIn(ExperimentalForeignApi::class)
private fun nativeStatusCallback(state: Int, port: Int, generation: ULong, message: CPointer<ByteVar>?) {
    activeTorManager?.handleStatus(state, port, generation, message?.toKString().orEmpty())
}

@OptIn(ExperimentalForeignApi::class)
private fun nativeLogCallback(message: CPointer<ByteVar>?) {
    println("TorManager: ${message?.toKString().orEmpty()}")
}

/** Linux Arti manager. Native callbacks are generation-tagged; their message pointer is copied here. */
@OptIn(ExperimentalForeignApi::class)
actual class TorManager actual constructor(
    private val dataDir: String,
) {
    private val lifecycle = Mutex()
    private val _statusFlow = MutableStateFlow(TorStatus(socksPort = 0))
    actual val statusFlow: StateFlow<TorStatus> = _statusFlow.asStateFlow()

    @Volatile private var activeGeneration = 0UL
    @Volatile private var stopping = false
    @Volatile private var currentPort: Int? = null

    init {
        activeTorManager = this
        arti_set_status_callback(statusCallback)
        arti_set_log_callback(logCallback)
    }

    actual val isAvailable: Boolean get() = true

    actual fun getSocksProxyAddress(): Pair<String, Int>? = currentPort
        ?.takeIf { isProxyReady() }
        ?.let { "127.0.0.1" to it }

    actual fun isProxyReady(): Boolean {
        val status = _statusFlow.value
        return status.state == TorState.RUNNING && status.running && currentPort != null
    }

    actual suspend fun start() = lifecycle.withLock {
        stopping = false
        activeGeneration += 1UL
        currentPort = null
        _statusFlow.value = TorStatus(
            mode = TorMode.ON,
            state = TorState.STARTING,
            lastLogLine = "Starting Arti",
            socksPort = 0,
            routeGeneration = activeGeneration.toLong(),
        )
        val result = arti_start(dataDir, 0, activeGeneration)
        if (result != 0) {
            nativeError(activeGeneration, "Arti start call failed: $result")
        }
    }

    actual suspend fun stop() = lifecycle.withLock {
        val generation = activeGeneration
        stopping = true
        currentPort = null
        _statusFlow.update { it.copy(running = false, bootstrapPercent = 0, state = TorState.STOPPING, socksPort = 0, routeGeneration = 0) }
        val result = arti_stop_generation(generation)
        if (result != 0) {
            nativeError(generation, "Arti stop call failed: $result")
        } else {
            _statusFlow.value = TorStatus(socksPort = 0)
        }
        activeGeneration += 1UL
        stopping = false
    }

    actual fun destroy() {
        if (activeTorManager === this) activeTorManager = null
    }

    internal fun handleStatus(state: Int, port: Int, generation: ULong, message: String) {
        if (generation != activeGeneration) return
        if (stopping && state != STATUS_STOPPED) return
        when (state) {
            STATUS_INITIALIZING -> _statusFlow.update { it.copy(state = TorState.STARTING, lastLogLine = message) }
            STATUS_SOCKS_LISTENING -> {
                if (port <= 0) return
                currentPort = port
                _statusFlow.update { it.copy(state = TorState.BOOTSTRAPPING, socksPort = port, lastLogLine = message) }
            }
            STATUS_READY -> {
                val boundPort = currentPort ?: return
                _statusFlow.update {
                    it.copy(mode = TorMode.ON, running = true, bootstrapPercent = 100, state = TorState.RUNNING, socksPort = boundPort, lastLogLine = message, errorMessage = null, routeGeneration = generation.toLong())
                }
            }
            STATUS_STOPPED -> {
                currentPort = null
                _statusFlow.value = TorStatus(socksPort = 0, lastLogLine = message)
            }
            STATUS_ERROR -> nativeError(generation, message)
        }
    }

    private fun nativeError(generation: ULong, message: String) {
        if (generation != activeGeneration) return
        currentPort = null
        _statusFlow.value = TorStatus(mode = TorMode.OFF, state = TorState.ERROR, socksPort = 0, lastLogLine = message, errorMessage = message, routeGeneration = 0)
    }

    companion object {
        private const val STATUS_INITIALIZING = 1
        private const val STATUS_SOCKS_LISTENING = 2
        private const val STATUS_READY = 3
        private const val STATUS_STOPPED = 4
        private const val STATUS_ERROR = 5

        private val statusCallback: CPointer<CFunction<(Int, Int, ULong, CPointer<ByteVar>?) -> Unit>> =
            staticCFunction(::nativeStatusCallback).reinterpret()
        private val logCallback: CPointer<CFunction<(CPointer<ByteVar>?) -> Unit>> =
            staticCFunction(::nativeLogCallback).reinterpret()
    }
}
