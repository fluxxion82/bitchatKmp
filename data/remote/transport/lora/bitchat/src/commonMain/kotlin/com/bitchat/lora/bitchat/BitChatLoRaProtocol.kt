package com.bitchat.lora.bitchat

import com.bitchat.lora.LoRaPeer
import com.bitchat.lora.LoRaProtocol
import com.bitchat.lora.bitchat.logging.LoRaLogger
import com.bitchat.lora.bitchat.logging.LoRaTags
import com.bitchat.lora.bitchat.protocol.HeartbeatPayload
import com.bitchat.lora.bitchat.protocol.LoRaAssembler
import com.bitchat.lora.bitchat.protocol.LoRaFrame
import com.bitchat.lora.bitchat.protocol.LoRaFragmenter
import com.bitchat.lora.bitchat.protocol.RangePiBeacon
import com.bitchat.lora.bitchat.radio.LoRaRadio
import com.bitchat.lora.radio.LoRaConfig
import com.bitchat.lora.radio.LoRaEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * High-level LoRa transport layer implementing the BitChat protocol.
 *
 * Handles:
 * - Fragmentation of large messages into LoRa-sized frames
 * - Reassembly of received fragments into complete messages
 * - Radio management and configuration
 * - Peer discovery via periodic heartbeat broadcasts
 * - Tracking of discovered peers with timeout-based cleanup
 *
 * Usage:
 * ```
 * val transport = BitChatLoRaProtocol(radio, fragmenter, assembler)
 * transport.deviceId = "0123456789abcdef"
 * transport.nickname = "Alice"
 * transport.start(LoRaConfig.US_915)
 *
 * // Send a message
 * transport.send(packetBytes)
 *
 * // Receive messages
 * transport.incomingMessages.collect { packet ->
 *     // Process received packet
 * }
 *
 * // Monitor peers
 * transport.peers.collect { peerList ->
 *     // Update UI with peer count
 * }
 * ```
 */
class BitChatLoRaProtocol(
    private val radio: LoRaRadio,
    private val fragmenter: LoRaFragmenter,
    private val assembler: LoRaAssembler,
    private val beaconProbeEnabled: Boolean = false
) : LoRaProtocol {

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // Peer tracking
    private val _peers = MutableStateFlow<List<LoRaPeer>>(emptyList())
    override val peers: StateFlow<List<LoRaPeer>> = _peers.asStateFlow()

    // Incoming messages (non-heartbeat packets)
    private val _incomingMessages = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
    override val incomingMessages: Flow<ByteArray> = _incomingMessages.asSharedFlow()

    // Legacy alias for backward compatibility
    val incomingPackets: Flow<ByteArray> get() = incomingMessages

    private val _transportEvents = MutableSharedFlow<TransportEvent>(extraBufferCapacity = 64)
    /** Flow of transport-level events */
    val transportEvents: Flow<TransportEvent> = _transportEvents.asSharedFlow()

    override val isReady: Boolean get() = radio.isReady

    override val protocolName: String = "BitChat"

    override var deviceId: String = ""
    override var nickname: String = ""

    /** Legacy property - use deviceId instead */
    var senderPeerID: String
        get() = deviceId
        set(value) { deviceId = value }

    // Heartbeat jobs
    private var heartbeatJob: Job? = null
    private var peerCleanupJob: Job? = null

    override suspend fun start(config: LoRaConfig): Boolean {
        LoRaLogger.i(LoRaTags.TRANSPORT, "Starting LoRa transport (BitChat protocol)")

        if (!radio.configure(config)) {
            LoRaLogger.e(LoRaTags.TRANSPORT, "Failed to configure radio")
            return false
        }

        // Start listening to radio events
        scope.launch {
            radio.events.collect { event ->
                handleRadioEvent(event)
            }
        }

        radio.startReceiving()

        startHeartbeat()
        startPeerCleanup()

        LoRaLogger.i(LoRaTags.TRANSPORT, "LoRa transport started successfully")
        emitTransportEvent(TransportEvent.Started(config))
        return true
    }

    override fun stop() {
        LoRaLogger.i(LoRaTags.TRANSPORT, "Stopping LoRa transport")

        // Cancel background jobs
        heartbeatJob?.cancel()
        heartbeatJob = null
        peerCleanupJob?.cancel()
        peerCleanupJob = null

        // Clear peer list
        scope.launch {
            _peers.emit(emptyList())
        }

        radio.stopReceiving()
        radio.close()
        scope.cancel()
        emitTransportEvent(TransportEvent.Stopped)
    }

    override suspend fun send(data: ByteArray): Boolean {
        return sendWithFlags(data, LoRaFrame.FLAG_NONE)
    }

    /**
     * Send a packet with specific flags.
     *
     * @param data Raw packet bytes
     * @param flags Frame flags (e.g., FLAG_HEARTBEAT)
     * @return true if all fragments were sent successfully
     */
    private suspend fun sendWithFlags(data: ByteArray, flags: UByte): Boolean {
        if (!radio.isReady) {
            LoRaLogger.w(LoRaTags.TRANSPORT, "Cannot send: radio not ready")
            return false
        }

        LoRaLogger.d(LoRaTags.TRANSPORT, "Sending ${data.size} bytes (flags=${flags})")

        val frames = fragmenter.fragment(data, flags)
        if (frames.isEmpty()) {
            LoRaLogger.e(LoRaTags.TRANSPORT, "Fragmentation produced no frames")
            return false
        }

        LoRaLogger.d(LoRaTags.TRANSPORT, "Fragmented into ${frames.size} frame(s)")

        var allSent = true
        for (frame in frames) {
            val frameBytes = frame.toBytes()
            if (!radio.send(frameBytes)) {
                LoRaLogger.e(LoRaTags.TRANSPORT, "Failed to send frame ${frame.fragmentIndex}/${frame.totalFragments}")
                allSent = false
                // Continue trying to send remaining frames
            }
        }

        return allSent
    }

    /**
     * Send a private message.
     *
     * @param packetBytes Serialized BitchatPacket for a private message
     */
    suspend fun sendPrivateMessage(packetBytes: ByteArray) {
        LoRaLogger.d(LoRaTags.TRANSPORT, "Sending private message: ${packetBytes.size} bytes")
        sendWithFlags(packetBytes, LoRaFrame.FLAG_DIRECT)
    }

    /**
     * Send a channel message.
     *
     * @param packetBytes Serialized BitchatPacket for a channel message
     */
    suspend fun sendChannelMessage(packetBytes: ByteArray) {
        LoRaLogger.d(LoRaTags.TRANSPORT, "Sending channel message: ${packetBytes.size} bytes")
        send(packetBytes)
    }

    /**
     * Send a delivery acknowledgment.
     *
     * @param packetBytes Serialized BitchatPacket for a delivery ack
     */
    suspend fun sendDeliveryAck(packetBytes: ByteArray) {
        LoRaLogger.d(LoRaTags.TRANSPORT, "Sending delivery ack: ${packetBytes.size} bytes")
        sendWithFlags(packetBytes, LoRaFrame.FLAG_ACK)
    }

    /**
     * Start periodic heartbeat broadcasting.
     */
    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            // Send initial heartbeat after a short delay
            delay(1000)

            while (isActive) {
                sendHeartbeat()
                delay(HEARTBEAT_INTERVAL_MS)
            }
        }
    }

    /**
     * Send a single heartbeat broadcast.
     */
    private suspend fun sendHeartbeat() {
        if (deviceId.isEmpty()) {
            LoRaLogger.w(LoRaTags.TRANSPORT, "Cannot send heartbeat: deviceId not set")
            return
        }

        val effectiveNickname = nickname.ifEmpty { "Anonymous" }

        LoRaLogger.d(LoRaTags.TRANSPORT, "📡 Sending heartbeat: $effectiveNickname ($deviceId)")

        try {
            val payload = HeartbeatPayload(
                deviceId = deviceId.take(16).padEnd(16, '0'),
                nickname = effectiveNickname.take(50)
            )
            sendWithFlags(payload.toBytes(), LoRaFrame.FLAG_HEARTBEAT)
        } catch (e: Exception) {
            LoRaLogger.e(LoRaTags.TRANSPORT, "Failed to send heartbeat", e)
        }
    }

    /**
     * Start periodic peer cleanup task.
     */
    private fun startPeerCleanup() {
        peerCleanupJob?.cancel()
        peerCleanupJob = scope.launch {
            while (isActive) {
                delay(PEER_CLEANUP_INTERVAL_MS)
                cleanupStalePeers()
            }
        }
    }

    /**
     * Remove peers that haven't been seen within the timeout period.
     */
    private suspend fun cleanupStalePeers() {
        val now = Clock.System.now()
        val currentPeers = _peers.value

        val activePeers = currentPeers.filter { peer ->
            peer.isOnline(now, PEER_TIMEOUT_SECONDS)
        }

        if (activePeers.size != currentPeers.size) {
            val removedCount = currentPeers.size - activePeers.size
            LoRaLogger.i(LoRaTags.TRANSPORT, "Removed $removedCount stale peer(s), ${activePeers.size} active")
            _peers.emit(activePeers)
        }
    }

    private suspend fun handleRadioEvent(event: LoRaEvent) {
        when (event) {
            is LoRaEvent.PacketReceived -> {
                LoRaLogger.v(LoRaTags.TRANSPORT, "Received ${event.data.size} bytes from radio")
                handleReceivedData(event.data, event.rssi, event.snr)
            }

            is LoRaEvent.RadioReady -> {
                LoRaLogger.i(LoRaTags.TRANSPORT, "Radio ready")
            }

            is LoRaEvent.SendComplete -> {
                LoRaLogger.v(LoRaTags.TRANSPORT, "Send complete for msgId=${event.messageId}")
            }

            is LoRaEvent.Error -> {
                LoRaLogger.e(LoRaTags.TRANSPORT, "Radio error: ${event.message}", event.cause)
                emitTransportEvent(TransportEvent.Error(event.message, event.cause))
            }

            is LoRaEvent.Disconnected -> {
                LoRaLogger.w(LoRaTags.TRANSPORT, "Radio disconnected")
                emitTransportEvent(TransportEvent.RadioDisconnected)
            }

            is LoRaEvent.ChannelActivity -> {
                LoRaLogger.v(LoRaTags.TRANSPORT, "Channel ${if (event.busy) "busy" else "clear"}")
            }
        }
    }

    private suspend fun handleReceivedData(data: ByteArray, rssi: Int, snr: Float) {
        when (val classification = classifyIncomingData(data, beaconProbeEnabled)) {
            is IncomingClassification.Beacon -> {
                val beacon = classification.beacon
                LoRaLogger.i(
                    LoRaTags.TRANSPORT,
                    "📶 Beacon probe: seq=${beacon.sequence} mode=${beacon.mode} chan=${beacon.channel} hz=${beacon.frequencyHz} " +
                        "(RSSI=$rssi, SNR=$snr)"
                )
                emitTransportEvent(
                    TransportEvent.BeaconProbeReceived(
                        sequence = beacon.sequence,
                        mode = beacon.mode.name,
                        channel = beacon.channel,
                        frequencyHz = beacon.frequencyHz,
                        rssi = rssi,
                        snr = snr
                    )
                )
                return
            }
            is IncomingClassification.BeaconInvalid -> {
                LoRaLogger.w(
                    LoRaTags.TRANSPORT,
                    "Beacon probe candidate rejected: ${classification.reason} (${classification.detail})"
                )
                return
            }
            IncomingClassification.Unparsed -> {
                LoRaLogger.w(LoRaTags.TRANSPORT, "Failed to parse received data as LoRaFrame")
                return
            }
            is IncomingClassification.Frame -> {
                val frame = classification.frame

                LoRaLogger.d(
                    LoRaTags.TRANSPORT,
                    "Received frame: msgId=${frame.messageId}, frag=${frame.fragmentIndex}/${frame.totalFragments}, flags=${frame.flags}"
                )

                // Check if this is a heartbeat frame
                if (frame.flags == LoRaFrame.FLAG_HEARTBEAT) {
                    handleHeartbeatFrame(frame, rssi, snr)
                    return
                }

                // Attempt to assemble into complete message
                val completePacket = assembler.processFrame(frame)
                if (completePacket != null) {
                    LoRaLogger.i(
                        LoRaTags.TRANSPORT,
                        "Complete packet assembled: ${completePacket.size} bytes (RSSI=$rssi, SNR=$snr)"
                    )
                    _incomingMessages.emit(completePacket)
                    emitTransportEvent(TransportEvent.PacketReceived(completePacket.size, rssi, snr))
                }
            }
        }
    }

    /**
     * Handle a received heartbeat frame.
     */
    private suspend fun handleHeartbeatFrame(frame: LoRaFrame, rssi: Int, snr: Float) {
        val heartbeat = HeartbeatPayload.fromBytes(frame.payload)
        if (heartbeat == null) {
            LoRaLogger.w(LoRaTags.TRANSPORT, "Failed to parse heartbeat payload")
            return
        }

        // Ignore our own heartbeats
        if (heartbeat.deviceId == deviceId.take(16).padEnd(16, '0')) {
            LoRaLogger.v(LoRaTags.TRANSPORT, "Ignoring own heartbeat")
            return
        }

        LoRaLogger.i(
            LoRaTags.TRANSPORT,
            "📡 Received heartbeat from ${heartbeat.nickname} (${heartbeat.deviceId}) RSSI=$rssi SNR=$snr"
        )

        // Update peer list
        val now = Clock.System.now()
        val newPeer = LoRaPeer(
            deviceId = heartbeat.deviceId,
            nickname = heartbeat.nickname,
            lastSeen = now,
            rssi = rssi,
            snr = snr
        )

        updatePeer(newPeer)
        emitTransportEvent(TransportEvent.PeerDiscovered(newPeer))
    }

    /**
     * Update or add a peer to the peer list.
     */
    private suspend fun updatePeer(peer: LoRaPeer) {
        val currentPeers = _peers.value.toMutableList()
        val existingIndex = currentPeers.indexOfFirst { it.deviceId == peer.deviceId }

        if (existingIndex >= 0) {
            // Update existing peer
            currentPeers[existingIndex] = peer
        } else {
            // Add new peer
            currentPeers.add(peer)
            LoRaLogger.i(LoRaTags.TRANSPORT, "New peer discovered: ${peer.nickname}")
        }

        _peers.emit(currentPeers)
    }

    private fun emitTransportEvent(event: TransportEvent) {
        scope.launch {
            _transportEvents.emit(event)
        }
    }

    companion object {
        /** Heartbeat broadcast interval in milliseconds */
        const val HEARTBEAT_INTERVAL_MS = 60_000L

        /** Peer timeout in seconds (peers not seen within this time are removed) */
        const val PEER_TIMEOUT_SECONDS = 180L

        /** Peer cleanup check interval in milliseconds */
        const val PEER_CLEANUP_INTERVAL_MS = 30_000L

        /**
         * Classify received bytes. Probe mode checks RangePi beacons first, then falls back
         * to standard LoRaFrame parsing.
         */
        fun classifyIncomingData(
            data: ByteArray,
            beaconProbeEnabled: Boolean
        ): IncomingClassification {
            if (beaconProbeEnabled) {
                when (val result = RangePiBeacon.parse(data)) {
                    is RangePiBeacon.ParseResult.Valid ->
                        return IncomingClassification.Beacon(result.beacon)
                    is RangePiBeacon.ParseResult.Invalid -> {
                        return IncomingClassification.BeaconInvalid(result.reason, result.detail)
                    }
                    RangePiBeacon.ParseResult.NotBeacon -> {
                        // Continue with regular frame parsing.
                    }
                }
            }

            val frame = LoRaFrame.fromBytes(data) ?: return IncomingClassification.Unparsed
            return IncomingClassification.Frame(frame)
        }
    }

    sealed class IncomingClassification {
        data class Beacon(val beacon: RangePiBeacon.Beacon) : IncomingClassification()
        data class BeaconInvalid(
            val reason: RangePiBeacon.InvalidReason,
            val detail: String
        ) : IncomingClassification()
        data class Frame(val frame: LoRaFrame) : IncomingClassification()
        data object Unparsed : IncomingClassification()
    }

    /**
     * Transport-level events for monitoring.
     */
    sealed class TransportEvent {
        data class Started(val config: LoRaConfig) : TransportEvent()
        data object Stopped : TransportEvent()
        data class PacketReceived(val size: Int, val rssi: Int, val snr: Float) : TransportEvent()
        data class BeaconProbeReceived(
            val sequence: UInt,
            val mode: String,
            val channel: Int,
            val frequencyHz: Int,
            val rssi: Int,
            val snr: Float
        ) : TransportEvent()
        data class Error(val message: String, val cause: Throwable?) : TransportEvent()
        data object RadioDisconnected : TransportEvent()
        data class PeerDiscovered(val peer: LoRaPeer) : TransportEvent()
    }
}
