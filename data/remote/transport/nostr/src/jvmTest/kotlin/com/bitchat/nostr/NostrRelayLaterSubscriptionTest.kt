package com.bitchat.nostr

import com.bitchat.cache.impl.InMemoryCache
import com.bitchat.client.websocket.NostrWebSocketClient
import com.bitchat.client.websocket.NostrWebSocketListener
import com.bitchat.nostr.model.NostrEvent
import com.bitchat.nostr.model.NostrFilter
import com.bitchat.nostr.model.NostrIdentity
import com.bitchat.nostr.model.NostrKind
import com.bitchat.nostr.model.RelayInfo
import com.bitchat.nostr.util.NostrEventDeduplicator
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A relay answers every REQ with what it has stored, so an event this client has seen comes again
 * whenever something subscribes anew: a second look at a place's notes, a search for another
 * channel, a chat opened for a geohash that was only being counted. Whoever subscribes later has to
 * get it; whoever already had it must not get it twice.
 */
class NostrRelayLaterSubscriptionTest {
    private val alice = NostrIdentity.generate()
    private val mallory = NostrIdentity.generate()

    private val listeners = mutableListOf<NostrWebSocketListener>()
    private val socket = mockk<NostrWebSocketClient>(relaxed = true).also {
        every { it.isConnected(any()) } returns false
        every { it.isConnecting(any()) } returns false
        every { it.connect(any(), any(), any(), any(), any()) } answers { listeners += secondArg<NostrWebSocketListener>() }
    }
    /** Runs while an event is being verified: after the gate found it owed, before it is handed on. */
    private var whileVerifying: () -> Unit = {}
    private val relay = NostrRelay(
        eventDeduplicator = NostrEventDeduplicator(maxCapacity = 100) { event ->
            whileVerifying()
            event.isValidSignature()
        },
        wsClient = socket,
        relayCache = InMemoryCache<String, RelayInfo>(),
        scope = CoroutineScope(Dispatchers.Unconfined),
    )

    init {
        relay.ensureDefaultRelaysConnected()
    }

    @Test
    fun `a subscription made after the first one ended gets what the relay sends again`() {
        val note = alice.note("first")
        val firstLook = mutableListOf<NostrEvent>()
        val secondLook = mutableListOf<NostrEvent>()

        relay.subscribe("notes", NOTES, handler = { firstLook += it })
        relaySends(note, "notes")
        relay.unsubscribe("notes")
        // The same id again, as a second look at the same place uses it; the relay answers as before.
        relay.subscribe("notes", NOTES, handler = { secondLook += it })
        relaySends(note, "notes")

        assertEquals(listOf(note), firstLook)
        assertEquals(listOf(note), secondLook)
    }

    @Test
    fun `a subscription made while another still runs gets what the relay sends again and the other gets nothing twice`() {
        val note = alice.note("first")
        val counting = mutableListOf<NostrEvent>()
        val chat = mutableListOf<NostrEvent>()

        relay.subscribe("counting", NOTES, handler = { counting += it })
        relaySends(note, "counting")
        relay.subscribe("chat", NOTES, handler = { chat += it })
        relaySends(note, "chat")
        relaySends(note, "chat", from = 1)

        assertEquals(listOf(note), chat)
        assertEquals(listOf(note), counting)
    }

    @Test
    fun `a relay answering the same subscription again hands nothing on twice`() {
        val note = alice.note("first")
        val seen = mutableListOf<NostrEvent>()
        relay.subscribe("notes", NOTES, handler = { seen += it })

        // What a reconnect looks like: the same subscription asked again, the stored events sent again.
        relaySends(note, "notes")
        relaySends(note, "notes")
        relaySends(note, "notes", from = 1)

        assertEquals(listOf(note), seen)
    }

    @Test
    fun `a copy that does not verify is not what a later subscription gets and does not stop the real one`() {
        val note = alice.note("first")
        val firstLook = mutableListOf<NostrEvent>()
        val secondLook = mutableListOf<NostrEvent>()
        relay.subscribe("first", NOTES, handler = { firstLook += it })
        relaySends(note, "first")

        relay.subscribe("second", NOTES, handler = { secondLook += it })
        // The id is known to be genuine; what arrives under it now is not the event it belongs to.
        relaySends(note.copy(content = "something else"), "second")
        relaySends(mallory.note("mallory's").copy(id = note.id), "second")
        assertEquals(emptyList(), secondLook)

        relaySends(note, "second", from = 1)
        assertEquals(listOf(note), secondLook)
        assertEquals(listOf(note), firstLook)
    }

    @Test
    fun `a handler added under an id that is still subscribed is not owed what the first one had`() {
        val note = alice.note("first")
        val first = mutableListOf<NostrEvent>()
        val added = mutableListOf<NostrEvent>()
        relay.subscribe("notes", NOTES, handler = { first += it })
        relaySends(note, "notes")

        // The same subscription asked for again: it carries on, with one more handler.
        relay.subscribe("notes", NOTES, handler = { added += it })
        relaySends(note, "notes")
        val next = alice.note("second")
        relaySends(next, "notes")

        assertEquals(listOf(note, next), first)
        assertEquals(listOf(next), added)
    }

    @Test
    fun `an id asked for other events is a new subscription and gets what the relay stores of them`() {
        val note = alice.note("first")
        val everything = mutableListOf<NostrEvent>()
        val notesOnly = mutableListOf<NostrEvent>()
        val askedForSomethingElse = mutableListOf<NostrEvent>()
        // The older of the two subscriptions, asking for something else at first.
        relay.subscribe("mine", NostrFilter(kinds = listOf(NostrKind.CHANNEL_MESSAGE)), handler = { askedForSomethingElse += it })
        relay.subscribe("everything", NostrFilter(kinds = listOf(NostrKind.TEXT_NOTE)), handler = { everything += it })
        relaySends(note, "everything")

        // Still subscribed, and asked again under the same id for notes: not the subscription it was.
        relay.subscribe("mine", NOTES, handler = { notesOnly += it })
        relaySends(note, "mine")

        assertEquals(listOf(note), notesOnly)
        assertEquals(listOf(note), everything)
        assertEquals(emptyList(), askedForSomethingElse, "the handler of the subscription this one replaced")
    }

    @Test
    fun `a handler added while an event is verified gets it with the others`() {
        val note = alice.note("first")
        val first = mutableListOf<NostrEvent>()
        val added = mutableListOf<NostrEvent>()
        relay.subscribe("notes", NOTES, handler = { first += it })
        whileVerifying = {
            whileVerifying = {}
            relay.subscribe("notes", NOTES, handler = { added += it })
        }

        relaySends(note, "notes")
        relaySends(note, "notes", from = 1)

        // Nobody had it yet when the handler joined: the subscription is handed it once, all of it.
        assertEquals(listOf(note), first)
        assertEquals(listOf(note), added)
    }

    @Test
    fun `an id given up and taken again while its event is verified does not get that event twice`() {
        val note = alice.note("first")
        val firstLook = mutableListOf<NostrEvent>()
        val secondLook = mutableListOf<NostrEvent>()
        relay.subscribe("notes", NOTES, handler = { firstLook += it })
        whileVerifying = {
            whileVerifying = {}
            relay.unsubscribe("notes")
            relay.subscribe("notes", NOTES, handler = { secondLook += it })
        }

        // Found owed to the first look, which is gone by the time it could be handed over.
        relaySends(note, "notes")
        // The second look is owed the relay's answer to it, once.
        relaySends(note, "notes")
        relaySends(note, "notes", from = 1)

        assertEquals(emptyList(), firstLook)
        assertEquals(listOf(note), secondLook)
    }

    private fun relaySends(event: NostrEvent, subscriptionId: String, from: Int = 0) {
        val frame = """["EVENT",${Json.encodeToString(subscriptionId)},${Json.encodeToString(NostrEvent.serializer(), event)}]"""
        listeners[from].onMessage("wss://relay-$from.example", frame)
    }

    private fun NostrIdentity.note(content: String): NostrEvent = signEvent(
        NostrEvent(
            pubkey = publicKeyHex,
            createdAt = 1_780_000_000,
            kind = NostrKind.TEXT_NOTE,
            tags = listOf(listOf("g", "u4pruy")),
            content = content,
        )
    )

    private companion object {
        val NOTES = NostrFilter(kinds = listOf(NostrKind.TEXT_NOTE), tagFilters = mapOf("g" to listOf("u4pruy")))
    }
}
