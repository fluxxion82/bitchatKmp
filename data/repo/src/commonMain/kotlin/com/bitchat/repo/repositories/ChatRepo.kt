@file:OptIn(ExperimentalUuidApi::class)

package com.bitchat.repo.repositories

import com.bitchat.domain.base.logStackTrace
import com.bitchat.domain.base.logError
import com.bitchat.domain.base.logPath
import com.bitchat.domain.base.logBytes
import com.bitchat.domain.base.logBody
import com.bitchat.api.dto.chat.ReadReceipt
import com.bitchat.api.dto.mapper.toBitchatPacket
import com.bitchat.api.dto.protocol.MessageType
import com.bitchat.bluetooth.service.BluetoothMeshDelegate
import com.bitchat.bluetooth.service.BluetoothMeshService
import com.bitchat.cache.Cache
import com.bitchat.client.httpEngineSupportsTorProxy
import com.bitchat.crypto.Cryptography
import com.bitchat.domain.app.model.ActiveState
import com.bitchat.domain.app.model.UserState
import com.bitchat.domain.base.CoroutineScopeFacade
import com.bitchat.domain.base.CoroutinesContextFacade
import com.bitchat.domain.chat.eventbus.ChatEventBus
import com.bitchat.domain.chat.model.BitchatFilePacket
import com.bitchat.domain.chat.model.BitchatMessage
import com.bitchat.domain.chat.model.BitchatMessageType
import com.bitchat.domain.chat.model.ChannelInfo
import com.bitchat.domain.chat.model.ChannelMember
import com.bitchat.domain.chat.model.ChannelTransport
import com.bitchat.domain.chat.model.ChatEvent
import com.bitchat.domain.chat.model.DeliveryStatus
import com.bitchat.domain.chat.model.LoRaPerson
import com.bitchat.domain.chat.model.PrivateMessageText
import com.bitchat.domain.chat.repository.ChatRepository
import com.bitchat.domain.connectivity.model.BluetoothConnectionEvent
import com.bitchat.domain.location.eventbus.LocationEventBus
import com.bitchat.domain.location.model.Channel
import com.bitchat.domain.location.model.GeoPerson
import com.bitchat.domain.location.model.LocationEvent
import com.bitchat.domain.lora.model.LoRaBandwidth
import com.bitchat.domain.lora.model.LoRaRegion
import com.bitchat.domain.lora.model.LoRaTxPower
import com.bitchat.domain.user.model.AppUser
import com.bitchat.domain.user.model.UserEvent
import com.bitchat.domain.user.UNKNOWN_PEER_NICKNAME
import com.bitchat.domain.user.meshChatName
import com.bitchat.domain.user.sanitizedMeshNickname
import com.bitchat.domain.user.sanitizedNickname
import com.bitchat.local.prefs.BlockListPreferences
import com.bitchat.local.prefs.ChannelPreferences
import com.bitchat.local.prefs.UserPreferences
import com.bitchat.mediautils.compressImageForTransfer
import com.bitchat.mediautils.getFileName
import com.bitchat.mediautils.getMimeType
import com.bitchat.mediautils.readFileBytes
import com.bitchat.mediautils.saveFileToLocal
import com.bitchat.mediautils.safeReceivedFileName
import com.bitchat.noise.model.NoisePayload
import com.bitchat.noise.model.NoisePayloadType
import com.bitchat.noise.model.PrivateMessagePacket
import com.bitchat.nostr.Bech32
import com.bitchat.nostr.NostrClient
import com.bitchat.nostr.NostrPreferences
import com.bitchat.nostr.NostrProofOfWork
import com.bitchat.nostr.NostrRelay
import com.bitchat.nostr.NostrSubscriptionId
import com.bitchat.nostr.NostrTransport
import com.bitchat.nostr.logging.logNostrDebug
import com.bitchat.nostr.model.NostrEvent
import com.bitchat.nostr.model.NostrFilter
import com.bitchat.nostr.model.NostrIdentity
import com.bitchat.nostr.model.NostrKind
import com.bitchat.nostr.participant.NostrParticipantTracker
import com.bitchat.nostr.util.HandledGiftWraps
import com.bitchat.nostr.util.hexStringToByteArray
import com.bitchat.nostr.util.toHexString
import com.bitchat.lora.LoRaPeer
import com.bitchat.lora.loRaHeartbeatNickname
import com.bitchat.lora.mayBeCutLoRaNickname
import com.bitchat.local.prefs.FavoritesUpdate
import com.bitchat.local.prefs.LoRaPreferences
import com.bitchat.repo.lora.loRaConfiguration
import com.bitchat.repo.lora.toLoRaConfiguration
import com.bitchat.repo.utils.CrossTransportTwins
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import com.bitchat.repo.utils.BoundedIdSet
import com.bitchat.repo.utils.FavoriteNotification
import com.bitchat.repo.utils.LearnedNames
import com.bitchat.repo.utils.announcedMeshName
import com.bitchat.repo.utils.meshNameHandedBack
import com.bitchat.repo.utils.MessageLimits
import com.bitchat.repo.utils.MAX_NOT_FAVOURITED_RELATIONSHIPS
import com.bitchat.repo.utils.ReceivedFileBudget
import com.bitchat.repo.utils.applyFavoriteNotification
import com.bitchat.repo.utils.notFavouritedOverLimit
import com.bitchat.lora.LoRaProtocol
import com.bitchat.lora.LoRaProtocolManager
import com.bitchat.lora.LoRaProtocolType
import com.bitchat.repo.tor.torGateAllowsTraffic
import com.bitchat.domain.tor.RequestedTorIntent
import com.bitchat.domain.tor.model.TorMode
import com.bitchat.tor.TorManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

class ChatRepo(
    private val coroutineScopeFacade: CoroutineScopeFacade,
    private val coroutinesContextFacade: CoroutinesContextFacade,
    private val mesh: BluetoothMeshService,
    private val nostr: NostrTransport,
    private val nostrPreferences: NostrPreferences,
    private val nostrClient: NostrClient,
    private val nostrRelay: NostrRelay,
    private val geohashAliasCache: Cache<String, String>,
    private val geohashConversationCache: Cache<String, String>,
    private val channelPreferences: ChannelPreferences,
    private val userPreferences: UserPreferences,
    private val blockListPreferences: BlockListPreferences,
    private val participantTracker: NostrParticipantTracker,
    private val locationEventBus: LocationEventBus,
    private val chatEventBus: ChatEventBus,
    private val userRepository: com.bitchat.domain.user.repository.UserRepository,
    private val appRepository: com.bitchat.domain.app.repository.AppRepository,
    private val userEventBus: com.bitchat.domain.user.eventbus.UserEventBus,
    private val connectEventBus: com.bitchat.domain.connectivity.eventbus.ConnectionEventBus,
    private val torManager: TorManager? = null,
    private val requestedTorIntent: RequestedTorIntent? = null,
    private val lora: LoRaProtocol? = null,
    private val loraPreferences: LoRaPreferences? = null,
    private val clock: Clock = Clock.System,
    private val receivedFileBudget: ReceivedFileBudget = ReceivedFileBudget(),
    private val messageLimits: MessageLimits = MessageLimits(),
) : ChatRepository, BluetoothMeshDelegate {
    // Private texts waiting for a session, per peer, oldest first. Queueing, sending what is
    // queued and the decision to send a text without queueing it all happen under outboxLock: a
    // text never goes out ahead of one that was written before it.
    private val outbox = mutableMapOf<String, MutableList<Triple<String, String, String>>>()
    private val outboxLock = SynchronizedObject()

    private val channelKeys = mutableMapOf<String, ByteArray>()

    private val geohashMessagesFlows = mutableMapOf<String, MutableStateFlow<List<BitchatMessage>>>()
    private val activeGeohashSubscriptions = mutableSetOf<String>()

    private val meshChannelMessages = mutableListOf<BitchatMessage>()
    // The connected mesh peers, as one immutable snapshot replaced whole. It is the only record of
    // who is connected, so the mesh people and the LoRa peers hidden because of them cannot disagree,
    // and any thread can read it.
    private val meshPeers = MutableStateFlow<List<GeoPerson>>(emptyList())
    private val crossTransportTwins = CrossTransportTwins()
    // Guards every read and write of [meshChannelMessages], and [crossTransportTwins] with it.
    private val meshChannelMessagesMutex = Mutex()

    /**
     * Guards the private chats and what is kept beside them: [privateChats], [writtenPrivateChats],
     * [unreadPrivatePeers], [unreadPrivateMessageIds], [latestUnreadPrivatePeer], [knownPrivatePeers] and
     * [meshChatNames].
     * Handlers for several relays and for the mesh add private messages at the same time, and keeping
     * the strangers' chats within their shared budget reads every chat, and removes what was kept about
     * an emptied one, from whichever of them is adding. Held only around plain reads and writes:
     * nothing suspends, sends or publishes inside it.
     */
    private val privateChatsLock = SynchronizedObject()

    // Each chat's messages in the order they ARRIVED; getPrivateChats sorts a copy for display. The
    // oldest arrival is what a full chat drops, whatever timestamp its sender wrote on it.
    private val privateChats = mutableMapOf<String, MutableList<BitchatMessage>>()
    // What each mesh private chat is called: the name it was opened under, given once and then kept for as
    // long as the chat is. Nothing on the mesh authenticates a nickname, so a later announcement, by the
    // peer or by anyone using its id, must not rename a conversation that a Noise session authenticates.
    // Its keys are always a subset of [privateChats]' keys.
    private val meshChatNames = mutableMapOf<String, String>()
    // The chats the user has sent a message in during this run. Deliberately not inferred from the
    // messages: the user's own can age out of a chat, and the chat stays theirs.
    private val writtenPrivateChats = mutableSetOf<String>()

    private val unreadPrivatePeers = mutableSetOf<String>()
    private val unreadPrivateMessageIds = mutableMapOf<String, MutableSet<String>>()
    private var latestUnreadPrivatePeer: String? = null
    private var selectedPrivatePeer: String? = null
    private val knownPrivatePeers = mutableMapOf<String, String>()
    // The names peers go by, in memory only: what traffic teaches (bounded) and what the user's own
    // actions recorded. Nothing a peer calls itself is saved.
    private val learnedNames = LearnedNames()
    private val lastReadTimestamps = mutableMapOf<String, Long>().apply {
        putAll(userPreferences.getAllLastReadTimestamps())
    }
    /** Gift wraps whose envelope decryptPrivateMessage accepted; see [HandledGiftWraps] for the bound. */
    private val handledGiftWraps = HandledGiftWraps()
    private val activeDmSubscriptions = mutableSetOf<String>()
    private val activeGeohashDmSubscriptions = mutableSetOf<String>()
    private val deliveredMessageIds = BoundedIdSet(messageLimits.maxTrackedReceiptIds)
    private val readMessageIds = BoundedIdSet(messageLimits.maxTrackedReceiptIds)
    private val sentDeliveryAckIds = BoundedIdSet(messageLimits.maxTrackedReceiptIds)

    private val namedChannelMessages = mutableMapOf<String, MutableList<BitchatMessage>>()
    private val namedChannelMembers = mutableMapOf<String, MutableSet<ChannelMember>>()
    private val channelCreatorNpubs = mutableMapOf<String, String>()
    private val channelKeyCommitments = mutableMapOf<String, String>()
    private val channelNostrEventIds = mutableMapOf<String, String>()
    private val verifiedOwnerChannels = mutableSetOf<String>()

    private fun normalizeChannelName(name: String): String {
        val withHash = if (name.startsWith("#")) name else "#$name"
        return withHash.lowercase()
    }

    private fun isMeshChatKey(peerID: String): Boolean = !peerID.startsWith("nostr_")

    /**
     * Gives the mesh private chat with [peerID] the name [claim] if it has none yet, and says whether it
     * did. A name that is there is never replaced, and a conversation that is not a mesh chat gets none
     * (callers hand in whatever they know without asking which kind it is). Called with
     * [privateChatsLock] held, for a chat that exists; whoever calls it publishes
     * [ChatEvent.PrivateChatsUpdated] once the lock is released when it answers true, because the people
     * list reads the chats' names again only on that event.
     */
    private fun nameMeshChatIfUnnamed(peerID: String, claim: String?): Boolean {
        if (!isMeshChatKey(peerID) || claim == null || peerID in meshChatNames) return false
        meshChatNames[peerID] = meshChatName(claim, peerID)
        return true
    }

    /** What the mesh peer announces as its name right now. Unauthenticated: anyone in range can announce for any id. */
    private fun claimedMeshName(peerID: String): String? = announcedMeshName(mesh.getPeerInfo(peerID)?.nickname)

    init {
        mesh.delegate = this

        coroutineScopeFacade.applicationScope.launch {
            combine(
                userEventBus.events()
                    .onStart { emit(UserEvent.StateChanged) },
                connectEventBus.getBluetoothConnectionEvent()
                    .onStart { emit(connectEventBus.getBluetoothConnectionEvent().first()) }
            ) { userEvent, bluetoothEvent ->
                when (userEvent) {
                    UserEvent.StateChanged,
                    is UserEvent.LoginChanged -> {
                        val userState = userRepository.getUserState()
                        val hasPermissions = appRepository.hasRequiredPermissions()
                        val bluetoothEnabled = bluetoothEvent == BluetoothConnectionEvent.CONNECTED

                        val shouldRun = userState is UserState.Active && hasPermissions && bluetoothEnabled
                        val reason = "state=$userState, bt=$bluetoothEnabled, perms=$hasPermissions"

                        shouldRun to reason
                    }

                    else -> null
                }
            }
                .filterNotNull()
                .distinctUntilChanged { old, new -> old.first == new.first }
                .collect { (shouldRun, reason) ->
                    if (shouldRun) {
                        println("🔵 Starting BluetoothMeshService: $reason")
                        mesh.startServices()
                    } else {
                        println("🔴 Stopping BluetoothMeshService: $reason")
                        mesh.stopServices()
                    }
                }
        }

        observeTorReadyAndEstablishConnections()
        observeTorTurnedOffAndRestoreRelays()
        subscribeToDirectMessages()

        // Older versions saved every name any key announced, and a favourite record plus a peer-id to
        // npub mapping for anyone who sent a notification. The names are removed: they are kept in
        // memory only now. So is the mapping: it was a second copy of the key each record carries
        // itself, nothing read it, and a copy has to be kept right through every toggle and restart
        // or it is wrong. The records the user never favourited are cut to the limit for new ones.
        userPreferences.clearPeerDisplayNames()
        userPreferences.clearAllPeerIDMappings()
        coroutineScopeFacade.applicationScope.launch {
            val removedKey = withContext(coroutinesContextFacade.io) {
                userPreferences.updateFavorites { favorites ->
                    val excess = notFavouritedOverLimit(favorites, MAX_NOT_FAVOURITED_RELATIONSHIPS)
                    FavoritesUpdate(result = excess.firstOrNull(), remove = excess)
                }
            }
            // Whoever read the favourites before this finished still shows what was removed.
            if (removedKey != null) userEventBus.update(UserEvent.FavoriteStatusChanged(removedKey))
        }

        // Listen for incoming LoRa packets
        lora?.let { loraTransport ->
            mesh.radioLink = loraTransport.meshPacketLink
            coroutineScopeFacade.applicationScope.launch {
                loraTransport.incomingMessages.collect { packetBytes ->
                    handleLoRaPacket(packetBytes)
                }
            }
            coroutineScopeFacade.applicationScope.launch {
                loraTransport.incomingMeshPackets.collect { mesh.onLoRaPacketReceived(it) }
            }
            // A peer the radio hears again may be one the user is owed a handshake with, or one
            // with texts waiting for it.
            coroutineScopeFacade.applicationScope.launch {
                loraTransport.peers.collect {
                    mesh.onRadioHearsChanged()
                    flushOutboxOverMesh()
                }
            }
        }

        coroutineScopeFacade.applicationScope.launch {
            val persistedEventIds = channelPreferences.getChannelEventIds()
            persistedEventIds.forEach { (channelName, eventId) ->
                channelNostrEventIds[channelName] = eventId
            }

            delay(2000)

            val joinedChannels = channelPreferences.getJoinedChannelsList()
            joinedChannels.forEach { channelName ->
                val eventId = channelNostrEventIds[channelName]
                if (eventId != null) {
                    subscribeToNamedChannelMessages(channelName, eventId)
                } else {
                    try {
                        val channelInfo = discoverNamedChannel(channelName)
                        val discoveredEventId = channelInfo?.nostrEventId
                        if (discoveredEventId != null) {
                            channelNostrEventIds[channelName] = discoveredEventId
                            channelPreferences.setChannelEventId(channelName, discoveredEventId)
                            subscribeToNamedChannelMessages(channelName, discoveredEventId)
                        }
                    } catch (e: Exception) {
                    }
                }
            }
        }
    }

    override suspend fun getGeohashMessages(geohash: String): List<BitchatMessage> = withContext(coroutinesContextFacade.io) {
        if (!activeGeohashSubscriptions.contains(geohash)) {
            subscribeToGeohash(geohash)
            activeGeohashSubscriptions.add(geohash)
        }

        val flow = geohashMessagesFlows.getOrPut(geohash) {
            MutableStateFlow(emptyList())
        }

        // Kept in arrival order (the oldest arrival is what a full chat drops), shown by timestamp.
        flow.value.sortedBy { it.timestamp }
    }

    override suspend fun getMeshMessages(): List<BitchatMessage> = withContext(coroutinesContextFacade.io) {
        meshChannelMessagesMutex.withLock { meshChannelMessages.toList() }
    }

    // The block list is read once for a whole list: every lookup through isMeshUserBlocked decodes it again.
    private fun blockedMeshIds(): Set<String> = blockListPreferences.getMeshBlockedUsers().keys

    override suspend fun getMeshPeers(): List<GeoPerson> = withContext(coroutinesContextFacade.io) {
        val blocked = blockedMeshIds()
        meshPeers.value.filter { it.id.lowercase() !in blocked }
    }

    override suspend fun getLoRaPeers(): List<LoRaPerson> = withContext(coroutinesContextFacade.io) {
        val peers = lora?.peers?.value ?: return@withContext emptyList()
        val idsAreMeshIds = lora.peerIdsAreMeshIds
        val blocked = blockedMeshIds()
        peers.mapNotNull { peer ->
            val meshDeviceId = peer.deviceId.takeIf { idsAreMeshIds }
            if (meshDeviceId != null && meshDeviceId.lowercase() in blocked) {
                null
            } else {
                LoRaPerson(
                    id = peer.deviceId,
                    // A heartbeat's name is its sender's choice like a mesh nickname, and is shown in the same list.
                    displayName = sanitizedMeshNickname(peer.nickname) ?: peer.deviceId.take(12),
                    lastSeen = peer.lastSeen,
                    meshDeviceId = meshDeviceId,
                )
            }
        }
    }

    override fun observeLoRaPeerChanges(): Flow<Unit> =
        lora?.peers?.map { } ?: kotlinx.coroutines.flow.flowOf(Unit)

    override suspend fun getPrivateChatNames(): Map<String, String?> = withContext(coroutinesContextFacade.io) {
        synchronized(privateChatsLock) {
            privateChats.mapValues { (key, messages) ->
                if (isMeshChatKey(key)) meshChatNames[key]
                else messages.asReversed().filter { it.senderPeerID == key }.maxByOrNull { it.timestamp }?.sender
            }
        }
    }

    override suspend fun switchLoRaProtocol(protocol: String): Boolean = withContext(coroutinesContextFacade.io) {
        val manager = lora as? LoRaProtocolManager
        if (manager == null) {
            println("⚠️ ChatRepo: Cannot switch protocol - LoRaProtocolManager not available")
            return@withContext false
        }

        val protocolType = when (protocol.uppercase()) {
            "MESHTASTIC" -> LoRaProtocolType.MESHTASTIC
            "MESHCORE" -> LoRaProtocolType.MESHCORE
            else -> LoRaProtocolType.BITCHAT
        }

        println("📡 ChatRepo: Switching LoRa protocol to $protocol")
        manager.switchProtocol(protocolType, loraPreferences?.toLoRaConfiguration())
    }

    override suspend fun reconfigureLoRa(region: LoRaRegion, txPower: LoRaTxPower): Boolean =
        withContext(coroutinesContextFacade.io) {
            val manager = lora as? LoRaProtocolManager
            if (manager == null) {
                println("⚠️ ChatRepo: Cannot reconfigure LoRa - LoRaProtocolManager not available")
                return@withContext false
            }

            val config = loRaConfiguration(
                region,
                txPower,
                loraPreferences?.getBandwidth() ?: LoRaBandwidth.KHZ_125,
            )

            println(
                "📡 ChatRepo: Reconfiguring LoRa runtime (region=$region, txPower=$txPower, " +
                    "freq=${config.frequency}, bandwidth=${config.bandwidth}, sf=${config.spreadingFactor}, " +
                    "sync=0x${config.syncWord.toString(16)})"
            )
            manager.reconfigure(config)
        }

    override fun getActiveLoRaProtocol(): String {
        val manager = lora as? LoRaProtocolManager
        return manager?.activeType?.value?.name ?: lora?.protocolName ?: "BITCHAT"
    }

    override suspend fun getGeohashParticipants(geohash: String): Map<String, String> = withContext(coroutinesContextFacade.io) {
        val cutoff = Clock.System.now() - 5.minutes
        val participants = participantTracker.currentGeohashPeople.value

        val active = participants
            .filter { it.lastSeen > cutoff }
            .filter { !blockListPreferences.isGeohashUserBlocked(it.id) }
        val mapped = active.associate { participant ->
            participant.id to participant.displayName
        }
        logNostrDebug(
            "ChatRepo",
            "getGeohashParticipants($geohash) -> ${mapped.size} active: ${active.joinToString { it.displayName }}"
        )
        mapped
    }

    override suspend fun getPrivateChats(): Map<String, List<BitchatMessage>> = withContext(coroutinesContextFacade.io) {
        val chats = synchronized(privateChatsLock) { privateChats.mapValues { it.value.toList() } }
        // Kept in arrival order, shown in the order of their timestamps (equal ones as they arrived).
        chats.mapValues { (_, messages) -> messages.sortedBy { it.timestamp } }
    }

    override fun observeMiningStatus(): Flow<String?> {
        return nostrClient.currentlyMiningMessageId
    }

    override suspend fun getUnreadPrivatePeers(): Set<String> = withContext(coroutinesContextFacade.io) {
        synchronized(privateChatsLock) { unreadPrivatePeers.toSet() }
    }

    override suspend fun getPeerSessionStates(): Map<String, String> = withContext(coroutinesContextFacade.io) {
        mesh.getPeerNicknames()
            .keys
            .filter { it != mesh.myPeerID }
            .associateWith { peerID ->
                mesh.getSessionState(peerID)
            }
    }

    override suspend fun getLatestUnreadPrivatePeer(): String? = withContext(coroutinesContextFacade.io) {
        synchronized(privateChatsLock) { latestUnreadPrivatePeer }
    }

    override suspend fun getSelectedPrivatePeer(): String? = withContext(coroutinesContextFacade.io) {
        selectedPrivatePeer
    }

    override suspend fun setSelectedPrivatePeer(peerID: String?) = withContext(coroutinesContextFacade.io) {
        selectedPrivatePeer = peerID
        chatEventBus.update(ChatEvent.SelectedPrivatePeerChanged)
    }

    override suspend fun markPrivateChatRead(peerID: String) = withContext(coroutinesContextFacade.io) {
        var wasUnread = false
        var latestChanged = false
        val unreadIds = synchronized(privateChatsLock) {
            val ids = unreadPrivateMessageIds.remove(peerID).orEmpty()
            wasUnread = unreadPrivatePeers.remove(peerID)
            if (latestUnreadPrivatePeer == peerID) {
                latestUnreadPrivatePeer = resolveLatestUnreadPeer()
                latestChanged = true
            }
            ids
        }
        unreadIds.forEach { messageId ->
            sendReadReceipt(messageId, readerPeerID = null, toPeerID = peerID)
        }
        if (latestChanged) {
            chatEventBus.update(ChatEvent.LatestUnreadPrivatePeerChanged)
        }

        if (wasUnread) {
            chatEventBus.update(ChatEvent.UnreadPrivatePeersUpdated)
        }

        val now = Clock.System.now().toEpochMilliseconds()
        lastReadTimestamps[peerID.lowercase()] = now
        userPreferences.setLastReadTimestamp(peerID, now)
    }

    private fun subscribeToGeohash(geohash: String) {
        println("ChatRepo: Subscribing to geohash: $geohash")

        coroutineScopeFacade.nostrScope.launch {
            if (!torGateAllowsTraffic()) return@launch
            nostrRelay.ensureGeohashRelaysConnected(geohash, nRelays = 5, includeDefaults = false)
        }

        val filter = NostrFilter(
            kinds = listOf(NostrKind.EPHEMERAL_EVENT),
            tagFilters = mapOf("g" to listOf(geohash))
        )

        val relayUrls = nostrRelay.getRelaysForGeohash(geohash).toSet()
        println("ChatRepo: Creating subscription for ${relayUrls.size} relays (geohash $geohash)")

        nostrRelay.subscribe(
            subscriptionId = NostrSubscriptionId.geohash(geohash),
            filter = filter,
            handler = { event -> handleGeohashEvent(geohash, event) },
            targetRelayUrls = relayUrls.ifEmpty { null },
            originGeohash = geohash
        )

        println("ChatRepo: Geohash subscription created - will be sent when relays connect")

        subscribeToGeohashDirectMessages(geohash)
    }

    private fun subscribeToDirectMessages() {
        coroutineScopeFacade.nostrScope.launch {
            val identity = nostrClient.getCurrentNostrIdentity() ?: return@launch
            val pubkey = identity.publicKeyHex
            if (!activeDmSubscriptions.add(pubkey)) return@launch

            // Subscription intent is independent of route readiness. NostrRelay retains this
            // registration and sends it as soon as a READY/OFF-recovery connection opens.
            nostrRelay.ensureDefaultRelaysConnected()

            val filter = NostrFilter.giftWrapsFor(pubkey)
            nostrRelay.subscribe(
                subscriptionId = NostrSubscriptionId.directMessages(pubkey),
                filter = filter,
                handler = { event -> handleDirectMessageEvent(event, identity, null) },
                targetRelayUrls = null,
                originGeohash = null
            )
        }
    }

    private fun subscribeToGeohashDirectMessages(geohash: String) {
        if (!activeGeohashDmSubscriptions.add(geohash)) return

        coroutineScopeFacade.nostrScope.launch {
            val identity = runCatching { nostrClient.deriveIdentity(geohash) }.getOrNull() ?: return@launch
            // Keep the desired geohash subscription while Tor is starting. The relay restores
            // it when its route reconnects instead of losing it to a one-shot readiness gate.
            nostrRelay.ensureGeohashRelaysConnected(geohash, nRelays = 5, includeDefaults = false)

            val relayUrls = nostrRelay.getRelaysForGeohash(geohash).toSet()
            val filter = NostrFilter.giftWrapsFor(identity.publicKeyHex)
            nostrRelay.subscribe(
                subscriptionId = NostrSubscriptionId.geohashDirectMessages(geohash),
                filter = filter,
                handler = { event -> handleDirectMessageEvent(event, identity, geohash) },
                targetRelayUrls = relayUrls.ifEmpty { null },
                originGeohash = geohash
            )
        }
    }

    override suspend fun sendGeohashMessage(content: String, geohash: String, nickname: String): Unit {
        requireSendable(content)
        sendGeohashMessage(content, geohash, nickname, BitchatMessageType.Message)
    }

    private suspend fun sendGeohashMessage(
        content: String,
        geohash: String,
        nickname: String,
        messageType: BitchatMessageType
    ): Unit = withContext(coroutinesContextFacade.io) {
            try {
                println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                println("📨 ChatRepo.sendGeohashMessage STARTED")
                println("   Geohash: $geohash")
                println("   Nickname: $nickname")
                println("   Content length: ${content.length}")
                println("   Content: ${logBody(content, 50)}")
                println("   Message type: $messageType")
                println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")

                // Refuse rather than walk into a request the engine will reject anyway. The
                // message is not sent either way; this just skips the wait and the doomed attempt.
                if (!torGateAllowsTraffic()) {
                    println("⚠️  ChatRepo: message not sent - Tor is requested but not ready")
                    return@withContext
                }

                // Check relay availability first
                val relays = nostrRelay.getRelaysForGeohash(geohash)
                if (relays.isEmpty()) {
                    println("⚠️  ChatRepo: No relays available, ensuring connection...")
                    nostrRelay.ensureGeohashRelaysConnected(geohash, nRelays = 5, includeDefaults = false)
                    delay(2000) // Wait for connections
                }

                val identity = nostrClient.deriveIdentity(geohash)
                println("✅ ChatRepo: Identity derived, pubkey=${identity.publicKeyHex.take(16)}...")

                // Generate temporary ID for mining tracking
                val tempId = "mining-${Clock.System.now().toEpochMilliseconds()}"
                println("🎬 ChatRepo: Generated temp ID: $tempId")

                // ✨ STEP 1: Add local echo BEFORE mining starts (with tempId)
                val localMessage = messageLimits.notLaterThan(BitchatMessage(
                    id = tempId,  // Use tempId for animation tracking
                    sender = nickname,
                    content = content,
                    type = messageType,
                    timestamp = Clock.System.now(),
                    isPrivate = false,
                    senderPeerID = identity.publicKeyHex,
                    channel = geohash,
                    powDifficulty = null  // Will be set after mining
                ), clock.now())

                val flow = geohashMessagesFlows.getOrPut(geohash) {
                    MutableStateFlow(emptyList())
                }
                val currentMessages = flow.value.toMutableList()
                currentMessages.add(localMessage)
                flow.value = messageLimits.trimmed(currentMessages)

                // Emit event to notify observers
                coroutineScopeFacade.nostrScope.launch {
                    chatEventBus.update(ChatEvent.GeohashMessagesUpdated(geohash))
                }

                println("✅ ChatRepo: Added local echo with tempId to UI, total messages: ${flow.value.size}")

                // ✨ STEP 2: NOW mine the event (this will trigger animation via tempId tracking)
                println("🔨 ChatRepo: Creating ephemeral geohash event...")
                val event = nostrClient.createEphemeralGeohashEvent(
                    content = content,
                    geohash = geohash,
                    senderIdentity = identity,
                    nickname = nickname,
                    teleported = false,
                    tempId = tempId
                )
                println("✅ ChatRepo: Event created with FINAL id=${event.id.take(16)}... kind=${event.kind}")

                // ✨ STEP 3: Update message from tempId to final event.id
                val updatedMessages = flow.value.map { msg ->
                    if (msg.id == tempId) {
                        msg.copy(
                            id = event.id,  // Replace tempId with final event.id
                            powDifficulty = event.tags.find { it.firstOrNull() == "nonce" }?.getOrNull(2)?.toIntOrNull()
                        )
                    } else {
                        msg
                    }
                }
                flow.value = updatedMessages

                // Emit event to notify observers
                coroutineScopeFacade.nostrScope.launch {
                    chatEventBus.update(ChatEvent.GeohashMessagesUpdated(geohash))
                }

                println("✅ ChatRepo: Updated message from tempId=${tempId.take(16)}... to final event.id=${event.id.take(16)}...")

                // Now send to relays
                val finalRelays = nostrRelay.getRelaysForGeohash(geohash)
                println("📡 ChatRepo: Publishing to ${finalRelays.size} relays")
                finalRelays.forEachIndexed { index, relay ->
                    println("   ${index + 1}. $relay")
                }

                nostrRelay.sendEventToGeohash(
                    event = event,
                    geohash = geohash,
                    includeDefaults = false,
                    nRelays = 5
                )

                // The user has just posted here, so they are present. This used to happen when a
                // relay echoed the event back; that echo is a duplicate of the local echo and marks
                // nothing any more.
                markGeohashParticipant(geohash, identity.publicKeyHex, sanitizedNickname(nickname), event.createdAt, isTeleported = false)

                // Note: Geohash messages are ONLY sent via Nostr, not Bluetooth mesh
                // (matches legacy Android behavior - Bluetooth is for mesh broadcasts only)

                println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                println("✅ ChatRepo.sendGeohashMessage COMPLETED")
                println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
            } catch (e: Exception) {
                println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                println("❌ ChatRepo.sendGeohashMessage FAILED")
                println("   Error: ${e.message}")
                println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                e.printStackTrace()
                throw e // Re-throw so ViewModel can show error to user
            }
        }

    override suspend fun sendMeshMessage(content: String, nickname: String): Unit {
        requireSendable(content)
        sendMeshMessage(content, nickname, BitchatMessageType.Message)
    }

    private suspend fun sendMeshMessage(
        content: String,
        nickname: String,
        messageType: BitchatMessageType
    ): Unit = withContext(coroutinesContextFacade.io) {
        try {
            println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
            println("📨 ChatRepo.sendMeshMessage STARTED")
            println("   Nickname: $nickname")
            println("   Content length: ${content.length}")
            println("   Content: ${logBody(content, 50)}")
            println("   Message type: $messageType")
            println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")

            val messageID = "mesh-${Clock.System.now().toEpochMilliseconds()}"

            // Local echo
            val localMessage = BitchatMessage(
                id = messageID,
                sender = nickname,
                content = content,
                type = messageType,
                timestamp = Clock.System.now(),
                isPrivate = false,
                senderPeerID = mesh.myPeerID
            )

            val total = meshChannelMessagesMutex.withLock {
                meshChannelMessages.add(localMessage)
                trimMeshMessages()
                meshChannelMessages.size
            }
            chatEventBus.update(ChatEvent.MeshMessagesUpdated)
            println("✅ ChatRepo: Added local echo to mesh messages, total: $total")

            // Send via LoRa FIRST (independent of BLE state)
            println("📻 ChatRepo: LoRa check - lora=${lora != null}, isReady=${lora?.isReady}, messageType=$messageType")
            if (lora != null && lora.isReady && messageType == BitchatMessageType.Message) {
                try {
                    // Format: "nickname:content" for simple text protocol
                    val loraPayload = "$nickname:$content".encodeToByteArray()
                    println("📻 ChatRepo: Sending via LoRa: ${loraPayload.size} bytes")
                    val sent = lora.send(loraPayload)
                    if (sent) {
                        println("📻 ChatRepo: Message broadcast via LoRa SUCCESS (${loraPayload.size} bytes)")
                    } else {
                        println("⚠️ ChatRepo: LoRa send returned false (radio not ready or busy)")
                    }
                } catch (e: Exception) {
                    println("⚠️ ChatRepo: LoRa send error: ${e.message}")
                    e.printStackTrace()
                }
            } else if (lora != null && messageType == BitchatMessageType.Message) {
                println("📻 ChatRepo: LoRa available but not ready (isReady=${lora.isReady}), skipping")
            } else if (lora == null) {
                println("📻 ChatRepo: LoRa transport is null")
            }

            // Send via Bluetooth - handle file types specially
            try {
                when (messageType) {
                    BitchatMessageType.Image -> {
                        // Compress image for BLE transfer (max 100KB)
                        val preparedImage = compressImageForTransfer(content)
                        if (preparedImage != null) {
                            val filePacket = BitchatFilePacket(
                                fileName = preparedImage.fileName,
                                fileSize = preparedImage.bytes.size.toLong(),
                                mimeType = preparedImage.mimeType,
                                content = preparedImage.bytes
                            )
                            // Log first bytes to verify JPEG format (should start with FF D8 FF)
                            val firstBytes = preparedImage.bytes.take(10).joinToString(" ") { byte ->
                                (byte.toInt() and 0xFF).toString(16).padStart(2, '0').uppercase()
                            }
                            println("📎 ChatRepo: Image first bytes: ${logBytes(10) { firstBytes }}")
                            if (preparedImage.bytes.size > BitchatFilePacket.MAX_CONTENT_BYTES) {
                                markMeshMessageFailed(messageID, fileTooLargeReason())
                            } else {
                                mesh.sendFileBroadcast(filePacket)
                                println("📎 ChatRepo: Compressed image broadcast sent: ${logPath(preparedImage.fileName)} (${preparedImage.bytes.size} bytes, ${preparedImage.mimeType})")
                            }
                        } else {
                            println("❌ ChatRepo: Failed to compress image for BLE transfer: ${logPath(content)}")
                        }
                    }
                    BitchatMessageType.Audio -> {
                        // Audio files - read as-is (already compressed)
                        val fileBytes = readFileBytes(content)
                        if (fileBytes != null) {
                            val fileName = getFileName(content)
                            val mimeType = getMimeType(content)
                            val filePacket = BitchatFilePacket(
                                fileName = fileName,
                                fileSize = fileBytes.size.toLong(),
                                mimeType = mimeType,
                                content = fileBytes
                            )
                            if (fileBytes.size > BitchatFilePacket.MAX_CONTENT_BYTES) {
                                markMeshMessageFailed(messageID, fileTooLargeReason())
                            } else {
                                mesh.sendFileBroadcast(filePacket)
                                println("📎 ChatRepo: Audio file broadcast sent: ${logPath(fileName)} (${fileBytes.size} bytes)")
                            }
                        } else {
                            println("❌ ChatRepo: Failed to read audio file: ${logPath(content)}")
                        }
                    }
                    else -> {
                        // Regular text message
                        mesh.sendMessage(content, mentions = emptyList())
                        println("📡 ChatRepo: Message broadcast via Bluetooth mesh")
                    }
                }
            } catch (e: Exception) {
                println("⚠️ ChatRepo: BLE send failed: ${logError(e)}")
                // Don't fail if BLE fails - LoRa may have succeeded
            }

            println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
            println("✅ ChatRepo.sendMeshMessage COMPLETED")
            println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
        } catch (e: Exception) {
            println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
            println("❌ ChatRepo.sendMeshMessage FAILED")
            println("   Error: ${e.message}")
            println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
            e.printStackTrace()
            throw e // Re-throw so ViewModel can show error to user
        }
    }

    /**
     * Send a message via Meshtastic protocol.
     *
     * Creates a local echo, stores it, and sends via the LoRa transport
     * (which is bound to MeshtasticProtocol when Meshtastic is selected).
     */
    private suspend fun sendMeshtasticMessage(
        content: String,
        nickname: String,
        messageType: BitchatMessageType
    ): Unit = withContext(coroutinesContextFacade.io) {
        try {
            println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
            println("📡 ChatRepo.sendMeshtasticMessage STARTED")
            println("   Nickname: $nickname")
            println("   Content length: ${content.length}")
            println("   Content: ${logBody(content, 50)}")
            println("   Message type: $messageType")
            println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")

            val messageID = "meshtastic-${Clock.System.now().toEpochMilliseconds()}"

            // Local echo
            val localMessage = BitchatMessage(
                id = messageID,
                sender = nickname,
                content = content,
                type = messageType,
                timestamp = Clock.System.now(),
                isPrivate = false,
                senderPeerID = lora?.deviceId ?: ""
            )

            // Store in meshChannelMessages (shared with BLE mesh for UI simplicity)
            val total = meshChannelMessagesMutex.withLock {
                meshChannelMessages.add(localMessage)
                trimMeshMessages()
                meshChannelMessages.size
            }
            chatEventBus.update(ChatEvent.MeshMessagesUpdated)
            println("✅ ChatRepo: Added local echo to mesh messages, total: $total")

            // Send via LoRa (MeshtasticProtocol)
            if (lora != null && lora.isReady && messageType == BitchatMessageType.Message) {
                try {
                    // Format: "nickname:content" - MeshtasticProtocol handles protobuf encoding
                    val payload = "$nickname:$content".encodeToByteArray()
                    println("📡 ChatRepo: Sending via Meshtastic: ${payload.size} bytes")
                    val sent = lora.send(payload)
                    if (sent) {
                        println("📡 ChatRepo: Message sent via Meshtastic SUCCESS")
                    } else {
                        println("⚠️ ChatRepo: Meshtastic send returned false (not ready)")
                    }
                } catch (e: Exception) {
                    println("⚠️ ChatRepo: Meshtastic send error: ${e.message}")
                    e.printStackTrace()
                }
            } else if (lora == null) {
                println("⚠️ ChatRepo: No LoRa transport available for Meshtastic")
            } else if (!lora.isReady) {
                println("⚠️ ChatRepo: Meshtastic not ready (config not received?)")
            }

            println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
            println("✅ ChatRepo.sendMeshtasticMessage COMPLETED")
            println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
        } catch (e: Exception) {
            println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
            println("❌ ChatRepo.sendMeshtasticMessage FAILED")
            println("   Error: ${e.message}")
            println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
            e.printStackTrace()
            throw e
        }
    }

    /**
     * Process incoming geohash event and convert to BitchatMessage
     */
    private fun handleGeohashEvent(geohash: String, event: NostrEvent) {
        try {
            println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
            println("📬 ChatRepo.handleGeohashEvent STARTED")
            if (event.content.length > BitchatMessage.MAX_CONTENT_CHARS) {
                println("ChatRepo: Dropped geohash message with content length ${event.content.length}")
                return
            }
            println("   Geohash: $geohash")
            println("   Event ID: ${event.id.take(16)}...")
            println("   Event kind: ${event.kind}")
            println("   Content: ${logBody(event.content, 50)}")
            println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")

            // ENSURE flow exists for this geohash - prevent messages from being lost
            if (geohashMessagesFlows[geohash] == null) {
                println("⚠️  ChatRepo: Creating new flow for geohash=$geohash")
                geohashMessagesFlows[geohash] = kotlinx.coroutines.flow.MutableStateFlow(emptyList())
                println("✅ ChatRepo: Flow created and initialized")
            }

            val senderPubkey = event.pubkey
            val senderNickname = sanitizedNickname(event.tags.find { it.firstOrNull() == "n" }?.getOrNull(1))
            val isTeleported = event.tags.any { it.size >= 2 && it[0] == "t" && it[1] == "teleport" }

            println("   Sender pubkey: ${senderPubkey.take(16)}...")
            println("   Sender nickname: $senderNickname")
            println("   Is teleported: $isTeleported")

            // Block list check - silently drop messages from blocked users
            if (blockListPreferences.isGeohashUserBlocked(senderPubkey)) {
                println("🚫 ChatRepo: BLOCKED geohash message from ${senderPubkey.take(16)}...")
                println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                return
            }

            logNostrDebug(
                "ChatRepo",
                "Geohash=$geohash event=${event.id.take(16)} sender=${senderPubkey.take(16)}... nickname=${senderNickname ?: "anon"} teleported=$isTeleported"
            )

            val message = messageLimits.notLaterThan(BitchatMessage(
                id = event.id,
                sender = senderNickname ?: senderPubkey.take(16),
                content = event.content,
                type = BitchatMessageType.Message,
                timestamp = kotlin.time.Instant.fromEpochSeconds(event.createdAt.toLong()),
                isPrivate = false,
                senderPeerID = senderPubkey,
                channel = geohash,
                powDifficulty = event.tags.find { it.firstOrNull() == "nonce" }?.getOrNull(2)?.toIntOrNull()
            ), clock.now())

            val flow = geohashMessagesFlows[geohash]
            if (flow != null) {
                val currentMessages = flow.value.toMutableList()

                // Check for duplicates (our local echo or relay echo). A copy of something already
                // shown changes nothing: no presence, no name. It is only an id until it is checked.
                if (currentMessages.any { it.id == event.id }) {
                    println("⏭️  ChatRepo: Duplicate message detected, skipping")
                    println("   Message ID: ${event.id}")
                    println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                    return
                }

                // PoW validation (if enabled)
                val powEnabled = nostrPreferences.getPowEnabled()
                val powDifficulty = nostrPreferences.getPowDifficulty()

                if (powEnabled && powDifficulty > 0) {
                    if (!NostrProofOfWork.validateDifficulty(event, powDifficulty)) {
                        println("❌ ChatRepo: REJECTED geohash event - insufficient PoW (difficulty < $powDifficulty)")
                        println("   Event ID: ${event.id.take(16)}...")
                        println("   Sender: ${event.pubkey.take(16)}...")
                        println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                        return  // Drop message without processing
                    }
                    println("✅ ChatRepo: PoW validated for event ${event.id.take(16)} (difficulty >= $powDifficulty)")
                }

                // Only now, with the event accepted: one that is refused leaves no presence and no name.
                markGeohashParticipant(geohash, senderPubkey, senderNickname, event.createdAt, isTeleported)
                senderNickname?.let { learnedNames.learn("nostr_${senderPubkey.take(16)}", it) }

                currentMessages.add(message)
                flow.value = messageLimits.trimmed(currentMessages)

                // Emit event to notify observers
                coroutineScopeFacade.nostrScope.launch {
                    chatEventBus.update(ChatEvent.GeohashMessagesUpdated(geohash))
                }

                println("✅ ChatRepo: New message added to flow")
                println("   Total messages now: ${currentMessages.size}")
                println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                println("✅ ChatRepo.handleGeohashEvent COMPLETED")
                println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
            } else {
                println("❌ ChatRepo: ERROR - No flow for geohash $geohash")
                println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
            }
        } catch (e: Exception) {
            println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
            println("❌ ChatRepo.handleGeohashEvent FAILED")
            println("   Error: ${e.message}")
            println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
            e.printStackTrace()
        }
    }

    private fun handleDirectMessageEvent(
        event: NostrEvent,
        identity: NostrIdentity,
        sourceGeohash: String?
    ) {
        // Recorded only once the envelope authenticates, so a forged copy carrying a real DM's id
        // cannot have the real DM dropped as its duplicate.
        val decrypted = handledGiftWraps.acceptOnce(event) { nostrClient.decryptPrivateMessage(it, identity) } ?: return
        val (content, senderPubkey, timestamp) = decrypted
        if (!content.startsWith("bitchat1:")) return

        val payload = content.removePrefix("bitchat1:")
        val decoded = decodeBase64Url(payload) ?: return
        val packet = decoded.toBitchatPacket() ?: return
        if (packet.type != MessageType.NOISE_ENCRYPTED.value) return

        val noisePayload = NoisePayload.decode(packet.payload) ?: return
        when (noisePayload.type) {
            NoisePayloadType.PRIVATE_MESSAGE -> {
                handlePrivateMessagePayload(
                    payload = noisePayload.data,
                    senderPubkey = senderPubkey,
                    timestamp = timestamp,
                    sourceGeohash = sourceGeohash
                )
            }

            NoisePayloadType.DELIVERED -> {
                handleDeliveryAckPayload(
                    payload = noisePayload.data,
                    senderPubkey = senderPubkey,
                    timestamp = timestamp
                )
            }

            NoisePayloadType.READ_RECEIPT -> {
                handleReadReceiptPayload(
                    payload = noisePayload.data,
                    senderPubkey = senderPubkey,
                    timestamp = timestamp
                )
            }

            NoisePayloadType.FILE_TRANSFER, NoisePayloadType.DELIVERED_NUMBERS -> Unit
        }
    }

    private fun markGeohashParticipant(
        geohash: String,
        pubkey: String,
        nickname: String?,
        createdAt: Int,
        isTeleported: Boolean,
    ) {
        coroutineScopeFacade.nostrScope.launch {
            participantTracker.updateParticipant(
                geohash = geohash,
                pubkey = pubkey,
                nickname = nickname ?: "anon",
                timestamp = Instant.fromEpochSeconds(createdAt.toLong()),
                isTeleported = isTeleported
            )
            locationEventBus.update(LocationEvent.ParticipantsChanged)
            chatEventBus.update(ChatEvent.GeohashParticipantsChanged(geohash))
        }
    }

    private fun handlePrivateMessagePayload(
        payload: ByteArray,
        senderPubkey: String,
        timestamp: Int,
        sourceGeohash: String?
    ) {
        if (blockListPreferences.isGeohashUserBlocked(senderPubkey)) {
            println("🚫 ChatRepo: BLOCKED private message from ${senderPubkey.take(16)}...")
            return
        }

        val packet = PrivateMessagePacket.decode(payload) ?: return
        if (packet.content.length > BitchatMessage.MAX_CONTENT_CHARS) {
            println("ChatRepo: Dropped private message with content length ${packet.content.length}")
            return
        }
        val convKey = conversationKeyFor(senderPubkey)

        // Try to resolve display name from multiple sources:
        // 1. Cached display names from previous interactions
        // 2. Participant tracker (nicknames learned from geohash presence)
        // 3. Fall back to truncated pubkey for display only (don't cache fallback values)
        val knownName = learnedNames[convKey]
            ?: sanitizedNickname(participantTracker.getNicknameByPubkeySync(senderPubkey))
                ?.also { learnedNames.learn(convKey, it) }
        val senderDisplayName = knownName ?: senderPubkey.take(16)

        val favoritePayloadHandled = handleFavoriteNotificationIfNeeded(packet.content, convKey, senderDisplayName, senderPubkey)
        if (favoritePayloadHandled) return

        val message = BitchatMessage(
            id = packet.messageID,
            sender = senderDisplayName,
            content = packet.content,
            type = BitchatMessageType.Message,
            timestamp = Instant.fromEpochSeconds(timestamp.toLong()),
            isPrivate = true,
            senderPeerID = convKey,
            deliveryStatus = DeliveryStatus.Delivered(
                to = convKey,
                at = Clock.System.now()
            )
        )

        addPrivateMessage(
            convKey,
            message,
            markUnread = selectedPrivatePeer != convKey,
            sendReadReceipt = true,
            route = PrivateRoute(senderPubkey, sourceGeohash),
        )
        maybeSendDeliveryAck(packet.messageID, convKey)
    }

    private fun handleDeliveryAckPayload(
        payload: ByteArray,
        senderPubkey: String,
        timestamp: Int
    ) {
        val messageId = payload.decodeToString()
        if (!deliveredMessageIds.add(messageId)) return
        val convKey = conversationKeyFor(senderPubkey)
        updateDeliveryStatus(
            convKey = convKey,
            messageId = messageId,
            status = DeliveryStatus.Delivered(
                to = convKey,
                at = Instant.fromEpochSeconds(timestamp.toLong())
            )
        )
    }

    private fun handleReadReceiptPayload(
        payload: ByteArray,
        senderPubkey: String,
        timestamp: Int
    ) {
        val messageId = payload.decodeToString()
        if (!readMessageIds.add(messageId)) return
        val convKey = conversationKeyFor(senderPubkey)
        updateDeliveryStatus(
            convKey = convKey,
            messageId = messageId,
            status = DeliveryStatus.Read(
                by = convKey,
                at = Instant.fromEpochSeconds(timestamp.toLong())
            )
        )
    }

    /**
     * Adds a private message to its chat and keeps the chats within their limits ([MessageLimits]).
     *
     * A message longer than the limit is not kept, and nothing else happens for it. The message that
     * was just added is never the one dropped: a full chat drops its oldest ARRIVAL, and the strangers'
     * budget ([enforceStrangerMessageBudget]) passes over it. So a receipt is only ever sent for a
     * message that is there.
     *
     * [route] is where a Nostr chat is answered. It is recorded here, under the lock that also removes
     * it with an emptied chat, and nowhere before: recorded ahead of the message, another handler
     * could remove the chat and its key in between and leave a chat that cannot be answered. A message
     * that opens no chat (a favourite notification, an over-long one) so records nothing.
     */
    private fun addPrivateMessage(
        peerID: String,
        message: BitchatMessage,
        markUnread: Boolean,
        sendReadReceipt: Boolean,
        route: PrivateRoute? = null,
        meshNameClaim: String? = null,
        stampMeshSender: Boolean = false,
    ) {
        if (!messageLimits.accepts(message)) {
            println("ChatRepo: Dropped private message with content length ${message.content.length}")
            return
        }
        val limitedMessage = messageLimits.notLaterThan(message, clock.now())
        var unreadChanged = false
        var readReceiptDue = false
        var named = false

        val added = synchronized(privateChatsLock) {
            val messages = privateChats.getOrPut(peerID) { mutableListOf() }
            named = nameMeshChatIfUnnamed(peerID, meshNameClaim)
            val storedMessage = if (stampMeshSender && isMeshChatKey(peerID)) {
                limitedMessage.copy(sender = meshChatNames[peerID] ?: peerID.take(12))
            } else {
                limitedMessage
            }
            if (route != null) {
                knownPrivatePeers[peerID] = route.pubkeyHex
                if (route.sourceGeohash != null) {
                    geohashAliasCache[peerID] = route.pubkeyHex
                    geohashConversationCache[peerID] = route.sourceGeohash
                }
            }
            if (messages.any { it.id == storedMessage.id }) return@synchronized false
            messages.add(storedMessage)

            val isCurrentlyViewing = selectedPrivatePeer == peerID
            val lastReadTimestamp = lastReadTimestamps[peerID.lowercase()]
            val messageTimestampMillis = storedMessage.timestamp.toEpochMilliseconds()
            val wasAlreadyRead = lastReadTimestamp != null && messageTimestampMillis <= lastReadTimestamp

            if (markUnread && !isCurrentlyViewing && !wasAlreadyRead) {
                unreadPrivatePeers.add(peerID)
                unreadPrivateMessageIds.getOrPut(peerID) { mutableSetOf() }.add(storedMessage.id)
                latestUnreadPrivatePeer = peerID
                unreadChanged = true
            } else if (sendReadReceipt || isCurrentlyViewing) {
                readReceiptDue = true
            }

            unreadChanged = removeDroppedPrivateMessages(peerID, messageLimits.trim(messages)) || unreadChanged
            if (peerID !in writtenPrivateChats) {
                unreadChanged = enforceStrangerMessageBudget(justAdded = storedMessage) || unreadChanged
            }
            true
        }
        if (!added) {
            // The message was there already, but the chat may have got its name just now.
            if (named) coroutineScopeFacade.nostrScope.launch { chatEventBus.update(ChatEvent.PrivateChatsUpdated) }
            return
        }

        coroutineScopeFacade.nostrScope.launch {
            if (readReceiptDue) sendReadReceipt(limitedMessage.id, readerPeerID = null, toPeerID = peerID)
        }
        coroutineScopeFacade.nostrScope.launch {
            chatEventBus.update(ChatEvent.PrivateChatsUpdated)
            if (unreadChanged) {
                chatEventBus.update(ChatEvent.UnreadPrivatePeersUpdated)
                chatEventBus.update(ChatEvent.LatestUnreadPrivatePeerChanged)
            }
        }
    }

    /** The Nostr key a private chat is answered with, and the geohash it was opened from, if any. */
    private class PrivateRoute(val pubkeyHex: String, val sourceGeohash: String?)

    /**
     * Forgets that [dropped] were unread, and the peer with them when none of its unread messages is
     * left. Returns whether the unread peers changed. Called with [privateChatsLock] held.
     */
    private fun removeDroppedPrivateMessages(peerID: String, dropped: List<BitchatMessage>): Boolean {
        if (dropped.isEmpty()) return false
        val unreadIds = unreadPrivateMessageIds[peerID] ?: return false
        unreadIds.removeAll(dropped.mapTo(mutableSetOf()) { it.id })
        if (unreadIds.isNotEmpty()) return false
        unreadPrivateMessageIds.remove(peerID)
        unreadPrivatePeers.remove(peerID)
        latestUnreadPrivatePeer = resolveLatestUnreadPeer()
        return true
    }

    /**
     * Keeps the private chats the user has not written in within the budget they share: a number of
     * messages and a number of characters ([MessageLimits]). Any Nostr key can open a private chat by
     * sending one message, so how many of these chats there are is not the user's choice.
     *
     * While either number is passed, the largest of those chats (by characters when it is characters
     * that are over, else by messages; among equals the chat that was opened first) loses its oldest
     * arrival, and a chat left empty is removed with what was kept about it. A flood from one key so
     * costs that key's own messages first, and many keys can only push out one another. [justAdded],
     * the message that brought this about, is passed over: a new message is never the one to go. A chat
     * the user has written in is not looked at, whatever is sent into it. The chat in
     * [selectedPrivatePeer] (the one open as a mesh private chat) can lose messages like any other,
     * but it stays, with the key kept for it: the first message written in it must still have
     * somewhere to go. A chat open as a Nostr DM is not tracked there and can be removed while open:
     * its channel carries the key it is answered with, and every message that arrives in it records
     * that key again together with the message ([addPrivateMessage]).
     *
     * Returns whether the unread peers changed. Called with [privateChatsLock] held.
     */
    private fun enforceStrangerMessageBudget(justAdded: BitchatMessage): Boolean {
        var messages = 0
        var chars = 0L
        for ((peerID, chat) in privateChats) {
            if (peerID in writtenPrivateChats) continue
            messages += chat.size
            for (message in chat) chars += message.content.length
        }
        var unreadChanged = false

        while (messages > messageLimits.maxStrangerMessages || chars > messageLimits.maxStrangerChars) {
            val byChars = chars > messageLimits.maxStrangerChars
            var largest: Map.Entry<String, MutableList<BitchatMessage>>? = null
            var largestSize = -1L
            // In the order the chats were opened, and only a strictly larger one takes over: among
            // equals the chat opened first is the one that gives way.
            for (entry in privateChats.entries) {
                if (entry.key in writtenPrivateChats || entry.value.isEmpty()) continue
                if (entry.value.first() === justAdded) continue
                val size = if (byChars) entry.value.sumOf { it.content.length.toLong() } else entry.value.size.toLong()
                if (size > largestSize) {
                    largest = entry
                    largestSize = size
                }
            }
            val entry = largest ?: break
            val dropped = entry.value.removeAt(0)
            messages--
            chars -= dropped.content.length
            unreadChanged = removeDroppedPrivateMessages(entry.key, listOf(dropped)) || unreadChanged
            if (entry.value.isEmpty() && entry.key != selectedPrivatePeer) {
                privateChats.remove(entry.key)
                meshChatNames.remove(entry.key)
                val wasUnreadPeer = unreadPrivatePeers.remove(entry.key)
                val hadUnreadIds = unreadPrivateMessageIds.remove(entry.key) != null
                if (wasUnreadPeer || hadUnreadIds) {
                    latestUnreadPrivatePeer = resolveLatestUnreadPeer()
                    unreadChanged = true
                }
                knownPrivatePeers.remove(entry.key)
                geohashAliasCache.remove(entry.key)
                geohashConversationCache.remove(entry.key)
            }
        }
        return unreadChanged
    }

    private fun updateDeliveryStatus(convKey: String, messageId: String, status: DeliveryStatus) {
        val updated = synchronized(privateChatsLock) {
            val messages = privateChats[convKey] ?: return
            val index = messages.indexOfFirst { it.id == messageId }
            val current = messages.getOrNull(index)?.deliveryStatus
            // A report that a message did not go out never takes back what a receipt has said of it.
            val mayUpdate = status !is DeliveryStatus.Failed || current is DeliveryStatus.Sending || current is DeliveryStatus.Sent
            if (index >= 0 && mayUpdate) messages[index] = messages[index].copy(deliveryStatus = status)
            index >= 0 && mayUpdate
        }

        if (updated) {
            coroutineScopeFacade.nostrScope.launch {
                chatEventBus.update(ChatEvent.PrivateChatsUpdated)
            }
        }
    }

    /**
     * Drops the oldest mesh messages past the limits. A row that goes takes its cross-transport record
     * with it, so the other transport's copy of it, should it still come, is shown rather than hidden
     * behind a row that is no longer there. A dropped file row leaves its received file on disk.
     * Called with [meshChannelMessagesMutex] held.
     */
    private fun trimMeshMessages() {
        val dropped = messageLimits.trim(meshChannelMessages)
        if (dropped.isNotEmpty()) crossTransportTwins.forget(dropped.map { it.id })
    }

    private suspend fun markMeshMessageFailed(messageId: String, reason: String) {
        meshChannelMessagesMutex.withLock {
            val index = meshChannelMessages.indexOfFirst { it.id == messageId }
            if (index >= 0) {
                meshChannelMessages[index] = meshChannelMessages[index].copy(
                    deliveryStatus = DeliveryStatus.Failed(reason)
                )
            }
        }
        chatEventBus.update(ChatEvent.MeshMessagesUpdated)
    }

    private fun fileTooLargeReason(): String =
        "file is larger than ${BitchatFilePacket.MAX_CONTENT_BYTES / 1024} KiB"

    private fun notSentOverLoRaReason(): String = "not sent over LoRa"

    private fun maybeSendDeliveryAck(messageId: String, peerID: String) {
        if (!sentDeliveryAckIds.add(messageId)) return
        coroutineScopeFacade.nostrScope.launch {
            sendDeliveryAck(messageId, peerID)
        }
    }

    private fun conversationKeyFor(pubkeyHex: String): String {
        return "nostr_${pubkeyHex.take(16)}"
    }

    /**
     * Handles a private message that is a favourite notification; true when [content] was one, so it
     * is never shown as a chat message. What it may change is decided by [applyFavoriteNotification]:
     * over Nostr ([nostrSenderPubkey] set) only a relationship that already exists, over the mesh also
     * a bounded number of "favourited you" records. The peer's Nostr key is kept in its record and
     * nowhere else.
     */
    private fun handleFavoriteNotificationIfNeeded(
        content: String,
        convKey: String,
        senderDisplayName: String,
        nostrSenderPubkey: String? = null,
    ): Boolean {
        val notification = FavoriteNotification.parse(content) ?: return false
        val senderKey = convKey.removePrefix("nostr_").lowercase()
        // Over Nostr the sender's key is known from the envelope; without it there is nobody to update.
        val senderNpub = if (nostrSenderPubkey == null) null else hexToNpub(nostrSenderPubkey) ?: return true
        coroutineScopeFacade.applicationScope.launch {
            // Decided and written as one step of the preferences, which the user's own toggle goes
            // through as well: what is decided here is never applied to a record that changed since.
            // The event is published afterwards, so a slow observer holds nothing up.
            val senderName = sanitizedNickname(senderDisplayName) ?: senderKey
            val changedKey = withContext(coroutinesContextFacade.io) {
                userPreferences.updateFavorites { favorites ->
                    val change = applyFavoriteNotification(
                        favorites = favorites,
                        notification = notification,
                        senderKey = senderKey,
                        senderNpub = senderNpub,
                        senderName = senderName,
                        now = clock.now().toEpochMilliseconds(),
                    )
                    FavoritesUpdate(
                        result = change.saved?.peerNoisePublicKeyHex?.lowercase() ?: change.removedKeys.firstOrNull(),
                        save = listOfNotNull(change.saved),
                        remove = change.removedKeys,
                    )
                }
            }
            if (changedKey != null) userEventBus.update(UserEvent.FavoriteStatusChanged(changedKey))
        }

        return true
    }

    private fun resolveLatestUnreadPeer(): String? {
        return latestUnreadPrivatePeer?.takeIf { unreadPrivatePeers.contains(it) }
            ?: unreadPrivatePeers.firstOrNull()
    }

    @OptIn(ExperimentalEncodingApi::class)
    private fun decodeBase64Url(value: String): ByteArray? {
        val normalized = value.replace("-", "+").replace("_", "/")
        val padding = (4 - normalized.length % 4) % 4
        val padded = normalized + "=".repeat(padding)
        return runCatching { Base64.decode(padded) }.getOrNull()
    }

    private fun hexToNpub(hex: String): String? {
        return runCatching { Bech32.encode("npub", hexStringToByteArray(hex)) }.getOrNull()
    }

    @OptIn(ExperimentalUuidApi::class)
    override suspend fun sendPrivate(
        content: String,
        toPeerID: String,
        recipientNickname: String,
    ): Unit {
        requireSendable(content)
        sendPrivate(content, toPeerID, recipientNickname, BitchatMessageType.Message)
    }

    /**
     * [route] is the DM this line was sent in, when the caller knows it (sendMessage does). Without
     * it the active chat is read here, which may already be another conversation by the time this
     * runs: a line typed in one DM must not go out through another.
     *
     * A text longer than one private message carries goes out as several, in order, each shown
     * and sent as a message of its own: that is what the other side receives.
     */
    private suspend fun sendPrivate(
        content: String,
        toPeerID: String,
        recipientNickname: String,
        messageType: BitchatMessageType,
        route: Channel? = null,
    ): Unit = withContext(coroutinesContextFacade.io) {
        // Read once: every piece of one text takes the same way out.
        val currentChannel = route ?: userPreferences.getUserState()
            ?.let { it as? UserState.Active }
            ?.activeState?.let { it as? ActiveState.Chat }
            ?.channel
        // One message over the radio carries less than one over Bluetooth; read once for the whole
        // text, and only for a mesh conversation: what goes through a relay is not cut for the radio.
        val textLimit = if (currentChannel is Channel.MeshDM) mesh.privateTextLimitFor(toPeerID) else PrivateMessageText.MAX_BYTES
        requireSendablePrivately(content, messageType, textLimit)
        val senderName = when (val user = userPreferences.getAppUser()) {
            is AppUser.ActiveAnonymous -> user.name
            AppUser.Anonymous -> "anon"
        }

        val pieces = if (isSentAsFile(messageType)) listOf(content) else PrivateMessageText.split(content, textLimit)
        for (piece in pieces) {
            sendPrivatePiece(piece, toPeerID, recipientNickname, messageType, senderName, currentChannel)
        }
    }

    private suspend fun sendPrivatePiece(
        content: String,
        toPeerID: String,
        recipientNickname: String,
        messageType: BitchatMessageType,
        senderName: String,
        currentChannel: Channel?,
    ) {
        val messageId = Uuid.random().toString().uppercase()
        val localMessage = BitchatMessage(
            id = messageId,
            sender = senderName,
            content = content,
            type = messageType,
            timestamp = Clock.System.now(),
            isPrivate = true,
            recipientNickname = recipientNickname,
            senderPeerID = mesh.myPeerID,
            deliveryStatus = DeliveryStatus.Sent
        )
        // A chat opened by the first line sent is called what its sender knew the peer as, when that was
        // handed in, and otherwise what the peer announces now.
        val meshNameClaim = meshNameHandedBack(recipientNickname, toPeerID) ?: claimedMeshName(toPeerID)
        synchronized(privateChatsLock) { writtenPrivateChats.add(toPeerID) }
        addPrivateMessage(toPeerID, localMessage, markUnread = false, sendReadReceipt = false, meshNameClaim = meshNameClaim)

        when (currentChannel) {
            is Channel.MeshDM -> {
                val connectedOnMesh = mesh.getPeerInfo(toPeerID)?.isConnected == true
                val hasMesh = reachesOverMesh(toPeerID)
                val hasEstablished = mesh.hasEstablishedSession(toPeerID)

                println("🔍 sendPrivate [MeshDM]: toPeerID=$toPeerID, hasMesh=$hasMesh, hasEstablished=$hasEstablished")

                if (!connectedOnMesh && mesh.reachesByRadio(toPeerID) && isSentAsFile(messageType)) {
                    updateDeliveryStatus(toPeerID, messageId, DeliveryStatus.Failed(notSentOverLoRaReason()))
                } else if (hasMesh && hasEstablished) {
                    println("✅ Sending via mesh (established session)")

                    when (messageType) {
                        BitchatMessageType.Image -> {
                            // Compress image for BLE transfer (max 100KB)
                            val preparedImage = compressImageForTransfer(content)
                            if (preparedImage != null) {
                                val filePacket = BitchatFilePacket(
                                    fileName = preparedImage.fileName,
                                    fileSize = preparedImage.bytes.size.toLong(),
                                    mimeType = preparedImage.mimeType,
                                    content = preparedImage.bytes
                                )
                                // Log first bytes to verify JPEG format
                                val firstBytes = preparedImage.bytes.take(10).joinToString(" ") { byte ->
                                    (byte.toInt() and 0xFF).toString(16).padStart(2, '0').uppercase()
                                }
                                println("📎 ChatRepo: Private image first bytes: ${logBytes(10) { firstBytes }}")
                                if (preparedImage.bytes.size > BitchatFilePacket.MAX_CONTENT_BYTES) {
                                    updateDeliveryStatus(toPeerID, messageId, DeliveryStatus.Failed(fileTooLargeReason()))
                                } else {
                                    handFileToMesh(toPeerID, filePacket, messageId)
                                    println("📎 ChatRepo: Private compressed image sent to $toPeerID: ${logPath(preparedImage.fileName)} (${preparedImage.bytes.size} bytes, ${preparedImage.mimeType})")
                                }
                            } else {
                                println("❌ ChatRepo: Failed to compress image for BLE transfer: ${logPath(content)}")
                            }
                        }

                        BitchatMessageType.Audio -> {
                            // Audio files - read as-is (already compressed)
                            val fileBytes = readFileBytes(content)
                            if (fileBytes != null) {
                                val fileName = getFileName(content)
                                val mimeType = getMimeType(content)
                                val filePacket = BitchatFilePacket(
                                    fileName = fileName,
                                    fileSize = fileBytes.size.toLong(),
                                    mimeType = mimeType,
                                    content = fileBytes
                                )
                                if (fileBytes.size > BitchatFilePacket.MAX_CONTENT_BYTES) {
                                    updateDeliveryStatus(toPeerID, messageId, DeliveryStatus.Failed(fileTooLargeReason()))
                                } else {
                                    handFileToMesh(toPeerID, filePacket, messageId)
                                    println("📎 ChatRepo: Private audio file sent to $toPeerID: ${logPath(fileName)} (${fileBytes.size} bytes)")
                                }
                            } else {
                                println("❌ ChatRepo: Failed to read audio file: ${logPath(content)}")
                            }
                        }

                        else -> {
                            // Behind what is still queued for this peer, never past it: a session
                            // that came up a moment ago may not have had its queue sent yet.
                            val waitsInQueue = synchronized(outboxLock) {
                                val queued = outbox[toPeerID]
                                if (queued.isNullOrEmpty()) {
                                    handToMesh(content, toPeerID, recipientNickname, messageId)
                                    false
                                } else {
                                    queued.add(Triple(content, recipientNickname, messageId))
                                    true
                                }
                            }
                            if (waitsInQueue) flushOutboxFor(toPeerID)
                        }
                    }
                } else {
                    // Queue and initiate handshake
                    println("📦 Queuing to outbox, initiating handshake")
                    val queuedNow = synchronized(outboxLock) {
                        val q = outbox.getOrPut(toPeerID) { mutableListOf() }
                        q.add(Triple(content, recipientNickname, messageId))
                        q.size
                    }
                    println("📦 Outbox size for $toPeerID: $queuedNow")
                    // This call is safe while a handshake is in flight (the service sends nothing new then), and
                    // marks the peer as chosen by the user by taking the in-flight handshake over as theirs.
                    mesh.initiateNoiseHandshake(toPeerID)
                }
            }

            is Channel.NostrDM -> {
                println("📡 sendPrivate [NostrDM]: fullPubkey=${currentChannel.fullPubkey.take(16)}..., sourceGeohash=${currentChannel.sourceGeohash}")

                if (currentChannel.sourceGeohash != null) {
                    // Geohash-scoped Nostr DM
                    println("📡 Sending geohash-scoped Nostr DM")
                    nostr.sendPrivateMessageGeohash(
                        content,
                        currentChannel.fullPubkey,
                        messageId,
                        currentChannel.sourceGeohash.orEmpty()
                    )
                } else {
                    println("📡 Sending direct Nostr DM")
                    nostr.sendPrivateMessage(
                        content = content,
                        recipientNostrPubkey = currentChannel.fullPubkey,
                        recipientPeerID = toPeerID,
                        messageID = messageId,
                        recipientNickname = recipientNickname
                    )
                }
            }

            else -> {
                println("⚠️ sendPrivate called with non-DM channel: $currentChannel")
            }
        }
    }

    override suspend fun sendReadReceipt(originalMessageID: String, readerPeerID: String?, toPeerID: String): Unit =
        withContext(coroutinesContextFacade.io) {
            val readReceipt = ReadReceipt(originalMessageID, readerPeerID)
            if (geohashAliasCache.getAllValues().contains(toPeerID)) {
                val recipientHex = geohashAliasCache[toPeerID] ?: return@withContext
                val sourceGeohash = geohashConversationCache[toPeerID]
                    ?: (userPreferences.getUserState()
                        ?.let { it as? UserState.Active }
                        ?.activeState?.let { it as? ActiveState.Chat }
                        ?.channel as? Channel.Location)?.geohash
                if (sourceGeohash != null) {
                    val identity = runCatching { nostrClient.deriveIdentity(sourceGeohash) }.getOrNull()
                    if (identity != null) {
                        nostr.sendReadReceiptGeohash(originalMessageID, recipientHex, identity)
                    }
                }
                return@withContext
            }
            if ((mesh.getPeerInfo(toPeerID)?.isConnected == true) && mesh.hasEstablishedSession(toPeerID)) {
                mesh.sendReadReceipt(readReceipt.originalMessageID, toPeerID, mesh.getPeerNicknames()[toPeerID] ?: mesh.myPeerID)
            } else {
                resolveNostrPublicKey(toPeerID)?.let {
                    nostr.sendReadReceipt(readReceipt, toPeerID, it)
                }
            }
        }

    private fun resolveNostrPublicKey(peerID: String): String? {
        try {
            if (peerID.startsWith("nostr_")) {
                val hex = synchronized(privateChatsLock) { knownPrivatePeers[peerID] } ?: return null
                return hexToNpub(hex)
            }

            userPreferences.getFavorite(peerID)?.peerNostrPublicKey?.let {
                return it
            }

            val noiseKey = hexStringToByteArray(peerID)
            userPreferences.getFavorite(noiseKey.toHexString())

            val favoriteStatus = userPreferences.getFavorite(noiseKey.toHexString())
            if (favoriteStatus?.peerNostrPublicKey != null) return favoriteStatus.peerNostrPublicKey

            if (peerID.length == 16) {
                val fallbackStatus = userPreferences.getFavorite(peerID)
                return fallbackStatus?.peerNostrPublicKey
            }

            return null
        } catch (e: Exception) {
            return null
        }
    }

    private fun findPeerIDForNostrPubkey(npub: String): String? {
        if (npub.isEmpty()) return null

        try {
            val npubHex = try {
                val (hrp, data) = Bech32.decode(npub)
                if (hrp != "npub") return null
                data.toHexString()
            } catch (e: Exception) {
                return null
            }

            val allFavorites = userPreferences.getAllFavorites()
            for ((peerID, favorite) in allFavorites) {
                if (favorite.peerNostrPublicKey == npub) {
                    return favorite.peerNoisePublicKeyHex.takeIf { it.isNotEmpty() } ?: peerID
                }
            }

            synchronized(privateChatsLock) {
                knownPrivatePeers.entries.firstOrNull { it.value == npubHex }?.key
            }?.let { return it }

            return null
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }

    override suspend fun sendDeliveryAck(messageID: String, toPeerID: String) = withContext(coroutinesContextFacade.io) {
        if (geohashAliasCache.getAllValues().contains(toPeerID)) {
            val recipientHex = geohashAliasCache[toPeerID]
            if (recipientHex != null) {
                val sourceGeohash = geohashConversationCache[toPeerID]
                    ?: (userPreferences.getUserState()
                        ?.let { it as? UserState.Active }
                        ?.activeState?.let { it as? ActiveState.Chat }
                        ?.channel as? Channel.Location)?.geohash
                val identity = sourceGeohash?.let { runCatching { nostrClient.deriveIdentity(it) }.getOrNull() } ?: return@withContext
                nostr.sendDeliveryAckGeohash(messageID, recipientHex, identity)
                return@withContext
            }
        }
        if (!((mesh.getPeerInfo(toPeerID)?.isConnected == true) && mesh.hasEstablishedSession(toPeerID))) {
            resolveNostrPublicKey(toPeerID)?.let {
                nostr.sendDeliveryAck(messageID, toPeerID, it)
            }
        }
    }

    override suspend fun sendFavoriteNotification(toPeerID: String, isFavorite: Boolean): Unit = withContext(coroutinesContextFacade.io) {
        if (mesh.getPeerInfo(toPeerID)?.isConnected == true) {
            val myNpub = try {
                nostrClient.getCurrentNostrIdentity()?.npub
            } catch (_: Exception) {
                null
            }
            val content = if (isFavorite) "[FAVORITED]:${myNpub ?: ""}" else "[UNFAVORITED]:${myNpub ?: ""}"
            val nickname = mesh.getPeerNicknames()[toPeerID] ?: toPeerID
            mesh.sendPrivateMessage(content, toPeerID, nickname)
        } else {
            resolveNostrPublicKey(toPeerID)?.let {
                nostr.sendFavoriteNotification(toPeerID, it, isFavorite)
            }
        }
    }

    // What is queued for a peer is queued under its mesh id, and only that id says whose queue is
    // sent. The Noise key a peer announces is checked against nothing: it names no queue.
    override suspend fun onPeersUpdated(peers: List<String>) = withContext(coroutinesContextFacade.io) {
        peers.forEach { pid -> flushOutboxFor(pid) }
    }

    override suspend fun onSessionEstablished(peerID: String): Unit = withContext(coroutinesContextFacade.io) {
        println("🔐 Session established for: $peerID")
        flushOutboxFor(peerID)
    }

    override suspend fun joinChannel(channel: String, password: String?): Boolean = withContext(coroutinesContextFacade.io) {
        val channelTag = normalizeChannelName(channel)
        val joinedChannels = channelPreferences.getJoinedChannelsList()

        if (joinedChannels.contains(channelTag)) {
            channelNostrEventIds[channelTag]?.let { subscribeToNamedChannelMessages(channelTag, it) }

            return@withContext true
        }

        // If password protected and no key yet, derive key from password
        val protectedChannels = channelPreferences.getSavedProtectedChannels()
        if (protectedChannels.contains(channelTag) && !channelKeys.containsKey(channelTag)) {
            if (password != null) {
                // Derive encryption key from password using channel name as salt
                val key = Cryptography.createAESSecretKey(password, channelTag.encodeToByteArray())
                channelKeys[channelTag] = key
            } else {
                return@withContext false
            }
        }

        channelPreferences.setJoinedChannel(channelTag)

        if (password != null) {
            channelPreferences.setSavedProtectedChannel(channelTag)
        }

        true
    }

    override suspend fun leaveChannel(channel: String): Unit = withContext(coroutinesContextFacade.io) {
        val normalized = normalizeChannelName(channel)

        channelKeys.remove(normalized)
        channelPreferences.removeJoinedChannel(normalized)
        channelPreferences.removeSavedProtectedChannel(normalized)
        channelPreferences.removeChannelCreator(normalized)
        verifiedOwnerChannels.remove(normalized)
        namedChannelMessages.remove(normalized)
        namedChannelMembers.remove(normalized)
        channelKeyCommitments.remove(normalized)
        channelCreatorNpubs.remove(normalized)

        channelNostrEventIds.remove(normalized)?.let { eventId ->
            nostrRelay.unsubscribeFromChannelMessages(eventId)
        }
        channelPreferences.removeChannelEventId(normalized)
    }

    override suspend fun decryptChannelMessage(encryptedContent: ByteArray, channel: String): String? =
        withContext(coroutinesContextFacade.io) {
            val normalized = normalizeChannelName(channel)
            val key = channelKeys[normalized] ?: return@withContext null

            try {
                if (encryptedContent.size < 12) return@withContext null // Minimum: 12 bytes IV

                Cryptography.decryptAESGCM(encryptedContent, key)
            } catch (e: Exception) {
                null
            }
        }

    override suspend fun addChannelMessage(
        channel: String,
        message: BitchatMessage,
        senderPeerID: String?
    ): Unit = withContext(coroutinesContextFacade.io) {
        // Currently just tracking members, not storing messages
        // In future, this could interact with a message storage layer
        senderPeerID?.let { peerID ->
            // Note: ChannelPreferences doesn't have member tracking yet
        }
    }

    override suspend fun removeChannelMember(channel: String, peerID: String) = withContext(coroutinesContextFacade.io) {

    }

    override suspend fun cleanupDisconnectedMembers(connectedPeers: List<String>, myPeerID: String) =
        withContext(coroutinesContextFacade.io) {

        }

    override suspend fun hasChannelKey(channel: String): Boolean = withContext(coroutinesContextFacade.io) {
        channelKeys.containsKey(normalizeChannelName(channel))
    }

    override suspend fun isChannelCreator(channel: String, peerID: String): Boolean = withContext(coroutinesContextFacade.io) {
        val normalized = normalizeChannelName(channel)
        val creators = channelPreferences.getChannelCreators()
        creators[normalized] == peerID
    }

    override suspend fun getJoinedChannelsList(): List<String> = withContext(coroutinesContextFacade.io) {
        channelPreferences.getJoinedChannelsList().toList().sorted()
    }

    override suspend fun loadChannelData(): Pair<Set<String>, Set<String>> = withContext(coroutinesContextFacade.io) {
        val joined = channelPreferences.getJoinedChannelsList()
        val protected = channelPreferences.getSavedProtectedChannels()
        Pair(joined, protected)
    }

    override suspend fun setChannelPassword(channel: String, password: String) = withContext(coroutinesContextFacade.io) {
        val normalized = normalizeChannelName(channel)
        val key = Cryptography.createAESSecretKey(password, normalized.encodeToByteArray())
        channelKeys[normalized] = key
        channelPreferences.setSavedProtectedChannel(normalized)
    }

    override suspend fun clearAllChannels() = withContext(coroutinesContextFacade.io) {
        channelKeys.clear()
    }

    override suspend fun clearMessages(channel: Channel) = withContext(coroutinesContextFacade.io) {
        when (channel) {
            is Channel.Mesh -> {
                meshChannelMessagesMutex.withLock {
                    meshChannelMessages.clear()
                    crossTransportTwins.clear()
                }
                chatEventBus.update(ChatEvent.MeshMessagesUpdated)
            }

            is Channel.Location -> {
                val flow = geohashMessagesFlows[channel.geohash]
                flow?.value = emptyList()
                chatEventBus.update(ChatEvent.GeohashMessagesUpdated(channel.geohash))
            }

            is Channel.MeshDM -> {
                synchronized(privateChatsLock) {
                    privateChats[channel.peerID]?.clear()
                    unreadPrivatePeers.remove(channel.peerID)
                    unreadPrivateMessageIds.remove(channel.peerID)
                    if (latestUnreadPrivatePeer == channel.peerID) {
                        latestUnreadPrivatePeer = resolveLatestUnreadPeer()
                    }
                }
                chatEventBus.update(ChatEvent.PrivateChatsUpdated)
            }

            is Channel.NostrDM -> {
                synchronized(privateChatsLock) {
                    privateChats[channel.peerID]?.clear()
                    unreadPrivatePeers.remove(channel.peerID)
                    unreadPrivateMessageIds.remove(channel.peerID)
                    if (latestUnreadPrivatePeer == channel.peerID) {
                        latestUnreadPrivatePeer = resolveLatestUnreadPeer()
                    }
                }
                chatEventBus.update(ChatEvent.PrivateChatsUpdated)
            }

            is Channel.NamedChannel -> {
                val normalized = normalizeChannelName(channel.channelName)
                namedChannelMessages[normalized]?.clear()
                chatEventBus.update(ChatEvent.NamedChannelMessagesUpdated(channel.channelName))
            }

            is Channel.Meshtastic -> {
                // Meshtastic messages not yet implemented
            }
        }
    }

    override suspend fun setSelectedChannel(channel: Channel) = withContext(coroutinesContextFacade.io) {
        if (channel is Channel.MeshDM) {
            setSelectedPrivatePeer(channel.peerID)
        } else {
            setSelectedPrivatePeer(null)
        }

        chatEventBus.update(ChatEvent.ChannelChanged)
    }

    override suspend fun storePersonDataForDM(peerID: String, fullPubkey: String, sourceGeohash: String?, displayName: String?) =
        withContext(coroutinesContextFacade.io) {
            synchronized(privateChatsLock) { knownPrivatePeers[peerID] = fullPubkey }
            if (sourceGeohash != null) {
                geohashAliasCache[peerID] = fullPubkey
                geohashConversationCache[peerID] = sourceGeohash
            }
            // The user opened this conversation: its name is theirs to keep, whatever traffic teaches.
            sanitizedNickname(displayName)?.let { learnedNames.remember(peerID, it) }
            Unit
        }

    override suspend fun getFullPubkey(peerID: String): String? = withContext(coroutinesContextFacade.io) {
        synchronized(privateChatsLock) { knownPrivatePeers[peerID] }
    }

    override suspend fun getSourceGeohash(peerID: String): String? = withContext(coroutinesContextFacade.io) {
        geohashConversationCache[peerID]
    }

    override suspend fun getDisplayName(peerID: String): String? = withContext(coroutinesContextFacade.io) {
        learnedNames[peerID]?.let { return@withContext it }

        val fullPubkey = synchronized(privateChatsLock) { knownPrivatePeers[peerID] }
        if (fullPubkey != null) {
            sanitizedNickname(participantTracker.getNicknameByPubkey(fullPubkey))?.let { nickname ->
                learnedNames.learn(peerID, nickname)
                return@withContext nickname
            }
        }

        null
    }

    @OptIn(ExperimentalUuidApi::class)
    override suspend fun sendMessage(
        content: String,
        channel: Channel,
        sender: String,
        messageType: BitchatMessageType
    ) = withContext(coroutinesContextFacade.io) {
        requireSendable(content)
        println("📬 ChatRepo.sendMessage: channel=$channel, sender=$sender, contentLen=${content.length}")
        when (channel) {
            is Channel.Mesh -> {
                println("📬 ChatRepo.sendMessage: Routing to sendMeshMessage")
                sendMeshMessage(content, sender, messageType)
            }
            is Channel.Location -> sendGeohashMessage(content, channel.geohash, sender, messageType)
            is Channel.NostrDM -> {
                requireSendablePrivately(content, messageType)
                initializePrivateDMIfNeeded(channel.peerID)
                sendPrivate(
                    content = content,
                    toPeerID = channel.peerID,
                    recipientNickname = "",
                    messageType = messageType,
                    route = channel,
                )
            }

            is Channel.MeshDM -> {
                // Before the chat is opened: a text refused for what the peer's link carries leaves nothing behind.
                requireSendablePrivately(content, messageType, mesh.privateTextLimitFor(channel.peerID))
                initializePrivateDMIfNeeded(channel.peerID, openedAs = channel.displayName)
                sendPrivate(
                    content = content,
                    toPeerID = channel.peerID,
                    recipientNickname = "",
                    messageType = messageType,
                    route = channel,
                )
            }

            is Channel.NamedChannel -> {
                sendNamedChannelMessage(channel.channelName, content, sender, messageType)
            }

            is Channel.Meshtastic -> {
                // sendMeshtasticMessage broadcasts to every node: a line meant for one node must not go out.
                if (channel.nodeNum != null) throw UnsupportedOperationException("LoRa DMs are not supported yet")
                sendMeshtasticMessage(content, sender, messageType)
            }
        }
    }

    private fun requireSendable(content: String) {
        require(content.length <= BitchatMessage.MAX_CONTENT_CHARS) {
            "message is longer than ${BitchatMessage.MAX_CONTENT_CHARS} characters"
        }
    }

    /**
     * A private text too long to be sent, even as several messages, is refused here, before any of
     * it is shown, queued or handed to a transport: no transport can send it, and none reports
     * that.
     */
    private fun requireSendablePrivately(
        content: String,
        messageType: BitchatMessageType,
        maxBytes: Int = PrivateMessageText.MAX_BYTES,
    ) {
        if (isSentAsFile(messageType)) return
        PrivateMessageText.refusal(content, maxBytes)?.let { throw IllegalArgumentException(it) }
    }

    /** An image or a voice note carries a path as content and travels as a file, not as that text. */
    private fun isSentAsFile(messageType: BitchatMessageType): Boolean =
        messageType == BitchatMessageType.Image || messageType == BitchatMessageType.Audio

    /**
     * [openedAs] is the name the user opened a mesh chat under (its channel's), when there is one: a chat
     * the user started is called what the user saw, not what is announced by the time the first line is sent.
     */
    private fun initializePrivateDMIfNeeded(peerID: String, openedAs: String? = null) {
        val meshNameClaim = meshNameHandedBack(openedAs, peerID) ?: claimedMeshName(peerID)
        val named = synchronized(privateChatsLock) {
            if (!privateChats.containsKey(peerID)) {
                println("🆕 Initializing new DM: $peerID")
                privateChats[peerID] = mutableListOf()
            }
            nameMeshChatIfUnnamed(peerID, meshNameClaim)
        }
        if (named) coroutineScopeFacade.nostrScope.launch { chatEventBus.update(ChatEvent.PrivateChatsUpdated) }

        val person = findPersonByPeerID(peerID)

        if (person != null) {
            synchronized(privateChatsLock) { knownPrivatePeers[peerID] = person.fullPubkey }

            if (person.sourceGeohash != null) {
                geohashAliasCache[peerID] = person.fullPubkey
                geohashConversationCache[peerID] = person.sourceGeohash
                println("✅ Populated geohash DM caches: $peerID → ${person.sourceGeohash}")
            }

            if (person.isMeshPeer && !mesh.hasEstablishedSession(peerID)) {
                println("🔐 Mesh peer detected: $peerID (handshake will be initiated when sending)")
            }
        }
    }

    private fun findPersonByPeerID(peerID: String): PersonData? {
        meshPeers.value.find { it.id == peerID || "nostr_${it.id.take(16)}" == peerID }?.let {
            return PersonData(
                fullPubkey = it.id,
                nickname = it.displayName,
                sourceGeohash = null,
                isMeshPeer = true
            )
        }

        val fullPubkey = geohashAliasCache.get(peerID)
        val sourceGeohash = geohashConversationCache.get(peerID)

        if (fullPubkey != null) {
            return PersonData(
                fullPubkey = fullPubkey,
                nickname = "",
                sourceGeohash = sourceGeohash,
                isMeshPeer = false
            )
        }

        return null
    }

    /**
     * Sends what is queued for [peerID], oldest first, and stops at the first text that cannot go
     * yet: nothing queued behind it may overtake it. With [overMeshOnly], a text the mesh cannot
     * carry now waits instead of going through a relay.
     */
    private fun flushOutboxFor(peerID: String, overMeshOnly: Boolean = false): Unit = synchronized(outboxLock) {
        val queued = outbox[peerID] ?: return@synchronized
        if (queued.isEmpty()) return@synchronized

        println("🚀 Flushing outbox for $peerID: ${queued.size} messages")

        val iterator = queued.iterator()
        while (iterator.hasNext()) {
            val (content, nickname, messageID) = iterator.next()
            val hasMesh = reachesOverMesh(peerID) && mesh.hasEstablishedSession(peerID)
            val recipientNpub = if (!hasMesh && !overMeshOnly && canSendViaNostr(peerID)) resolveNostrPublicKey(peerID) else null
            if (hasMesh) {
                println("   → Sending queued message via mesh: $messageID")
                handToMesh(content, peerID, nickname, messageID)
                iterator.remove()
            } else if (recipientNpub != null) {
                val recipientPeerIDForEmbed = findPeerIDForNostrPubkey(recipientNpub) ?: peerID
                println("   → Sending queued message via Nostr: $messageID")
                nostr.sendPrivateMessage(
                    content = content,
                    recipientNostrPubkey = recipientNpub,
                    recipientPeerID = recipientPeerIDForEmbed,
                    messageID = messageID,
                    recipientNickname = nickname
                )
                iterator.remove()
            } else {
                break
            }
        }
        if (queued.isEmpty()) {
            outbox.remove(peerID)
            println("✅ Outbox flushed for $peerID")
        }
    }

    /**
     * Sends what the mesh can carry now of everything that is queued. For when a peer may be back:
     * the mesh's peer list changed, or the radio heard someone. With its session still standing no
     * handshake will complete to send its queue, because the mesh service starts none over a
     * standing session. Over the mesh or not yet: both signals are ones nothing authenticates, and
     * neither may move a text to a relay.
     */
    private fun flushOutboxOverMesh() {
        synchronized(outboxLock) { outbox.keys.toList() }.forEach { flushOutboxFor(it, overMeshOnly = true) }
    }

    suspend fun flushAllOutbox() = withContext(coroutinesContextFacade.io) {
        synchronized(outboxLock) { outbox.keys.toList() }.forEach { flushOutboxFor(it) }
    }

    /**
     * Hands a private text to the mesh service. It refuses what its way out for this peer cannot
     * carry (a text cut for Bluetooth when the peer has since become reachable over the radio only):
     * the row then says so, instead of standing as sent when nothing was.
     */
    private fun handToMesh(content: String, toPeerID: String, nickname: String, messageID: String?) {
        if (mesh.sendPrivateMessage(content, toPeerID, nickname, messageID)) return
        if (messageID != null) updateDeliveryStatus(toPeerID, messageID, DeliveryStatus.Failed("too long for the link it would take: send it again"))
    }

    /**
     * Hands a private file to the mesh service, which starts nothing for a peer whose way out has
     * become the radio since this was looked at: the row then says so.
     */
    private fun handFileToMesh(toPeerID: String, file: BitchatFilePacket, messageID: String) {
        if (mesh.sendFilePrivate(toPeerID, file)) return
        updateDeliveryStatus(toPeerID, messageID, DeliveryStatus.Failed(notSentOverLoRaReason()))
    }

    override fun didFailToSendPrivateMessage(messageID: String, recipientPeerID: String, reason: String) {
        updateDeliveryStatus(recipientPeerID, messageID, DeliveryStatus.Failed(reason))
    }

    private fun reachesOverMesh(peerID: String): Boolean =
        mesh.getPeerInfo(peerID)?.isConnected == true || mesh.reachesByRadio(peerID)

    private fun canSendViaNostr(peerID: String): Boolean {
        return try {
            when (peerID.length) {
                64 if peerID.matches(Regex("^[0-9a-fA-F]+$")) -> {
                    val fav = userPreferences.getFavorite(peerID.lowercase())
                    fav != null && fav.isMutual && fav.peerNostrPublicKey != null
                }
                16 if peerID.matches(Regex("^[0-9a-fA-F]+$")) -> {
                    val allFavorites = userPreferences.getAllFavorites()
                    val matchingFav = allFavorites.values.firstOrNull { fav ->
                        fav.peerNoisePublicKeyHex.lowercase().startsWith(peerID.lowercase())
                    }
                    matchingFav != null && matchingFav.isMutual && matchingFav.peerNostrPublicKey != null
                }

                else -> {
                    false
                }
            }
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Whether this request may go out: see the shared [torGateAllowsTraffic]. Waits for a Tor that
     * is coming up, blocks when one was asked for and cannot be had, and allows the request on a
     * build whose engine could never have proxied it in the first place.
     */
    private suspend fun torGateAllowsTraffic(): Boolean = torGateAllowsTraffic(
        torManager = torManager,
        torRequested = requestedTorIntent?.current == TorMode.ON,
    ) { println("ChatRepo: $it") }

    @OptIn(FlowPreview::class)
    private fun observeTorReadyAndEstablishConnections() {
        // On an engine that ignores the SOCKS proxy this only ever announced a protection the
        // relays never got, so there is nothing to wait for and no reconnect to trigger.
        torManager?.takeIf { httpEngineSupportsTorProxy }?.let { manager ->
            coroutineScopeFacade.nostrScope.launch {
                manager.statusFlow
                    .distinctUntilChangedBy { manager.isProxyReady() }
                    .filter { manager.isProxyReady() }
                    .debounce(1000) // ADDED: Debounce for 1 second as additional defense layer
                    .collect {
                        println("🚀 ChatRepo: Tor is now ready, establishing relay connections")
                        /*
                         * Defaults first, and explicitly.
                         *
                         * establishConnectionsForActiveChannels returns immediately when no
                         * geohash channel is active, so on a cold start with Tor enabled this
                         * reconnected nothing at all: the subscriptions had already given up
                         * while Tor was still bootstrapping, and the event that was supposed to
                         * revive them skipped the default relays entirely. The app then sat with
                         * Tor running and no relay connected.
                         */
                        nostrRelay.ensureDefaultRelaysConnected()
                        establishConnectionsForActiveChannels()
                    }
            }
        }
    }

    /**
     * Restores relay connectivity when the user switches Tor off.
     *
     * The existing recovery waits for Tor to reach RUNNING, which is useless as an escape: on a
     * host with no Arti library that event never arrives, so relays killed by enforcement stayed
     * dead and switching the policy off changed nothing until the app was restarted. Turning Tor
     * off is the user's way out, so it has to be the thing that revives them.
     *
     * Default relays are reconnected explicitly, because [establishConnectionsForActiveChannels]
     * returns immediately when no geohash channel is active and would leave them down.
     *
     * The ON direction matters just as much: sockets opened while traffic went direct have to be
     * closed, or enforcement only covers connections that have not been made yet.
     */
    private fun observeTorTurnedOffAndRestoreRelays() {
        val intent = requestedTorIntent ?: return
        coroutineScopeFacade.nostrScope.launch {
            intent.updates
                // The initial stored ON is a real route transition too. Skipping it let relay
                // startup keep direct sessions alive until some later user toggle.
                .collect { mode ->
                    if (mode == TorMode.OFF) {
                        println("🚀 ChatRepo: Tor switched off, restoring relay connections")
                        nostrRelay.ensureDefaultRelaysConnected()
                        establishConnectionsForActiveChannels()
                    } else {
                        /*
                         * Closing them is the point. An established WebSocket keeps sending
                         * through the socket it already has -- it never consults the proxy
                         * selector again -- so refusing new connections would leave the relays
                         * the user was already talking to streaming outside Tor indefinitely.
                         */
                        println("🔒 ChatRepo: Tor switched on, closing direct relay connections")
                        nostrRelay.disconnectAll()
                    }
                }
        }
    }

    private fun establishConnectionsForActiveChannels() {
        val activeGeohashes = geohashMessagesFlows.keys.toList()

        if (activeGeohashes.isEmpty()) {
            println("  No active channels yet")
            return
        }

        activeGeohashes.forEach { geohash ->
            coroutineScopeFacade.nostrScope.launch {
                println("  🔗 Establishing relay connections for $geohash")
                nostrRelay.ensureGeohashRelaysConnected(geohash, nRelays = 5, includeDefaults = true)
                println("  ✅ Relay connections established for $geohash")
            }
        }
    }

    /**
     * Handle incoming LoRa packet.
     * Format: "nickname:content" for simple text protocol.
     */
    /** A mesh peer id is eight bytes in hex. */
    private fun isMeshPeerId(id: String): Boolean =
        id.length == 16 && id.all { it in '0'..'9' || it.lowercaseChar() in 'a'..'f' }

    private suspend fun handleLoRaPacket(packetBytes: ByteArray) = withContext(coroutinesContextFacade.io) {
        try {
            val packetString = packetBytes.decodeToString()
            println("📻 ChatRepo: Received LoRa packet: ${logBody(packetString)}")

            // Parse "nickname:content" format
            val colonIndex = packetString.indexOf(':')
            if (colonIndex <= 0) {
                println("⚠️ ChatRepo: Invalid LoRa packet format (no colon found)")
                return@withContext
            }

            val nickname = packetString.substring(0, colonIndex)
            // The name is the sender's own choice and nothing checks it. What is SHOWN is cleaned like a
            // mesh nickname, so it cannot carry the number sign the app writes after the name of a private
            // chat. What devices are told apart by stays the name as it was sent: two names that only
            // look the same once cleaned are still two senders.
            val shownName = sanitizedMeshNickname(nickname) ?: UNKNOWN_PEER_NICKNAME
            val content = packetString.substring(colonIndex + 1)

            println("📻 ChatRepo: LoRa message from '$nickname': ${logBody(content)}")
            if (content.length > BitchatMessage.MAX_CONTENT_CHARS) {
                println("ChatRepo: Dropped LoRa message with content length ${content.length}")
                return@withContext
            }

            // Create message for mesh channel
            val now = clock.now()
            // Unique, not the arrival time: two packets can be handled in the same millisecond.
            val messageID = "lora-${Uuid.random()}"
            // A LoRa packet names its sender only by nickname, so first work out which device that can
            // be: every mesh identity known under that name, from the mesh itself and from bitchat LoRa
            // heartbeats. Only the bitchat LoRa stack announces the mesh identity; a Meshtastic or
            // MeshCore node id is another namespace and identifies nobody on the mesh.
            //
            // A bitchat heartbeat carries at most the first 24 bytes of a name. A name heard there that
            // is long enough to be such a cut may be the whole name of one device and the start of
            // another's, so it identifies nobody; it only says who may be meant (below).
            val (heardMaybeCut, heardWhole) = lora?.peers?.value.orEmpty().partition {
                isMeshPeerId(it.deviceId) && mayBeCutLoRaNickname(it.nickname)
            }
            val loRaPeersNamed = heardWhole.filter { it.nickname == nickname }
            val (meshIdentityPeers, foreignPeers) = loRaPeersNamed.partition { isMeshPeerId(it.deviceId) }
            val knownSenderIds = (
                meshIdentityPeers.map { it.deviceId.lowercase() } +
                    meshPeers.value.filter { it.displayName == nickname }.map { it.id.lowercase() }
                ).toSet()
            val peerId = knownSenderIds.singleOrNull()
            // A device heard under what this name sounds like in a heartbeat, and not counted above,
            // may be the sender, another device of the name, or one that only shares its start: the
            // name is ambiguous. Nothing a peer sends can take a device out of this count, only put it
            // among the known ones, which makes them two.
            val heardAs = loRaHeartbeatNickname(nickname)
            val possibleNamesakes = heardMaybeCut.any {
                it.nickname == heardAs && it.deviceId.lowercase() !in knownSenderIds
            }
            // When the name could be more than one device, pairing could hide one person's message
            // behind another's, so this copy is shown as it is: a duplicate row at worst. A node of a
            // foreign LoRa stack cannot be told from a mesh peer of the same name; those two are taken
            // to be one device, which is what they are when our own app runs that stack.
            val senderIsUnambiguous = knownSenderIds.size <= 1 &&
                foreignPeers.size <= 1 &&
                (foreignPeers.isEmpty() || meshIdentityPeers.isEmpty()) &&
                !possibleNamesakes
            val message = BitchatMessage(
                id = messageID,
                sender = shownName,
                content = content,
                type = BitchatMessageType.Message,
                timestamp = now,
                isPrivate = false,
                senderPeerID = "lora-$nickname" // Prefix to identify LoRa origin
            )

            // null: the BLE twin is already shown, so this copy is dropped (the first copy to arrive is
            // the one shown). Otherwise the new row count.
            val total = meshChannelMessagesMutex.withLock {
                if (senderIsUnambiguous && crossTransportTwins.onLoRa(nickname, peerId, content, now, rowId = messageID)) {
                    null
                } else {
                    meshChannelMessages.add(message)
                    trimMeshMessages()
                    meshChannelMessages.size
                }
            } ?: return@withContext

            println("📻 ChatRepo: Added LoRa message to mesh channel, total: $total")
            chatEventBus.update(ChatEvent.MeshMessagesUpdated)
            println("📻 ChatRepo: Emitted MeshMessagesUpdated event")

            chatEventBus.update(ChatEvent.MessageReceived)
        } catch (e: Exception) {
            println("⚠️ ChatRepo: Failed to parse LoRa packet: ${e.message}")
            e.printStackTrace()
        }
    }

    override fun didReceiveMessage(message: BitchatMessage) {
        if (!messageLimits.accepts(message)) {
            println("Bluetooth: Dropped mesh message with content length ${message.content.length}")
            return
        }
        if (message.isPrivate) {
            println("Bluetooth: Dropped unauthenticated private mesh message id=${message.id} from ${message.senderPeerID}")
            return
        }

        coroutineScopeFacade.applicationScope.launch {
            println("Bluetooth: Received message ${message.id} from ${message.sender} (${message.senderPeerID}): ${logBody(message.content)}")

            message.senderPeerID?.let { peerID ->
                if (blockListPreferences.isMeshUserBlocked(peerID)) {
                    println("🚫 ChatRepo: BLOCKED mesh message from $peerID")
                    return@launch
                }
            }

            if (message.channel == null) {
                // The first copy to arrive is the one shown: a BLE copy whose LoRa twin is already on
                // screen is dropped, and never replaces it (neither transport authenticates its sender).
                val changed = meshChannelMessagesMutex.withLock {
                    val isNew = meshChannelMessages.none { it.id == message.id } &&
                        !crossTransportTwins.onMesh(
                            message.id,
                            message.sender,
                            message.senderPeerID,
                            message.content,
                            clock.now(),
                        )
                    if (isNew) {
                        meshChannelMessages.add(message)
                        trimMeshMessages()
                    }
                    isNew
                }
                if (changed) {
                    chatEventBus.update(ChatEvent.MeshMessagesUpdated)
                }
            } else {
                println("Bluetooth: Dropped a mesh message that names a channel")
                return@launch
            }

            chatEventBus.update(ChatEvent.MessageReceived)
        }
    }

    override fun didReceiveAuthenticatedPrivateMessage(message: BitchatMessage) {
        val peerID = message.senderPeerID
        if (!messageLimits.accepts(message)) {
            println("Bluetooth: Dropped authenticated private mesh message with content length ${message.content.length}")
            return
        }
        if (!message.isPrivate || peerID == null) {
            println("Bluetooth: Dropped malformed authenticated private mesh message id=${message.id}")
            return
        }

        coroutineScopeFacade.applicationScope.launch {
            if (blockListPreferences.isMeshUserBlocked(peerID)) {
                println("🚫 ChatRepo: BLOCKED authenticated mesh message from $peerID")
                return@launch
            }

            val meshNameClaim = claimedMeshName(peerID)

            val handled = handleFavoriteNotificationIfNeeded(
                content = message.content,
                convKey = peerID,
                senderDisplayName = meshNameClaim ?: peerID.take(12)
            )
            if (handled) return@launch

            addPrivateMessage(
                peerID,
                message,
                markUnread = true,
                sendReadReceipt = false,
                meshNameClaim = meshNameClaim,
                stampMeshSender = true,
            )
            chatEventBus.update(ChatEvent.MessageReceived)
        }
    }

    private suspend fun updateMessagesFromUnknownPeer(peerID: String, newNickname: String) {
        var updatedCount = 0

        meshChannelMessagesMutex.withLock {
            val updatedMeshMessages = meshChannelMessages.map { message ->
                if (message.senderPeerID == peerID && message.sender == UNKNOWN_PEER_NICKNAME) {
                    updatedCount++
                    message.copy(sender = newNickname)
                } else {
                    message
                }
            }
            meshChannelMessages.clear()
            meshChannelMessages.addAll(updatedMeshMessages)
        }

        if (updatedCount > 0) {
            println("🔄 Updated $updatedCount messages from 'Unknown' to '$newNickname' for peer $peerID")
            chatEventBus.update(ChatEvent.MeshMessagesUpdated)
        }
    }

    override fun didUpdatePeerList(peers: List<String>) {
        coroutineScopeFacade.applicationScope.launch {
            println("📊 ChatRepo.didUpdatePeerList: Received ${peers.size} peers: $peers")

            // Get our own peer ID to exclude from the list
            val myPeerID = mesh.myPeerID

            // Filter to only ACTUALLY CONNECTED peers (excluding self)
            val activePeers = peers.filter { peerID ->
                val peerInfo = mesh.getPeerInfo(peerID)
                val isSelf = peerID == myPeerID
                val isConnected = peerInfo?.isConnected == true

                // Debug each peer
                if (peerInfo != null) {
                    println("📊 ChatRepo: Peer $peerID ('${peerInfo.nickname}') - Self: $isSelf, Connected: $isConnected")
                } else {
                    println("📊 ChatRepo: Peer $peerID - NO PEER INFO!")
                }

                // Exclude self from peer list (we're not a "connected peer" to ourselves)
                !isSelf && isConnected
                // Note: Removed hasEstablishedSession() requirement
                // Noise sessions only exist for encrypted private messages
                // Peers in broadcast mesh don't need Noise sessions to count
            }

            println("📊 ChatRepo: After filtering: ${activePeers.size} active peers (excluding self)")

            val previousPeerIds = meshPeers.value.map { it.id }.toSet()
            val activePeerSet = activePeers.toSet()
            val addedPeers = (activePeerSet - previousPeerIds).toList()
            val removedPeers = (previousPeerIds - activePeerSet).toList()
            val addedSummary = if (addedPeers.isEmpty()) "none" else addedPeers.joinToString { it.take(12) }
            val removedSummary = if (removedPeers.isEmpty()) "none" else removedPeers.joinToString { it.take(12) }
            logNostrDebug(
                "ConnectedPeers",
                "[CONNECTED-PEERS] event=didUpdatePeerList filtered=${activePeers.size} prev=${previousPeerIds.size} added=$addedSummary removed=$removedSummary"
            )

            // Retroactive nickname updates for all peers (improvement over legacy app)
            peers.forEach { peerID ->
                claimedMeshName(peerID)?.let { updateMessagesFromUnknownPeer(peerID, it) }
            }

            // A private chat that was opened before its peer announced a name gets the first one announced.
            val meshNameClaims = peers.associateWith(::claimedMeshName)
            val namedChat = synchronized(privateChatsLock) {
                var named = false
                meshNameClaims.forEach { (peerID, claim) ->
                    if (peerID in privateChats && nameMeshChatIfUnnamed(peerID, claim)) named = true
                }
                named
            }

            // Each peer is listed under what it is announced as right now, read here and published at
            // once, with nothing in between that could let an older update put its names over a newer
            // one's. A peer that announced no name is listed under the placeholder, as it always was:
            // the start of its id here would be taken for a name wherever a name is looked up. (The
            // nickname is clean when it is kept; cleaning it again changes nothing and costs nothing.)
            val connected = activePeers.map { peerID ->
                val peerInfo = mesh.getPeerInfo(peerID)
                GeoPerson(
                    id = peerID,
                    displayName = peerInfo?.nickname?.let { sanitizedMeshNickname(it) ?: UNKNOWN_PEER_NICKNAME }
                        ?: peerID.take(12),
                    lastSeen = Clock.System.now(),
                )
            }
            meshPeers.value = connected

            if (namedChat) chatEventBus.update(ChatEvent.PrivateChatsUpdated)
            println("📊 ChatRepo: meshPeers list now contains ${connected.size} peers")
            chatEventBus.update(ChatEvent.MeshPeersUpdated)

            // A peer that is on the list again may be one with texts waiting for it.
            flushOutboxOverMesh()
        }
    }

    override fun didReceiveChannelLeave(channel: String, fromPeer: String) {
        println("Bluetooth: Peer $fromPeer left channel $channel")
    }

    // Mesh receipts arrive only out of a Noise session (see MessageHandler). A delivery receipt moves
    // the row of that message, in that peer's chat, forward to Delivered: the look at the row and the
    // write are one step under the chats' lock, a row that is Delivered or Read already is left as it
    // is, and a message id that is not in that chat changes and creates nothing. Read receipts are
    // not applied yet.
    override fun didReceiveAuthenticatedDeliveryAck(messageID: String, recipientPeerID: String) {
        val updated = synchronized(privateChatsLock) {
            val messages = privateChats[recipientPeerID] ?: return@synchronized false
            val index = messages.indexOfFirst { it.id == messageID }
            val current = messages.getOrNull(index)?.deliveryStatus
            if (index < 0 || current !is DeliveryStatus.Sending && current !is DeliveryStatus.Sent && current !is DeliveryStatus.Failed) {
                return@synchronized false
            }
            messages[index] = messages[index].copy(
                deliveryStatus = DeliveryStatus.Delivered(
                    to = meshChatNames[recipientPeerID] ?: recipientPeerID,
                    at = Clock.System.now()
                )
            )
            true
        }
        if (updated) coroutineScopeFacade.nostrScope.launch { chatEventBus.update(ChatEvent.PrivateChatsUpdated) }
    }

    override fun didReceiveAuthenticatedReadReceipt(messageID: String, recipientPeerID: String) {
        println("Bluetooth: Message $messageID read by $recipientPeerID")
    }

    override fun getNickname(): String? {
        return when (val user = userPreferences.getAppUser()) {
            is AppUser.ActiveAnonymous -> user.name
            AppUser.Anonymous -> "anon"
        }
    }

    override fun isFavorite(peerID: String): Boolean {
        return userPreferences.getFavorite(peerID) != null
    }

    override fun didReceivePublicFile(peerID: String, filePacket: BitchatFilePacket) {
        receiveMeshFile(peerID, filePacket, isPrivate = false)
    }

    override fun didReceiveAuthenticatedPrivateFile(peerID: String, filePacket: BitchatFilePacket) {
        receiveMeshFile(peerID, filePacket, isPrivate = true)
    }

    private fun receiveMeshFile(peerID: String, filePacket: BitchatFilePacket, isPrivate: Boolean) {
        coroutineScopeFacade.applicationScope.launch {
            try {
                val firstBytes = filePacket.content.take(10).joinToString(" ") { byte ->
                    (byte.toInt() and 0xFF).toString(16).padStart(2, '0').uppercase()
                }
                println("ChatRepo: Received file first bytes: ${logBytes(10) { firstBytes }}")

                val messageType = when {
                    filePacket.mimeType.startsWith("image/") -> BitchatMessageType.Image
                    filePacket.mimeType.startsWith("audio/") -> BitchatMessageType.Audio
                    else -> BitchatMessageType.Message
                }

                val incomingDir = when (messageType) {
                    BitchatMessageType.Image -> "images/incoming"
                    BitchatMessageType.Audio -> "audio/incoming"
                    else -> "files/incoming"
                }
                if (!receivedFileBudget.reserve(filePacket.content.size)) {
                    println("⚠️ ChatRepo: Not saving a received file: the limit on received files for this run is reached (restart to receive files again)")
                    return@launch
                }
                // Each received file gets a directory of its own, named here and never by the peer: a later
                // file with the same name, from anyone, cannot replace one that a message already shows.
                val token = Uuid.random()
                val subDir = "$incomingDir/$token"

                // A peer's name is never used as a path.
                val fileName = safeReceivedFileName(filePacket.fileName)
                val localPath = saveFileToLocal(filePacket.content, fileName, subDir)
                if (localPath == null) {
                    // The charge stays: a save that failed may have left a directory or part of a file.
                    println("❌ ChatRepo: Failed to save received file: ${logPath(filePacket.fileName)}")
                    return@launch
                }

                val meshNameClaim = claimedMeshName(peerID)
                // A private file is stamped with its chat's name when it is stored; a public one from a peer
                // that has announced nothing keeps the placeholder that its first announcement replaces.
                val senderName = meshNameClaim ?: UNKNOWN_PEER_NICKNAME
                val now = Clock.System.now()

                val bitchatMessage = BitchatMessage(
                    id = "file-$token",
                    sender = senderName,
                    content = localPath,
                    type = messageType,
                    timestamp = now,
                    isPrivate = isPrivate,
                    senderPeerID = peerID,
                    channel = null,
                    deliveryStatus = DeliveryStatus.Delivered(to = mesh.myPeerID, at = now)
                )

                if (!isPrivate) {
                    val stored = meshChannelMessagesMutex.withLock {
                        if (!messageLimits.accepts(bitchatMessage)) {
                            println("ChatRepo: Dropped file message with content length ${bitchatMessage.content.length}")
                            false
                        } else {
                            meshChannelMessages.add(bitchatMessage)
                            trimMeshMessages()
                            true
                        }
                    }
                    if (!stored) return@launch
                    chatEventBus.update(ChatEvent.MeshMessagesUpdated)
                    println("ChatRepo: Added file message to mesh channel")
                } else {
                    addPrivateMessage(
                        peerID,
                        bitchatMessage,
                        markUnread = true,
                        sendReadReceipt = false,
                        meshNameClaim = meshNameClaim,
                        stampMeshSender = true,
                    )
                    println("ChatRepo: Added file message to private DM with $peerID")
                }
            } catch (e: Exception) {
                println("ChatRepo: Error handling received file: ${logError(e)}")
                logStackTrace(e)
            }
        }
    }

    override suspend fun discoverNamedChannel(channelName: String): ChannelInfo? = withContext(coroutinesContextFacade.io) {
        val normalized = normalizeChannelName(channelName)

        channelNostrEventIds[normalized]?.let { eventId ->
            return@withContext ChannelInfo(
                name = normalized,
                isProtected = channelKeyCommitments[normalized] != null || channelPreferences.getSavedProtectedChannels()
                    .contains(normalized),
                memberCount = namedChannelMembers[normalized]?.size ?: 0,
                creatorNpub = channelCreatorNpubs[normalized],
                keyCommitment = channelKeyCommitments[normalized],
                isOwner = isChannelOwner(normalized),
                nostrEventId = eventId
            )
        }

        coroutineScopeFacade.nostrScope.launch {
            if (!torGateAllowsTraffic()) return@launch
            nostrRelay.ensureDefaultRelaysConnected()
        }

        val deferred = CompletableDeferred<ChannelInfo?>()
        val subscriptionId = nostrRelay.subscribeToChannelCreations(
            handler = { event ->
                val parsed = NostrEvent.parseChannelInfo(event)
                if (parsed != null && parsed.name.equals(normalized, ignoreCase = true)) {
                    val isOwner = runCatching { nostrClient.getCurrentNostrIdentity()?.npub == parsed.creatorPubkey }.getOrDefault(false)
                    val info = ChannelInfo(
                        name = normalized,
                        isProtected = parsed.keyCommitment != null,
                        memberCount = 0,
                        creatorNpub = parsed.creatorPubkey,
                        keyCommitment = parsed.keyCommitment,
                        isOwner = isOwner,
                        nostrEventId = parsed.eventId
                    )

                    coroutineScopeFacade.applicationScope.launch {
                        ensureNamedChannelMetadata(info)
                    }
                    deferred.complete(info)
                }
            },
            channelName = normalized
        )

        val result = withTimeoutOrNull(5_000) { deferred.await() }
        nostrRelay.unsubscribe(subscriptionId)

        result
    }

    override suspend fun ensureNamedChannelMetadata(channelInfo: ChannelInfo): Unit = withContext(coroutinesContextFacade.io) {
        val normalized = normalizeChannelName(channelInfo.name)

        channelInfo.creatorNpub?.let { creator ->
            channelCreatorNpubs[normalized] = creator
            channelPreferences.setChannelCreator(creator, normalized)
        }

        channelInfo.keyCommitment?.let { commitment ->
            channelKeyCommitments[normalized] = commitment
            channelPreferences.setSavedProtectedChannel(normalized)
        }

        channelInfo.nostrEventId?.let { eventId ->
            channelNostrEventIds[normalized] = eventId
            channelPreferences.setChannelEventId(normalized, eventId)
            subscribeToNamedChannelMessages(normalized, eventId)
        }
    }

    override suspend fun getNamedChannelMessages(channelName: String): List<BitchatMessage> = withContext(coroutinesContextFacade.io) {
        val normalized = normalizeChannelName(channelName)
        // Kept in arrival order (the oldest arrival is what a full chat drops), shown by timestamp.
        namedChannelMessages[normalized]?.toList().orEmpty().sortedBy { it.timestamp }
    }

    override suspend fun addNamedChannelMessage(channelName: String, message: BitchatMessage) = withContext(coroutinesContextFacade.io) {
        if (!messageLimits.accepts(message)) {
            println("ChatRepo: Dropped named channel message with content length ${message.content.length}")
            return@withContext
        }
        val normalized = normalizeChannelName(channelName)
        val messages = namedChannelMessages.getOrPut(normalized) { mutableListOf() }
        val limitedMessage = messageLimits.notLaterThan(message, clock.now())
        if (messages.none { it.id == limitedMessage.id }) {
            messages.add(limitedMessage)
            messageLimits.trim(messages)
            chatEventBus.update(ChatEvent.NamedChannelMessagesUpdated(normalized))
        }
    }

    override suspend fun getChannelMembers(channelName: String): List<ChannelMember> = withContext(coroutinesContextFacade.io) {
        val normalized = normalizeChannelName(channelName)
        namedChannelMembers[normalized]?.toList().orEmpty()
    }

    override suspend fun addChannelMember(channelName: String, member: ChannelMember) = withContext(coroutinesContextFacade.io) {
        val normalized = normalizeChannelName(channelName)
        val members = namedChannelMembers.getOrPut(normalized) { mutableSetOf() }
        members.removeAll { it.peerID == member.peerID }
        members.add(member)
        chatEventBus.update(ChatEvent.ChannelMembersUpdated(normalized))
    }

    override suspend fun isChannelOwner(channelName: String): Boolean = withContext(coroutinesContextFacade.io) {
        val normalized = normalizeChannelName(channelName)
        val myNpub = try {
            nostrClient.getCurrentNostrIdentity()?.npub
        } catch (_: Exception) {
            null
        }
        val creatorNpub = channelCreatorNpubs[normalized]
        if (myNpub != null && myNpub == creatorNpub) {
            return@withContext true
        }

        verifiedOwnerChannels.contains(normalized)
    }

    override suspend fun verifyPasswordOwnership(channelName: String, password: String): Boolean = withContext(coroutinesContextFacade.io) {
        val normalized = normalizeChannelName(channelName)
        val storedCommitment = channelKeyCommitments[normalized] ?: return@withContext false
        val key = deriveChannelKey(normalized, password)
        val commitment = calculateKeyCommitment(key)
        if (commitment == storedCommitment) {
            verifiedOwnerChannels.add(normalized)
            channelKeys[normalized] = key
            chatEventBus.update(ChatEvent.ChannelOwnershipVerified(normalized))
            return@withContext true
        }
        false
    }

    override suspend fun getChannelCreatorNpub(channelName: String): String? = withContext(coroutinesContextFacade.io) {
        channelCreatorNpubs[normalizeChannelName(channelName)]
    }

    override suspend fun getChannelKeyCommitment(channelName: String): String? = withContext(coroutinesContextFacade.io) {
        channelKeyCommitments[normalizeChannelName(channelName)]
    }

    override suspend fun encryptChannelMessage(plaintext: String, channelName: String): ByteArray? =
        withContext(coroutinesContextFacade.io) {
            val normalized = normalizeChannelName(channelName)
            val key = channelKeys[normalized] ?: return@withContext null
            try {
                Cryptography.encryptAESGCM(plaintext, key)
            } catch (e: Exception) {
                println("❌ ChatRepo: Failed to encrypt channel message: ${e.message}")
                null
            }
        }

    override suspend fun deriveChannelKey(channelName: String, password: String): ByteArray = withContext(coroutinesContextFacade.io) {
        val salt = normalizeChannelName(channelName).encodeToByteArray()
        Cryptography.createAESSecretKey(password, salt)
    }

    override suspend fun calculateKeyCommitment(key: ByteArray): String = withContext(coroutinesContextFacade.io) {
        Cryptography.getDigestHash(key).toHexString()
    }

    override suspend fun getAvailableChannels(): List<ChannelInfo> = withContext(coroutinesContextFacade.io) {
        val joinedChannels = channelPreferences.getJoinedChannelsList()
        val protectedChannels = channelPreferences.getSavedProtectedChannels()

        joinedChannels.map { channelName ->
            val memberCount = namedChannelMembers[channelName]?.size ?: 0
            val creatorNpub = channelCreatorNpubs[channelName]
            val keyCommitment = channelKeyCommitments[channelName]
            val nostrEventId = channelNostrEventIds[channelName]

            ChannelInfo(
                name = channelName,
                isProtected = protectedChannels.contains(channelName) || keyCommitment != null,
                memberCount = memberCount,
                creatorNpub = creatorNpub,
                keyCommitment = keyCommitment,
                isOwner = isChannelOwner(channelName),
                nostrEventId = nostrEventId
            )
        }
    }

    override fun observeJoinedNamedChannels(): Flow<List<ChannelInfo>> = chatEventBus.events()
        .onStart { emit(ChatEvent.ChannelListUpdated) }
        .filter { event ->
            event is ChatEvent.ChannelListUpdated ||
                    event is ChatEvent.ChannelJoined ||
                    event is ChatEvent.ChannelLeft
        }
        .map { getAvailableChannels() }

    override suspend fun createNamedChannel(channelName: String, password: String?): ChannelInfo = withContext(coroutinesContextFacade.io) {
        val normalizedName = normalizeChannelName(channelName)

        val myNpub = try {
            nostrClient.getCurrentNostrIdentity()?.npub
        } catch (_: Exception) {
            null
        }

        var keyCommitment: String? = null
        if (password != null) {
            val key = deriveChannelKey(normalizedName, password)
            keyCommitment = calculateKeyCommitment(key)
            channelKeys[normalizedName] = key
            channelKeyCommitments[normalizedName] = keyCommitment
            channelPreferences.setSavedProtectedChannel(normalizedName)
        }

        if (myNpub != null) {
            channelCreatorNpubs[normalizedName] = myNpub
            channelPreferences.setChannelCreator(myNpub, normalizedName)
        }

        channelPreferences.setJoinedChannel(normalizedName)

        namedChannelMessages[normalizedName] = mutableListOf()
        namedChannelMembers[normalizedName] = mutableSetOf()

        val myPeerID = mesh.myPeerID
        val myNickname = getNickname() ?: "anon"
        val selfMember = ChannelMember(
            peerID = myPeerID,
            nickname = myNickname,
            npub = myNpub,
            joinedAt = Clock.System.now().toEpochMilliseconds(),
            transport = ChannelTransport.BOTH
        )
        namedChannelMembers[normalizedName]?.add(selfMember)

        chatEventBus.update(ChatEvent.ChannelListUpdated)
        chatEventBus.update(ChatEvent.ChannelJoined)

        var nostrEventId: String? = null
        try {
            val identity = nostrClient.getCurrentNostrIdentity()
            if (identity != null) {
                println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                println("📤 ChatRepo: Creating Nostr kind 40 event for channel: $normalizedName")

                val channelEvent = NostrEvent.createChannelCreation(
                    channelName = normalizedName,
                    about = null,
                    keyCommitment = keyCommitment,
                    publicKeyHex = identity.publicKeyHex,
                    privateKeyHex = identity.privateKeyHex
                )

                nostrEventId = channelEvent.id
                channelNostrEventIds[normalizedName] = nostrEventId
                channelPreferences.setChannelEventId(normalizedName, nostrEventId)

                nostrRelay.ensureDefaultRelaysConnected()
                nostrRelay.sendChannelCreation(channelEvent)

                println("✅ ChatRepo: Kind 40 event broadcasted")
                println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")

                // Subscribe to channel messages
                subscribeToNamedChannelMessages(normalizedName, nostrEventId)
            }
        } catch (e: Exception) {
            println("❌ ChatRepo: Failed to broadcast kind 40 event: ${e.message}")
            e.printStackTrace()
        }

        // TODO: Broadcast channel creation to mesh (deferred)

        ChannelInfo(
            name = normalizedName,
            isProtected = password != null,
            memberCount = 1,
            creatorNpub = myNpub,
            keyCommitment = keyCommitment,
            isOwner = true,
            nostrEventId = nostrEventId
        )
    }

    private fun subscribeToNamedChannelMessages(channelName: String, channelEventId: String) {
        println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
        println("🔔 ChatRepo: Subscribing to messages for channel: $channelName")
        println("   Channel Event ID: ${channelEventId.take(16)}...")
        println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")

        nostrRelay.subscribeToChannelMessages(
            channelEventId = channelEventId,
            handler = { event -> handleNamedChannelMessageEvent(channelName, event) }
        )
    }

    @OptIn(ExperimentalEncodingApi::class)
    private fun handleNamedChannelMessageEvent(channelName: String, event: com.bitchat.nostr.model.NostrEvent) {
        coroutineScopeFacade.nostrScope.launch {
            try {
                println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                println("📬 ChatRepo: Received channel message for: $channelName")
                println("   Event ID: ${event.id.take(16)}...")
                println("   Sender: ${event.pubkey.take(16)}...")
                println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")

                // Extract nickname from tags
                val senderNickname = event.tags.find { it.firstOrNull() == "n" }?.getOrNull(1)

                // Check if message is encrypted
                val isEncrypted = event.tags.any { it.firstOrNull() == "encrypted" }

                // Decrypt content if needed
                val content: String = if (isEncrypted) {
                    val key = channelKeys[channelName]
                    if (key != null) {
                        try {
                            val encryptedBytes = Base64.decode(event.content)
                            Cryptography.decryptAESGCM(encryptedBytes, key)
                                ?: "[Decryption failed]"
                        } catch (e: Exception) {
                            println("❌ ChatRepo: Failed to decrypt channel message: ${e.message}")
                            "[Encrypted message - wrong password?]"
                        }
                    } else {
                        "[Encrypted message - no key available]"
                    }
                } else {
                    event.content
                }

                val message = BitchatMessage(
                    id = event.id,
                    sender = senderNickname ?: event.pubkey.take(16),
                    content = content,
                    type = BitchatMessageType.Message,
                    timestamp = kotlin.time.Instant.fromEpochSeconds(event.createdAt.toLong()),
                    isPrivate = false,
                    senderPeerID = event.pubkey,
                    channel = channelName
                )

                addNamedChannelMessage(channelName, message)
                println("✅ ChatRepo: Channel message added to $channelName")
                println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
            } catch (e: Exception) {
                println("❌ ChatRepo: Failed to handle channel message: ${e.message}")
                e.printStackTrace()
            }
        }
    }

    @OptIn(ExperimentalEncodingApi::class)
    suspend fun sendNamedChannelMessage(
        channelName: String,
        content: String,
        nickname: String,
        messageType: BitchatMessageType = BitchatMessageType.Message
    ) = withContext(coroutinesContextFacade.io) {
        requireSendable(content)
        try {
            val normalizedName = normalizeChannelName(channelName)
            println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
            println("📤 ChatRepo: Sending message to named channel: $normalizedName")
            println("   Content length: ${content.length}")
            println("   Message type: $messageType")
            println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")

            val channelEventId = channelNostrEventIds[normalizedName] ?: discoverNamedChannel(normalizedName)?.nostrEventId
            if (channelEventId == null) {
                println("❌ ChatRepo: No event ID for channel $normalizedName")
                return@withContext
            }

            val identity = nostrClient.getCurrentNostrIdentity()
            if (identity == null) {
                println("❌ ChatRepo: No Nostr identity")
                return@withContext
            }

            // Encrypt content if channel has a key
            val key = channelKeys[normalizedName]
            val messageContent: String
            val isEncrypted: Boolean

            if (key != null) {
                val encryptedBytes = Cryptography.encryptAESGCM(content, key)
                messageContent = Base64.encode(encryptedBytes)
                isEncrypted = true
                println("   Encrypted: yes")
            } else {
                messageContent = content
                isEncrypted = false
                println("   Encrypted: no")
            }

            // Add local echo first
            val messageId = "local-${Clock.System.now().toEpochMilliseconds()}"
            val localMessage = BitchatMessage(
                id = messageId,
                sender = nickname,
                content = content, // Show unencrypted content locally
                type = messageType,
                timestamp = Clock.System.now(),
                isPrivate = false,
                senderPeerID = identity.publicKeyHex,
                channel = normalizedName
            )
            addNamedChannelMessage(normalizedName, localMessage)

            val event = NostrEvent.createChannelMessage(
                channelEventId = channelEventId,
                relayUrl = "wss://relay.damus.io", // Primary relay - TODO: should track source relay
                content = messageContent,
                isEncrypted = isEncrypted,
                nickname = nickname,
                publicKeyHex = identity.publicKeyHex,
                privateKeyHex = identity.privateKeyHex
            )

            // Update local message with actual event ID
            val messages = namedChannelMessages[normalizedName]
            if (messages != null) {
                val index = messages.indexOfFirst { it.id == messageId }
                if (index >= 0) {
                    messages[index] = messages[index].copy(id = event.id)
                }
            }

            nostrRelay.sendChannelMessage(event)

            println("✅ ChatRepo: Channel message sent, event ID: ${event.id.take(16)}...")
            println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
        } catch (e: Exception) {
            println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
            println("❌ ChatRepo: Failed to send channel message: ${e.message}")
            println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
            e.printStackTrace()
        }
    }

    override suspend fun clearData() = withContext(coroutinesContextFacade.io) {
        synchronized(outboxLock) { outbox.clear() }
        channelKeys.clear()
        geohashMessagesFlows.clear()
        activeGeohashSubscriptions.clear()
        meshChannelMessagesMutex.withLock {
            meshChannelMessages.clear()
            crossTransportTwins.clear()
        }
        meshPeers.value = emptyList()

        synchronized(privateChatsLock) {
            privateChats.clear()
            meshChatNames.clear()
            writtenPrivateChats.clear()
            unreadPrivatePeers.clear()
            unreadPrivateMessageIds.clear()
            latestUnreadPrivatePeer = null
            knownPrivatePeers.clear()
        }
        selectedPrivatePeer = null
        learnedNames.clear()
        lastReadTimestamps.clear()
        handledGiftWraps.clear()
        activeDmSubscriptions.clear()
        activeGeohashDmSubscriptions.clear()
        deliveredMessageIds.clear()
        readMessageIds.clear()
        sentDeliveryAckIds.clear()

        namedChannelMessages.clear()
        namedChannelMembers.clear()
        channelCreatorNpubs.clear()
        channelKeyCommitments.clear()
        channelNostrEventIds.clear()
        verifiedOwnerChannels.clear()

        mesh.clearAllInternalData()
        mesh.clearAllEncryptionData()

        nostrClient.clearAllAssociations()

        channelPreferences.clearJoinedChannels()
        channelPreferences.clearProtectedChannels()
        channelPreferences.clearChannelEventIds()

        chatEventBus.update(ChatEvent.MeshMessagesUpdated)
        chatEventBus.update(ChatEvent.PrivateChatsUpdated)
    }

    private data class PersonData(
        val fullPubkey: String,
        val nickname: String,
        val sourceGeohash: String?,
        val isMeshPeer: Boolean
    )
}
