package com.bitchat.bluetooth.service

import com.bitchat.domain.base.logPath
import com.bitchat.api.dto.mapper.toWireFormat
import com.bitchat.bluetooth.facade.CryptoSigningFacade
import com.bitchat.bluetooth.facade.NoiseEncryptionFacade
import com.bitchat.bluetooth.handler.MessageHandler
import com.bitchat.bluetooth.handler.MessageHandlerDelegate
import com.bitchat.bluetooth.handler.PrivateReceipt
import com.bitchat.bluetooth.handler.RadioAckNotes
import com.bitchat.bluetooth.handler.SentRadioTexts
import com.bitchat.bluetooth.manager.HandshakeRefreshPolicy
import com.bitchat.bluetooth.manager.FragmentManager
import com.bitchat.bluetooth.manager.HandshakeSupervisor
import com.bitchat.bluetooth.manager.OwedHandshakes
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
import com.bitchat.bluetooth.protocol.DeliveredNumbers
import com.bitchat.bluetooth.protocol.MessageType
import com.bitchat.bluetooth.protocol.LORA_LINK
import com.bitchat.bluetooth.protocol.MAX_LORA_PACKET_BYTES
import com.bitchat.bluetooth.protocol.LORA_PRIVATE_TEXT_BYTES
import com.bitchat.bluetooth.protocol.LoRaIngressResult
import com.bitchat.bluetooth.protocol.SpecialRecipients
import com.bitchat.bluetooth.protocol.admitFromLoRa
import com.bitchat.bluetooth.protocol.logDebug
import com.bitchat.bluetooth.protocol.logError
import com.bitchat.bluetooth.protocol.logInfo
import com.bitchat.crypto.Cryptography
import com.bitchat.domain.chat.model.BitchatFilePacket
import com.bitchat.domain.chat.model.BitchatMessage
import com.bitchat.domain.chat.model.BitchatMessageType
import com.bitchat.domain.chat.model.DeliveryStatus
import com.bitchat.domain.chat.model.PrivateMessageText
import com.bitchat.domain.chat.model.nextSendTime
import com.bitchat.domain.user.UNKNOWN_PEER_NICKNAME
import com.bitchat.local.prefs.SecureStoreUnavailableException
import com.bitchat.noise.model.NoisePayload
import com.bitchat.noise.model.NoisePayloadType
import com.bitchat.noise.model.PrivateMessagePacket
import com.bitchat.transport.MeshRadioLink
import com.bitchat.transport.RadioPurpose
import com.bitchat.transport.RadioSendResult
import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.concurrent.Volatile
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource
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
    // Only the decrypt-failure cooldown reads this clock (see MessageHandler); everything else in
    // the service keeps real time.
    private val failureClock: Clock = Clock.System,
    // How many owed handshakes of each kind are kept. A parameter so a test can fill it.
    maxOwedHandshakes: Int = HandshakeSupervisor.MAX_OWED_HANDSHAKES,
    // How long a private message waits for the one before it to reach the links. A parameter so a
    // test can make it short, or too long to wait out.
    private val privateSendHandoverWaitMs: Long = PRIVATE_SEND_HANDOVER_WAIT_MS,
    private val radioRetryMs: Long = RADIO_RETRY_MS,
    private val radioGiveUpMs: Long = RADIO_GIVE_UP_MS,
    private val ackGatherMs: Long = ACK_GATHER_MS,
    private val ackRetryMs: Long = ACK_RETRY_MS,
    private val ackMaxAgeMs: Long = ACK_MAX_AGE_MS,
    // What the wait for the radio is measured with: a clock that only goes forward, whatever is done
    // to the device's time of day (a board without a battery clock has its own set after it starts).
    private val radioWaitClock: TimeSource = TimeSource.Monotonic,
) : ConnectionEstablishedCallback {
    val myPeerID: String = cryptoSigning.getIdentityFingerprint()

    private val noiseEncryption = NoiseEncryptionFacade(myPeerID)
    private val peerManager = PeerManager()
    private val securityManager = SecurityManager(noiseEncryption, cryptoSigning, myPeerID)
    private val fragmentManager = FragmentManager()
    private val devicePeerLock = Mutex()
    private val peerLinks = PeerLinkDirectory()
    private val radioAckNotes = RadioAckNotes(radioWaitClock, ackMaxAgeMs)
    private val sentRadioTexts = SentRadioTexts(radioWaitClock)
    private val acknowledgers = atomic(0)

    // A lock-free view of [peerLinks] for the connection service, which has to answer "is this
    // address the peer I am already linked to?" while deciding whether to connect and cannot
    // suspend to take devicePeerLock.
    @Volatile
    private var peerLinkSnapshot: Map<String, String> = emptyMap()
    private lateinit var messageHandler: MessageHandler
    private lateinit var packetProcessor: PacketProcessor

    private val loraDropLogLock = SynchronizedObject()
    private var lastLoRaDropLogMillis: Long? = null
    private var loraDropsNotLogged = 0

    var delegate: BluetoothMeshDelegate? = null

    /** The LoRa radio as a second way to a peer. Set by the repository; null where there is no radio. */
    @Volatile
    var radioLink: MeshRadioLink? = null

    /**
     * Whether what this device starts for [peerID] would go over the radio: the mesh does not have
     * the peer, the radio hears it, and either there is no session yet (the handshake will show
     * whether the key holder is there) or the session was itself made over the radio. A session made
     * over Bluetooth is not used over the radio on a heartbeat's word: a forged heartbeat would
     * otherwise turn what is written to an absent peer into frames nobody receives.
     */
    fun reachesByRadio(peerID: String): Boolean = goesByRadio(peerID, arrivedOn = null)

    /**
     * Whether a packet for [peerID] goes out over the radio. An answer ([arrivedOn] is the link its
     * cause came in on) goes back the way its cause came. What this device starts goes over
     * Bluetooth whenever the mesh has the peer, as it always did; see [reachesByRadio] for the rest.
     */
    private fun goesByRadio(peerID: String, arrivedOn: String?): Boolean {
        if (radioLink == null) return false
        if (arrivedOn != null) return arrivedOn == LORA_LINK
        // A heartbeat only says that someone claiming an id is in range; a handshake that completed
        // over the radio shows its key holder was. Where a session was made is kept with the session
        // and read with it in one look: there is no moment at which a session made over Bluetooth
        // has taken the place of one made over the radio and still counts as that one.
        val made = noiseEncryption.establishedSessionLink(peerID)
        return (made == null || made == LORA_LINK) && radioReaches(peerID)
    }

    /** Whether the radio is [peerID]'s way out as far as the links go: the mesh does not have it and the radio hears it. */
    private fun radioReaches(peerID: String): Boolean {
        val link = radioLink ?: return false
        return !meshHas(peerID) && link.hears(peerID)
    }

    /** Whether the mesh has [peerID] as a connected peer: the condition under which a private message is handed to the Bluetooth links. */
    private fun meshHas(peerID: String): Boolean = peerManager.getPeer(peerID)?.isConnected == true

    /**
     * Whether a private message for [peerID] that does not go over the radio has no way out at all:
     * on a device with a radio, the Bluetooth links are for the peers the mesh has. A message for
     * anybody else was handed over because the radio reached its peer; when the radio no longer does,
     * it is said not to have gone. Handing it to whatever links there are would leave it standing as
     * sent on the word of a link layer that cannot tell whether anything took it.
     */
    private fun isOutOfReach(peerID: String): Boolean = radioLink != null && !meshHas(peerID)

    /**
     * [packet] as the radio carries it: never relayed (TTL 0), unsigned, neither padded nor
     * compressed. Whether it fits a frame is the radio link's to say.
     */
    private fun radioBytes(packet: BitchatPacket): ByteArray? =
        BinaryProtocol.encodeExact(packet.copy(ttl = 0u, signature = null))

    private var isActive = false
    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private val pendingAnnounces = mutableSetOf<String>()
    private val announceMutex = Mutex()

    // Private messages are encrypted and dated one after another, in the order they were handed
    // over, and those to one peer reach the links in that order: a long text is sent as several
    // messages, and the other side either sorts them by the time on each (the upstream clients) or
    // shows them as they arrive (this app). Only what this device's own user sends is ever queued
    // here.
    private val privateSends = Channel<PrivateSend>(Channel.UNLIMITED)

    // For each peer, the handover of the last private message sent to it while that still runs.
    // Touched only by the one coroutine that takes messages off privateSends.
    private val handovers = mutableMapOf<String, Job>()

    // What goes over the radio is kept apart: every radio handover that still runs, whoever it is
    // for, oldest first. What waits for the radio is bounded by it, and a radio message waits for
    // all of those to its own peer, however many went over Bluetooth in between; a Bluetooth message
    // never waits for one that is waiting for time on air. Touched only by that same coroutine.
    private val waitingForRadio = mutableListOf<RadioHandover>()

    private class RadioHandover(val peerID: String, val job: Job)

    // A handover that another one waited for in vain. Read and written by the handovers themselves.
    @Volatile
    private var stuckHandover: Job? = null

    // The time on the last encrypted packet sent, a private text or a private file alike: sender,
    // time and type are how a receiver tells a repeated packet from a new one, so no two of them
    // may carry the same time.
    private val encryptedPacketTimes = SynchronizedObject()
    private var lastEncryptedPacketAt = 0L

    // Deadline and retry budget for handshakes still in flight. Guarded by handshakeMutex
    // because the sweeper and initiateNoiseHandshake both run on serviceScope's dispatcher.
    private val handshakeSupervisor = HandshakeSupervisor()
    private val handshakeMutex = Mutex()

    private val handshakeRefreshPolicy = HandshakeRefreshPolicy()

    // Peers this node owes a handshake to but could not send one for, or is still attempting. They
    // are retried when a link to them comes up rather than on a timer. What the user asked for and
    // what this node started by itself are kept apart (see OwedHandshakes). Guarded by
    // handshakeMutex.
    private val handshakesOwed = OwedHandshakes(maxOwedHandshakes)

    // When the handshake with a peer was last (re)started, so two link-up signals for the same
    // link -- the inbound packet that binds the address and the outbound connection becoming
    // ready, which the journal shows 315ms apart -- do not tear down a handshake that has only
    // just been sent. Guarded by handshakeMutex.
    private val handshakeStartedAt = mutableMapOf<String, Long>()

    init {
        setupComponents()
        setupDelegates()
        wireConnectionService()
        serviceScope.launch {
            for (send in privateSends) deliverPrivateMessage(send)
        }

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

        val nickname = nicknameToAnnounce(otherwise = "Me") ?: "Me"
        peerManager.initializeSelfPeer(
            myPeerID = myPeerID,
            myNickname = nickname,
            myNoisePublicKey = cryptoSigning.getNoisePublicKey(),
            mySigningPublicKey = cryptoSigning.getSigningPublicKey()
        )
    }

    private fun setupComponents() {
        messageHandler = MessageHandler(myPeerID, securityManager, peerManager, cryptoSigning, failureClock)
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

                packetProcessor.processPacket(packet, peerID, deviceAddress)
            } catch (e: Exception) {
                logError("BluetoothMeshService", "Error processing received packet: ${e.message}")
            }
        }
    }

    /**
     * A packet arrived over the LoRa radio. Only a complete, unsigned, uncompressed Noise packet
     * addressed to this device with TTL zero enters. It never binds a sender to a device address,
     * refreshes or supersedes a Bluetooth link, and uses the radio's one link name so every
     * per-link limit treats the whole radio as one link.
     *
     * The packet goes straight into its bounded lane: nothing is launched or queued per packet
     * ahead of that bound.
     */
    fun onLoRaPacketReceived(data: ByteArray) {
        when (val result = admitFromLoRa(data, myPeerID)) {
            is LoRaIngressResult.Rejected -> logLoRaDrop(result.reason.name)
            is LoRaIngressResult.Accepted ->
                packetProcessor.processPacket(result.packet, result.senderPeerID, LORA_LINK)
        }
    }

    /** Logs rejected radio packets at a bounded rate because the radio is an unauthenticated link. */
    private fun logLoRaDrop(reason: String) {
        val now = Clock.System.now().toEpochMilliseconds()
        val message = synchronized(loraDropLogLock) {
            val last = lastLoRaDropLogMillis
            if (last != null && now - last in 0 until LORA_DROP_LOG_INTERVAL_MS) {
                loraDropsNotLogged++
                null
            } else {
                val skipped = if (loraDropsNotLogged > 0) " ($loraDropsNotLogged more drops not logged)" else ""
                lastLoRaDropLogMillis = now
                loraDropsNotLogged = 0
                "Dropped LoRa packet: $reason$skipped"
            }
        }
        if (message != null) logInfo("BluetoothMeshService", message)
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

            override fun onMessageReceived(peerID: String, message: String) {
                val peer = peerManager.getPeer(peerID)
                val senderName = peer?.nickname ?: UNKNOWN_PEER_NICKNAME
                val now = Clock.System.now()

                val bitchatMessage = BitchatMessage(
                    id = generateMessageID(),
                    sender = senderName,
                    content = message,
                    type = BitchatMessageType.Message,
                    timestamp = now,
                    isPrivate = false,
                    senderPeerID = peerID,
                    channel = null,
                    deliveryStatus = DeliveryStatus.Delivered(to = myPeerID, at = now)
                )

                delegate?.didReceiveMessage(bitchatMessage)
            }

            override fun onAuthenticatedPrivateMessage(peerID: String, messageId: String, content: String, receipt: PrivateReceipt) {
                val peer = peerManager.getPeer(peerID)
                val senderName = peer?.nickname ?: UNKNOWN_PEER_NICKNAME
                val now = Clock.System.now()

                val bitchatMessage = BitchatMessage(
                    id = messageId,
                    sender = senderName,
                    content = content,
                    type = BitchatMessageType.Message,
                    timestamp = now,
                    isPrivate = true,
                    senderPeerID = peerID,
                    deliveryStatus = DeliveryStatus.Delivered(to = myPeerID, at = now)
                )

                delegate?.didReceiveAuthenticatedPrivateMessage(bitchatMessage)
                // A text that came over the radio is acknowledged there, in the session it was read in.
                // One read by a session that has been replaced is noted under that session's token,
                // which no acknowledgement is ever encrypted under: it is dropped when its turn comes.
                if (receipt.link == LORA_LINK && receipt.number != null &&
                    radioAckNotes.note(peerID, receipt.sessionToken, receipt.number)) {
                    startAcknowledging(peerID, receipt.sessionToken)
                }
            }

            override fun onAuthenticatedPrivateFile(peerID: String, file: BitchatFilePacket) {
                logInfo("BluetoothMeshService", "📎 Authenticated private file received from $peerID: ${logPath(file.fileName)}")
                delegate?.didReceiveAuthenticatedPrivateFile(peerID, file)
            }

            override fun onAuthenticatedDelivered(peerID: String, messageId: String) {
                delegate?.didReceiveAuthenticatedDeliveryAck(messageId, peerID)
            }

            override fun onAuthenticatedDeliveredNumbers(peerID: String, numbers: List<Long>, sessionToken: Long) {
                sentRadioTexts.resolve(peerID, sessionToken, numbers).forEach { messageID ->
                    delegate?.didReceiveAuthenticatedDeliveryAck(messageID, peerID)
                }
            }

            override fun onAuthenticatedRead(peerID: String, messageId: String) {
                delegate?.didReceiveAuthenticatedReadReceipt(messageId, peerID)
            }

            override fun onHandshakeReceived(peerID: String) {
                logInfo("BluetoothMeshService", "Noise handshake received from $peerID")
            }

            override fun onHandshakeResponse(peerID: String, responsePacket: ByteArray, link: String, final: Boolean) {
                logInfo("BluetoothMeshService", "Sending handshake response to $peerID")
                this@BluetoothMeshService.onHandshakeResponse(peerID, responsePacket, link, final)
            }

            override fun onSessionUnusable(peerID: String) {
                // Keep the validated key only for decrypting while lifecycle state starts the
                // ordinary first handshake again; outgoing traffic still waits for establishment.
                // SessionFailureTracker rate limits this, so a peer that can
                // forge a packet cannot use it to demand handshakes.
                //
                // The session is changed here, on the coroutine that reached the verdict, not on a
                // launched one. A peer's packets go through one lane, one at a time, so
                // a handshake from that peer cannot complete between the verdict and this line;
                // deferred, the same call could land after one had, and take down the session that
                // had just replaced the bad one. Only the handshake itself is started
                // asynchronously.
                logInfo("BluetoothMeshService", "Replacing the unusable Noise session with $peerID")
                noiseEncryption.demote(peerID)
                startHandshake(peerID, byUser = false)
            }

            override fun onSessionNotShared(peerID: String) {
                // Synchronous for the same reason as onSessionUnusable.
                logInfo("BluetoothMeshService", "The established Noise session is not shared with $peerID")
                noiseEncryption.discardEstablished(peerID)
                startHandshake(peerID, byUser = false)
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

            override fun onPublicFileReceived(peerID: String, filePacket: BitchatFilePacket) {
                logInfo("BluetoothMeshService", "📎 File received from $peerID: ${logPath(filePacket.fileName)}")
                delegate?.didReceivePublicFile(peerID, filePacket)
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
     * link that has just been replaced, and the peer never saw it. Discard the stalled candidate
     * and start over, and give the peer a fresh retry budget, since its history says nothing about a
     * link it did not have.
     */
    private fun onPeerLinkRefreshed(peerID: String) {
        serviceScope.launch {
            val now = Clock.System.now().toEpochMilliseconds()
            val (owed, byUser) = handshakeMutex.withLock {
                handshakeSupervisor.reset(peerID)
                (peerID in handshakesOwed) to handshakesOwed.isForUser(peerID)
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
                    startHandshake(peerID, byUser = byUser)
                }

                HandshakeRefreshPolicy.Decision.RESTART -> {
                    logInfo(
                        "BluetoothMeshService",
                        "Peer $peerID reappeared with our handshake in flight; restarting it on the new link"
                    )
                    noiseEncryption.abandonHandshake(peerID)
                    // A link coming up is reported by a packet that only CLAIMS the peer's id, so
                    // it may restart a handshake but must not turn it into one the user asked for.
                    startHandshake(peerID, byUser = byUser)
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
     * left in place: it is the marker that says an automatic retry is still coming, and dropping
     * it early would lose that retry.
     */
    internal suspend fun sweepStalledHandshakes(now: Long) {
        val inFlight = noiseEncryption.handshakesInFlight()
        handshakeMutex.withLock {
            handshakeSupervisor.prune(
                now,
                HandshakeSupervisor.HANDSHAKE_RECORD_MAX_AGE_MS
            ) { peerID -> peerID in inFlight || peerID in handshakesOwed }
            pruneStartedAt(now, inFlight.keys)
        }
        if (inFlight.isEmpty()) return

        inFlight.forEach { (peerID, startedAt) ->
            if (!handshakeSupervisor.isExpired(startedAt, now)) return@forEach

            val exhausted = handshakeMutex.withLock { handshakeSupervisor.isExhausted(peerID) }
            if (exhausted) {
                logInfo(
                    "BluetoothMeshService",
                    "Noise handshake with $peerID stalled for ${now - startedAt}ms and the retry " +
                        "budget is spent; discarding the candidate and giving up until the peer returns"
                )
                noiseEncryption.abandonHandshake(peerID)
                return@forEach
            }

            val mayRetry = handshakeMutex.withLock { handshakeSupervisor.mayAttempt(peerID, now) }
            if (!mayRetry) return@forEach

            val attempts = handshakeMutex.withLock { handshakeSupervisor.attemptsFor(peerID) }
            logInfo(
                "BluetoothMeshService",
                "Noise handshake with $peerID stalled for ${now - startedAt}ms after $attempts " +
                    "attempt(s); discarding the candidate"
            )
            noiseEncryption.abandonHandshake(peerID)

            if (peerManager.isPeerActive(peerID)) {
                // initiateNoiseHandshake defers to the link coming up if nothing can carry it, so
                // a peer that is "active" only because its announce is still inside the three
                // minute window no longer costs an attempt.
                startHandshake(peerID, byUser = handshakeMutex.withLock { handshakesOwed.isForUser(peerID) })
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
            connectionService.prepareForStart()
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

    /**
     * False when the message does not fit the private message encoding. That is decided here, on
     * the caller's thread, before anything is started for the message: nothing goes out in its
     * place (an empty payload would cost the session a nonce and the mesh a packet no receiver
     * can read). A message that fits is queued behind those handed over before it.
     */
    fun sendPrivateMessage(content: String, recipientPeerID: String, recipientNickname: String, messageID: String? = null): Boolean {
        noiseEncryption.markChosenByUser(recipientPeerID)
        val messageData = buildPrivateMessagePayload(content, messageID)
        if (messageData == null) {
            logError(
                "BluetoothMeshService",
                "Private message for $recipientPeerID cannot be encoded (${content.encodeToByteArray().size} bytes), not sent"
            )
            return false
        }
        // Refused here when it is known already that it would take the radio and not fit a frame; the
        // way out is decided again when the message is encrypted, and what does not fit then is
        // reported as failed.
        if (goesByRadio(recipientPeerID, arrivedOn = null) && !fitsARadioFrame(messageData)) return false
        privateSends.trySend(PrivateSend(recipientPeerID, messageData, messageID))
        return true
    }

    /** A Noise message adds 20 bytes (its number and its tag) and an addressed packet 30 of header and ids. */
    private fun fitsARadioFrame(plaintext: ByteArray): Boolean = plaintext.size + 20 + 30 <= MAX_LORA_PACKET_BYTES

    fun privateTextLimitFor(peerID: String): Int =
        if (goesByRadio(peerID, arrivedOn = null)) LORA_PRIVATE_TEXT_BYTES else PrivateMessageText.MAX_BYTES

    private suspend fun deliverPrivateMessage(send: PrivateSend) {
        try {
            if (!securityManager.hasEstablishedSession(send.recipientPeerID)) {
                logError(
                    "BluetoothMeshService",
                    "No established session with ${send.recipientPeerID}, cannot send (handshake should be initiated by ChatRepo)"
                )
                // Don't initiate handshake here - that's ChatRepo's responsibility
                // ChatRepo queues messages and initiates handshake once
                failed(send, "no session with the peer any more")
                return
            }

            // The way out is decided here, with the session the message is about to be encrypted in,
            // not when the message was handed over: the session can have been made again over another
            // link in between, and one made over Bluetooth is not used over the radio.
            val byRadio = goesByRadio(send.recipientPeerID, arrivedOn = null)
            if (!byRadio && isOutOfReach(send.recipientPeerID)) {
                failed(send, OUT_OF_REACH)
                return
            }
            if (byRadio) {
                // Before a number of the session is used for it.
                waitingForRadio.removeAll { !it.job.isActive }
                val refusal = when {
                    !fitsARadioFrame(send.payload) -> "too long for one LoRa frame"
                    waitingForRadio.size >= MAX_WAITING_RADIO_MESSAGES -> "too many messages are waiting for the radio"
                    else -> null
                }
                if (refusal != null) {
                    failed(send, refusal)
                    return
                }
            }

            val encrypted = noiseEncryption.encryptNamingLink(send.recipientPeerID, send.payload)
            if (encrypted == null) {
                failed(send, "it could not be encrypted")
                return
            }
            val encryptedPayload = encrypted.bytes
            // Said by the encryption itself, of the session it used: the look above was at a session
            // that may have been replaced since.
            val madeOverRadio = encrypted.sessionLink == LORA_LINK

            // Dated now that it is taken off the queue, not when it was put on: a receiver refuses
            // a packet whose time is too far from its own. From here it leaves within the handover
            // wait, however many are ahead of it.
            val packet = BitchatPacket(
                type = MessageType.NOISE_ENCRYPTED.value,
                senderID = BitchatPacket.hexStringToByteArray(myPeerID),
                recipientID = BitchatPacket.hexStringToByteArray(send.recipientPeerID),
                timestamp = nextEncryptedPacketTime(),
                payload = encryptedPayload,
                ttl = 3u
            )

            if (byRadio) {
                // Behind every radio message to this peer that has not ended, not only the one before
                // it: that one may give up waiting and end while an earlier one is still with the
                // radio. Whether the radio is still the way out is looked at before every offer, the
                // first one too: had the session been made again over Bluetooth while this message
                // was encrypted, it leaves for the Bluetooth links from there.
                val ahead = waitingForRadio.filter { it.peerID == send.recipientPeerID }.map { it.job }
                val handedOver = radioWaitClock.markNow()
                val handover = serviceScope.launch {
                    // For as long as this one may wait at all and no longer: whatever keeps them,
                    // this one ends too. (A coroutine's timeout does not follow the time of day.)
                    if (withTimeoutOrNull(radioGiveUpMs) { ahead.joinAll() } == null) {
                        failed(send, "no time on air for it")
                        return@launch
                    }
                    try {
                        sendOverRadioUntilSent(
                            packet, send, handedOver, madeOverRadio,
                            encrypted.sessionToken, DeliveredNumbers.packetNumber(encrypted.bytes)
                        )
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        failed(send, "an error")
                    }
                }
                waitingForRadio += RadioHandover(send.recipientPeerID, handover)
                return
            }

            // Behind the message sent to this peer before it, and behind nobody else's: the wait is
            // the handover's own, so the next message, whoever it is for, is taken up at once.
            handovers.values.removeAll { !it.isActive }
            val before = handovers[send.recipientPeerID]
            handovers[send.recipientPeerID] = serviceScope.launch {
                if (before != null) awaitHandover(before)
                sendPacket(packet)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logError("BluetoothMeshService", "Error sending private message: ${e.message}")
            failed(send, "an error")
        }
    }

    /**
     * Tells the delegate that a private message handed to this service has not gone out. Every message
     * taken off the queue is handed to the Bluetooth links (only without a radio, or for a peer the
     * mesh has: as it always was), leaves the air, or ends here.
     */
    private fun failed(send: PrivateSend, reason: String) {
        logError("BluetoothMeshService", "Private message to ${send.recipientPeerID} not sent: $reason")
        send.messageID?.let { delegate?.didFailToSendPrivateMessage(it, send.recipientPeerID, reason) }
    }

    /**
     * Offers [packet] to the radio until it has gone out. Without time on air left it is offered
     * again, the same bytes, every [radioRetryMs]. Given up, and reported as failed, [radioGiveUpMs]
     * after the message was handed over ([handedOver]: the wait behind the messages before it
     * counts), or at once when the radio itself fails. Nothing is offered that was not encrypted in
     * a session made over the radio ([madeOverRadio]: the encryption's own word, of the session it
     * used, whatever session has taken its place since). Should that not be so, or should the radio
     * stop reaching this peer while it waits, the same packet goes to
     * the Bluetooth links when the mesh has the peer by then, as any private message for such a peer
     * does (and is reported as failed when they say none of them took it), and is reported as failed
     * otherwise (the peer is no longer heard, or the session was made again over Bluetooth while the
     * mesh does not have the peer).
     */
    private suspend fun sendOverRadioUntilSent(
        packet: BitchatPacket,
        send: PrivateSend,
        handedOver: TimeMark,
        madeOverRadio: Boolean,
        sessionToken: Long,
        packetNumber: Long?
    ) {
        val peerID = send.recipientPeerID
        val bytes = radioBytes(packet)
        if (bytes == null) {
            failed(send, "too long for one LoRa frame")
            return
        }
        var remembered = false
        while (true) {
            if (!madeOverRadio || !radioReaches(peerID)) {
                if (isOutOfReach(peerID)) {
                    failed(send, OUT_OF_REACH)
                    return
                }
                // The mesh has the peer now. On its own: a Bluetooth write that never answers must
                // hold up nothing that waits for the radio. And when the links say that none of them
                // took it, the message has gone nowhere.
                serviceScope.launch { if (!sendPacket(packet)) failed(send, "no link carried it") }
                return
            }
            if (!remembered && send.messageID != null && packetNumber != null) {
                sentRadioTexts.remember(peerID, sessionToken, packetNumber, send.messageID)
                remembered = true
            }
            when (radioLink?.send(bytes, peerID, RadioPurpose.PrivateMessage)) {
                RadioSendResult.SENT -> return
                RadioSendResult.NO_TIME_ON_AIR -> {
                    if (handedOver.elapsedNow() >= radioGiveUpMs.milliseconds) {
                        failed(send, "no time on air for it")
                        return
                    }
                    delay(radioRetryMs)
                }
                RadioSendResult.FAILED, null -> {
                    failed(send, "the radio failed")
                    return
                }
            }
        }
    }

    /**
     * Tells [peerID] which of its private texts arrived over the radio, by their packet numbers, in
     * the session they were read in ([token]): one frame for all that were noted by the time it goes,
     * after a short wait in which more can come. It is what a received packet caused, so it is paid
     * from that peer's own allowance and never from this device's user's; without time on air it is
     * tried again with whatever has been noted by then, until the numbers are too old. It goes only
     * over the radio, only in a session made over the radio, and only in the session the numbers
     * belong to: if another session has taken its place, the numbers are dropped.
     */
    private fun startAcknowledging(peerID: String, token: Long) {
        acknowledgers.incrementAndGet()
        serviceScope.launch {
            try {
                delay(ackGatherMs)
                while (true) {
                    val numbers = radioAckNotes.snapshot(peerID, token)
                    if (numbers.isEmpty()) return@launch
                    val data = DeliveredNumbers.encode(numbers) ?: run {
                        radioAckNotes.drop(peerID, token)
                        return@launch
                    }
                    val encrypted = noiseEncryption.encryptNamingLink(
                        peerID, NoisePayload(NoisePayloadType.DELIVERED_NUMBERS, data).encode()
                    )
                    if (encrypted == null || encrypted.sessionToken != token || encrypted.sessionLink != LORA_LINK || !radioReaches(peerID)) {
                        radioAckNotes.drop(peerID, token)
                        return@launch
                    }
                    val packet = BitchatPacket(
                        type = MessageType.NOISE_ENCRYPTED.value,
                        senderID = BitchatPacket.hexStringToByteArray(myPeerID),
                        recipientID = BitchatPacket.hexStringToByteArray(peerID),
                        timestamp = nextEncryptedPacketTime(),
                        payload = encrypted.bytes,
                        ttl = 0u
                    )
                    val bytes = radioBytes(packet)
                    if (bytes == null) {
                        radioAckNotes.drop(peerID, token)
                        return@launch
                    }
                    when (radioLink?.send(bytes, peerID, RadioPurpose.DeliveryAck)) {
                        RadioSendResult.SENT -> {
                            // Ends in the step that empties the entry, not after a wait: a number
                            // noted from then on starts a sender of its own.
                            if (!radioAckNotes.sent(peerID, token, numbers)) return@launch
                            delay(ackRetryMs)
                        }
                        RadioSendResult.NO_TIME_ON_AIR -> delay(ackRetryMs)
                        RadioSendResult.FAILED, null -> {
                            radioAckNotes.drop(peerID, token)
                            return@launch
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                radioAckNotes.drop(peerID, token)
            } finally {
                acknowledgers.decrementAndGet()
            }
        }
    }

    /** How many acknowledgement senders are alive now, for tests. One whose entry is gone or replaced may still be on its way out. */
    internal fun acknowledgersRunning(): Int = acknowledgers.value

    /**
     * Waits until the private message sent to the same peer [before] this one is with the links:
     * only between messages to one peer does the order matter. Not waited for without end, and the
     * write is never cancelled: one platform writes to its links before it answers, and a link
     * that does not answer must not hold up what comes behind it. While such a write still hangs,
     * nothing waits again: that would add its delay to every message and put none of them in
     * order.
     */
    private suspend fun awaitHandover(before: Job) {
        if (stuckHandover?.isActive == true) return
        if (withTimeoutOrNull(privateSendHandoverWaitMs) { before.join() } != null) return
        // Several may give up at the same moment, each on its own predecessor: the one already
        // known to hang stays the one that is watched.
        if (stuckHandover?.isActive != true) stuckHandover = before
    }

    /** The time for the next encrypted packet: later than on the one before (see [nextSendTime]). */
    private fun nextEncryptedPacketTime(): ULong = synchronized(encryptedPacketTimes) {
        lastEncryptedPacketAt = nextSendTime(
            lastEncryptedPacketAt,
            Clock.System.now().toEpochMilliseconds(),
            ENCRYPTED_PACKET_MAX_AHEAD_MS
        )
        lastEncryptedPacketAt.toULong()
    }

    /** Null when the message does not fit the encoding: id and content have one-byte lengths. */
    private fun buildPrivateMessagePayload(content: String, messageID: String?): ByteArray? {
        val packet = PrivateMessagePacket(
            messageID = messageID ?: "",
            content = content
        )

        val tlvData = packet.encode() ?: return null
        val noisePayload = NoisePayload(
            type = NoisePayloadType.PRIVATE_MESSAGE,
            data = tlvData
        )

        return noisePayload.encode()
    }

    fun sendReadReceipt(messageID: String, recipientPeerID: String, readerNickname: String) {
        // TODO: Implement read receipt
    }

    fun sendBroadcastAnnounce(): Job =
        serviceScope.launch {
            val nickname = nicknameToAnnounce(otherwise = "Anonymous") ?: return@launch
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

    /**
     * The name an ANNOUNCE carries: the delegate's, [otherwise] while there is no delegate or it
     * has none, and null while the secure store that holds it does not answer (the Keychain of a
     * locked phone). Null means "do not announce": a name that is not this device's must not go
     * out under its id, since a peer names a conversation after the first one it hears. Only that
     * one failure is taken; anything else the delegate throws is a fault and stays one.
     */
    internal fun nicknameToAnnounce(otherwise: String): String? = try {
        delegate?.getNickname() ?: otherwise
    } catch (e: SecureStoreUnavailableException) {
        logError("ANNOUNCE", "Nickname unavailable, not announcing: ${e.message}")
        null
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
            peerLinks.bind(peerID, deviceAddress, Clock.System.now().toEpochMilliseconds())
                .also { peerLinkSnapshot = peerLinks.snapshot() }
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

    /** Handshakes the user asked for and that are still owed. */
    internal suspend fun handshakesOwedCount(): Int = handshakeMutex.withLock { handshakesOwed.userCount }

    /** Handshakes this node started by itself (recoveries, retries) and that are still owed. */
    internal suspend fun automaticHandshakesOwedCount(): Int = handshakeMutex.withLock { handshakesOwed.automaticCount }

    internal suspend fun handshakeStartedAtCount(): Int = handshakeMutex.withLock { handshakeStartedAt.size }

    internal suspend fun handshakeSupervisorSize(): Int = handshakeMutex.withLock { handshakeSupervisor.size }

    internal fun hasNoiseCandidate(peerID: String): Boolean = securityManager.hasCandidate(peerID)

    internal fun hasValidatedNoiseSession(peerID: String): Boolean = securityManager.hasValidatedSession(peerID)

    internal fun pendingEncryptedPayloadCount(): Int = messageHandler.pendingEncryptedPayloadCount

    internal fun failureCount(peerID: String): Int = messageHandler.sessionFailureTracker.consecutiveFailures(peerID)

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
        noiseEncryption.markChosenByUser(peerID)
        startHandshake(peerID, byUser = true, paidByUser = true)
    }

    internal fun sessionIsChosenByUser(peerID: String): Boolean = noiseEncryption.isChosenByUser(peerID)

    /**
     * [byUser] says who wanted this handshake: the user (a private message was sent to the peer) or
     * this node by itself (a recovery, a retry). It only decides which kind of owed entry is kept.
     * [paidByUser] is true only for the call the user's own action makes: see [openOverRadio].
     */
    private fun startHandshake(peerID: String, byUser: Boolean, paidByUser: Boolean = false) {
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
                    // Nothing new goes out, but if a handshake with this peer is already in flight
                    // (one this node started by itself, or one the peer opened) and the USER has
                    // now asked for a session, it is the user's from here on. Otherwise it would
                    // stay in the room that forged traffic can churn.
                    // Looked at and recorded under the lock that the completion's cleanup takes: a
                    // handshake that finishes first is seen as finished here, and one that finishes
                    // after has its cleanup run after this and remove the entry.
                    if (byUser) {
                        handshakeMutex.withLock {
                            if (noiseEncryption.isHandshaking(peerID)) handshakesOwed.rememberForUser(peerID)
                        }
                    }
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

                if (goesByRadio(peerID, arrivedOn = null)) {
                    openOverRadio(peerID, packet, byUser, paidByUser)
                    return@launch
                }

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
                    // The candidate is dropped rather than left handshaking: initiateHandshake()
                    // returns empty while one exists, so keeping it would block the retry that the
                    // link coming up is about to ask for.
                    noiseEncryption.abandonHandshake(peerID)
                    handshakeMutex.withLock { rememberOwed(peerID, byUser) }
                    return@launch
                }

                val attempts = handshakeMutex.withLock {
                    val now = Clock.System.now().toEpochMilliseconds()
                    handshakeSupervisor.recordAttempt(peerID, now)
                    recordStartedAt(peerID, now)
                    rememberOwed(peerID, byUser)
                    handshakeSupervisor.attemptsFor(peerID)
                }

                logInfo("BluetoothMeshService", "Initiated Noise handshake with $peerID (attempt $attempts)")

            } catch (e: Exception) {
                logError("BluetoothMeshService", "Error initiating handshake: ${e.message}")
            }
        }
    }

    /**
     * Sends the opening of the handshake just started with [peerID] over the radio.
     *
     * [paidByUser]: only an opening the user has just asked for is paid from what belongs to the
     * user. A retry or a restart, whoever the handshake is owed to, is something a received packet
     * can bring about, and is paid like anything else this device starts by itself.
     *
     * The radio reports a frame only once it has left the air. By then the peer may have answered,
     * the sweeper may have given the attempt up and another may have begun, so what is recorded or
     * undone afterwards is recorded or undone only for the attempt this opening belongs to, known by
     * the moment it began.
     */
    private suspend fun openOverRadio(peerID: String, packet: BitchatPacket, byUser: Boolean, paidByUser: Boolean) {
        val attempt = noiseEncryption.handshakesInFlight()[peerID]
        val result = radioBytes(packet)?.let { bytes ->
            radioLink?.send(bytes, peerID, RadioPurpose.HandshakeOpening(byUser = paidByUser))
        }
        handshakeMutex.withLock {
            if (attempt == null || noiseEncryption.handshakesInFlight()[peerID] != attempt) return
            if (result == RadioSendResult.SENT) {
                val now = Clock.System.now().toEpochMilliseconds()
                handshakeSupervisor.recordAttempt(peerID, now)
                recordStartedAt(peerID, now)
                rememberOwed(peerID, byUser)
            } else {
                logInfo("BluetoothMeshService", "The radio did not carry a handshake to $peerID ($result)")
                noiseEncryption.abandonHandshake(peerID)
                rememberOwed(peerID, byUser)
            }
        }
    }

    private fun rememberOwed(peerID: String, byUser: Boolean) {
        if (byUser) handshakesOwed.rememberForUser(peerID) else handshakesOwed.rememberAutomatic(peerID)
    }

    /** A new id at capacity gives up the least recently started record. */
    private fun recordStartedAt(peerID: String, now: Long) {
        if (peerID !in handshakeStartedAt && handshakeStartedAt.size >= HandshakeSupervisor.MAX_HANDSHAKE_RECORDS) {
            handshakeStartedAt.minByOrNull { it.value }?.key?.let(handshakeStartedAt::remove)
        }
        handshakeStartedAt[peerID] = now
    }

    private fun pruneStartedAt(now: Long, inFlight: Set<String>) {
        val iterator = handshakeStartedAt.iterator()
        while (iterator.hasNext()) {
            val (peerID, startedAt) = iterator.next()
            if (
                now - startedAt >= HandshakeSupervisor.HANDSHAKE_RECORD_MAX_AGE_MS &&
                peerID !in inFlight && peerID !in handshakesOwed
            ) {
                iterator.remove()
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

    private fun onHandshakeResponse(peerID: String, handshakeData: ByteArray, link: String, final: Boolean) {
        if (!goesByRadio(peerID, link)) {
            sendNoiseHandshakePacket(peerID, handshakeData)
            return
        }
        serviceScope.launch {
            val packet = BitchatPacket(
                type = MessageType.NOISE_HANDSHAKE.value,
                senderID = BitchatPacket.hexStringToByteArray(myPeerID),
                recipientID = BitchatPacket.hexStringToByteArray(peerID),
                timestamp = Clock.System.now().toEpochMilliseconds().toULong(),
                payload = handshakeData,
                ttl = 0u,
            )
            val result = radioBytes(packet)?.let { bytes ->
                radioLink?.send(bytes, peerID, if (final) RadioPurpose.HandshakeFinal else RadioPurpose.HandshakeAnswer)
            } ?: RadioSendResult.FAILED
            if (result != RadioSendResult.SENT) logError("BluetoothMeshService", "Radio handshake response to $peerID: $result")
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
                logInfo("BluetoothMeshService", "📎 Broadcasting file: ${logPath(file.fileName)} (${file.fileSize} bytes)")

                val payload = file.toWireFormat()
                if (payload == null) {
                    logError("BluetoothMeshService", "Failed to encode file for broadcast: ${logPath(file.fileName)}")
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
                logInfo("BluetoothMeshService", "✅ File broadcast sent: ${logPath(file.fileName)}")

            } catch (e: Exception) {
                logError("BluetoothMeshService", "Error broadcasting file: ${e.message}")
            }
        }
    }

    /**
     * False when nothing is started for the file because this peer's way out is the radio, which
     * carries no files: said here, on the caller's thread, so that the caller can say so in turn.
     */
    fun sendFilePrivate(recipientPeerID: String, file: BitchatFilePacket): Boolean {
        noiseEncryption.markChosenByUser(recipientPeerID)
        if (goesByRadio(recipientPeerID, arrivedOn = null)) {
            logInfo("BluetoothMeshService", "Private file to $recipientPeerID is not sent over LoRa")
            return false
        }
        serviceScope.launch {
            try {
                if (!securityManager.hasEstablishedSession(recipientPeerID)) {
                    logError(
                        "BluetoothMeshService",
                        "No established session with $recipientPeerID, cannot send file"
                    )
                    return@launch
                }

                logInfo("BluetoothMeshService", "📎 Sending private file to $recipientPeerID: ${logPath(file.fileName)} (${file.fileSize} bytes)")

                // Encode file to TLV
                val fileData = file.toWireFormat()
                if (fileData == null) {
                    logError("BluetoothMeshService", "Failed to encode file for private transfer: ${logPath(file.fileName)}")
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
                    timestamp = nextEncryptedPacketTime(),
                    payload = encryptedPayload,
                    signature = null,
                    ttl = 3u
                )

                broadcastPacket(packet)
                logInfo("BluetoothMeshService", "✅ Private file sent to $recipientPeerID: ${logPath(file.fileName)}")

            } catch (e: Exception) {
                logError("BluetoothMeshService", "Error sending private file: ${e.message}")
            }
        }
        return true
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

    private class PrivateSend(val recipientPeerID: String, val payload: ByteArray, val messageID: String?)

    companion object {
        /** How long a private message waits for the one before it, to the same peer, to reach the links. */
        const val PRIVATE_SEND_HANDOVER_WAIT_MS = 2_000L
        const val RADIO_RETRY_MS = 1_000L
        const val RADIO_GIVE_UP_MS = 180_000L

        /** Private messages that may wait for the radio at one time; one more is reported as failed. */
        const val MAX_WAITING_RADIO_MESSAGES = 16
        const val OUT_OF_REACH = "the peer is out of reach"
        /** How long the first text to acknowledge waits for others to share its frame. */
        const val ACK_GATHER_MS = 2_000L
        const val ACK_RETRY_MS = 5_000L
        /** A text not acknowledged by then is not acknowledged at all. */
        const val ACK_MAX_AGE_MS = 120_000L

        /**
         * How far the time on an encrypted packet may run ahead of the clock (see [nextSendTime]).
         * Upstream drops a packet dated more than two minutes off its own clock.
         */
        const val ENCRYPTED_PACKET_MAX_AHEAD_MS = 10_000L

        private const val LORA_DROP_LOG_INTERVAL_MS = 1_000L
    }
}

interface BluetoothMeshDelegate {
    fun didReceiveMessage(message: BitchatMessage)
    fun didReceiveAuthenticatedPrivateMessage(message: BitchatMessage)
    fun didUpdatePeerList(peers: List<String>)
    fun didReceiveChannelLeave(channel: String, fromPeer: String)
    fun didReceiveAuthenticatedDeliveryAck(messageID: String, recipientPeerID: String)
    fun didReceiveAuthenticatedReadReceipt(messageID: String, recipientPeerID: String)
    suspend fun decryptChannelMessage(encryptedContent: ByteArray, channel: String): String?
    fun getNickname(): String?
    fun isFavorite(peerID: String): Boolean
    suspend fun onSessionEstablished(peerID: String)
    fun didReceivePublicFile(peerID: String, filePacket: BitchatFilePacket)
    fun didReceiveAuthenticatedPrivateFile(peerID: String, filePacket: BitchatFilePacket)

    /**
     * A private message this service had accepted ([messageID] as given to `sendPrivateMessage`) will
     * not go out after all: it was meant for the radio and did not fit, got no time on air, or the
     * radio failed.
     */
    fun didFailToSendPrivateMessage(messageID: String, recipientPeerID: String, reason: String) = Unit
}
