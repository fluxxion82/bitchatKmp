package com.bitchat.repo.repositories

import com.bitchat.cache.impl.InMemoryCache
import com.bitchat.client.websocket.NostrWebSocketClient
import com.bitchat.client.websocket.NostrWebSocketListener
import com.bitchat.domain.base.CoroutineScopeFacade
import com.bitchat.domain.base.CoroutinesContextFacade
import com.bitchat.nostr.NostrRelay
import com.bitchat.nostr.NostrSubscriptionId
import com.bitchat.nostr.model.NostrEvent
import com.bitchat.nostr.model.NostrIdentity
import com.bitchat.nostr.model.NostrKind
import com.bitchat.nostr.model.RelayInfo
import com.bitchat.nostr.util.NostrEventDeduplicator
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

/**
 * Two things the app does more than once with the same relays: look at the notes of a place, and
 * search for a named channel. Each time it subscribes anew and the relays answer with what they
 * store, which is what they answered the time before. The relay here is the real [NostrRelay]; what
 * it is sent arrives as frames on its socket listener.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LaterSubscriptionRepoTest {
    private val author = NostrIdentity.generate()

    @Test
    fun aSecondLookAtAPlaceShowsItsNotesAgain() = withRelay { relay, relaySends, scope, dispatcher ->
        val repo = locationRepo(relay, scope, dispatcher)
        val note = author.signEvent(
            NostrEvent(pubkey = author.publicKeyHex, createdAt = 1_780_000_000, kind = NostrKind.TEXT_NOTE, tags = listOf(listOf("g", PLACE)), content = "a note")
        )

        val firstLook = async { repo.getNotes(PLACE) }
        runCurrent()
        relaySends(note, "the-relay's-answer")
        advanceTimeBy(3.seconds)
        assertEquals(listOf("a note"), firstLook.await().map { it.content })

        val secondLook = async { repo.getNotes(PLACE) }
        runCurrent()
        relaySends(note, "the-relay's-answer")
        advanceTimeBy(3.seconds)
        assertEquals(listOf("a note"), secondLook.await().map { it.content }, "the note is still stored at the relay and was sent again")
    }

    @Test
    fun aSearchForASecondChannelFindsItAfterAnotherWasSearchedFor() = withRelay { relay, relaySends, scope, dispatcher ->
        val repo = chatRepo(scope, dispatcher, mutableListOf(), relay = relay)
        val one = channel("#one")
        val two = channel("#two")

        val first = async { repo.discoverNamedChannel("one") }
        runCurrent()
        // A relay answers a search with every channel it knows of; the app picks the one it wants.
        relaySends(two, NostrSubscriptionId.channelCreations("#one"))
        relaySends(one, NostrSubscriptionId.channelCreations("#one"))
        advanceTimeBy(6.seconds)
        assertEquals(one.id, first.await()?.nostrEventId)

        val second = async { repo.discoverNamedChannel("two") }
        runCurrent()
        relaySends(two, NostrSubscriptionId.channelCreations("#two"))
        relaySends(one, NostrSubscriptionId.channelCreations("#two"))
        advanceTimeBy(6.seconds)
        assertEquals(two.id, second.await()?.nostrEventId, "the channel exists and the relay said so again")
    }

    private fun channel(name: String) = NostrEvent.createChannelCreation(
        channelName = name,
        publicKeyHex = author.publicKeyHex,
        privateKeyHex = author.privateKeyHex,
        createdAt = 1_780_000_000,
    )

    private fun locationRepo(relay: NostrRelay, scope: CoroutineScope, dispatcher: CoroutineContext) = LocationRepo(
        nostrClient = mockk(relaxed = true),
        nostrRelay = relay,
        nostrPreferences = mockk(relaxed = true),
        geohashPreferences = mockk(relaxed = true),
        locationService = mockk(relaxed = true),
        bookmarkPrefs = mockk(relaxed = true),
        participantTracker = mockk(relaxed = true),
        geocoder = mockk(relaxed = true),
        coroutinesContextFacade = object : CoroutinesContextFacade {
            override val io: CoroutineContext = dispatcher
            override val main: CoroutineContext = dispatcher
            override val default: CoroutineContext = dispatcher
            override val unconfined: CoroutineContext = dispatcher
        },
        coroutineScopeFacade = object : CoroutineScopeFacade {
            override val applicationScope: CoroutineScope = scope
            override val connectivityEventScope: CoroutineScope = scope
            override val nostrScope: CoroutineScope = scope
        },
        locationEventBus = mockk(relaxed = true),
    )

    private fun withRelay(
        test: suspend TestScope.(NostrRelay, relaySends: (NostrEvent, subscriptionId: String) -> Unit, CoroutineScope, CoroutineContext) -> Unit,
    ) = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val listeners = mutableListOf<NostrWebSocketListener>()
        val socket = mockk<NostrWebSocketClient>(relaxed = true).also {
            every { it.isConnected(any()) } returns false
            every { it.isConnecting(any()) } returns false
            every { it.connect(any(), any(), any(), any(), any()) } answers { listeners += secondArg<NostrWebSocketListener>() }
        }
        val relay = NostrRelay(
            eventDeduplicator = NostrEventDeduplicator(),
            wsClient = socket,
            relayCache = InMemoryCache<String, RelayInfo>(),
            scope = CoroutineScope(Dispatchers.Unconfined),
        )
        try {
            relay.ensureDefaultRelaysConnected()
            test(relay, { event, subscriptionId ->
                val frame = """["EVENT",${Json.encodeToString(subscriptionId)},${Json.encodeToString(NostrEvent.serializer(), event)}]"""
                listeners.first().onMessage("wss://relay.example", frame)
            }, scope, dispatcher)
        } finally {
            scope.cancel()
        }
    }

    private companion object {
        const val PLACE = "u4pruy"
    }
}
