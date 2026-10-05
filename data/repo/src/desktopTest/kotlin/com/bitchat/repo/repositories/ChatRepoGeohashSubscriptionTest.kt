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
import com.bitchat.domain.user.repository.UserRepository
import com.bitchat.local.prefs.BlockListPreferences
import com.bitchat.local.prefs.ChannelPreferences
import com.bitchat.local.prefs.UserPreferences
import com.bitchat.nostr.NostrClient
import com.bitchat.nostr.NostrPreferences
import com.bitchat.nostr.NostrRelay
import com.bitchat.nostr.NostrSubscriptionId
import com.bitchat.nostr.NostrTransport
import com.bitchat.nostr.model.NostrFilter
import com.bitchat.nostr.model.NostrKind
import com.bitchat.nostr.participant.NostrParticipantTracker
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class ChatRepoGeohashSubscriptionTest {

    @Test
    fun getGeohashMessagesCreatesOneChatSubscription() = runTest {
        val subscriptions = mutableListOf<Subscription>()
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)

        try {
            val chatRepo = chatRepo(scope, dispatcher, subscriptions)
            val geohash = "9q8yy"

            chatRepo.getGeohashMessages(geohash)

            val geohashSubscriptions = subscriptions.filter { subscription ->
                subscription.filter.kinds == listOf(NostrKind.EPHEMERAL_EVENT) &&
                    subscription.filter.tagFilters?.get("g") == listOf(geohash)
            }

            assertEquals(1, geohashSubscriptions.size)
            assertEquals(NostrSubscriptionId.geohash(geohash), geohashSubscriptions.single().id)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun getGeohashMessagesDoesNotSubscribeTwiceForTheSameGeohash() = runTest {
        val subscriptions = mutableListOf<Subscription>()
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)

        try {
            val chatRepo = chatRepo(scope, dispatcher, subscriptions)
            val geohash = "9q8yy"

            chatRepo.getGeohashMessages(geohash)
            chatRepo.getGeohashMessages(geohash)

            val geohashSubscriptions = subscriptions.filter { subscription ->
                subscription.id == NostrSubscriptionId.geohash(geohash)
            }

            assertEquals(1, geohashSubscriptions.size)
        } finally {
            scope.cancel()
        }
    }

    private fun chatRepo(
        scope: CoroutineScope,
        dispatcher: CoroutineContext,
        subscriptions: MutableList<Subscription>,
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
        val userPreferences = mockk<UserPreferences>(relaxed = true).also {
            every { it.getAllPeerDisplayNames() } returns emptyMap()
            every { it.getAllLastReadTimestamps() } returns emptyMap()
        }
        val channelPreferences = mockk<ChannelPreferences>(relaxed = true).also {
            every { it.getChannelEventIds() } returns emptyMap()
            every { it.getJoinedChannelsList() } returns emptySet()
        }
        val nostrClient = mockk<NostrClient>(relaxed = true).also {
            every { it.getCurrentNostrIdentity() } returns null
            every { it.deriveIdentity(any()) } throws IllegalStateException()
        }
        val nostrRelay = mockk<NostrRelay>(relaxed = true).also {
            every { it.getRelaysForGeohash(any()) } returns emptyList()
            every { it.subscribe(any(), any(), any(), any(), any()) } answers {
                val subscription = Subscription(firstArg(), secondArg())
                subscriptions += subscription
                subscription.id
            }
        }
        val userEventBus = mockk<UserEventBus>(relaxed = true).also {
            every { it.events() } returns emptyFlow()
        }
        val connectEventBus = mockk<ConnectionEventBus>(relaxed = true).also {
            coEvery { it.getBluetoothConnectionEvent() } returns flowOf(BluetoothConnectionEvent.DISCONNECTED)
        }

        return ChatRepo(
            coroutineScopeFacade = scopeFacade,
            coroutinesContextFacade = contextFacade,
            mesh = mockk<BluetoothMeshService>(relaxed = true),
            nostr = mockk<NostrTransport>(relaxed = true),
            nostrPreferences = mockk<NostrPreferences>(relaxed = true),
            nostrClient = nostrClient,
            nostrRelay = nostrRelay,
            geohashAliasCache = mockk<Cache<String, String>>(relaxed = true),
            geohashConversationCache = mockk<Cache<String, String>>(relaxed = true),
            channelPreferences = channelPreferences,
            userPreferences = userPreferences,
            blockListPreferences = mockk<BlockListPreferences>(relaxed = true),
            participantTracker = mockk<NostrParticipantTracker>(relaxed = true),
            locationEventBus = mockk<LocationEventBus>(relaxed = true),
            chatEventBus = mockk<ChatEventBus>(relaxed = true),
            userRepository = mockk<UserRepository>(relaxed = true).also {
                coEvery { it.getUserState() } returns null
            },
            appRepository = mockk<AppRepository>(relaxed = true).also {
                coEvery { it.hasRequiredPermissions() } returns false
            },
            userEventBus = userEventBus,
            connectEventBus = connectEventBus,
        )
    }

    private data class Subscription(val id: String, val filter: NostrFilter)
}
