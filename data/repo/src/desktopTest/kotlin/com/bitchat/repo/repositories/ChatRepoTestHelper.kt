package com.bitchat.repo.repositories

import com.bitchat.bluetooth.service.BluetoothMeshService
import com.bitchat.cache.Cache
import com.bitchat.domain.app.repository.AppRepository
import com.bitchat.domain.base.CoroutineScopeFacade
import com.bitchat.domain.base.CoroutinesContextFacade
import com.bitchat.domain.chat.eventbus.ChatEventBus
import com.bitchat.domain.connectivity.eventbus.ConnectionEventBus
import com.bitchat.domain.connectivity.model.BluetoothConnectionEvent
import com.bitchat.domain.location.eventbus.LocationEventBus
import com.bitchat.domain.user.eventbus.UserEventBus
import com.bitchat.domain.user.model.FavoriteRelationship
import com.bitchat.domain.user.model.BlockType
import com.bitchat.domain.user.model.BlockedUser
import com.bitchat.domain.user.repository.UserRepository
import com.bitchat.local.prefs.BlockListPreferences
import com.bitchat.local.prefs.ChannelPreferences
import com.bitchat.local.prefs.FavoritesUpdate
import com.bitchat.local.prefs.UserPreferences
import com.bitchat.lora.LoRaProtocol
import com.bitchat.nostr.NostrClient
import com.bitchat.nostr.NostrPreferences
import com.bitchat.nostr.NostrRelay
import com.bitchat.nostr.NostrSubscriptionId
import com.bitchat.nostr.NostrTransport
import com.bitchat.nostr.model.NostrEvent
import com.bitchat.nostr.model.NostrFilter
import com.bitchat.nostr.model.NostrIdentity
import com.bitchat.nostr.participant.NostrParticipantTracker
import com.bitchat.repo.utils.ReceivedFileBudget
import com.bitchat.repo.utils.MessageLimits
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlin.coroutines.CoroutineContext
import kotlin.time.Clock

internal fun chatRepo(
    scope: CoroutineScope,
    dispatcher: CoroutineContext,
    subscriptions: MutableList<Subscription>,
    lora: LoRaProtocol? = null,
    mesh: BluetoothMeshService = mockk(relaxed = true),
    clock: Clock = Clock.System,
    blockedMeshIds: Set<String> = emptySet(),
    receivedFileBudget: ReceivedFileBudget = ReceivedFileBudget(),
    messageLimits: MessageLimits = MessageLimits(),
    nostrIdentity: NostrIdentity? = null,
    geohashIdentity: NostrIdentity? = null,
    // Empty caches by default: a relaxed mock hands back an Object where a String is expected, which
    // breaks any path that looks a peer up (the private send path does). These discard what is written.
    geohashAliasCache: Cache<String, String> = mockk<Cache<String, String>>(relaxed = true).also { every { it.get(any()) } returns null },
    geohashConversationCache: Cache<String, String> = mockk<Cache<String, String>>(relaxed = true).also { every { it.get(any()) } returns null },
    userPreferences: UserPreferences = emptyUserPreferences(),
    userRepository: UserRepository = mockk<UserRepository>(relaxed = true).also { coEvery { it.getUserState() } returns null },
    nostrPreferences: NostrPreferences = mockk(relaxed = true),
    participantTracker: NostrParticipantTracker = mockk(relaxed = true),
    configureNostrClient: (NostrClient) -> Unit = {},
    userEventBus: UserEventBus = mockk<UserEventBus>(relaxed = true).also { every { it.events() } returns emptyFlow() },
): ChatRepo {
    val contextFacade = object : CoroutinesContextFacade {
        override val io: CoroutineContext = dispatcher
        override val main: CoroutineContext = dispatcher
        override val default: CoroutineContext = dispatcher
        override val unconfined: CoroutineContext = dispatcher
    }
    val scopeFacade = object : CoroutineScopeFacade {
        override val applicationScope: CoroutineScope = scope
        override val connectivityEventScope: CoroutineScope = scope
        override val bluetoothScope: CoroutineScope = scope
        override val nostrScope: CoroutineScope = scope
    }
    val channelPreferences = mockk<ChannelPreferences>(relaxed = true).also {
        every { it.getChannelEventIds() } returns emptyMap()
        every { it.getJoinedChannelsList() } returns emptySet()
    }
    val nostrClient = mockk<NostrClient>(relaxed = true).also {
        every { it.getCurrentNostrIdentity() } returns nostrIdentity
        if (geohashIdentity != null) {
            every { it.deriveIdentity(any()) } returns geohashIdentity
        } else {
            every { it.deriveIdentity(any()) } throws IllegalStateException()
        }
        // A test gift wrap carries its own "decryption": content, sender key and timestamp as they are.
        every { it.decryptPrivateMessage(any(), any()) } answers {
            firstArg<NostrEvent>().let { Triple(it.content, it.pubkey, it.createdAt) }
        }
        configureNostrClient(it)
    }
    val nostrRelay = mockk<NostrRelay>(relaxed = true).also {
        every { it.getRelaysForGeohash(any()) } returns emptyList()
        every { it.subscribe(any(), any(), any(), any(), any()) } answers {
            val subscription = Subscription(firstArg(), secondArg(), thirdArg())
            subscriptions += subscription
            subscription.id
        }
    }
    val connectEventBus = mockk<ConnectionEventBus>(relaxed = true).also {
        coEvery { it.getBluetoothConnectionEvent() } returns flowOf(BluetoothConnectionEvent.DISCONNECTED)
    }

    return ChatRepo(
        coroutineScopeFacade = scopeFacade,
        coroutinesContextFacade = contextFacade,
        mesh = mesh,
        nostr = mockk<NostrTransport>(relaxed = true),
        nostrPreferences = nostrPreferences,
        nostrClient = nostrClient,
        nostrRelay = nostrRelay,
        geohashAliasCache = geohashAliasCache,
        geohashConversationCache = geohashConversationCache,
        channelPreferences = channelPreferences,
        userPreferences = userPreferences,
        blockListPreferences = mockk<BlockListPreferences>(relaxed = true).also {
            every { it.isMeshUserBlocked(any()) } answers { firstArg<String>().lowercase() in blockedMeshIds }
            every { it.getMeshBlockedUsers() } returns blockedMeshIds.associate {
                it.lowercase() to BlockedUser(it.lowercase(), null, 0, BlockType.MESH)
            }
        },
        participantTracker = participantTracker,
        locationEventBus = mockk<LocationEventBus>(relaxed = true),
        chatEventBus = mockk<ChatEventBus>(relaxed = true),
        userRepository = userRepository,
        appRepository = mockk<AppRepository>(relaxed = true).also {
            coEvery { it.hasRequiredPermissions() } returns false
        },
        userEventBus = userEventBus,
        connectEventBus = connectEventBus,
        lora = lora,
        clock = clock,
        receivedFileBudget = receivedFileBudget,
        messageLimits = messageLimits,
    )
}

/** Preferences with nothing saved; what a test wants to observe it verifies on the mock. */
internal fun emptyUserPreferences(): UserPreferences = mockk<UserPreferences>(relaxed = true).also {
    every { it.getAllLastReadTimestamps() } returns emptyMap()
    every { it.getAllFavorites() } returns emptyMap()
    every { it.getFavorite(any()) } returns null
    every { it.getAllPeerIDMappings() } returns emptyMap()
    every { it.getNostrPubkeyForPeerID(any()) } returns null
    every { it.updateFavorites<Any?>(any()) } answers {
        firstArg<(Map<String, FavoriteRelationship>) -> FavoritesUpdate<Any?>>().invoke(emptyMap()).result
    }
}

/**
 * [delegate] behind a proxy that notes the name of every method called on it, so a test can say
 * "nothing was written" about a whole interface rather than about the methods it thought of.
 */
internal class Recorded<T : Any>(type: Class<T>, delegate: T) {
    val calls = java.util.concurrent.CopyOnWriteArrayList<String>()

    @Suppress("UNCHECKED_CAST")
    val proxy: T = java.lang.reflect.Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, arguments ->
        calls += method.name
        try {
            method.invoke(delegate, *(arguments ?: emptyArray()))
        } catch (e: java.lang.reflect.InvocationTargetException) {
            throw e.targetException
        }
    } as T

    /** Every call that is not a read. */
    fun writes(): List<String> = calls.filterNot { name ->
        name.startsWith("get") || name.startsWith("find") || name.startsWith("is") || name in setOf("hashCode", "equals", "toString")
    }
}

/** Preferences whose favourite state changes as the repository writes it. */
internal class StatefulFavoritePreferences(
    initial: Map<String, FavoriteRelationship> = emptyMap(),
    initialMappings: Map<String, String> = emptyMap(),
) {
    val favorites = initial.mapKeys { it.key.lowercase() }.toMutableMap()
    val mappings = initialMappings.mapKeys { it.key.lowercase() }.toMutableMap()
    var favoriteWrites = 0
        private set
    var mappingWrites = 0
        private set

    val preferences: UserPreferences = mockk<UserPreferences>(relaxed = true).also { preferences ->
        every { preferences.getAllLastReadTimestamps() } returns emptyMap()
        every { preferences.getAllFavorites() } answers { favorites.toMap() }
        every { preferences.getFavorite(any()) } answers { favorites[firstArg<String>().lowercase()] }
        // One step, as the real store does it: decided on what is saved now, written once if at all.
        every { preferences.updateFavorites<Any?>(any()) } answers {
            val update = firstArg<(Map<String, FavoriteRelationship>) -> FavoritesUpdate<Any?>>().invoke(favorites.toMap())
            var changed = update.remove.count { favorites.remove(it.lowercase()) != null } > 0
            for (favorite in update.save) {
                val key = favorite.peerNoisePublicKeyHex.lowercase()
                if (favorites[key] != favorite) {
                    favorites[key] = favorite
                    changed = true
                }
            }
            if (changed) favoriteWrites++
            update.result
        }
        // The repository changes favourites through that one step only: a separate read and write
        // could be decided on a record the user has changed in between.
        every { preferences.saveFavorite(any()) } throws IllegalStateException("favourites are changed through updateFavorites")
        every { preferences.deleteFavorite(any()) } throws IllegalStateException("favourites are changed through updateFavorites")
        every { preferences.getAllPeerIDMappings() } answers { mappings.toMap() }
        every { preferences.getNostrPubkeyForPeerID(any()) } answers { mappings[firstArg<String>().lowercase()] }
        every { preferences.setNostrPubkeyForPeerID(any(), any()) } answers {
            mappings[firstArg<String>().lowercase()] = secondArg()
            mappingWrites++
        }
        every { preferences.clearAllPeerIDMappings() } answers {
            if (mappings.isNotEmpty()) mappingWrites++
            mappings.clear()
        }
    }
}

internal data class Subscription(val id: String, val filter: NostrFilter, val handler: (NostrEvent) -> Unit = {})
