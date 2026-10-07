package com.bitchat.nostr

import com.bitchat.cache.impl.InMemoryCache
import com.bitchat.client.websocket.NostrWebSocketClient
import com.bitchat.client.websocket.NostrWebSocketListener
import com.bitchat.nostr.model.NostrFilter
import com.bitchat.nostr.model.NostrKind
import com.bitchat.nostr.model.RelayInfo
import com.bitchat.nostr.util.NostrEventDeduplicator
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime

/**
 * The socket client drops what a relay sends beyond its limit and says so once that relay's backlog
 * is empty again. Nobody knows what was dropped, and it may have been a private message, so the
 * relay is asked again. The clock here is the test's own.
 */
@OptIn(ExperimentalCoroutinesApi::class, ExperimentalTime::class)
class NostrRelayResubscribeTest {
    private val scheduler = TestCoroutineScheduler()
    private val listeners = mutableMapOf<String, NostrWebSocketListener>()
    private val sent = mutableListOf<Pair<String, String>>()
    private val socket = mockk<NostrWebSocketClient>(relaxed = true).also {
        every { it.isConnected(any()) } returns false
        every { it.isConnecting(any()) } returns false
        every { it.connect(any(), any(), any(), any(), any()) } answers { listeners[firstArg()] = secondArg() }
        coEvery { it.send(any(), any()) } answers { sent += firstArg<String>() to secondArg<String>() }
    }
    private val relay = NostrRelay(
        eventDeduplicator = NostrEventDeduplicator(),
        wsClient = socket,
        relayCache = InMemoryCache<String, RelayInfo>(),
        // The relay as the app builds it, on a clock and a scope that only this test moves.
        scope = TestScope(UnconfinedTestDispatcher(scheduler)),
        timeSource = scheduler.timeSource,
    )

    init {
        relay.ensureDefaultRelaysConnected()
        relay.subscribe("for-every-relay", NOTES, handler = {})
        relay.subscribe("for-damus", NOTES, handler = {}, targetRelayUrls = setOf(DAMUS))
        relay.subscribe("for-primal", NOTES, handler = {}, targetRelayUrls = setOf(PRIMAL))
        sent.clear()
    }

    @Test
    fun `a relay that had frames dropped is sent its subscriptions again`() {
        listeners.getValue(DAMUS).onBacklogDrained(DAMUS, 92)

        assertEquals(setOf(DAMUS to "for-every-relay", DAMUS to "for-damus"), requests(), "its own subscriptions, and no other relay's")
    }

    @Test
    fun `a relay is asked again at most once a minute and a request that comes sooner is not forgotten`() {
        listeners.getValue(DAMUS).onBacklogDrained(DAMUS, 92)
        assertEquals(2, requests().size)
        sent.clear()

        scheduler.advanceTimeBy(10.seconds)
        listeners.getValue(DAMUS).onBacklogDrained(DAMUS, 5)
        scheduler.advanceTimeBy(10.seconds)
        listeners.getValue(DAMUS).onBacklogDrained(DAMUS, 7)
        scheduler.advanceTimeBy(40.seconds - 1.milliseconds)
        scheduler.runCurrent()
        assertEquals(emptySet(), requests(), "not a minute yet since it was last asked")

        scheduler.advanceTimeBy(1.milliseconds)
        scheduler.runCurrent()
        assertEquals(setOf(DAMUS to "for-every-relay", DAMUS to "for-damus"), requests(), "the minute is over: asked, once, for both that came too soon")
        assertEquals(2, sent.size)
        sent.clear()

        scheduler.advanceTimeBy(5.minutes)
        assertEquals(emptySet(), requests(), "and not again until frames are dropped again")
        listeners.getValue(DAMUS).onBacklogDrained(DAMUS, 1)
        assertEquals(2, sent.size, "long after the last time there is nothing to wait for")
    }

    @Test
    fun `one relay being asked again does not make another wait`() {
        listeners.getValue(DAMUS).onBacklogDrained(DAMUS, 92)
        sent.clear()

        scheduler.advanceTimeBy(1.seconds)
        listeners.getValue(PRIMAL).onBacklogDrained(PRIMAL, 3)

        assertEquals(setOf(PRIMAL to "for-every-relay", PRIMAL to "for-primal"), requests())
    }

    /** The REQ messages sent since the list was last cleared, as (relay, subscription id). */
    private fun requests(): Set<Pair<String, String>> = sent.mapNotNull { (url, message) ->
        val parts = Json.parseToJsonElement(message).jsonArray
        if (parts[0].jsonPrimitive.content == "REQ") url to parts[1].jsonPrimitive.content else null
    }.toSet()

    private companion object {
        const val DAMUS = "wss://relay.damus.io"
        const val PRIMAL = "wss://relay.primal.net"
        val NOTES = NostrFilter(kinds = listOf(NostrKind.TEXT_NOTE))
    }
}
