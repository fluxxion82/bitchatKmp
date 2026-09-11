package com.bitchat.lora

import com.bitchat.lora.radio.LoRaConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Serializes radio ownership and keeps callers' flow subscriptions stable across switches. */
class LoRaProtocolManager(
    private val bitChatProtocol: Lazy<LoRaProtocol>,
    private val meshtasticProtocol: Lazy<LoRaProtocol>,
    private val meshcoreProtocol: Lazy<LoRaProtocol>,
    private val scope: CoroutineScope,
    private val readinessTimeoutMs: Long = 20_000
) : LoRaProtocol {
    private val mutex = Mutex()
    private val _activeType = MutableStateFlow(LoRaProtocolType.BITCHAT)
    val activeType = _activeType.asStateFlow()
    private val _peers = MutableStateFlow<List<LoRaPeer>>(emptyList())
    override val peers = _peers.asStateFlow()
    private val _incomingMessages = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
    override val incomingMessages = _incomingMessages.asSharedFlow()
    private var peersJob: Job? = null
    private var messagesJob: Job? = null
    private var ownsSession = false
    private var lifecycleUsed = false
    private var readySession = false
    private var activeConfig: LoRaConfig? = null
    private var requestedRadioConfig = LoRaConfig.US_915

    private val active: LoRaProtocol
        get() = when (_activeType.value) {
            LoRaProtocolType.BITCHAT -> bitChatProtocol.value
            LoRaProtocolType.MESHTASTIC -> meshtasticProtocol.value
            LoRaProtocolType.MESHCORE -> meshcoreProtocol.value
        }

    override var deviceId = ""
        set(value) { field = value; active.deviceId = value }
    override var nickname = ""
        set(value) { field = value; active.nickname = value }
    override val protocolName get() = active.protocolName
    override val isReady get() = readySession && active.isReady
    override val supportsRadioConfiguration get() = active.supportsRadioConfiguration

    /** Initialization only, before any start, stop or switch request. */
    fun setActiveType(type: LoRaProtocolType) {
        check(!lifecycleUsed && !mutex.isLocked)
        _activeType.value = type
    }

    suspend fun switchProtocol(type: LoRaProtocolType, config: LoRaConfig? = null): Boolean =
        mutex.withLock {
            lifecycleUsed = true
            if (config != null) requestedRadioConfig = config
            switchLocked(type, requestedRadioConfig)
        }

    override suspend fun start(config: LoRaConfig): Boolean =
        mutex.withLock {
            lifecycleUsed = true
            requestedRadioConfig = config
            switchLocked(_activeType.value, config)
        }

    /** A daemon's RF settings must be changed through its own configuration. */
    suspend fun reconfigure(config: LoRaConfig): Boolean = mutex.withLock {
        lifecycleUsed = true
        requestedRadioConfig = config
        if (!active.supportsRadioConfiguration) false else switchLocked(_activeType.value, config)
    }

    private suspend fun switchLocked(type: LoRaProtocolType, config: LoRaConfig): Boolean {
        if (type == _activeType.value && isReady &&
            (!active.supportsRadioConfiguration || config == activeConfig)) return true
        readySession = false
        try {
            stopLocked()
            _activeType.value = type
            val target = active
            target.deviceId = deviceId
            target.nickname = nickname
            // Subscribe before startup, which can produce the first configuration/peer events.
            peersJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                target.peers.collect { _peers.value = it }
            }
            messagesJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                target.incomingMessages.collect { _incomingMessages.emit(it) }
            }
            ownsSession = true // Even a partially failed start requires cleanup.
            val ready = withTimeoutOrNull(readinessTimeoutMs) {
                if (!target.start(config)) return@withTimeoutOrNull false
                while (!target.isReady) delay(50)
                true
            } == true
            if (!ready) {
                withContext(NonCancellable) { stopLocked() }
                return false
            }
            activeConfig = if (target.supportsRadioConfiguration) config else null
            readySession = true
            return true
        } catch (e: CancellationException) {
            withContext(NonCancellable) { cleanupAfterFailure() }
            throw e
        } catch (e: Exception) {
            println("LoRa ${_activeType.value} transition failed: ${e.message}")
            withContext(NonCancellable) { cleanupAfterFailure() }
            return false
        }
    }

    private suspend fun cleanupAfterFailure() {
        try { stopLocked() } catch (e: Exception) {
            // Retain ownsSession so a later attempt must verify this owner stops first.
            println("LoRa cleanup failed: ${e.message}")
        }
    }

    private suspend fun stopLocked() {
        readySession = false
        peersJob?.cancelAndJoin()
        messagesJob?.cancelAndJoin()
        peersJob = null
        messagesJob = null
        _peers.value = emptyList()
        if (ownsSession) active.stop()
        ownsSession = false
        activeConfig = null
    }

    override suspend fun stop() = mutex.withLock {
        lifecycleUsed = true
        withContext(NonCancellable) { stopLocked() }
    }

    override suspend fun send(data: ByteArray): Boolean = mutex.withLock {
        if (isReady) active.send(data) else false
    }
}
