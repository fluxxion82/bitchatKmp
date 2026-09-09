package com.bitchat.bluetooth.service

import com.bitchat.api.dto.mapper.toWireFormat
import com.bitchat.bluetooth.facade.CryptoSigningFacade
import com.bitchat.bluetooth.facade.NoiseEncryptionFacade
import com.bitchat.bluetooth.handler.MessageHandler
import com.bitchat.bluetooth.handler.MessageHandlerDelegate
import com.bitchat.bluetooth.manager.HandshakeRefreshPolicy
import com.bitchat.bluetooth.manager.FragmentManager
import com.bitchat.bluetooth.manager.HandshakeSupervisor
import com.bitchat.bluetooth.manager.PeerLinkDirectory
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
import kotlin.concurrent.Volatile
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

    private val noiseEncryption = NoiseEncryptionFacade(myPeerID)
    private val peerManager = PeerManager()
    private val securityManager = SecurityManager(noiseEncryption, cryptoSigning, myPeerID)
    private val fragmentManager = FragmentManager()
    private val devicePeerLock = Mutex()
    private val peerLinks = PeerLinkDirectory()

    // A lock-free view of [peerLinks] for the connection service, which has to answer "is this
    // address the peer I am already linked to?" while deciding whether to connect and cannot
    // suspend to take devicePeerLock.
    @Volatile
    private var peerLinkSnapshot: Map<String, String> = emptyMap()
    private lateinit var messageHandler: MessageHandler
    private lateinit var packetProcessor: PacketProcessor

    var delegate: BluetoothMeshDelegate? = null

    private var isActive = false
    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private val pendingAnnounces = mutableSetOf<String>()
    private val announceMutex = Mutex()

    // Deadline and retry budget for handshakes still in flight. Guarded by handshakeMutex
    // because the sweeper and initiateNoiseHandshake both run on serviceScope's dispatcher.
    private val handshakeSupervisor = HandshakeSupervisor()
    private val handshakeMutex = Mutex()

    private val handshakeRefreshPolicy = HandshakeRefreshPolicy()

    // Peers this node owes a handshake to but could not send one for, because no link could carry
    // it. They are retried when a link to them comes up rather than on a timer. Guarded by
    // handshakeMutex.
    private val handshakesOwed = mutableSetOf<String>()

    // When the handshake with a peer was last (re)started, so two link-up signals for the same
    // link -- the inbound packet that binds the address and the outbound connection becoming
    // ready, which the journal shows 315ms apart -- do not tear down a handshake that has only
    // just been sent. Guarded by handshakeMutex.
    private val handshakeStartedAt = mutableMapOf<String, Long>()

    init {
        setupComponents()
        setupDelegates()
        wireConnectionService()

        connectionService.setConnectionEstablishedCallback(this)

        // Let the connection service recognise a peer it is already linked to when the peer turns
        // up under a rotated address. Reads the lock-free snapshot: it is called while a connection
        // decision is being taken and must not suspend.
        connectionService.setPeerAddressLookup { deviceAddress ->
            val snapshot = peerLinkSnapshot
            val peerID = snapshot[deviceAddress] ?: return@setPeerAddressLookup emptySet()
            snapshot.filterValues { it == peerID }.keys
        }

        connectionService.setConnectionReadyCallback(object : ConnectionReadyCallback {
            override fun onConnectionReady(deviceAddress: String) {
                this@BluetoothMeshService.onConnectionReady(deviceAddress)
            }
        })

        // Use connection service's packet callback instead of directly setting GATT server delegate
        // This allows the connection service to track server client connections properly
        connectionService.setOnPacketReceivedCallback(object : OnPacketReceivedCallback {
            override fun onPacketReceived(data: ByteArray, deviceAddress: String) {
                logInfo("BluetoothMeshService", "📥 Data received from $deviceAddress (${data.size} bytes)")
                this@BluetoothMeshService.onPacketReceived(data, deviceAddress)
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

    fun onPacketReceived(data: ByteArray, deviceAddress: String) {
        serviceScope.launch {
            try {
                val packet = BinaryProtocol.decode(data)
                if (packet == null) {
                    val mappedPeer = devicePeerLock.withLock { peerLinks.peerFor(deviceAddress) }
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

                // Our own packets come back to us: the mesh relays what it receives, including onto
                // the link it arrived on, so a peer forwards our packet straight back. Binding that
                // to the sending address pointed the peer's address at our own ID, and the peer's
                // next packet then looked like a brand new link -- which tore down whatever
                // handshake was in flight. Nothing downstream could undo it, because the binding had
                // already happened by the time the self check ran.
                if (peerID == myPeerID) {
                    return@launch
                }

                val binding = recordPeerDeviceMapping(peerID, deviceAddress)
                if (binding.isNewLink) {
                    releaseSupersededLinks(peerID, binding.superseded)
                    onPeerLinkRefreshed(peerID)
                }

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

            override fun onSessionUnusable(peerID: String) {
                // The session says it is established and cannot read what the peer sends, so it is
                // not the session the peer is using. Discard it and open a fresh handshake;
                // initiateNoiseHandshake refuses while one is established, so the removal has to
                // come first. SessionFailureTracker rate limits this, so a peer that can forge a
                // packet cannot use it to demand handshakes.
                serviceScope.launch {
                    logInfo("BluetoothMeshService", "Rebuilding the unusable Noise session with $peerID")
                    noiseEncryption.removeSession(peerID)
                    initiateNoiseHandshake(peerID)
                }
            }

            override fun onSessionEstablished(peerID: String) {
                logInfo("BluetoothMeshService", "Noise session established with $peerID")
                serviceScope.launch {
                    handshakeMutex.withLock {
                        handshakeSupervisor.reset(peerID)
                        handshakesOwed.remove(peerID)
                        handshakeStartedAt.remove(peerID)
                    }
                    delegate?.onSessionEstablished(peerID)
                }
            }

            override fun onPeerLeft(peerID: String) {
                serviceScope.launch {
                    handshakeMutex.withLock {
                        handshakesOwed.remove(peerID)
                        handshakeStartedAt.remove(peerID)
                        handshakeSupervisor.reset(peerID)
                    }
                }
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

            // A link we already know the peer behind has just come back up. If we bind the address
            // later (the usual case with rotating addresses) the same re-arm happens from
            // onPacketReceived instead.
            devicePeerLock.withLock { peerLinks.peerFor(deviceAddress) }?.let {
                onPeerLinkRefreshed(it)
            }
        }
    }

    /**
     * A peer has reappeared on a link we know is live — either a fresh address bound to it by an
     * incoming packet, or a reconnect on an address we had already mapped.
     *
     * A handshake still in flight at this point cannot complete: our message went out over the
     * link that has just been replaced, and the peer never saw it. Discard the stalled session and
     * start over, and give the peer a fresh retry budget, since its history says nothing about a
     * link it did not have.
     */
    private fun onPeerLinkRefreshed(peerID: String) {
        serviceScope.launch {
            val now = Clock.System.now().toEpochMilliseconds()
            val owed = handshakeMutex.withLock {
                handshakeSupervisor.reset(peerID)
                peerID in handshakesOwed
            }
            val startedAt = handshakeMutex.withLock { handshakeStartedAt[peerID] }

            when (handshakeRefreshPolicy.decide(
                established = noiseEncryption.hasEstablishedSession(peerID),
                handshaking = noiseEncryption.isHandshaking(peerID),
                ourHandshakeStartedAt = startedAt,
                now = now,
                owed = owed
            )) {
                HandshakeRefreshPolicy.Decision.LEAVE -> Unit

                HandshakeRefreshPolicy.Decision.INITIATE -> {
                    logInfo(
                        "BluetoothMeshService",
                        "Peer $peerID is reachable again; sending the handshake it is owed"
                    )
                    initiateNoiseHandshake(peerID)
                }

                HandshakeRefreshPolicy.Decision.RESTART -> {
                    logInfo(
                        "BluetoothMeshService",
                        "Peer $peerID reappeared with our handshake in flight; restarting it on the new link"
                    )
                    noiseEncryption.removeSession(peerID)
                    initiateNoiseHandshake(peerID)
                }
            }
        }
    }

    /**
     * Abandon handshakes that have been in flight past the deadline and, while the peer still
     * looks reachable and the retry budget allows, start a fresh one.
     *
     * Without this a single lost handshake packet is permanent: [NoiseEncryptionFacade
     * .initiateHandshake] returns empty while a session is handshaking, so nothing can ever
     * replace it and every later direct message to that peer is queued and never sent.
     *
     * A session that is past the deadline but is still inside its retry backoff is deliberately
     * left in place: it is the marker that says a handshake is owed, and dropping it early would
     * lose the only record that the retry is still coming.
     */
    internal suspend fun sweepStalledHandshakes(now: Long) {
        val inFlight = noiseEncryption.handshakesInFlight()
        if (inFlight.isEmpty()) return

        inFlight.forEach { (peerID, startedAt) ->
            if (!handshakeSupervisor.isExpired(startedAt, now)) return@forEach

            val exhausted = handshakeMutex.withLock { handshakeSupervisor.isExhausted(peerID) }
            if (exhausted) {
                logInfo(
                    "BluetoothMeshService",
                    "Noise handshake with $peerID stalled for ${now - startedAt}ms and the retry " +
                        "budget is spent; discarding the session and giving up until the peer returns"
                )
                noiseEncryption.removeSession(peerID)
                return@forEach
            }

            val mayRetry = handshakeMutex.withLock { handshakeSupervisor.mayAttempt(peerID, now) }
            if (!mayRetry) return@forEach

            val attempts = handshakeMutex.withLock { handshakeSupervisor.attemptsFor(peerID) }
            logInfo(
                "BluetoothMeshService",
                "Noise handshake with $peerID stalled for ${now - startedAt}ms after $attempts " +
                    "attempt(s); discarding the session"
            )
            noiseEncryption.removeSession(peerID)

            if (peerManager.isPeerActive(peerID)) {
                // initiateNoiseHandshake defers to the link coming up if nothing can carry it, so
                // a peer that is "active" only because its announce is still inside the three
                // minute window no longer costs an attempt.
                initiateNoiseHandshake(peerID)
            } else {
                logInfo(
                    "BluetoothMeshService",
                    "Not retrying the handshake with $peerID: the peer is no longer active"
                )
            }
        }
    }

    private fun startHandshakeSweeper() {
        serviceScope.launch {
            while (isActive) {
                delay(HandshakeSupervisor.SWEEP_INTERVAL_MS)
                try {
                    sweepStalledHandshakes(Clock.System.now().toEpochMilliseconds())
                } catch (e: Exception) {
                    logError("BluetoothMeshService", "Handshake sweep failed: ${e.message}")
                }
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
            startHandshakeSweeper()
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
        serviceScope.launch { sendPacket(packet) }
    }

    /**
     * Sign, encode and hand [packet] to the link layer.
     *
     * @return true when at least one live link carried it. Callers that keep a retry budget need
     *   this: a send into no links at all used to be indistinguishable from a delivered one, so a
     *   Noise handshake spent all five of its attempts while `clients:0, servers:0` -- thirty such
     *   broadcasts in one twelve-minute window of the device journal.
     */
    private suspend fun sendPacket(packet: BitchatPacket): Boolean {
        return try {
            val packetTypeName = MessageType.entries.find { it.value == packet.type }?.name ?: "UNKNOWN"
            logInfo("BROADCAST", "Broadcasting $packetTypeName (${packet.payload.size}B, TTL:${packet.ttl})")

            val signedPacket = signPacket(packet)
            val binaryData = BinaryProtocol.encode(signedPacket)
            if (binaryData == null) {
                logError("BROADCAST", "Failed to encode $packetTypeName")
                return false
            }

            connectionService.broadcastPacket(binaryData)
        } catch (e: Exception) {
            logError("BROADCAST", "Broadcast error: ${e.message}")
            false
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

    /** Bind [deviceAddress] to [peerID], reporting whether this is a link the peer did not hold. */
    private suspend fun recordPeerDeviceMapping(
        peerID: String,
        deviceAddress: String
    ): PeerLinkDirectory.Binding {
        val binding = devicePeerLock.withLock {
            peerLinks.bind(peerID, deviceAddress).also { peerLinkSnapshot = peerLinks.snapshot() }
        }
        if (binding.isNewLink) {
            logDebug(
                "BluetoothMeshService",
                "Mapped device ${deviceAddress.take(8)} to peer ${peerID.take(8)}"
            )
        }
        return binding
    }

    /**
     * Drop the links a peer used before it turned up on [superseded]'s replacement.
     *
     * BLE allows one link between two devices, so an address a peer has moved off names either a
     * link that is already gone or a duplicate of the one it is using now. Holding those open costs
     * the controller a connection slot it does not have -- on the embedded radio that budget is
     * what the churn was spending -- and keeps the GATT server notifying into nothing.
     */
    private suspend fun releaseSupersededLinks(peerID: String, superseded: List<String>) {
        if (superseded.isEmpty()) return

        logInfo(
            "BluetoothMeshService",
            "Peer ${peerID.take(8)} moved address; releasing ${superseded.size} superseded " +
                "link(s): ${superseded.joinToString { it.take(8) }}"
        )
        superseded.forEach { address ->
            devicePeerLock.withLock {
                peerLinks.release(address)
                peerLinkSnapshot = peerLinks.snapshot()
            }
            connectionService.disconnectDeviceByAddress(address)
        }
    }

    fun getPeerNicknames(): Map<String, String> {
        return peerManager.getAllPeers().associate { it.id to it.nickname }
    }

    fun hasEstablishedSession(peerID: String): Boolean {
        return securityManager.hasEstablishedSession(peerID)
    }

    /**
     * True while a handshake with [peerID] has been started and has neither completed nor been
     * abandoned. Callers that queue a message use this to decide whether initiating again would
     * be a duplicate or the only thing that will ever unstick the peer.
     */
    fun isHandshakeInFlight(peerID: String): Boolean {
        return noiseEncryption.isHandshaking(peerID)
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

                // An attempt only counts once a link has actually carried it. Counting a send
                // into nothing burned the budget while the peer could not possibly answer, and by
                // the time a link existed the peer had none left.
                val delivered = sendPacket(packet)
                if (!delivered) {
                    logInfo(
                        "BluetoothMeshService",
                        "No live link can carry a handshake to $peerID; it will be sent when one " +
                            "comes up"
                    )
                    // The session is dropped rather than left handshaking: initiateHandshake()
                    // returns empty while one exists, so keeping it would block the retry that the
                    // link coming up is about to ask for.
                    noiseEncryption.removeSession(peerID)
                    handshakeMutex.withLock { handshakesOwed.add(peerID) }
                    return@launch
                }

                val attempts = handshakeMutex.withLock {
                    val now = Clock.System.now().toEpochMilliseconds()
                    handshakeSupervisor.recordAttempt(peerID, now)
                    handshakeStartedAt[peerID] = now
                    handshakesOwed.add(peerID)
                    handshakeSupervisor.attemptsFor(peerID)
                }

                logInfo("BluetoothMeshService", "Initiated Noise handshake with $peerID (attempt $attempts)")

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

    fun getDeviceAddressForPeer(peerID: String): String? =
        peerLinkSnapshot.entries.firstOrNull { it.value == peerID }?.key

    fun getDeviceAddressToPeerMapping(): Map<String, String> = peerLinkSnapshot

    fun printDeviceAddressesForPeers(): String {
        val mappings = peerLinkSnapshot
        return if (mappings.isEmpty()) {
            "No device mappings yet"
        } else {
            mappings.entries.joinToString(", ") { (device, peer) ->
                "${device.take(8)} -> ${peer.take(8)}"
            }
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

    companion object {
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
