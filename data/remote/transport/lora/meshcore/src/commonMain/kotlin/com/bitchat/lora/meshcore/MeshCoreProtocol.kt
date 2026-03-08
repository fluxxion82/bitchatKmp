package com.bitchat.lora.meshcore

import com.bitchat.lora.LoRaPeer
import com.bitchat.lora.LoRaProtocol
import com.bitchat.lora.radio.LoRaConfig
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

/**
 * MeshCore protocol implementation for LoRa communication.
 *
 * Communicates with MeshCore companion radio daemon via TCP using the official
 * MeshCore companion protocol. Supports the full startup sequence:
 *
 * 1. CMD_DEVICE_QUERY -> RESP_CODE_DEVICE_INFO
 * 2. CMD_APP_START -> RESP_CODE_SELF_INFO
 * 3. CMD_GET_CONTACTS -> CONTACTS_START -> CONTACT*N -> END_OF_CONTACTS
 * 4. CMD_SYNC_NEXT_MESSAGE loop -> messages or NO_MORE_MESSAGES
 * 5. Listen for push notifications (PUSH_CODE_*)
 */
class MeshCoreProtocol(
    private val serial: MeshCoreSerial
) : LoRaProtocol {

    private var scope: CoroutineScope? = null

    // Peer tracking from contacts
    private val _peers = MutableStateFlow<List<LoRaPeer>>(emptyList())
    override val peers: StateFlow<List<LoRaPeer>> = _peers.asStateFlow()

    // Incoming text messages
    private val _incomingMessages = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
    override val incomingMessages: Flow<ByteArray> = _incomingMessages.asSharedFlow()

    // Transport events for debugging/monitoring
    private val _events = MutableSharedFlow<MeshCoreEvent>(extraBufferCapacity = 64)
    val events: Flow<MeshCoreEvent> = _events.asSharedFlow()

    override val isReady: Boolean get() = serial.isConnected && selfInfoReceived
    override val protocolName: String = "MeshCore"

    override var deviceId: String = ""
    override var nickname: String = ""

    // Device/self info state
    private var myPublicKey: ByteArray = ByteArray(32)
    private var deviceInfoReceived = false
    private var selfInfoReceived = false
    private var channelConfigured = false
    private var contactsLastmod: Long = 0

    // Contact map: pubkey hex -> Contact info
    private val contacts = mutableMapOf<String, MeshCoreResponse.Contact>()

    // Jobs for background tasks
    private var initJob: Job? = null
    private var reconnectJob: Job? = null
    private var advertJob: Job? = null
    private var syncJob: Job? = null

    // Reconnection state
    private var reconnecting = false

    companion object {
        /** Interval between self-advertisements */
        const val ADVERT_INTERVAL_MS = 60_000L

        /** Init timeout */
        const val INIT_TIMEOUT_MS = 10_000L

        /** Delay between startup sequence steps */
        const val STEP_DELAY_MS = 500L

        /** Default channel name for BitChat group messaging */
        const val DEFAULT_CHANNEL_NAME = "Bitchat"

        /** Default channel index to use for send/receive */
        const val DEFAULT_CHANNEL_IDX = 0

        /**
         * Default channel secret key (128-bit / 16 bytes).
         * All devices must share this key to communicate on the channel.
         */
        val DEFAULT_CHANNEL_SECRET: ByteArray = byteArrayOf(
            0xe8.toByte(), 0x89.toByte(), 0x17.toByte(), 0x02.toByte(),
            0x5f.toByte(), 0xdb.toByte(), 0x74.toByte(), 0x80.toByte(),
            0x78.toByte(), 0x82.toByte(), 0xa1.toByte(), 0x0e.toByte(),
            0xaa.toByte(), 0x0b.toByte(), 0x87.toByte(), 0x96.toByte()
        )
    }

    override suspend fun start(config: LoRaConfig): Boolean {
        println("📡 Starting MeshCore protocol")

        scope?.cancel()
        scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

        serial.onDisconnect = {
            handleDisconnect()
        }

        if (!serial.open()) {
            println("❌ Failed to open MeshCore serial connection")
            scope?.cancel()
            scope = null
            return false
        }

        // Start listening for responses
        startIncomingListener()

        // Wait for connection to stabilize
        delay(STEP_DELAY_MS)

        // Begin startup sequence: Step 1 - Device Query
        beginStartupSequence()

        println("✅ MeshCore protocol started")
        emitEvent(MeshCoreEvent.Started)
        return true
    }

    override fun stop() {
        println("📡 Stopping MeshCore protocol")

        initJob?.cancel()
        initJob = null
        reconnectJob?.cancel()
        reconnectJob = null
        advertJob?.cancel()
        advertJob = null
        syncJob?.cancel()
        syncJob = null

        serial.onDisconnect = null
        serial.close()
        scope?.cancel()
        scope = null

        _peers.value = emptyList()
        contacts.clear()
        deviceInfoReceived = false
        selfInfoReceived = false
        channelConfigured = false
        reconnecting = false
    }

    override suspend fun send(data: ByteArray): Boolean {
        println("📡 MeshCoreProtocol.send() called with ${data.size} bytes")

        if (!isReady) {
            println("❌ Cannot send: MeshCore not ready")
            return false
        }

        val now = Clock.System.now().epochSeconds
        val command = MeshCoreCommands.sendChannelMessage(
            channelIdx = DEFAULT_CHANNEL_IDX,
            text = data.decodeToString(),
            timestamp = now
        )
        return if (serial.send(command)) {
            println("✅ MeshCore channel message sent")
            emitEvent(MeshCoreEvent.MessageSent)
            true
        } else {
            println("❌ Failed to send MeshCore message")
            false
        }
    }

    /**
     * Send a direct message to a specific contact.
     *
     * @param pubKeyPrefix First 6 bytes of recipient's public key
     * @param text Message text
     */
    suspend fun sendDirectMessage(pubKeyPrefix: ByteArray, text: String): Boolean {
        if (!isReady) {
            println("❌ Cannot send: MeshCore not ready")
            return false
        }

        val now = Clock.System.now().epochSeconds
        val command = MeshCoreCommands.sendTextMessage(
            pubKeyPrefix = pubKeyPrefix,
            text = text,
            timestamp = now
        )
        return if (serial.send(command)) {
            println("✅ Direct message sent")
            emitEvent(MeshCoreEvent.MessageSent)
            true
        } else {
            println("❌ Failed to send direct message")
            false
        }
    }

    /**
     * Broadcast self-advertisement to discover peers.
     */
    suspend fun sendAdvert(flood: Boolean = false): Boolean {
        if (!serial.isConnected) return false
        return serial.send(MeshCoreCommands.sendSelfAdvert(flood))
    }

    /**
     * Request contact list from device.
     */
    suspend fun refreshContacts(): Boolean {
        if (!serial.isConnected) return false
        return serial.send(MeshCoreCommands.getContacts(since = contactsLastmod))
    }

    // --- Startup sequence ---

    private fun beginStartupSequence() {
        initJob?.cancel()
        initJob = scope?.launch {
            // Step 1: CMD_DEVICE_QUERY
            var attempts = 0
            while (isActive && !deviceInfoReceived) {
                attempts++
                println("📡 Sending CMD_DEVICE_QUERY (attempt $attempts)")
                serial.send(MeshCoreCommands.deviceQuery())
                delay(2000)

                if (attempts >= 5) {
                    println("⚠️ Device query timeout after $attempts attempts")
                    emitEvent(MeshCoreEvent.InitTimeout)
                    break
                }
            }
        }
    }

    /**
     * Continue startup after receiving DEVICE_INFO: send CMD_APP_START.
     */
    private fun sendAppStart() {
        scope?.launch {
            delay(STEP_DELAY_MS)
            println("📡 Sending CMD_APP_START")
            serial.send(MeshCoreCommands.appStart())
        }
    }

    /**
     * Continue startup after receiving SELF_INFO: configure channel, then request contacts.
     */
    private fun configureChannelAndRequestContacts() {
        scope?.launch {
            // Configure the default channel with our shared secret
            if (!channelConfigured) {
                delay(STEP_DELAY_MS)
                println("Setting channel $DEFAULT_CHANNEL_IDX: '$DEFAULT_CHANNEL_NAME'")
                serial.send(
                    MeshCoreCommands.setChannel(
                        channelIdx = DEFAULT_CHANNEL_IDX,
                        name = DEFAULT_CHANNEL_NAME,
                        secret = DEFAULT_CHANNEL_SECRET
                    )
                )
                channelConfigured = true
                delay(STEP_DELAY_MS)

                // Verify channel configuration
                println("📋 Verifying channel $DEFAULT_CHANNEL_IDX configuration...")
                serial.send(MeshCoreCommands.getChannel(DEFAULT_CHANNEL_IDX))
                delay(STEP_DELAY_MS)
            }

            // Now request contacts
            println("Sending CMD_GET_CONTACTS (since=0)")
            serial.send(MeshCoreCommands.getContacts(since = 0))
        }
    }

    /**
     * After contacts loaded, drain offline message queue.
     */
    private fun drainOfflineMessages() {
        syncJob?.cancel()
        syncJob = scope?.launch {
            delay(STEP_DELAY_MS)
            println("📡 Draining offline message queue...")
            serial.send(MeshCoreCommands.syncNextMessage())
        }
    }

    /**
     * Sync device time to our clock.
     */
    private fun syncDeviceTime() {
        scope?.launch {
            val now = Clock.System.now().epochSeconds
            serial.send(MeshCoreCommands.setDeviceTime(now))
        }
    }

    // --- Incoming message handling ---

    private fun startIncomingListener() {
        scope?.launch {
            serial.incoming.collect { data ->
                handleResponse(data)
            }
        }
    }

    private suspend fun handleResponse(data: ByteArray) {
        val response = MeshCoreParser.parseResponse(data)

        when (response) {
            is MeshCoreResponse.DeviceInfo -> handleDeviceInfo(response)
            is MeshCoreResponse.SelfInfo -> handleSelfInfo(response)
            is MeshCoreResponse.ContactsStart -> {
                println("📋 Receiving ${response.count} contacts...")
            }
            is MeshCoreResponse.Contact -> handleContact(response)
            is MeshCoreResponse.EndOfContacts -> {
                contactsLastmod = response.mostRecentLastmod
                println("📋 End of contacts (lastmod=${response.mostRecentLastmod})")
                updatePeerList()
                // Start draining offline messages
                drainOfflineMessages()
            }
            is MeshCoreResponse.ContactMessage -> handleContactMessage(response)
            is MeshCoreResponse.ChannelMessage -> handleChannelMessage(response)
            is MeshCoreResponse.Sent -> {
                println("📤 Message sent: type=${response.msgType}, ack=${response.expectedAck}, timeout=${response.timeoutMs}ms")
            }
            is MeshCoreResponse.DeviceTime -> {
                println("⏰ Device time: ${response.unixTime}")
            }
            is MeshCoreResponse.NoMoreMessages -> {
                println("📋 No more offline messages")
                syncJob?.cancel()
                syncJob = null
                // Startup sequence complete - start periodic tasks
                startAdvertLoop()
                syncDeviceTime()
            }
            is MeshCoreResponse.Ok -> {
                println("✅ MeshCore: OK")
            }
            is MeshCoreResponse.Error -> {
                println("❌ MeshCore error: code=${response.errorCode}")
                emitEvent(MeshCoreEvent.Error("Error code: ${response.errorCode}"))
            }
            is MeshCoreResponse.BatteryAndStorage -> {
                println("🔋 Battery: ${response.batteryMv}mV")
            }
            is MeshCoreResponse.ChannelInfo -> {
                println("Channel ${response.channelIdx}: '${response.name}' secret=${response.secret.toHexString()}")
            }

            // Push notifications
            is MeshCoreResponse.PushAdvert -> {
                val keyHex = response.publicKey.toHexString()
                println("📢 Push: advert from $keyHex")
                emitEvent(MeshCoreEvent.AdvertReceived(keyHex))
            }
            is MeshCoreResponse.PushPathUpdated -> {
                val keyHex = response.publicKey.toHexString()
                println("🔄 Push: path updated for $keyHex")
            }
            is MeshCoreResponse.PushSendConfirmed -> {
                println("✅ Push: send confirmed, ack=${response.ackCode}, RTT=${response.roundTripMs}ms")
                emitEvent(MeshCoreEvent.SendConfirmed(response.ackCode, response.roundTripMs))
            }
            is MeshCoreResponse.PushMsgWaiting -> {
                println("📬 Push: message waiting")
                // Trigger sync to retrieve the message
                scope?.launch {
                    serial.send(MeshCoreCommands.syncNextMessage())
                }
            }
            is MeshCoreResponse.PushNewAdvert -> {
                handleContact(response.contact)
            }
            is MeshCoreResponse.Unknown -> {
                val hex = response.data.toHexString()
                println("❓ Unknown response: code=0x${(response.code.toInt() and 0xFF).toString(16)}, data=$hex")
            }
        }
    }

    private fun handleDeviceInfo(info: MeshCoreResponse.DeviceInfo) {
        println("📱 Device info: fw=${info.firmwareVer}, model=${info.model}, ver=${info.semanticVersion}")
        println("   Build: ${info.buildDate}, contacts=${info.maxContacts}, channels=${info.maxChannels}")

        deviceInfoReceived = true
        initJob?.cancel()
        emitEvent(MeshCoreEvent.DeviceInfoReceived(info.model, info.semanticVersion))

        // Step 2: Send CMD_APP_START
        sendAppStart()
    }

    private fun handleSelfInfo(info: MeshCoreResponse.SelfInfo) {
        myPublicKey = info.publicKey
        deviceId = info.publicKey.copyOfRange(0, 6).toHexString()
        nickname = info.name

        println("📱 Self info: ${info.name} ($deviceId)")
        println("   Type=${info.advType}, TX=${info.txPower}dBm, max=${info.maxTxPower}dBm")
        println("   Radio: ${info.radioFreq}kHz, BW=${info.radioBw}Hz, SF=${info.radioSf}, CR=${info.radioCr}")
        println("   Location: ${info.lat}, ${info.lon}")

        // Check if radio params match expected US config and correct if needed
        correctRadioParamsIfNeeded(info)

        selfInfoReceived = true
        emitEvent(MeshCoreEvent.SelfInfoReceived(info.name, info.txPower))

        // Step 3: Configure channel and request contacts
        configureChannelAndRequestContacts()
    }

    /**
     * Check if radio params match expected US config and send CMD_SET_RADIO_PARAMS if not.
     * MeshCore phone network: 910.525 MHz, BW=62.5kHz, SF=7, CR=5.
     */
    private fun correctRadioParamsIfNeeded(info: MeshCoreResponse.SelfInfo) {
        val expectedFreqKhz = 910_525L
        val expectedBwHz = 62_500L
        val expectedSf = 7
        val expectedCr = 5

        if (info.radioFreq != expectedFreqKhz ||
            info.radioBw != expectedBwHz ||
            info.radioSf != expectedSf ||
            info.radioCr != expectedCr) {

            println("⚠️ Radio params mismatch! Current: ${info.radioFreq}kHz, Expected: ${expectedFreqKhz}kHz")
            println("   Sending CMD_SET_RADIO_PARAMS: freq=${expectedFreqKhz}kHz, BW=${expectedBwHz}Hz, SF=$expectedSf, CR=$expectedCr")

            scope?.launch {
                delay(STEP_DELAY_MS)
                serial.send(
                    MeshCoreCommands.setRadioParams(
                        freqKhz = expectedFreqKhz,
                        bwHz = expectedBwHz,
                        sf = expectedSf,
                        cr = expectedCr
                    )
                )
                // Force TX→IDLE→RX cycle to recover radio from standby
                delay(STEP_DELAY_MS)
                println("📢 Sending self-advert to force radio back to RX mode")
                serial.send(MeshCoreCommands.sendSelfAdvert())
            }
        } else {
            println("✅ Radio params OK: ${info.radioFreq}kHz, BW=${info.radioBw}Hz, SF=${info.radioSf}, CR=${info.radioCr}")
        }
    }

    private suspend fun handleContact(contact: MeshCoreResponse.Contact) {
        val keyHex = contact.publicKey.toHexString()

        // Skip self
        if (contact.publicKey.contentEquals(myPublicKey)) {
            return
        }

        val pathInfo = if (contact.outPathLen < 0) "no path" else "${contact.outPathLen} hops"
        println("👤 Contact: ${contact.name} (${keyHex.take(12)}...) type=${contact.type} $pathInfo")

        contacts[keyHex] = contact
        emitEvent(MeshCoreEvent.ContactDiscovered(keyHex, contact.name))
        updatePeerList()
    }

    private suspend fun handleContactMessage(message: MeshCoreResponse.ContactMessage) {
        val prefixHex = message.pubKeyPrefix.toHexString()
        val senderContact = findContactByPrefix(message.pubKeyPrefix)
        val senderName = senderContact?.name ?: "Mesh-${prefixHex.takeLast(4)}"

        val snrInfo = message.snr?.let { " SNR=${it}dB" } ?: ""
        println("💬 DM from $senderName: ${message.text}$snrInfo")

        // Format as "nickname:content" for compatibility with ChatRepo
        val formattedMessage = "$senderName:${message.text}"
        _incomingMessages.emit(formattedMessage.encodeToByteArray())

        emitEvent(MeshCoreEvent.TextMessageReceived(prefixHex, message.text))

        // If we got this from sync, request the next message
        if (syncJob?.isActive == true) {
            scope?.launch {
                delay(100)
                serial.send(MeshCoreCommands.syncNextMessage())
            }
        }
    }

    private suspend fun handleChannelMessage(message: MeshCoreResponse.ChannelMessage) {
        val snrInfo = message.snr?.let { " SNR=${it}dB" } ?: ""
        println("📢 Channel[${message.channelIdx}]: ${message.text}$snrInfo")

        // Channel messages don't have sender identity in v2, format without name
        val formattedMessage = "channel:${message.text}"
        _incomingMessages.emit(formattedMessage.encodeToByteArray())

        emitEvent(MeshCoreEvent.ChannelMessageReceived(message.channelIdx, message.text))

        // If we got this from sync, request the next message
        if (syncJob?.isActive == true) {
            scope?.launch {
                delay(100)
                serial.send(MeshCoreCommands.syncNextMessage())
            }
        }
    }

    /**
     * Find a contact by matching the first 6 bytes of their public key.
     */
    private fun findContactByPrefix(prefix: ByteArray): MeshCoreResponse.Contact? {
        return contacts.values.find { contact ->
            contact.publicKey.size >= 6 &&
                    contact.publicKey[0] == prefix[0] &&
                    contact.publicKey[1] == prefix[1] &&
                    contact.publicKey[2] == prefix[2] &&
                    contact.publicKey[3] == prefix[3] &&
                    contact.publicKey[4] == prefix[4] &&
                    contact.publicKey[5] == prefix[5]
        }
    }

    // --- Peer list management ---

    private fun updatePeerList() {
        val peerList = contacts.values
            .filter { it.type != ContactType.NONE }
            .map { contact ->
                LoRaPeer(
                    deviceId = contact.publicKey.copyOfRange(0, 6).toHexString(),
                    nickname = contact.name,
                    lastSeen = kotlin.time.Instant.fromEpochSeconds(contact.lastAdvert),
                    rssi = 0,
                    snr = 0f
                )
            }

        _peers.value = peerList
    }

    // --- Reconnection ---

    private fun handleDisconnect() {
        if (reconnecting) return

        reconnectJob?.cancel()
        reconnectJob = scope?.launch {
            println("⚠️ MeshCore connection lost, attempting reconnection...")
            emitEvent(MeshCoreEvent.ConnectionLost)
            reconnecting = true
            deviceInfoReceived = false
            selfInfoReceived = false

            var attempt = 0
            var delayMs = 2000L
            val maxAttempts = 10

            while (isActive && !serial.isConnected) {
                attempt++
                println("🔄 Reconnection attempt $attempt/$maxAttempts...")
                emitEvent(MeshCoreEvent.Reconnecting)

                if (serial.open()) {
                    println("✅ Reconnected to MeshCore daemon")
                    startIncomingListener()
                    delay(STEP_DELAY_MS)
                    beginStartupSequence()
                    emitEvent(MeshCoreEvent.Reconnected)
                    reconnecting = false
                    return@launch
                }

                delay(delayMs)
                delayMs = minOf(delayMs * 2, 30_000L)

                if (attempt >= maxAttempts) {
                    println("❌ Failed to reconnect after $attempt attempts")
                    emitEvent(MeshCoreEvent.ReconnectFailed(attempt))
                    reconnecting = false
                    return@launch
                }
            }
            reconnecting = false
        }
    }

    // --- Periodic tasks ---

    private fun startAdvertLoop() {
        advertJob?.cancel()
        advertJob = scope?.launch {
            // Send first advert immediately to force radio into RX mode
            // (TX cycle: STANDBY -> TX -> IDLE -> startReceive -> RX)
            if (serial.isConnected) {
                println("📢 Sending initial self-advert to kick radio into RX mode")
                sendAdvert()
            }
            while (isActive && serial.isConnected) {
                delay(ADVERT_INTERVAL_MS)
                if (serial.isConnected) {
                    println("📢 Broadcasting self-advertisement")
                    sendAdvert()
                }
            }
        }
    }

    private fun emitEvent(event: MeshCoreEvent) {
        scope?.launch {
            _events.emit(event)
        }
    }

    /**
     * MeshCore protocol events for monitoring and debugging.
     */
    sealed class MeshCoreEvent {
        data object Started : MeshCoreEvent()
        data object Stopped : MeshCoreEvent()
        data object ConnectionLost : MeshCoreEvent()
        data object Reconnecting : MeshCoreEvent()
        data object Reconnected : MeshCoreEvent()
        data class ReconnectFailed(val attempts: Int) : MeshCoreEvent()
        data object InitTimeout : MeshCoreEvent()
        data class DeviceInfoReceived(val model: String, val version: String) : MeshCoreEvent()
        data class SelfInfoReceived(val name: String, val txPower: Int) : MeshCoreEvent()
        data class ContactDiscovered(val pubKeyHex: String, val name: String) : MeshCoreEvent()
        data object MessageSent : MeshCoreEvent()
        data class TextMessageReceived(val from: String, val text: String) : MeshCoreEvent()
        data class ChannelMessageReceived(val channelIdx: Int, val text: String) : MeshCoreEvent()
        data class AdvertReceived(val pubKeyHex: String) : MeshCoreEvent()
        data class SendConfirmed(val ackCode: Long, val roundTripMs: Long) : MeshCoreEvent()
        data class Error(val message: String) : MeshCoreEvent()
    }
}
