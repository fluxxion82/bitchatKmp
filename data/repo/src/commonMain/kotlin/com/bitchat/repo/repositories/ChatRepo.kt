@file:OptIn(ExperimentalUuidApi::class)

package com.bitchat.repo.repositories

import com.bitchat.api.dto.chat.ReadReceipt
import com.bitchat.api.dto.mapper.toBitchatPacket
import com.bitchat.api.dto.protocol.MessageType
import com.bitchat.bluetooth.service.BluetoothMeshDelegate
import com.bitchat.bluetooth.service.BluetoothMeshService
import com.bitchat.cache.Cache
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
import com.bitchat.domain.chat.repository.ChatRepository
import com.bitchat.domain.connectivity.model.BluetoothConnectionEvent
import com.bitchat.domain.location.eventbus.LocationEventBus
import com.bitchat.domain.location.model.Channel
import com.bitchat.domain.location.model.GeoPerson
import com.bitchat.domain.location.model.LocationEvent
import com.bitchat.domain.user.model.AppUser
import com.bitchat.domain.user.model.UserEvent
import com.bitchat.local.prefs.BlockListPreferences
import com.bitchat.local.prefs.ChannelPreferences
import com.bitchat.local.prefs.UserPreferences
import com.bitchat.mediautils.compressImageForTransfer
import com.bitchat.mediautils.getFileName
import com.bitchat.mediautils.getMimeType
import com.bitchat.mediautils.readFileBytes
import com.bitchat.mediautils.saveFileToLocal
import com.bitchat.noise.model.NoisePayload
import com.bitchat.noise.model.NoisePayloadType
import com.bitchat.noise.model.PrivateMessagePacket
import com.bitchat.nostr.Bech32
import com.bitchat.nostr.NostrClient
import com.bitchat.nostr.NostrPreferences
import com.bitchat.nostr.NostrProofOfWork
import com.bitchat.nostr.NostrRelay
import com.bitchat.nostr.NostrTransport
import com.bitchat.nostr.logging.logNostrDebug
import com.bitchat.nostr.model.NostrEvent
import com.bitchat.nostr.model.NostrFilter
import com.bitchat.nostr.model.NostrIdentity
import com.bitchat.nostr.model.NostrKind
import com.bitchat.nostr.participant.NostrParticipantTracker
import com.bitchat.nostr.util.hexStringToByteArray
import com.bitchat.nostr.util.toHexString
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
) : ChatRepository, BluetoothMeshDelegate {
    private val outbox = mutableMapOf<String, MutableList<Triple<String, String, String>>>()

    private val channelKeys = mutableMapOf<String, ByteArray>()

    private val geohashMessagesFlows = mutableMapOf<String, MutableStateFlow<List<BitchatMessage>>>()
    private val activeGeohashSubscriptions = mutableSetOf<String>()

    private val meshChannelMessages = mutableListOf<BitchatMessage>()
    private val meshPeers = mutableListOf<GeoPerson>()

    private val privateChats = mutableMapOf<String, MutableList<BitchatMessage>>()
    private val unreadPrivatePeers = mutableSetOf<String>()
    private val unreadPrivateMessageIds = mutableMapOf<String, MutableSet<String>>()
    private var latestUnreadPrivatePeer: String? = null
    private var selectedPrivatePeer: String? = null
    private val knownPrivatePeers = mutableMapOf<String, String>()
    private val peerDisplayNames = mutableMapOf<String, String>().apply {
        putAll(userPreferences.getAllPeerDisplayNames())
    }
    private val lastReadTimestamps = mutableMapOf<String, Long>().apply {
        putAll(userPreferences.getAllLastReadTimestamps())
    }
    private val handledGiftWrapIds = mutableSetOf<String>()
    private val activeDmSubscriptions = mutableSetOf<String>()
    private val activeGeohashDmSubscriptions = mutableSetOf<String>()
    private val deliveredMessageIds = mutableSetOf<String>()
    private val readMessageIds = mutableSetOf<String>()
    private val sentDeliveryAckIds = mutableSetOf<String>()

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
        subscribeToDirectMessages()

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

        flow.value
    }

    override suspend fun getMeshMessages(): List<BitchatMessage> = withContext(coroutinesContextFacade.io) {
        meshChannelMessages.toList()
    }

    override suspend fun getMeshPeers(): List<GeoPerson> = withContext(coroutinesContextFacade.io) {
        meshPeers.filter { !blockListPreferences.isMeshUserBlocked(it.id) }.toList()
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
        privateChats.mapValues { it.value.toList() }
    }

    override fun observeMiningStatus(): Flow<String?> {
        return nostrClient.currentlyMiningMessageId
    }

    override suspend fun getUnreadPrivatePeers(): Set<String> = withContext(coroutinesContextFacade.io) {
        unreadPrivatePeers.toSet()
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
        latestUnreadPrivatePeer
    }

    override suspend fun getSelectedPrivatePeer(): String? = withContext(coroutinesContextFacade.io) {
        selectedPrivatePeer
    }

    override suspend fun setSelectedPrivatePeer(peerID: String?) = withContext(coroutinesContextFacade.io) {
        selectedPrivatePeer = peerID
        chatEventBus.update(ChatEvent.SelectedPrivatePeerChanged)
    }

    override suspend fun markPrivateChatRead(peerID: String) = withContext(coroutinesContextFacade.io) {
        val unreadIds = unreadPrivateMessageIds.remove(peerID).orEmpty()
        unreadIds.forEach { messageId ->
            sendReadReceipt(messageId, readerPeerID = null, toPeerID = peerID)
        }

        val wasUnread = unreadPrivatePeers.remove(peerID)
        if (latestUnreadPrivatePeer == peerID) {
            latestUnreadPrivatePeer = resolveLatestUnreadPeer()
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
            waitForTorIfEnabled()
            nostrRelay.ensureGeohashRelaysConnected(geohash, nRelays = 5, includeDefaults = false)
        }

        val filter = NostrFilter(
            kinds = listOf(NostrKind.EPHEMERAL_EVENT),
            tagFilters = mapOf("g" to listOf(geohash))
        )

        val relayUrls = nostrRelay.getRelaysForGeohash(geohash).toSet()
        println("ChatRepo: Creating subscription for ${relayUrls.size} relays (geohash $geohash)")

        nostrRelay.subscribe(
            subscriptionId = "geohash_$geohash",
            filter = filter,
            handler = { event -> handleGeohashEvent(geohash, event) },
            targetRelayUrls = relayUrls.ifEmpty { null },
            originGeohash = geohash
        )

        nostrRelay.subscribe(
            subscriptionId = "sampling_$geohash",
            filter = filter,
            handler = { event -> handleGeohashEvent(geohash, event) },
            targetRelayUrls = relayUrls.ifEmpty { null },
            originGeohash = geohash
        )

        println("ChatRepo: Subscriptions created (geohash_ and sampling_) - will be sent when relays connect")

        subscribeToGeohashDirectMessages(geohash)
    }

    private fun subscribeToDirectMessages() {
        coroutineScopeFacade.nostrScope.launch {
            waitForTorIfEnabled()
            val identity = nostrClient.getCurrentNostrIdentity() ?: return@launch
            val pubkey = identity.publicKeyHex
            if (!activeDmSubscriptions.add(pubkey)) return@launch

            nostrRelay.ensureDefaultRelaysConnected()

            val filter = NostrFilter.giftWrapsFor(pubkey)
            nostrRelay.subscribe(
                subscriptionId = "dm_$pubkey",
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
            waitForTorIfEnabled()
            val identity = runCatching { nostrClient.deriveIdentity(geohash) }.getOrNull() ?: return@launch
            nostrRelay.ensureGeohashRelaysConnected(geohash, nRelays = 5, includeDefaults = false)

            val relayUrls = nostrRelay.getRelaysForGeohash(geohash).toSet()
            val filter = NostrFilter.giftWrapsFor(identity.publicKeyHex)
            nostrRelay.subscribe(
                subscriptionId = "geodm_$geohash",
                filter = filter,
                handler = { event -> handleDirectMessageEvent(event, identity, geohash) },
                targetRelayUrls = relayUrls.ifEmpty { null },
                originGeohash = geohash
            )
        }
    }

    override suspend fun sendGeohashMessage(content: String, geohash: String, nickname: String): Unit =
        sendGeohashMessage(content, geohash, nickname, BitchatMessageType.Message)

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
                println("   Content preview: ${content.take(50)}...")
                println("   Message type: $messageType")
                println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")

                // Wait for Tor if enabled before connecting to relays
                waitForTorIfEnabled()

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
                val localMessage = BitchatMessage(
                    id = tempId,  // Use tempId for animation tracking
                    sender = nickname,
                    content = content,
                    type = messageType,
                    timestamp = Clock.System.now(),
                    isPrivate = false,
                    senderPeerID = identity.publicKeyHex,
                    channel = geohash,
                    powDifficulty = null  // Will be set after mining
                )

                val flow = geohashMessagesFlows.getOrPut(geohash) {
                    MutableStateFlow(emptyList())
                }
                val currentMessages = flow.value.toMutableList()
                currentMessages.add(localMessage)
                currentMessages.sortBy { it.timestamp }
                flow.value = currentMessages

                // Emit event to notify observers
                coroutineScopeFacade.nostrScope.launch {
                    chatEventBus.update(ChatEvent.GeohashMessagesUpdated(geohash))
                }

                println("✅ ChatRepo: Added local echo with tempId to UI, total messages: ${currentMessages.size}")

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

    override suspend fun sendMeshMessage(content: String, nickname: String): Unit =
        sendMeshMessage(content, nickname, BitchatMessageType.Message)

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
            println("   Content preview: ${content.take(50)}...")
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

            meshChannelMessages.add(localMessage)
            chatEventBus.update(ChatEvent.MeshMessagesUpdated)
            println("✅ ChatRepo: Added local echo to mesh messages, total: ${meshChannelMessages.size}")

            // Send via Bluetooth - handle file types specially
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
                        println("📎 ChatRepo: Image first bytes: $firstBytes")
                        mesh.sendFileBroadcast(filePacket)
                        println("📎 ChatRepo: Compressed image broadcast sent: ${preparedImage.fileName} (${preparedImage.bytes.size} bytes, ${preparedImage.mimeType})")
                    } else {
                        println("❌ ChatRepo: Failed to compress image for BLE transfer: $content")
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
                        mesh.sendFileBroadcast(filePacket)
                        println("📎 ChatRepo: Audio file broadcast sent: $fileName (${fileBytes.size} bytes)")
                    } else {
                        println("❌ ChatRepo: Failed to read audio file: $content")
                    }
                }
                else -> {
                    // Regular text message
                    mesh.sendMessage(content, mentions = emptyList())
                    println("📡 ChatRepo: Message broadcast via Bluetooth mesh")
                }
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
     * Process incoming geohash event and convert to BitchatMessage
     */
    private fun handleGeohashEvent(geohash: String, event: NostrEvent) {
        try {
            println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
            println("📬 ChatRepo.handleGeohashEvent STARTED")
            println("   Geohash: $geohash")
            println("   Event ID: ${event.id.take(16)}...")
            println("   Event kind: ${event.kind}")
            println("   Content preview: ${event.content.take(50)}...")
            println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")

            // ENSURE flow exists for this geohash - prevent messages from being lost
            if (geohashMessagesFlows[geohash] == null) {
                println("⚠️  ChatRepo: Creating new flow for geohash=$geohash")
                geohashMessagesFlows[geohash] = kotlinx.coroutines.flow.MutableStateFlow(emptyList())
                println("✅ ChatRepo: Flow created and initialized")
            }

            val senderPubkey = event.pubkey
            val senderNickname = event.tags.find { it.firstOrNull() == "n" }?.getOrNull(1)
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

            coroutineScopeFacade.nostrScope.launch {
                participantTracker.updateParticipant(
                    geohash = geohash,
                    pubkey = senderPubkey,
                    nickname = senderNickname ?: "anon",
                    timestamp = kotlin.time.Instant.fromEpochSeconds(event.createdAt.toLong()),
                    isTeleported = isTeleported
                )
                locationEventBus.update(LocationEvent.ParticipantsChanged)
                chatEventBus.update(ChatEvent.GeohashParticipantsChanged(geohash))
            }

            val peerID = "nostr_${senderPubkey.take(16)}"
            if (!senderNickname.isNullOrBlank()) {
                savePeerDisplayName(peerID, senderNickname)
            }

            val message = BitchatMessage(
                id = event.id,
                sender = senderNickname ?: senderPubkey.take(16),
                content = event.content,
                type = BitchatMessageType.Message,
                timestamp = kotlin.time.Instant.fromEpochSeconds(event.createdAt.toLong()),
                isPrivate = false,
                senderPeerID = senderPubkey,
                channel = geohash,
                powDifficulty = event.tags.find { it.firstOrNull() == "nonce" }?.getOrNull(2)?.toIntOrNull()
            )

            val flow = geohashMessagesFlows[geohash]
            if (flow != null) {
                val currentMessages = flow.value.toMutableList()

                // Check for duplicates (our local echo or relay echo)
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

                currentMessages.add(message)
                currentMessages.sortBy { it.timestamp }
                flow.value = currentMessages

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
        if (!handledGiftWrapIds.add(event.id)) return

        val decrypted = nostrClient.decryptPrivateMessage(event, identity) ?: return
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

            NoisePayloadType.FILE_TRANSFER -> Unit
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
        val convKey = conversationKeyFor(senderPubkey)
        knownPrivatePeers[convKey] = senderPubkey

        if (sourceGeohash != null) {
            geohashAliasCache[convKey] = senderPubkey
            geohashConversationCache[convKey] = sourceGeohash
        }

        // Try to resolve display name from multiple sources:
        // 1. Cached display names from previous interactions
        // 2. Participant tracker (nicknames learned from geohash presence)
        // 3. Fall back to truncated pubkey for display only (don't cache fallback values)
        val cachedDisplayName = peerDisplayNames[convKey]
            ?: participantTracker.getNicknameByPubkeySync(senderPubkey)
        val senderDisplayName = cachedDisplayName ?: senderPubkey.take(16)

        if (cachedDisplayName != null) {
            savePeerDisplayName(convKey, cachedDisplayName)
        }

        val favoritePayloadHandled = handleFavoriteNotificationIfNeeded(packet.content, convKey, senderDisplayName)
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

        addPrivateMessage(convKey, message, markUnread = selectedPrivatePeer != convKey, sendReadReceipt = true)
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

    private fun addPrivateMessage(
        peerID: String,
        message: BitchatMessage,
        markUnread: Boolean,
        sendReadReceipt: Boolean
    ) {
        val messages = privateChats.getOrPut(peerID) { mutableListOf() }
        if (messages.any { it.id == message.id }) return

        messages.add(message)
        messages.sortBy { it.timestamp }

        coroutineScopeFacade.nostrScope.launch {
            chatEventBus.update(ChatEvent.PrivateChatsUpdated)
        }

        val isCurrentlyViewing = selectedPrivatePeer == peerID

        val lastReadTimestamp = lastReadTimestamps[peerID.lowercase()]
        val messageTimestampMillis = message.timestamp.toEpochMilliseconds()
        val wasAlreadyRead = lastReadTimestamp != null && messageTimestampMillis <= lastReadTimestamp

        if (markUnread && !isCurrentlyViewing && !wasAlreadyRead) {
            unreadPrivatePeers.add(peerID)
            unreadPrivateMessageIds.getOrPut(peerID) { mutableSetOf() }.add(message.id)
            latestUnreadPrivatePeer = peerID
            coroutineScopeFacade.nostrScope.launch {
                chatEventBus.update(ChatEvent.UnreadPrivatePeersUpdated)
                chatEventBus.update(ChatEvent.LatestUnreadPrivatePeerChanged)
            }
        } else if (sendReadReceipt || isCurrentlyViewing) {
            coroutineScopeFacade.nostrScope.launch {
                sendReadReceipt(message.id, readerPeerID = null, toPeerID = peerID)
            }
        }
    }

    private fun updateDeliveryStatus(convKey: String, messageId: String, status: DeliveryStatus) {
        val messages = privateChats[convKey] ?: return
        var updated = false
        val newMessages = messages.map { msg ->
            if (msg.id == messageId) {
                updated = true
                msg.copy(deliveryStatus = status)
            } else {
                msg
            }
        }

        if (updated) {
            privateChats[convKey] = newMessages.toMutableList()
            coroutineScopeFacade.nostrScope.launch {
                chatEventBus.update(ChatEvent.PrivateChatsUpdated)
            }
        }
    }

    private fun maybeSendDeliveryAck(messageId: String, peerID: String) {
        if (!sentDeliveryAckIds.add(messageId)) return
        coroutineScopeFacade.nostrScope.launch {
            sendDeliveryAck(messageId, peerID)
        }
    }

    private fun conversationKeyFor(pubkeyHex: String): String {
        return "nostr_${pubkeyHex.take(16)}"
    }

    private fun handleFavoriteNotificationIfNeeded(
        content: String,
        convKey: String,
        senderDisplayName: String
    ): Boolean {
        val normalized = content.trim()
        val isFavorite = when {
            normalized.startsWith("[FAVORITED]:") -> true
            normalized.startsWith("[UNFAVORITED]:") -> false
            else -> return false
        }

        val npub = normalized.substringAfter(":", "").takeIf { it.isNotBlank() }
        val normalizedKey = convKey.removePrefix("nostr_").lowercase()
        coroutineScopeFacade.applicationScope.launch {
            val now = Clock.System.now().toEpochMilliseconds()
            val existing = userRepository.getFavorite(normalizedKey)
            val updated = com.bitchat.domain.user.model.FavoriteRelationship(
                peerNoisePublicKeyHex = normalizedKey,
                peerNostrPublicKey = npub ?: existing?.peerNostrPublicKey,
                peerNickname = existing?.peerNickname ?: senderDisplayName,
                isFavorite = existing?.isFavorite ?: false,
                theyFavoritedUs = isFavorite,
                favoritedAt = existing?.favoritedAt ?: now,
                lastUpdated = now
            )

            userRepository.saveFavorite(updated)
            userEventBus.update(UserEvent.FavoriteStatusChanged(normalizedKey))
            npub?.let { userPreferences.setNostrPubkeyForPeerID(normalizedKey, it) }
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
    ): Unit = sendPrivate(content, toPeerID, recipientNickname, BitchatMessageType.Message)

    private suspend fun sendPrivate(
        content: String,
        toPeerID: String,
        recipientNickname: String,
        messageType: BitchatMessageType
    ): Unit = withContext(coroutinesContextFacade.io) {
        val senderName = when (val user = userPreferences.getAppUser()) {
            is AppUser.ActiveAnonymous -> user.name
            AppUser.Anonymous -> "anon"
        }

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
        addPrivateMessage(toPeerID, localMessage, markUnread = false, sendReadReceipt = false)

        val currentChannel = userPreferences.getUserState()
            ?.let { it as? UserState.Active }
            ?.activeState?.let { it as? ActiveState.Chat }
            ?.channel

        when (currentChannel) {
            is Channel.MeshDM -> {
                val hasMesh = mesh.getPeerInfo(toPeerID)?.isConnected == true
                val hasEstablished = mesh.hasEstablishedSession(toPeerID)

                println("🔍 sendPrivate [MeshDM]: toPeerID=$toPeerID, hasMesh=$hasMesh, hasEstablished=$hasEstablished")

                if (hasMesh && hasEstablished) {
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
                                println("📎 ChatRepo: Private image first bytes: $firstBytes")
                                mesh.sendFilePrivate(toPeerID, filePacket)
                                println("📎 ChatRepo: Private compressed image sent to $toPeerID: ${preparedImage.fileName} (${preparedImage.bytes.size} bytes, ${preparedImage.mimeType})")
                            } else {
                                println("❌ ChatRepo: Failed to compress image for BLE transfer: $content")
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
                                mesh.sendFilePrivate(toPeerID, filePacket)
                                println("📎 ChatRepo: Private audio file sent to $toPeerID: $fileName (${fileBytes.size} bytes)")
                            } else {
                                println("❌ ChatRepo: Failed to read audio file: $content")
                            }
                        }

                        else -> {
                            mesh.sendPrivateMessage(content, toPeerID, recipientNickname, messageId)
                        }
                    }
                } else {
                    // Queue and initiate handshake
                    println("📦 Queuing to outbox, initiating handshake")
                    val q = outbox.getOrPut(toPeerID) { mutableListOf() }
                    val isFirstMessage = q.isEmpty()
                    q.add(Triple(content, recipientNickname, messageId))
                    println("📦 Outbox size for $toPeerID: ${q.size}")
                    if (isFirstMessage) {
                        mesh.initiateNoiseHandshake(toPeerID)
                    } else {
                        println("📦 Handshake already in progress, message queued")
                    }
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
                val hex = knownPrivatePeers[peerID] ?: return null
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

            for ((convKey, pubkeyHex) in knownPrivatePeers) {
                if (pubkeyHex == npubHex) {
                    return convKey
                }
            }

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

    override suspend fun onPeersUpdated(peers: List<String>) = withContext(coroutinesContextFacade.io) {
        peers.forEach { pid ->
            flushOutboxFor(pid)
            val noiseHex = try {
                mesh.getPeerInfo(pid)?.noisePublicKey
            } catch (_: Exception) {
                null
            }
            noiseHex?.let { flushOutboxFor(it.toHexString()) }
        }
    }

    override suspend fun onSessionEstablished(peerID: String): Unit = withContext(coroutinesContextFacade.io) {
        println("🔐 Session established for: $peerID")
        flushOutboxFor(peerID)
        val noiseHex = try {
            mesh.getPeerInfo(peerID)?.noisePublicKey
        } catch (_: Exception) {
            null
        }
        noiseHex?.let { flushOutboxFor(it.toHexString()) }
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
                meshChannelMessages.clear()
                chatEventBus.update(ChatEvent.MeshMessagesUpdated)
            }

            is Channel.Location -> {
                val flow = geohashMessagesFlows[channel.geohash]
                flow?.value = emptyList()
                chatEventBus.update(ChatEvent.GeohashMessagesUpdated(channel.geohash))
            }

            is Channel.MeshDM -> {
                privateChats[channel.peerID]?.clear()
                unreadPrivatePeers.remove(channel.peerID)
                unreadPrivateMessageIds.remove(channel.peerID)
                if (latestUnreadPrivatePeer == channel.peerID) {
                    latestUnreadPrivatePeer = resolveLatestUnreadPeer()
                }
                chatEventBus.update(ChatEvent.PrivateChatsUpdated)
            }

            is Channel.NostrDM -> {
                privateChats[channel.peerID]?.clear()
                unreadPrivatePeers.remove(channel.peerID)
                unreadPrivateMessageIds.remove(channel.peerID)
                if (latestUnreadPrivatePeer == channel.peerID) {
                    latestUnreadPrivatePeer = resolveLatestUnreadPeer()
                }
                chatEventBus.update(ChatEvent.PrivateChatsUpdated)
            }

            is Channel.NamedChannel -> {
                val normalized = normalizeChannelName(channel.channelName)
                namedChannelMessages[normalized]?.clear()
                chatEventBus.update(ChatEvent.NamedChannelMessagesUpdated(channel.channelName))
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
            knownPrivatePeers[peerID] = fullPubkey
            if (sourceGeohash != null) {
                geohashAliasCache[peerID] = fullPubkey
                geohashConversationCache[peerID] = sourceGeohash
            }
            if (!displayName.isNullOrBlank()) {
                savePeerDisplayName(peerID, displayName)
            }
        }

    override suspend fun getFullPubkey(peerID: String): String? = withContext(coroutinesContextFacade.io) {
        knownPrivatePeers[peerID]
    }

    override suspend fun getSourceGeohash(peerID: String): String? = withContext(coroutinesContextFacade.io) {
        geohashConversationCache[peerID]
    }

    override suspend fun getDisplayName(peerID: String): String? = withContext(coroutinesContextFacade.io) {
        peerDisplayNames[peerID]?.let { return@withContext it }

        val fullPubkey = knownPrivatePeers[peerID]
        if (fullPubkey != null) {
            participantTracker.getNicknameByPubkey(fullPubkey)?.let { nickname ->
                savePeerDisplayName(peerID, nickname)
                return@withContext nickname
            }
        }

        null
    }

    private fun savePeerDisplayName(peerID: String, displayName: String) {
        if (displayName.isBlank()) return
        peerDisplayNames[peerID] = displayName
        userPreferences.setPeerDisplayName(peerID, displayName)
    }

    @OptIn(ExperimentalUuidApi::class)
    override suspend fun sendMessage(
        content: String,
        channel: Channel,
        sender: String,
        messageType: BitchatMessageType
    ) = withContext(coroutinesContextFacade.io) {
        when (channel) {
            is Channel.Mesh -> sendMeshMessage(content, sender, messageType)
            is Channel.Location -> sendGeohashMessage(content, channel.geohash, sender, messageType)
            is Channel.NostrDM -> {
                initializePrivateDMIfNeeded(channel.peerID)
                sendPrivate(
                    content = content,
                    toPeerID = channel.peerID,
                    recipientNickname = "",
                    messageType = messageType
                )
            }

            is Channel.MeshDM -> {
                initializePrivateDMIfNeeded(channel.peerID)
                sendPrivate(
                    content = content,
                    toPeerID = channel.peerID,
                    recipientNickname = "",
                    messageType = messageType
                )
            }

            is Channel.NamedChannel -> {
                sendNamedChannelMessage(channel.channelName, content, sender, messageType)
            }
        }
    }

    private fun initializePrivateDMIfNeeded(peerID: String) {
        if (!privateChats.containsKey(peerID)) {
            println("🆕 Initializing new DM: $peerID")
            privateChats[peerID] = mutableListOf()
        }

        val person = findPersonByPeerID(peerID)

        if (person != null) {
            knownPrivatePeers[peerID] = person.fullPubkey

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
        meshPeers.find { it.id == peerID || "nostr_${it.id.take(16)}" == peerID }?.let {
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

    private fun flushOutboxFor(peerID: String) {
        val queued = outbox[peerID] ?: return
        if (queued.isEmpty()) return

        println("🚀 Flushing outbox for $peerID: ${queued.size} messages")

        val iterator = queued.iterator()
        while (iterator.hasNext()) {
            val (content, nickname, messageID) = iterator.next()
            val hasMesh = mesh.getPeerInfo(peerID)?.isConnected == true && mesh.hasEstablishedSession(peerID)
            if (!hasMesh && peerID.length == 64 && peerID.matches(Regex("^[0-9a-fA-F]+$"))) {
                val meshPeer = resolveMeshPeerForNoiseHex(peerID)
                if (meshPeer != null && mesh.getPeerInfo(meshPeer)?.isConnected == true && mesh.hasEstablishedSession(meshPeer)) {
                    println("   → Sending queued message via mesh peer: $messageID")
                    mesh.sendPrivateMessage(content, meshPeer, nickname, messageID)
                    iterator.remove()
                    continue
                }
            }
            val canNostr = canSendViaNostr(peerID)
            if (hasMesh) {
                println("   → Sending queued message via mesh: $messageID")
                mesh.sendPrivateMessage(content, peerID, nickname, messageID)
                iterator.remove()
            } else if (canNostr) {
                val recipientNpub = resolveNostrPublicKey(peerID)
                val recipientPeerIDForEmbed = findPeerIDForNostrPubkey(recipientNpub ?: "") ?: peerID

                if (recipientNpub != null) {
                    println("   → Sending queued message via Nostr: $messageID")
                    nostr.sendPrivateMessage(
                        content = content,
                        recipientNostrPubkey = recipientNpub,
                        recipientPeerID = recipientPeerIDForEmbed,
                        messageID = messageID,
                        recipientNickname = nickname
                    )
                    iterator.remove()
                }
            }
        }
        if (queued.isEmpty()) {
            outbox.remove(peerID)
            println("✅ Outbox flushed for $peerID")
        }
    }

    suspend fun flushAllOutbox() = withContext(coroutinesContextFacade.io) {
        outbox.keys.toList().forEach { flushOutboxFor(it) }
    }

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

    private fun resolveMeshPeerForNoiseHex(noiseHex: String): String? {
        return try {
            mesh.getPeerNicknames().keys.firstOrNull { pid ->
                val info = mesh.getPeerInfo(pid)
                val keyHex = info?.noisePublicKey
                keyHex != null && keyHex.equals(noiseHex)
            }
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun waitForTorIfEnabled() {
        torManager?.let { manager ->
            if (manager.isProxyReady()) {
                println("🔒 ChatRepo: Tor is already ready")
                return
            }

            val status = manager.statusFlow.value
            if (status.state != com.bitchat.domain.tor.model.TorState.OFF) {
                println("🔒 ChatRepo: Waiting for Tor to be ready...")
                println("   Current state: ${status.state}, bootstrap: ${status.bootstrapPercent}%")

                // Add 30-second timeout to prevent infinite blocking
                withTimeoutOrNull(30.seconds) {
                    manager.statusFlow
                        .collect { torStatus ->
                            if (manager.isProxyReady()) {
                                println("✅ ChatRepo: Tor is now ready")
                                return@collect
                            }

                            // Exit on ERROR state - don't block indefinitely
                            if (torStatus.state == com.bitchat.domain.tor.model.TorState.ERROR) {
                                println("❌ ChatRepo: Tor is in ERROR state, proceeding without Tor")
                                println("   Error: ${torStatus.errorMessage}")
                                return@collect
                            }
                        }
                } ?: run {
                    println("⏱️ ChatRepo: Tor wait timeout (30s), proceeding without Tor")
                }
            }
        }
    }

    @OptIn(FlowPreview::class)
    private fun observeTorReadyAndEstablishConnections() {
        torManager?.let { manager ->
            coroutineScopeFacade.nostrScope.launch {
                manager.statusFlow
                    .distinctUntilChangedBy { it.running to it.bootstrapPercent to it.state }
                    .filter { it.running && it.bootstrapPercent >= 100 && it.state == com.bitchat.domain.tor.model.TorState.RUNNING }
                    .debounce(1000) // ADDED: Debounce for 1 second as additional defense layer
                    .collect {
                        println("🚀 ChatRepo: Tor is now ready, establishing relay connections for active channels")
                        establishConnectionsForActiveChannels()
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

    override fun didReceiveMessage(message: BitchatMessage) {
        coroutineScopeFacade.applicationScope.launch {
            println("Bluetooth: Received message from ${message.sender} (${message.senderPeerID}): ${message.content}")

            message.senderPeerID?.let { peerID ->
                if (blockListPreferences.isMeshUserBlocked(peerID)) {
                    println("🚫 ChatRepo: BLOCKED mesh message from $peerID")
                    return@launch
                }
            }

            if (message.isPrivate && message.senderPeerID != null) {
                val handled = handleFavoriteNotificationIfNeeded(
                    content = message.content,
                    convKey = message.senderPeerID!!,
                    senderDisplayName = message.sender
                )
                if (handled) return@launch
            }

            // Add to appropriate storage based on message type
            if (message.isPrivate && message.senderPeerID != null) {
                addPrivateMessage(message.senderPeerID!!, message, markUnread = true, sendReadReceipt = false)
            } else if (message.isPrivate == false && message.channel == null) {
                if (meshChannelMessages.none { it.id == message.id }) {
                    meshChannelMessages.add(message)
                    chatEventBus.update(ChatEvent.MeshMessagesUpdated)
                }
            } else if (message.channel != null) {
                val flow = geohashMessagesFlows.getOrPut(message.channel!!) {
                    MutableStateFlow(emptyList())
                }
                val currentMessages = flow.value.toMutableList()
                currentMessages.add(message)
                flow.value = currentMessages

                coroutineScopeFacade.nostrScope.launch {
                    chatEventBus.update(ChatEvent.GeohashMessagesUpdated(message.channel!!))
                }
            }

            chatEventBus.update(ChatEvent.MessageReceived)
        }
    }

    private suspend fun updateMessagesFromUnknownPeer(peerID: String, newNickname: String) {
        var updatedCount = 0

        val updatedMeshMessages = meshChannelMessages.map { message ->
            if (message.senderPeerID == peerID && message.sender == "Unknown") {
                updatedCount++
                message.copy(sender = newNickname)
            } else {
                message
            }
        }
        meshChannelMessages.clear()
        meshChannelMessages.addAll(updatedMeshMessages)

        privateChats[peerID]?.let { chatMessages ->
            val updatedPrivateMessages = chatMessages.map { message ->
                if (message.sender == "Unknown") {
                    updatedCount++
                    message.copy(sender = newNickname)
                } else {
                    message
                }
            }
            chatMessages.clear()
            chatMessages.addAll(updatedPrivateMessages)
        }

        if (updatedCount > 0) {
            println("🔄 Updated $updatedCount messages from 'Unknown' to '$newNickname' for peer $peerID")
            // Trigger UI refresh
            chatEventBus.update(ChatEvent.MeshMessagesUpdated)
            chatEventBus.update(ChatEvent.PrivateChatsUpdated)
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

            val previousPeerIds = meshPeers.map { it.id }.toSet()
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
                val peerInfo = mesh.getPeerInfo(peerID)
                if (peerInfo?.nickname != null && peerInfo.nickname != "Unknown") {
                    updateMessagesFromUnknownPeer(peerID, peerInfo.nickname)
                }
            }

            // Convert to GeoPerson for consistency with LocationRepository
            meshPeers.clear()
            meshPeers.addAll(activePeers.map { peerID ->
                val peerInfo = mesh.getPeerInfo(peerID)
                GeoPerson(
                    id = peerID,
                    displayName = peerInfo?.nickname ?: peerID.take(12),
                    lastSeen = Clock.System.now(),
                )
            })

            println("📊 ChatRepo: meshPeers list now contains ${meshPeers.size} peers")
            chatEventBus.update(ChatEvent.MeshPeersUpdated)
        }
    }

    override fun didReceiveChannelLeave(channel: String, fromPeer: String) {
        println("Bluetooth: Peer $fromPeer left channel $channel")
    }

    override fun didReceiveDeliveryAck(messageID: String, recipientPeerID: String) {
        println("Bluetooth: Message $messageID delivered to $recipientPeerID")
    }

    override fun didReceiveReadReceipt(messageID: String, recipientPeerID: String) {
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

    override fun didReceiveFile(peerID: String, filePacket: BitchatFilePacket, isBroadcast: Boolean) {
        coroutineScopeFacade.applicationScope.launch {
            try {
                val firstBytes = filePacket.content.take(10).joinToString(" ") { byte ->
                    (byte.toInt() and 0xFF).toString(16).padStart(2, '0').uppercase()
                }
                println("ChatRepo: Received file first bytes: $firstBytes")

                val messageType = when {
                    filePacket.mimeType.startsWith("image/") -> BitchatMessageType.Image
                    filePacket.mimeType.startsWith("audio/") -> BitchatMessageType.Audio
                    else -> BitchatMessageType.Message
                }

                val subDir = when (messageType) {
                    BitchatMessageType.Image -> "images/incoming"
                    BitchatMessageType.Audio -> "audio/incoming"
                    else -> "files/incoming"
                }

                val localPath = saveFileToLocal(filePacket.content, filePacket.fileName, subDir)
                if (localPath == null) {
                    println("❌ ChatRepo: Failed to save received file: ${filePacket.fileName}")
                    return@launch
                }

                val peer = mesh.getPeerInfo(peerID)
                val senderName = peer?.nickname ?: "Unknown"
                val now = Clock.System.now()

                val bitchatMessage = BitchatMessage(
                    id = "file-${now.toEpochMilliseconds()}",
                    sender = senderName,
                    content = localPath,
                    type = messageType,
                    timestamp = now,
                    isPrivate = !isBroadcast,
                    senderPeerID = peerID,
                    channel = null,
                    deliveryStatus = DeliveryStatus.Delivered(to = mesh.myPeerID, at = now)
                )

                if (isBroadcast) {
                    meshChannelMessages.add(bitchatMessage)
                    chatEventBus.update(ChatEvent.MeshMessagesUpdated)
                    println("ChatRepo: Added file message to mesh channel")
                } else {
                    addPrivateMessage(peerID, bitchatMessage, markUnread = true, sendReadReceipt = false)
                    println("ChatRepo: Added file message to private DM with $peerID")
                }
            } catch (e: Exception) {
                println("ChatRepo: Error handling received file: ${e.message}")
                e.printStackTrace()
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
            waitForTorIfEnabled()
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
        namedChannelMessages[normalized]?.toList().orEmpty()
    }

    override suspend fun addNamedChannelMessage(channelName: String, message: BitchatMessage) = withContext(coroutinesContextFacade.io) {
        val normalized = normalizeChannelName(channelName)
        val messages = namedChannelMessages.getOrPut(normalized) { mutableListOf() }
        if (messages.none { it.id == message.id }) {
            messages.add(message)
            messages.sortBy { it.timestamp }
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
        outbox.clear()
        channelKeys.clear()
        geohashMessagesFlows.clear()
        activeGeohashSubscriptions.clear()
        meshChannelMessages.clear()
        meshPeers.clear()

        privateChats.clear()
        unreadPrivatePeers.clear()
        unreadPrivateMessageIds.clear()
        latestUnreadPrivatePeer = null
        selectedPrivatePeer = null
        knownPrivatePeers.clear()
        peerDisplayNames.clear()
        lastReadTimestamps.clear()
        handledGiftWrapIds.clear()
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
