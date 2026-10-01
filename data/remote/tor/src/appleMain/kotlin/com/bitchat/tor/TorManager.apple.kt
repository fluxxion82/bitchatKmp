package com.bitchat.tor

import com.bitchat.domain.tor.model.TorMode
import com.bitchat.domain.tor.model.TorState
import com.bitchat.domain.tor.model.TorStatus
import com.bitchat.tor.native.ARTI_STATUS_ERROR
import com.bitchat.tor.native.ARTI_STATUS_INITIALIZING
import com.bitchat.tor.native.ARTI_STATUS_READY
import com.bitchat.tor.native.ARTI_STATUS_SOCKS_LISTENING
import com.bitchat.tor.native.ARTI_STATUS_STOPPED
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Apple (iOS and macOS) Arti manager on the Linux model: generation-tagged native statuses, a
 * loopback SOCKS port chosen by Arti, readiness only on READY of the current generation,
 * `routeGeneration` published on READY and cleared on STOP/ERROR, serialized start/stop.
 *
 * Threading: [start] and [stop] run their native calls on [artiLifecycleWorker], never on the
 * caller's context (B1.1). Native statuses are copied by the C callback and queued through
 * [events]; the same worker drains the queue, so every piece of lifecycle state here is touched by
 * one thread and a status is applied only if its generation is still current (B2.5).
 */
@OptIn(ExperimentalForeignApi::class)
actual class TorManager internal constructor(
    private val dataDir: String,
    private val native: ArtiNative,
) {
    actual constructor(dataDir: String) : this(dataDir, ArtiNativeBinding)

    private val lifecycle = Mutex()
    private val _statusFlow = MutableStateFlow(TorStatus(socksPort = 0))
    actual val statusFlow: StateFlow<TorStatus> = _statusFlow.asStateFlow()

    // Lifecycle state, touched only on [artiLifecycleWorker].
    private var activeGeneration = 0UL
    private var stopping = false
    private var currentPort: Int? = null

    private val events = Channel<ArtiStatusEvent>(Channel.UNLIMITED)
    private val scope = CoroutineScope(artiLifecycleWorker + SupervisorJob())

    init {
        // The sink runs on Arti's threads: enqueue and return, nothing else.
        native.setStatusSink { state, port, generation, message ->
            events.trySend(ArtiStatusEvent(state, port, generation, message))
        }
        scope.launch {
            for (event in events) applyStatus(event)
        }
    }

    /** Arti is statically linked into this binary; failures to start show up in [statusFlow]. */
    actual val isAvailable: Boolean get() = true

    actual fun getSocksProxyAddress(): Pair<String, Int>? = _statusFlow.value
        .takeIf { it.isReady }
        ?.let { "127.0.0.1" to it.socksPort }

    actual fun isProxyReady(): Boolean = _statusFlow.value.isReady

    private val TorStatus.isReady: Boolean
        get() = state == TorState.RUNNING && running && socksPort > 0 && routeGeneration != 0L

    actual suspend fun start() = lifecycle.withLock {
        onWorker {
            stopping = false
            activeGeneration += 1UL
            val generation = activeGeneration
            currentPort = null
            _statusFlow.value = TorStatus(
                mode = TorMode.ON,
                state = TorState.STARTING,
                lastLogLine = "Starting Arti",
                socksPort = 0,
            )
            val result = native.start(dataDir, 0, generation)
            if (result != 0) {
                nativeError(generation, "Arti start call failed: $result")
            }
        }
    }

    actual suspend fun stop() = lifecycle.withLock {
        onWorker {
            val generation = activeGeneration
            stopping = true
            currentPort = null
            _statusFlow.update {
                it.copy(running = false, bootstrapPercent = 0, state = TorState.STOPPING, socksPort = 0, routeGeneration = 0)
            }
            val result = native.stopGeneration(generation)
            if (result != 0) {
                nativeError(generation, "Arti stop call failed: $result")
            } else {
                _statusFlow.value = TorStatus(socksPort = 0)
            }
            activeGeneration += 1UL
            stopping = false
        }
    }

    actual fun destroy() {
        events.close()
        scope.cancel()
    }

    private suspend fun onWorker(block: () -> Unit) = withContext(artiLifecycleWorker) { block() }

    private fun applyStatus(event: ArtiStatusEvent) {
        if (event.generation != activeGeneration) return
        if (stopping && event.state != ARTI_STATUS_STOPPED) return
        when (event.state) {
            ARTI_STATUS_INITIALIZING -> _statusFlow.update {
                it.copy(state = TorState.STARTING, lastLogLine = event.message)
            }
            ARTI_STATUS_SOCKS_LISTENING -> {
                if (event.port <= 0) return
                currentPort = event.port
                _statusFlow.update {
                    it.copy(state = TorState.BOOTSTRAPPING, socksPort = event.port, lastLogLine = event.message)
                }
            }
            ARTI_STATUS_READY -> {
                val boundPort = currentPort ?: return
                _statusFlow.update {
                    it.copy(
                        mode = TorMode.ON,
                        running = true,
                        bootstrapPercent = 100,
                        state = TorState.RUNNING,
                        socksPort = boundPort,
                        lastLogLine = event.message,
                        errorMessage = null,
                        routeGeneration = event.generation.toLong(),
                    )
                }
            }
            ARTI_STATUS_STOPPED -> {
                // Only the stop this manager asked for is terminal here: `arti_start` also stops a
                // leftover generation first and tags that STOPPED with the new generation's number,
                // which must not reset the STARTING status just published.
                if (!stopping) return
                currentPort = null
                _statusFlow.value = TorStatus(socksPort = 0, lastLogLine = event.message)
            }
            ARTI_STATUS_ERROR -> nativeError(event.generation, event.message)
        }
    }

    private fun nativeError(generation: ULong, message: String) {
        if (generation != activeGeneration) return
        currentPort = null
        _statusFlow.value = TorStatus(
            mode = TorMode.OFF,
            state = TorState.ERROR,
            socksPort = 0,
            lastLogLine = message,
            errorMessage = message,
            routeGeneration = 0,
        )
    }
}
