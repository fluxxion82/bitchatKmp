package com.bitchat.bluetooth.service

import com.bitchat.api.dto.mapper.toWireFormat
import com.bitchat.bluetooth.facade.CryptoSigningFacade
import com.bitchat.bluetooth.facade.NoiseEncryptionFacade
import com.bitchat.bluetooth.handler.MessageHandler
import com.bitchat.bluetooth.handler.MessageHandlerDelegate
import com.bitchat.bluetooth.manager.FragmentManager
import com.bitchat.bluetooth.manager.PeerManager
import com.bitchat.bluetooth.manager.PeerManagerDelegate
import com.bitchat.bluetooth.manager.SecurityManager
import com.bitchat.bluetooth.model.PeerInfo
import com.bitchat.bluetooth.processor.PacketProcessor
import com.bitchat.bluetooth.processor.PacketProcessorDelegate
import com.bitchat.bluetooth.protocol.BinaryProtocol
import com.bitchat.bluetooth.protocol.BitchatPacket
import com.bitchat.bluetooth.protocol.IdentityAnnouncement
import com.bitchat.bluetooth.protocol.MessageType
import com.bitchat.bluetooth.protocol.SpecialRecipients
import com.bitchat.bluetooth.protocol.logDebug
import com.bitchat.bluetooth.protocol.logError
import com.bitchat.bluetooth.protocol.logInfo
import com.bitchat.crypto.Cryptography
import com.bitchat.domain.chat.model.BitchatFilePacket
import com.bitchat.domain.chat.model.BitchatMessage
import com.bitchat.domain.chat.model.BitchatMessageType
import com.bitchat.domain.chat.model.DeliveryStatus
import com.bitchat.noise.model.NoisePayload
import com.bitchat.noise.model.NoisePayloadType
import com.bitchat.noise.model.PrivateMessagePacket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

private const val BITCHAT_SERVICE_UUID = "F47B5E2D-4A9E-4C5A-9B3F-8E1D2C3A4B5C"

private fun ByteArray.toHexString(): String {
    return this.joinToString("") { byte ->
        val value = byte.toInt() and 0xFF
        value.toString(16).padStart(2, '0')
    }
}

class BluetoothMeshService(
    private val scanningService: CentralScanningService,
    private val connectionService: BluetoothConnectionService,
    private val gattServerService: GattServerService,
    private val advertisingService: AdvertisingService,
    private val cryptoSigning: CryptoSigningFacade,
) : ConnectionEstablishedCallback {
    val myPeerID: String = cryptoSigning.getIdentityFingerprint()

    private val noiseEncryption = NoiseEncryptionFacade()
    private val peerManager = PeerManager()
    private val securityManager = SecurityManager(noiseEncryption, cryptoSigning, myPeerID)
    private val fragmentManager = FragmentManager()
    private val devicePeerLock = Mutex()
    private val peerToDevices = mutableMapOf<String, MutableSet<String>>()
    private val deviceToPeer = mutableMapOf<String, String>()
    private lateinit var messageHandler: MessageHandler
    private lateinit var packetProcessor: PacketProcessor

    var delegate: BluetoothMeshDelegate? = null

    private var isActive = false
    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private val pendingAnnounces = mutableSetOf<String>()
    private val announceMutex = Mutex()

    init {
        setupComponents()
        setupDelegates()
        wireConnectionService()
        wireGattServerDelegate()

        connectionService.setConnectionEstablishedCallback(this)

        connectionService.setConnectionReadyCallback(object : ConnectionReadyCallback {
            override fun onConnectionReady(deviceAddress: String) {
                this@BluetoothMeshService.onConnectionReady(deviceAddress)
            }
        })

        val nickname = delegate?.getNickname() ?: "Me"
        peerManager.initializeSelfPeer(
            myPeerID = myPeerID,
            myNickname = nickname,
            myNoisePublicKey = cryptoSigning.getNoisePublicKey(),
            mySigningPublicKey = cryptoSigning.getSigningPublicKey()
        )
    }

    private fun setupComponents() {
        messageHandler = MessageHandler(myPeerID, securityManager, peerManager, cryptoSigning)
        packetProcessor = PacketProcessor(myPeerID, securityManager, messageHandler)
    }

    private fun wireConnectionService() {
        // Android-specific wiring happens in BleModule
        // iOS-specific wiring will happen in iOS DI module
    }

    private fun wireGattServerDelegate() {
        gattServerService.setDelegate(object : GattServerDelegate {
            override fun onDataReceived(data: ByteArray, deviceAddress: String) {
                logInfo("BluetoothMeshService", "📥 Data received from $deviceAddress (${data.size} bytes)")
                onPacketReceived(data, deviceAddress)
            }

            override fun onClientConnected(deviceAddress: String) {
                logInfo("BluetoothMeshService", "📱 GATT client connected: $deviceAddress")
            }

            override fun onClientDisconnected(deviceAddress: String) {
                logInfo("BluetoothMeshService", "📱 GATT client disconnected: $deviceAddress")
            }
        })
    }

    fun onPacketReceived(data: ByteArray, deviceAddress: String) {
        serviceScope.launch {
            try {
                val packet = BinaryProtocol.decode(data)
                if (packet == null) {
                    val mappedPeer = devicePeerLock.withLock { deviceToPeer[deviceAddress] }
                    val preview = data.take(16).joinToString(" ") { byte ->
                        (byte.toInt() and 0xFF).toString(16).padStart(2, '0')
                    }
                    val sessionState = mappedPeer?.let { noiseEncryption.getSessionState(it) } ?: "unknown"
                    logError(
                        "BluetoothMeshService",
                        "Failed to decode packet from $deviceAddress (mapped peer: ${mappedPeer ?: "unknown"}, session: $sessionState, len=${data.size}, preview=$preview)"
                    )
                    return@launch
                }

                val peerID = packet.senderID.toHexString()
                recordPeerDeviceMapping(peerID, deviceAddress)

                val messageType = MessageType.fromValue(packet.type)?.name ?: "UNKNOWN"
                val recipientHex = packet.recipientID?.toHexString() ?: "null"
                println("🔍 BLE: Packet received - Type: $messageType, From: $peerID, RecipientID: $recipientHex, DeviceAddr: $deviceAddress")

                packetProcessor.processPacket(packet, peerID)
            } catch (e: Exception) {
                logError("BluetoothMeshService", "Error processing received packet: ${e.message}")
            }
        }
    }

    private fun setupDelegates() {
        peerManager.delegate = object : PeerManagerDelegate {
            override fun onPeerUpdated(peer: PeerInfo) {
                val peerList = peerManager.getAllPeers()
                val peerCount = peerList.size
                val peerNames = peerList.joinToString(", ") { "${it.nickname} (${it.id.take(8)})" }

                logInfo("BluetoothMeshService", "👥 Peer list updated: $peerCount peers - [$peerNames]")
                delegate?.didUpdatePeerList(peerList.map { it.id })
            }

            override fun onPeerDisconnected(peerID: String) {
                delegate?.didUpdatePeerList(peerManager.getAllPeers().map { it.id })
            }

            override fun onPeerRemoved(peerID: String) {
                delegate?.didUpdatePeerList(peerManager.getAllPeers().map { it.id })
            }
        }

        messageHandler.delegate = object : MessageHandlerDelegate {
            override fun onPeerAnnounced(peerID: String, nickname: String) {
                logInfo("BluetoothMeshService", "Peer announced: $nickname ($peerID)")
            }

            override fun onMessageReceived(peerID: String, message: String, isBroadcast: Boolean) {
                val peer = peerManager.getPeer(peerID)
                val senderName = peer?.nickname ?: "Unknown"
                val now = Clock.System.now()

                val bitchatMessage = BitchatMessage(
                    id = generateMessageID(),
                    sender = senderName,
                    content = message,
                    type = BitchatMessageType.Message,
                    timestamp = now,
                    isPrivate = !isBroadcast,
                    senderPeerID = peerID,
                    channel = null,  // Routing handled by isPrivate flag
                    deliveryStatus = DeliveryStatus.Delivered(to = myPeerID, at = now)
                )

                delegate?.didReceiveMessage(bitchatMessage)
            }

            override fun onEncryptedMessageReceived(peerID: String, message: String) {
                val peer = peerManager.getPeer(peerID)
                val senderName = peer?.nickname ?: "Unknown"
                val now = Clock.System.now()

                val bitchatMessage = BitchatMessage(
                    id = generateMessageID(),
                    sender = senderName,
                    content = message,
                    type = BitchatMessageType.Message,
                    timestamp = now,
                    isPrivate = true,
                    senderPeerID = peerID,
                    deliveryStatus = DeliveryStatus.Delivered(to = myPeerID, at = now)
                )

                delegate?.didReceiveMessage(bitchatMessage)
            }

            override fun onHandshakeReceived(peerID: String) {
                logInfo("BluetoothMeshService", "Noise handshake received from $peerID")
            }

            override fun onHandshakeResponse(peerID: String, responsePacket: ByteArray) {
                logInfo("BluetoothMeshService", "Sending handshake response to $peerID")
                sendNoiseHandshakePacket(peerID, responsePacket)
            }

            override fun onSessionEstablished(peerID: String) {
                logInfo("BluetoothMeshService", "Noise session established with $peerID")
                serviceScope.launch {
                    delegate?.onSessionEstablished(peerID)
                }
            }

            override fun onPeerLeft(peerID: String) {
                delegate?.didUpdatePeerList(peerManager.getAllPeers().map { it.id })
            }

            override fun onFragmentReceived(peerID: String) {
                logDebug("BluetoothMeshService", "Fragment received from $peerID")
            }

            override fun onFileReceived(peerID: String, filePacket: BitchatFilePacket, isBroadcast: Boolean) {
                logInfo("BluetoothMeshService", "📎 File received from $peerID: ${filePacket.fileName}")
                delegate?.didReceiveFile(peerID, filePacket, isBroadcast)
            }
        }

        packetProcessor.delegate = object : PacketProcessorDelegate {
            override fun onPacketShouldRelay(packet: BitchatPacket) {
                relayPacket(packet)
            }
        }
    }

    override fun onDeviceConnected(deviceAddress: String) {
        logInfo("BluetoothMeshService", "Device connected: $deviceAddress; queuing for announce when ready")

        serviceScope.launch {
            announceMutex.withLock {
                pendingAnnounces.add(deviceAddress)
            }
        }
    }

    fun onConnectionReady(deviceAddress: String) {
        serviceScope.launch {
            val needsAnnounce = announceMutex.withLock {
                pendingAnnounces.remove(deviceAddress)
            }

            if (needsAnnounce) {
                logInfo("BluetoothMeshService", "Connection ready: $deviceAddress; sending announce")
                sendBroadcastAnnounce()
            }
        }
    }

    fun startServices() {
        if (isActive) return
        isActive = true

        serviceScope.launch {
            advertisingService.startAdvertising(BITCHAT_SERVICE_UUID, "Bitchat-${myPeerID.take(8)}")
            gattServerService.startAdvertising()
            scanningService.startScan(lowLatency = true)

            sendBroadcastAnnounce()
            sendPeriodicBroadcastAnnounce()
        }
    }

    fun stopServices() {
        if (!isActive) return
        isActive = false

        serviceScope.launch {
            advertisingService.stopAdvertising()
            gattServerService.stopAdvertising()
            scanningService.stopScan()
            connectionService.clearConnections()
        }
    }

    fun sendPrivateMessage(content: String, recipientPeerID: String, recipientNickname: String, messageID: String? = null) {
        serviceScope.launch {
            try {
                if (!securityManager.hasEstablishedSession(recipientPeerID)) {
                    logError(
                        "BluetoothMeshService",
                        "No established session with $recipientPeerID, cannot send (handshake should be initiated by ChatRepo)"
                    )
                    // Don't initiate handshake here - that's ChatRepo's responsibility
                    // ChatRepo queues messages and initiates handshake once
                    return@launch
                }

                val messageData = buildPrivateMessagePayload(content, messageID)

                val encryptedPayload = noiseEncryption.encrypt(recipientPeerID, messageData)
                if (encryptedPayload == null) {
                    logError("BluetoothMeshService", "Failed to encrypt message for $recipientPeerID")
                    return@launch
                }

                val packet = BitchatPacket(
                    type = MessageType.NOISE_ENCRYPTED.value,
                    senderID = BitchatPacket.hexStringToByteArray(myPeerID),
                    recipientID = BitchatPacket.hexStringToByteArray(recipientPeerID),
                    timestamp = (Clock.System.now().toEpochMilliseconds()).toULong(),
                    payload = encryptedPayload,
                    ttl = 3u
                )

                broadcastPacket(packet)
            } catch (e: Exception) {
                logError("BluetoothMeshService", "Error sending private message: ${e.message}")
            }
        }
    }

    private fun buildPrivateMessagePayload(content: String, messageID: String?): ByteArray {
        val packet = PrivateMessagePacket(
            messageID = messageID ?: "",
            content = content
        )

        val tlvData = packet.encode() ?: return ByteArray(0)
        val noisePayload = NoisePayload(
            type = NoisePayloadType.PRIVATE_MESSAGE,
            data = tlvData
        )

        return noisePayload.encode()
    }

    fun sendReadReceipt(messageID: String, recipientPeerID: String, readerNickname: String) {
        // TODO: Implement read receipt
    }

    fun sendBroadcastAnnounce() {
        serviceScope.launch {
            val nickname = delegate?.getNickname() ?: "Anonymous"
            logInfo("ANNOUNCE", "Sending announce: '$nickname' (${myPeerID.take(8)}...)")

            val noisePublicKey = cryptoSigning.getNoisePublicKey()
            val signingPublicKey = cryptoSigning.getSigningPublicKey()

            val fingerprint = calculateSHA256Fingerprint(noisePublicKey)
            logInfo("ANNOUNCE", "📍 Announcing with Noise pubkey fingerprint: $fingerprint")
            logInfo("ANNOUNCE", "   Noise pubkey (hex): ${noisePublicKey.joinToString("") { it.toHexString() }}")

            val announcement = IdentityAnnouncement(
                nickname = nickname,
                noisePublicKey = noisePublicKey,
                signingPublicKey = signingPublicKey
            )

            val payload = announcement.encode() ?: run {
                logError("ANNOUNCE", "Failed to encode announcement")
                return@launch
            }

            val packet = BitchatPacket(
                type = MessageType.ANNOUNCE.value,
                ttl = 3u,
                senderID = myPeerID,
                payload = payload
            )

            broadcastPacket(packet)
        }
    }

    fun sendAnnouncementToPeer(peerID: String) {
        // TODO: Implement direct peer announcement
    }

    private fun sendPeriodicBroadcastAnnounce() {
        serviceScope.launch {
            while (isActive) {
                delay(30_000) // 30 seconds
                sendBroadcastAnnounce()
            }
        }
    }

    private fun broadcastPacket(packet: BitchatPacket) {
        serviceScope.launch {
            try {
                val packetTypeName = MessageType.entries.find { it.value == packet.type }?.name ?: "UNKNOWN"
                logInfo("BROADCAST", "Broadcasting $packetTypeName (${packet.payload.size}B, TTL:${packet.ttl})")

                val signedPacket = signPacket(packet)
                val binaryData = BinaryProtocol.encode(signedPacket)
                if (binaryData == null) {
                    logError("BROADCAST", "Failed to encode $packetTypeName")
                    return@launch
                }

                connectionService.broadcastPacket(binaryData)

            } catch (e: Exception) {
                logError("BROADCAST", "Broadcast error: ${e.message}")
            }
        }
    }

    private fun signPacket(packet: BitchatPacket): BitchatPacket {
        val dataToSign = packet.toBinaryDataForSigning()
            ?: return packet
        val signature = cryptoSigning.signPacket(dataToSign)

        return packet.copy(signature = signature)
    }

    private fun relayPacket(packet: BitchatPacket) {
        val relayPacket = packet.copy(ttl = (packet.ttl - 1u).toUByte())
        if (relayPacket.ttl > 0u) {
            broadcastPacket(relayPacket)
        }
    }

    private suspend fun recordPeerDeviceMapping(peerID: String, deviceAddress: String) {
        var isNewDevice = false
        devicePeerLock.withLock {
            val existingPeer = deviceToPeer[deviceAddress]
            if (existingPeer != peerID) {
                isNewDevice = true
                deviceToPeer[deviceAddress] = peerID
            }
            val devices = peerToDevices.getOrPut(peerID) { mutableSetOf() }
            devices.add(deviceAddress)
        }
        if (isNewDevice) {
            logDebug(
                "BluetoothMeshService",
                "Mapped device ${deviceAddress.take(8)} to peer ${peerID.take(8)}"
            )
        }
    }

    fun getPeerNicknames(): Map<String, String> {
        return peerManager.getAllPeers().associate { it.id to it.nickname }
    }

    fun hasEstablishedSession(peerID: String): Boolean {
        return securityManager.hasEstablishedSession(peerID)
    }

    fun getSessionState(peerID: String): String {
        return noiseEncryption.getSessionState(peerID)
    }

    /**
     * Initiate Noise handshake with a peer
     *
     * TODO: Implement proper Noise key management
     * For MVP, this is a placeholder - full implementation requires:
     * - Separate Noise static keypair storage
     * - Key derivation from master identity
     * - Secure key persistence
     */
    fun initiateNoiseHandshake(peerID: String) {
        serviceScope.launch {
            try {
                // Get Noise static keys from crypto signing facade
                val localPrivateKey = cryptoSigning.getNoisePrivateKey()
                val localPublicKey = cryptoSigning.getNoisePublicKey()

                // Get initial handshake message from Noise encryption facade
                val handshakeData = noiseEncryption.initiateHandshake(
                    peerID = peerID,
                    localStaticPrivateKey = localPrivateKey,
                    localStaticPublicKey = localPublicKey
                )

                // If handshakeData is empty, session already exists - don't broadcast
                if (handshakeData.isEmpty()) {
                    logInfo("BluetoothMeshService", "Session with $peerID already exists, skipping handshake initiation")
                    return@launch
                }

                // Create NOISE_HANDSHAKE packet
                val packet = BitchatPacket(
                    type = MessageType.NOISE_HANDSHAKE.value,
                    senderID = BitchatPacket.hexStringToByteArray(myPeerID),
                    recipientID = BitchatPacket.hexStringToByteArray(peerID),
                    timestamp = (Clock.System.now().toEpochMilliseconds()).toULong(),
                    payload = handshakeData,
                    ttl = 3u
                )

                // Sign and broadcast
                broadcastPacket(packet)

                logError("BluetoothMeshService", "Initiated Noise handshake with $peerID")

            } catch (e: Exception) {
                logError("BluetoothMeshService", "Error initiating handshake: ${e.message}")
            }
        }
    }

    private fun sendNoiseHandshakePacket(peerID: String, handshakeData: ByteArray) {
        serviceScope.launch {
            try {
                val packet = BitchatPacket(
                    type = MessageType.NOISE_HANDSHAKE.value,
                    senderID = BitchatPacket.hexStringToByteArray(myPeerID),
                    recipientID = BitchatPacket.hexStringToByteArray(peerID),
                    timestamp = (Clock.System.now().toEpochMilliseconds()).toULong(),
                    payload = handshakeData,
                    ttl = 3u
                )

                broadcastPacket(packet)

                logInfo("BluetoothMeshService", "Sent Noise handshake response to $peerID")

            } catch (e: Exception) {
                logError("BluetoothMeshService", "Error sending handshake response: ${e.message}")
            }
        }
    }

    fun getPeerFingerprint(peerID: String): String? {
        return peerManager.getPeer(peerID)?.signingPublicKey?.let {
            it.joinToString("") { byte ->
                val value = byte.toInt() and 0xFF
                value.toString(16).padStart(2, '0')
            }
        }
    }

    fun getPeerInfo(peerID: String): PeerInfo? {
        return peerManager.getPeer(peerID)
    }

    fun updatePeerInfo(
        peerID: String,
        nickname: String,
        noisePublicKey: ByteArray,
        signingPublicKey: ByteArray,
        isVerified: Boolean
    ): Boolean {
        peerManager.addOrUpdatePeer(
            peerID = peerID,
            nickname = nickname,
            noisePublicKey = noisePublicKey,
            signingPublicKey = signingPublicKey,
            isVerified = isVerified
        )
        return true
    }

    fun getEncryptedPeers(): List<String> {
        return peerManager.getAllPeers()
            .filter { hasEstablishedSession(it.id) }
            .map { it.id }
    }

    fun getDeviceAddressForPeer(peerID: String): String? {
        return if (devicePeerLock.tryLock()) {
            try {
                peerToDevices[peerID]?.firstOrNull()
            } finally {
                devicePeerLock.unlock()
            }
        } else {
            peerToDevices[peerID]?.firstOrNull()
        }
    }

    fun getDeviceAddressToPeerMapping(): Map<String, String> {
        return if (devicePeerLock.tryLock()) {
            try {
                deviceToPeer.toMap()
            } finally {
                devicePeerLock.unlock()
            }
        } else {
            deviceToPeer.toMap()
        }
    }

    fun printDeviceAddressesForPeers(): String {
        return if (devicePeerLock.tryLock()) {
            try {
                if (deviceToPeer.isEmpty()) {
                    "No device mappings yet"
                } else {
                    deviceToPeer.entries.joinToString(", ") { (device, peer) ->
                        "${device.take(8)} -> ${peer.take(8)}"
                    }
                }
            } finally {
                devicePeerLock.unlock()
            }
        } else {
            "No device mappings yet"
        }
    }

    fun getDebugStatus(): String {
        val peerCount = peerManager.getAllPeers().size
        val encryptedCount = getEncryptedPeers().size
        return "Bluetooth Mesh - Peers: $peerCount, Encrypted: $encryptedCount, Active: $isActive"
    }

    fun clearAllInternalData() {
        peerManager.clearAll()
        fragmentManager.shutdown()
    }

    fun clearAllEncryptionData() {
        securityManager.clearAll()
    }

    fun sendFileBroadcast(file: BitchatFilePacket) {
        serviceScope.launch {
            try {
                logInfo("BluetoothMeshService", "📎 Broadcasting file: ${file.fileName} (${file.fileSize} bytes)")

                val payload = file.toWireFormat()
                if (payload == null) {
                    logError("BluetoothMeshService", "Failed to encode file for broadcast: ${file.fileName}")
                    return@launch
                }

                // Use version 2 for payloads > 65535 bytes (v1 only supports 2-byte length field)
                val packetVersion: UByte = if (payload.size > 65535) 2u else 1u
                logInfo("BluetoothMeshService", "📎 File payload size: ${payload.size} bytes, using packet version $packetVersion")

                val packet = BitchatPacket(
                    version = packetVersion,
                    type = MessageType.FILE_TRANSFER.value,
                    senderID = BitchatPacket.hexStringToByteArray(myPeerID),
                    recipientID = SpecialRecipients.BROADCAST,
                    timestamp = (Clock.System.now().toEpochMilliseconds()).toULong(),
                    payload = payload,
                    signature = null,
                    ttl = 3u
                )

                broadcastPacket(packet)
                logInfo("BluetoothMeshService", "✅ File broadcast sent: ${file.fileName}")

            } catch (e: Exception) {
                logError("BluetoothMeshService", "Error broadcasting file: ${e.message}")
            }
        }
    }

    fun sendFilePrivate(recipientPeerID: String, file: BitchatFilePacket) {
        serviceScope.launch {
            try {
                if (!securityManager.hasEstablishedSession(recipientPeerID)) {
                    logError(
                        "BluetoothMeshService",
                        "No established session with $recipientPeerID, cannot send file"
                    )
                    return@launch
                }

                logInfo("BluetoothMeshService", "📎 Sending private file to $recipientPeerID: ${file.fileName} (${file.fileSize} bytes)")

                // Encode file to TLV
                val fileData = file.toWireFormat()
                if (fileData == null) {
                    logError("BluetoothMeshService", "Failed to encode file for private transfer: ${file.fileName}")
                    return@launch
                }

                // Wrap in NoisePayload with FILE_TRANSFER type
                val noisePayload = NoisePayload(
                    type = NoisePayloadType.FILE_TRANSFER,
                    data = fileData
                )
                val payloadBytes = noisePayload.encode()

                // Encrypt with Noise
                val encryptedPayload = noiseEncryption.encrypt(recipientPeerID, payloadBytes)
                if (encryptedPayload == null) {
                    logError("BluetoothMeshService", "Failed to encrypt file for $recipientPeerID")
                    return@launch
                }

                // Use version 2 for payloads > 65535 bytes (v1 only supports 2-byte length field)
                val packetVersion: UByte = if (encryptedPayload.size > 65535) 2u else 1u
                logInfo("BluetoothMeshService", "📎 Encrypted file payload size: ${encryptedPayload.size} bytes, using packet version $packetVersion")

                val packet = BitchatPacket(
                    version = packetVersion,
                    type = MessageType.NOISE_ENCRYPTED.value,
                    senderID = BitchatPacket.hexStringToByteArray(myPeerID),
                    recipientID = BitchatPacket.hexStringToByteArray(recipientPeerID),
                    timestamp = (Clock.System.now().toEpochMilliseconds()).toULong(),
                    payload = encryptedPayload,
                    signature = null,
                    ttl = 3u
                )

                broadcastPacket(packet)
                logInfo("BluetoothMeshService", "✅ Private file sent to $recipientPeerID: ${file.fileName}")

            } catch (e: Exception) {
                logError("BluetoothMeshService", "Error sending private file: ${e.message}")
            }
        }
    }

    fun sendMessage(content: String, mentions: List<String> = emptyList()) {
        serviceScope.launch {
            val senderIDBytes = ByteArray(8) { 0 }
            var tempID = myPeerID
            var index = 0
            while (tempID.length >= 2 && index < 8) {
                val hexByte = tempID.substring(0, 2)
                val byte = hexByte.toIntOrNull(16)?.toByte()
                if (byte != null) {
                    senderIDBytes[index] = byte
                }
                tempID = tempID.substring(2)
                index++
            }

            val packet = BitchatPacket(
                version = 1u,
                type = MessageType.MESSAGE.value,
                senderID = senderIDBytes,
                recipientID = SpecialRecipients.BROADCAST,  // Use 0xFF for legacy compatibility
                timestamp = Clock.System.now().toEpochMilliseconds().toULong(),
                payload = content.encodeToByteArray(),
                signature = null,
                ttl = 3u
            )

            broadcastPacket(packet)
        }
    }

    @OptIn(ExperimentalUuidApi::class)
    private fun generateMessageID(): String {
        return Uuid.random().toString().uppercase()
    }

    private fun calculateSHA256Fingerprint(publicKey: ByteArray): String {
        val hash = Cryptography.getDigestHash(publicKey)
        return hash.joinToString("") { it.toHexString() }
    }
}

interface BluetoothMeshDelegate {
    fun didReceiveMessage(message: BitchatMessage)
    fun didUpdatePeerList(peers: List<String>)
    fun didReceiveChannelLeave(channel: String, fromPeer: String)
    fun didReceiveDeliveryAck(messageID: String, recipientPeerID: String)
    fun didReceiveReadReceipt(messageID: String, recipientPeerID: String)
    suspend fun decryptChannelMessage(encryptedContent: ByteArray, channel: String): String?
    fun getNickname(): String?
    fun isFavorite(peerID: String): Boolean
    suspend fun onSessionEstablished(peerID: String)
    fun didReceiveFile(peerID: String, filePacket: BitchatFilePacket, isBroadcast: Boolean)
}
