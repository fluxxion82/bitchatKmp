package com.bitchat.lora.meshtastic

import com.bitchat.lora.LoRaPeer
import com.bitchat.lora.LoRaProtocol
import com.bitchat.lora.meshtastic.proto.AdminMessage
import com.bitchat.lora.meshtastic.proto.Channel
import com.bitchat.lora.meshtastic.proto.ChannelSettings
import com.bitchat.lora.meshtastic.proto.Channel_Role
import com.bitchat.lora.meshtastic.proto.Config
import com.bitchat.lora.meshtastic.proto.Data
import com.bitchat.lora.meshtastic.proto.FromRadio
import com.bitchat.lora.meshtastic.proto.LoRaConfig as MeshtasticLoRaConfig
import com.bitchat.lora.meshtastic.proto.MeshPacket
import com.bitchat.lora.meshtastic.proto.ModemPreset
import com.bitchat.lora.meshtastic.proto.PortNum
import com.bitchat.lora.meshtastic.proto.RegionCode
import com.bitchat.lora.meshtastic.proto.ToRadio
import com.bitchat.lora.radio.LoRaConfig
import okio.ByteString.Companion.decodeBase64
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
import okio.ByteString.Companion.toByteString
import kotlin.random.Random
import kotlin.time.Clock

/**
 * Meshtastic protocol implementation for LoRa communication.
 *
 * Communicates with Meshtastic devices via USB serial, using the
 * Meshtastic protobuf protocol (ToRadio/FromRadio messages).
 *
 * Peer discovery uses the built-in Meshtastic NodeDB - no heartbeats needed.
 * The device maintains a list of all nodes it has heard from.
 *
 * Usage:
 * ```
 * val protocol = MeshtasticProtocol(serial)
 * protocol.start()
 *
 * // Send a text message
 * protocol.send("Hello Meshtastic!".encodeToByteArray())
 *
 * // Receive messages
 * protocol.incomingMessages.collect { bytes ->
 *     val text = bytes.decodeToString()
 * }
 *
 * // Monitor peers
 * protocol.peers.collect { peerList ->
 *     println("Nodes on mesh: ${peerList.size}")
 * }
 * ```
 */
class MeshtasticProtocol(
    private val serial: MeshtasticSerial
) : LoRaProtocol {

    // Scope is created fresh on each start() and cancelled on stop()
    private var scope: CoroutineScope? = null

    // Peer tracking from NodeDB
    private val _peers = MutableStateFlow<List<LoRaPeer>>(emptyList())
    override val peers: StateFlow<List<LoRaPeer>> = _peers.asStateFlow()

    // Incoming text messages
    private val _incomingMessages = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
    override val incomingMessages: Flow<ByteArray> = _incomingMessages.asSharedFlow()

    // Transport events for debugging/monitoring
    private val _events = MutableSharedFlow<MeshtasticEvent>(extraBufferCapacity = 64)
    val events: Flow<MeshtasticEvent> = _events.asSharedFlow()

    override val isReady: Boolean get() = serial.isConnected && configReceived
    override val protocolName: String = "Meshtastic"

    override var deviceId: String = ""
    override var nickname: String = ""

    // My node number from the device
    private var myNodeNum: Int = 0

    // Whether we've received initial config from device
    private var configReceived = false

    // Active channel index for sending messages (0 = PRIMARY)
    private var activeChannelIndex: Int = 0

    // Whether channel has been configured
    private var channelConfigured = false

    // Job for config request
    private var configJob: Job? = null

    // Pending config request ID
    private var pendingConfigId: Int = 0

    // Track whether LoRa and channel config have been applied (to avoid restart loops)
    private var loraConfigApplied = false
    private var channelConfigApplied = false

    // Track reconnection state
    private var reconnecting = false

    // Job for reconnection attempts
    private var reconnectJob: Job? = null

    companion object {
        /** Broadcast address for mesh-wide messages */
        const val BROADCAST_ADDR: Int = 0xFFFFFFFF.toInt()

        /** Default hop limit for messages */
        const val DEFAULT_HOP_LIMIT = 3

        /** Config request timeout in ms */
        const val CONFIG_TIMEOUT_MS = 10_000L

        /** Interval between config retries */
        const val CONFIG_RETRY_MS = 2_000L

        // Hardcoded channel config for testing
        // TODO: Move to settings/UI
        private const val TEST_CHANNEL_NAME = "Bitchat"
        private const val TEST_CHANNEL_PSK = "tz8EP6R+4iEffYy9gskR0fYaEt/DZmq5veITy5fMIKE="

        /** Frequency slot (determines RF frequency within region band) */
        const val TEST_FREQUENCY_SLOT = 20
    }

    override suspend fun start(config: LoRaConfig): Boolean {
        println("📡 Starting Meshtastic protocol")

        // Create fresh scope for this session
        scope?.cancel()
        scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

        // Subscribe to disconnect notifications for auto-reconnection
        serial.onDisconnect = {
            handleDisconnect()
        }

        if (!serial.open()) {
            println("❌ Failed to open Meshtastic serial connection")
            scope?.cancel()
            scope = null
            return false
        }

        // Start listening for FromRadio messages
        startIncomingListener()

        // Wait for meshtasticd to be ready before sending config request
        // This delay helps ensure the TCP connection is fully established
        // and meshtasticd is ready to process incoming frames
        delay(500)

        // Request initial config from device
        requestConfig()

        println("✅ Meshtastic protocol started")
        emitEvent(MeshtasticEvent.Started)
        return true
    }

    /**
     * Start listening for FromRadio messages.
     * Extracted to allow restarting after reconnection.
     */
    private fun startIncomingListener() {
        scope?.launch {
            serial.incoming.collect { data ->
                handleFromRadio(data)
            }
        }
    }

    /**
     * Handle disconnection from meshtasticd.
     * Attempts to reconnect with exponential backoff.
     */
    private fun handleDisconnect() {
        // Prevent multiple reconnection attempts
        if (reconnecting) {
            println("⚠️ Already reconnecting, ignoring disconnect notification")
            return
        }

        reconnectJob?.cancel()
        reconnectJob = scope?.launch {
            println("⚠️ Meshtastic connection lost, attempting reconnection...")
            emitEvent(MeshtasticEvent.ConnectionLost)
            reconnecting = true
            configReceived = false

            var attempt = 0
            var delayMs = 2000L
            val maxAttempts = 10

            while (isActive && !serial.isConnected) {
                attempt++
                println("🔄 Reconnection attempt $attempt/$maxAttempts...")
                emitEvent(MeshtasticEvent.Reconnecting)

                if (serial.open()) {
                    println("✅ Reconnected to meshtasticd")

                    // Restart the incoming listener
                    startIncomingListener()

                    // Wait for connection to stabilize
                    delay(500)

                    // Request config (but don't reconfigure LoRa/channel if already done)
                    requestConfig()

                    emitEvent(MeshtasticEvent.Reconnected)
                    reconnecting = false
                    return@launch
                }

                // Exponential backoff: 2s -> 4s -> 8s -> 16s -> 30s (max)
                delay(delayMs)
                delayMs = minOf(delayMs * 2, 30_000L)

                if (attempt >= maxAttempts) {
                    println("❌ Failed to reconnect after $attempt attempts")
                    emitEvent(MeshtasticEvent.ReconnectFailed(attempt))
                    reconnecting = false
                    return@launch
                }
            }
            reconnecting = false
        }
    }

    override fun stop() {
        println("📡 Stopping Meshtastic protocol")

        reconnectJob?.cancel()
        reconnectJob = null
        configJob?.cancel()
        configJob = null

        serial.onDisconnect = null
        serial.close()
        scope?.cancel()
        scope = null

        _peers.value = emptyList()
        configReceived = false
        channelConfigured = false
        activeChannelIndex = 0
        myNodeNum = 0
        loraConfigApplied = false
        channelConfigApplied = false
        reconnecting = false
    }

    /**
     * Send a text message over Meshtastic.
     *
     * @param data UTF-8 encoded text message
     * @return true if sent successfully
     */
    override suspend fun send(data: ByteArray): Boolean {
        println("📡 MeshtasticProtocol.send() called with ${data.size} bytes")
        println("📡 MeshtasticProtocol: isReady=$isReady, myNodeNum=$myNodeNum, configReceived=$configReceived")
        return sendTextMessage(data, BROADCAST_ADDR)
    }

    /**
     * Send a text message to a specific node.
     *
     * @param text UTF-8 encoded text
     * @param toNode Destination node number (or BROADCAST_ADDR)
     * @return true if sent successfully
     */
    suspend fun sendTextMessage(text: ByteArray, toNode: Int, channelIndex: Int = activeChannelIndex): Boolean {
        if (!isReady) {
            println("❌ Cannot send: Meshtastic not ready (isConnected=${serial.isConnected}, configReceived=$configReceived)")
            return false
        }

        val packetId = Random.nextInt() and 0x7FFFFFFF

        val dataPacket = Data(
            portnum = PortNum.TEXT_MESSAGE_APP,
            payload = text.toByteString(),
            want_response = false
        )

        val meshPacket = MeshPacket(
            from = myNodeNum,
            to = toNode,
            channel = channelIndex,
            decoded = dataPacket,
            id = packetId,
            hop_limit = DEFAULT_HOP_LIMIT,
            want_ack = toNode != BROADCAST_ADDR
        )

        val toRadio = ToRadio(packet = meshPacket)
        val encoded = ToRadio.ADAPTER.encode(toRadio)

        val destStr = if (toNode == BROADCAST_ADDR) "broadcast" else toNode.toString(16).padStart(8, '0')
        println("📡 TX: id=${packetId.toString(16).padStart(8, '0')} from=${myNodeNum.toString(16).padStart(8, '0')} to=$destStr ch=$channelIndex len=${text.size}")
        println("📤 Sending text message: \"${text.decodeToString().take(50)}${if (text.size > 50) "..." else ""}\"")

        return if (serial.send(encoded)) {
            emitEvent(MeshtasticEvent.MessageSent(packetId))
            println("✅ TX queued: packet ${packetId.toString(16).padStart(8, '0')}")
            true
        } else {
            println("❌ Failed to send message - serial.send() returned false")
            false
        }
    }

    /**
     * Configure a channel on the Meshtastic device.
     *
     * @param index Channel index (0 = PRIMARY, 1-7 = SECONDARY)
     * @param name Channel name (e.g., "bikTest")
     * @param psk Base64-encoded PSK (32 bytes for AES256)
     * @return true if sent successfully
     */
    suspend fun setChannel(index: Int, name: String, psk: String): Boolean {
        if (myNodeNum == 0) {
            println("❌ Cannot set channel: myNodeNum not known yet")
            return false
        }

        // Decode base64 PSK
        val pskBytes = psk.decodeBase64()
        if (pskBytes == null) {
            println("❌ Invalid base64 PSK")
            return false
        }

        println("🔧 MeshtasticProtocol.setChannel($index, \"$name\", psk=${pskBytes.size} bytes)")
        println("🔧 Channel config: index=$index, role=${if (index == 0) "PRIMARY" else "SECONDARY"}")

        // Build ChannelSettings
        val settings = ChannelSettings(
            name = name,
            psk = pskBytes
        )

        // Build Channel
        val role = if (index == 0) Channel_Role.PRIMARY else Channel_Role.SECONDARY
        val channel = Channel(
            index = index,
            settings = settings,
            role = role
        )

        // Build AdminMessage
        val admin = AdminMessage(set_channel = channel)
        val adminBytes = AdminMessage.ADAPTER.encode(admin)

        // Wrap in Data with ADMIN_APP port
        // want_response=true is important for admin messages to be processed correctly
        val data = Data(
            portnum = PortNum.ADMIN_APP,
            payload = adminBytes.toByteString(),
            want_response = true
        )

        val packetId = Random.nextInt() and 0x7FFFFFFF

        // Send to self (local config change)
        val packet = MeshPacket(
            from = myNodeNum,
            to = myNodeNum,
            decoded = data,
            id = packetId,
            want_ack = true
        )

        val toRadio = ToRadio(packet = packet)
        val encoded = ToRadio.ADAPTER.encode(toRadio)

        return if (serial.send(encoded)) {
            println("✅ Channel config sent for channel $index ($name)")
            activeChannelIndex = index
            channelConfigured = true
            emitEvent(MeshtasticEvent.ChannelConfigured(index, name))
            true
        } else {
            println("❌ Failed to send channel config")
            false
        }
    }

    /**
     * Set the LoRa frequency slot (channel_num).
     *
     * This determines the actual RF frequency within the region's band.
     * For US region with LONG_FAST preset, slot 20 = ~906.875 MHz.
     *
     * @param frequencySlot The frequency slot (0-31 typical for US)
     * @param region The region code (defaults to US)
     * @param modemPreset The modem preset (defaults to LONG_FAST)
     * @return true if sent successfully
     */
    suspend fun setLoRaConfig(
        frequencySlot: Int,
        region: RegionCode = RegionCode.US,
        modemPreset: ModemPreset = ModemPreset.LONG_FAST
    ): Boolean {
        if (myNodeNum == 0) {
            println("❌ Cannot set LoRa config: myNodeNum not known yet")
            return false
        }

        println("📻 Setting LoRa config: slot=$frequencySlot, region=$region, preset=$modemPreset")

        // Build LoRaConfig (using aliased import to avoid conflict with com.bitchat.lora.radio.LoRaConfig)
        val loraConfig = MeshtasticLoRaConfig(
            use_preset = true,
            modem_preset = modemPreset,
            region = region,
            channel_num = frequencySlot,
            tx_enabled = true,
            hop_limit = DEFAULT_HOP_LIMIT
        )

        // Wrap in Config
        val config = Config(lora = loraConfig)

        // Build AdminMessage
        val admin = AdminMessage(set_config = config)
        val adminBytes = AdminMessage.ADAPTER.encode(admin)

        // Wrap in Data with ADMIN_APP port
        val data = Data(
            portnum = PortNum.ADMIN_APP,
            payload = adminBytes.toByteString(),
            want_response = true
        )

        val packetId = Random.nextInt() and 0x7FFFFFFF

        // Send to self (local config change)
        val packet = MeshPacket(
            from = myNodeNum,
            to = myNodeNum,
            decoded = data,
            id = packetId,
            want_ack = true
        )

        val toRadio = ToRadio(packet = packet)
        val encoded = ToRadio.ADAPTER.encode(toRadio)

        return if (serial.send(encoded)) {
            println("✅ LoRa config sent: slot=$frequencySlot")
            emitEvent(MeshtasticEvent.LoRaConfigured(frequencySlot, region, modemPreset))
            true
        } else {
            println("❌ Failed to send LoRa config")
            false
        }
    }

    /**
     * Request config and NodeDB from the Meshtastic device.
     */
    private fun requestConfig() {
        configJob?.cancel()
        configJob = scope?.launch {
            var attempts = 0
            while (isActive && !configReceived) {
                attempts++
                println("📡 Requesting Meshtastic config (attempt $attempts)")

                pendingConfigId = Random.nextInt() and 0x7FFFFFFF

                val toRadio = ToRadio(want_config_id = pendingConfigId)
                val encoded = ToRadio.ADAPTER.encode(toRadio)
                serial.send(encoded)

                delay(CONFIG_RETRY_MS)

                if (attempts >= 5) {
                    println("⚠️ Config request timeout after $attempts attempts")
                    emitEvent(MeshtasticEvent.ConfigTimeout)
                    break
                }
            }
        }
    }

    /**
     * Handle an incoming FromRadio message.
     */
    private suspend fun handleFromRadio(data: ByteArray) {
        try {
            val fromRadio = FromRadio.ADAPTER.decode(data)

            // Handle different payload types
            when {
                fromRadio.my_info != null -> {
                    val myInfo = fromRadio.my_info
                    myNodeNum = myInfo.my_node_num
                    deviceId = myNodeNum.toString(16).padStart(8, '0')
                    println("📱 Meshtastic node: ${myInfo.my_node_num} firmware=${myInfo.firmware_version}")
                    emitEvent(MeshtasticEvent.MyInfoReceived(myInfo.my_node_num, myInfo.firmware_version ?: ""))
                }

                fromRadio.node_info != null -> {
                    handleNodeInfo(fromRadio.node_info)
                }

                fromRadio.config_complete_id != null -> {
                    if (fromRadio.config_complete_id == pendingConfigId) {
                        println("✅ Meshtastic config complete")
                        configReceived = true
                        configJob?.cancel()
                        emitEvent(MeshtasticEvent.ConfigComplete)

                        // Only configure LoRa and channel if not already done
                        // This prevents endless restart loops (setting config causes meshtasticd to restart)
                        if (!loraConfigApplied || !channelConfigApplied) {
                            scope?.launch {
                                delay(500)

                                // Set frequency slot first (determines RF frequency)
                                if (!loraConfigApplied) {
                                    println("📻 Setting LoRa frequency slot to $TEST_FREQUENCY_SLOT...")
                                    if (setLoRaConfig(TEST_FREQUENCY_SLOT)) {
                                        loraConfigApplied = true
                                    }
                                } else {
                                    println("📻 LoRa config already applied, skipping")
                                }

                                // Then set channel (determines encryption)
                                delay(500)
                                if (!channelConfigApplied) {
                                    println("🔧 Setting channel 0 to $TEST_CHANNEL_NAME...")
                                    if (setChannel(0, TEST_CHANNEL_NAME, TEST_CHANNEL_PSK)) {
                                        channelConfigApplied = true
                                    }
                                } else {
                                    println("🔧 Channel config already applied, skipping")
                                }
                            }
                        } else {
                            println("📡 Config already applied, ready to send messages")
                        }
                    }
                }

                fromRadio.packet != null -> {
                    handleMeshPacket(fromRadio.packet)
                }

                fromRadio.channel != null -> {
                    val ch = fromRadio.channel
                    val chName = ch.settings?.name ?: "(unnamed)"
                    val chRole = ch.role?.name ?: "UNKNOWN"
                    println("📻 Channel ${ch.index}: $chName ($chRole)")
                    // If this is the channel we're interested in, note it
                    if (ch.settings?.name == TEST_CHANNEL_NAME) {
                        println("✅ Found our channel: $TEST_CHANNEL_NAME at index ${ch.index}")
                        activeChannelIndex = ch.index
                    }
                }

                fromRadio.rebooted == true -> {
                    // Only handle reboot if we had already received config.
                    // During initial startup, we're already requesting config,
                    // so no need to re-request on reboot flag.
                    if (configReceived) {
                        println("🔄 Meshtastic device rebooted")
                        configReceived = false
                        requestConfig()
                        emitEvent(MeshtasticEvent.DeviceRebooted)
                    }
                }

                // Ignore log_record - too verbose
            }
        } catch (e: Exception) {
            val hexBytes = data.take(50).joinToString(" ") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
            println("❌ Failed to decode FromRadio: ${e.message}")
            println("   Raw bytes (first 50): $hexBytes")
        }
    }

    /**
     * Handle NodeInfo from the device's NodeDB.
     */
    private suspend fun handleNodeInfo(nodeInfo: com.bitchat.lora.meshtastic.proto.NodeInfo) {
        val user = nodeInfo.user
        val nodeNum = nodeInfo.num

        // Skip our own node
        if (nodeNum == myNodeNum) {
            return
        }

        val peerDeviceId = nodeNum.toString(16).padStart(8, '0')
        val peerNickname = user?.long_name ?: user?.short_name ?: "Node-${peerDeviceId.takeLast(4)}"
        val lastHeard = nodeInfo.last_heard.toLong()

        println("👤 Node: $peerNickname ($peerDeviceId) lastHeard=$lastHeard hops=${nodeInfo.hops_away}")

        val peer = LoRaPeer(
            deviceId = peerDeviceId,
            nickname = peerNickname,
            lastSeen = kotlin.time.Instant.fromEpochSeconds(lastHeard),
            rssi = 0, // NodeInfo doesn't include RSSI
            snr = nodeInfo.snr
        )

        updatePeer(peer)
        emitEvent(MeshtasticEvent.NodeDiscovered(peerDeviceId, peerNickname))
    }

    /**
     * Handle a received MeshPacket.
     */
    private suspend fun handleMeshPacket(packet: MeshPacket) {
        val decoded = packet.decoded
        if (decoded == null) {
            // Encrypted packet we can't decode
            println("🔒 Received encrypted packet from ${packet.from.toString(16)}")
            return
        }

        println("📬 MeshPacket from=${packet.from.toString(16)} port=${decoded.portnum}")

        when (decoded.portnum) {
            PortNum.TEXT_MESSAGE_APP -> {
                val rawText = decoded.payload.toByteArray().decodeToString()
                val senderDeviceId = packet.from.toString(16).padStart(8, '0')

                // Look up sender's nickname from peer list
                val senderNickname = _peers.value
                    .find { it.deviceId == senderDeviceId }
                    ?.nickname
                    ?: "Mesh-${senderDeviceId.takeLast(4)}"

                println("💬 Text message from $senderNickname: $rawText")

                // Format as "nickname:content" for compatibility with ChatRepo
                val formattedMessage = "$senderNickname:$rawText"
                _incomingMessages.emit(formattedMessage.encodeToByteArray())

                emitEvent(MeshtasticEvent.TextMessageReceived(
                    from = senderDeviceId,
                    text = rawText
                ))

                // Update peer last seen
                updatePeerLastSeen(packet.from, packet.rx_rssi, packet.rx_snr)
            }

            PortNum.NODEINFO_APP -> {
                // NodeInfo packets update our peer list
                println("📡 NodeInfo packet received")
            }

            PortNum.POSITION_APP -> {
                println("📍 Position packet received")
            }

            PortNum.TELEMETRY_APP -> {
                println("📊 Telemetry packet received")
            }

            PortNum.ROUTING_APP -> {
                // This is an ACK/NAK for a packet we sent (or routing info)
                val fromHex = packet.from.toString(16).padStart(8, '0')
                val idHex = packet.id.toString(16).padStart(8, '0')
                println("📬 ROUTING: from=$fromHex id=$idHex")
                // The routing payload contains ErrorReason if it's a NAK
                val routingBytes = decoded.payload.toByteArray()
                if (routingBytes.isNotEmpty()) {
                    val hexPayload = routingBytes.joinToString(" ") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
                    println("📬 ROUTING payload: $hexPayload")
                }
            }

            else -> {
                println("📦 Other packet type: ${decoded.portnum}")
            }
        }
    }

    /**
     * Update or add a peer to the peer list.
     */
    private suspend fun updatePeer(peer: LoRaPeer) {
        val currentPeers = _peers.value.toMutableList()
        val existingIndex = currentPeers.indexOfFirst { it.deviceId == peer.deviceId }

        if (existingIndex >= 0) {
            currentPeers[existingIndex] = peer
        } else {
            currentPeers.add(peer)
            println("👥 New peer: ${peer.nickname}")
        }

        _peers.emit(currentPeers)
    }

    /**
     * Update a peer's last seen time based on received packet.
     */
    private suspend fun updatePeerLastSeen(nodeNum: Int, rssi: Int, snr: Float) {
        val deviceId = nodeNum.toString(16).padStart(8, '0')
        val currentPeers = _peers.value.toMutableList()
        val existingIndex = currentPeers.indexOfFirst { it.deviceId == deviceId }

        if (existingIndex >= 0) {
            currentPeers[existingIndex] = currentPeers[existingIndex].copy(
                lastSeen = Clock.System.now(),
                rssi = rssi,
                snr = snr
            )
            _peers.emit(currentPeers)
        }
    }

    private fun emitEvent(event: MeshtasticEvent) {
        scope?.launch {
            _events.emit(event)
        }
    }

    /**
     * Meshtastic protocol events for monitoring and debugging.
     */
    sealed class MeshtasticEvent {
        data object Started : MeshtasticEvent()
        data object Stopped : MeshtasticEvent()
        data object ConfigComplete : MeshtasticEvent()
        data object ConfigTimeout : MeshtasticEvent()
        data object DeviceRebooted : MeshtasticEvent()
        data object ConnectionLost : MeshtasticEvent()
        data object Reconnecting : MeshtasticEvent()
        data object Reconnected : MeshtasticEvent()
        data class ReconnectFailed(val attempts: Int) : MeshtasticEvent()
        data class MyInfoReceived(val nodeNum: Int, val firmwareVersion: String) : MeshtasticEvent()
        data class NodeDiscovered(val deviceId: String, val nickname: String) : MeshtasticEvent()
        data class MessageSent(val packetId: Int) : MeshtasticEvent()
        data class TextMessageReceived(val from: String, val text: String) : MeshtasticEvent()
        data class ChannelConfigured(val index: Int, val name: String) : MeshtasticEvent()
        data class LoRaConfigured(val frequencySlot: Int, val region: RegionCode, val modemPreset: ModemPreset) : MeshtasticEvent()
    }
}
