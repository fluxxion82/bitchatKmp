package com.bitchat.nostr

import com.bitchat.cache.impl.InMemoryCache
import com.bitchat.client.websocket.NostrWebSocketClient
import com.bitchat.client.websocket.NostrWebSocketListener
import com.bitchat.domain.base.LogPolicy
import com.bitchat.nostr.model.NostrEvent
import com.bitchat.nostr.model.NostrFilter
import com.bitchat.nostr.model.NostrIdentity
import com.bitchat.nostr.model.NostrKind
import com.bitchat.nostr.model.RelayInfo
import com.bitchat.nostr.util.NostrEventDeduplicator
import com.bitchat.nostr.util.RejectedEventLog
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TestTimeSource

/**
 * What a relay can make a subscription handler see. A relay is not trusted: whatever it sends under
 * "EVENT" is only a claim until the id is recomputed from the fields and the signature checked
 * against the key the event names. Everything here goes through the relay's own message path.
 */
class NostrRelayEventVerificationTest {
    private val alice = NostrIdentity.generate()
    private val mallory = NostrIdentity.generate()

    private val listeners = mutableListOf<NostrWebSocketListener>()
    private val wsClient = mockk<NostrWebSocketClient>(relaxed = true).also {
        every { it.isConnected(any()) } returns false
        every { it.isConnecting(any()) } returns false
        every { it.connect(any(), any(), any(), any(), any()) } answers { listeners += secondArg<NostrWebSocketListener>() }
    }
    private val time = TestTimeSource()
    private val relay = NostrRelay(
        eventDeduplicator = NostrEventDeduplicator(),
        wsClient = wsClient,
        relayCache = InMemoryCache<String, RelayInfo>(),
        relayLogSink = null,
        torProxyStatus = null,
        scope = CoroutineScope(Dispatchers.Unconfined),
        // On a clock that only this test moves, so "one line" does not depend on how fast it runs.
        rejectedEvents = RejectedEventLog(interval = 1.minutes, timeSource = time),
    )
    private val seenInGeohash = mutableListOf<NostrEvent>()
    private val seenAsNotes = mutableListOf<NostrEvent>()
    private val seenInChannel = mutableListOf<NostrEvent>()

    init {
        relay.ensureDefaultRelaysConnected()
        relay.subscribe(
            subscriptionId = GEOHASH_SUBSCRIPTION,
            filter = NostrFilter(kinds = listOf(NostrKind.EPHEMERAL_EVENT), tagFilters = mapOf("g" to listOf(GEOHASH))),
            handler = { seenInGeohash += it },
        )
        relay.subscribe(
            subscriptionId = NOTES_SUBSCRIPTION,
            filter = NostrFilter(kinds = listOf(NostrKind.TEXT_NOTE), tagFilters = mapOf("g" to listOf(GEOHASH))),
            handler = { seenAsNotes += it },
        )
        relay.subscribeToChannelMessages(CHANNEL_EVENT_ID, handler = { seenInChannel += it })
    }

    @Test
    fun `a signed geohash event reaches its handler`() {
        val genuine = alice.geohashEvent("hello")

        relaySends(genuine)

        assertEquals(listOf(genuine), seenInGeohash)
    }

    @Test
    fun `a signed geohash event sent by several relays reaches its handler once`() {
        val genuine = alice.geohashEvent("hello")

        relaySends(genuine)
        relaySends(genuine, from = 1)
        relaySends(genuine, from = 2)

        assertEquals(listOf(genuine), seenInGeohash)
    }

    @Test
    fun `a geohash event whose signature does not verify reaches no handler`() {
        val forged = alice.geohashEvent("hello").copy(sig = alice.geohashEvent("something else").sig)

        relaySends(forged)

        assertEquals(emptyList(), seenInGeohash)
    }

    @Test
    fun `a geohash event whose id is not the hash of its fields reaches no handler`() {
        val altered = alice.geohashEvent("hello").copy(content = "send me your keys")

        relaySends(altered)

        assertEquals(emptyList(), seenInGeohash)
    }

    @Test
    fun `a geohash event posted under a key that did not sign it reaches no handler`() {
        // What a relay that wants to speak as alice sends: her key, a correct id, a signature it can make.
        val asAlice = mallory.geohashEvent("hello").copy(pubkey = alice.publicKeyHex).let { it.copy(id = it.computeEventIdHex()) }

        relaySends(asAlice)

        assertEquals(emptyList(), seenInGeohash)
    }

    @Test
    fun `a geohash event with no signature reaches no handler`() {
        val unsigned = alice.geohashEvent("hello").copy(sig = null)

        relaySends(unsigned)

        assertEquals(emptyList(), seenInGeohash)
    }

    @Test
    fun `an id of zeros nobody mined reaches no handler`() {
        val freeWork = alice.geohashEvent("hello", listOf("nonce", "1", "32")).copy(id = "0".repeat(64))

        relaySends(freeWork)

        assertEquals(emptyList(), seenInGeohash)
    }

    @Test
    fun `a forged copy carrying a real geohash event's id does not stop the real one`() {
        val genuine = alice.geohashEvent("hello")
        val decoy = mallory.geohashEvent("something else").copy(id = genuine.id)

        relaySends(decoy)
        relaySends(genuine, from = 1)

        assertEquals(listOf(genuine), seenInGeohash)
    }

    @Test
    fun `a location note that does not verify reaches no handler`() {
        val genuine = alice.signed(NostrKind.TEXT_NOTE, "a note", listOf("g", GEOHASH))

        relaySends(genuine.copy(content = "another note"), NOTES_SUBSCRIPTION)
        assertEquals(emptyList(), seenAsNotes)

        relaySends(genuine, NOTES_SUBSCRIPTION)
        assertEquals(listOf(genuine), seenAsNotes)
    }

    @Test
    fun `a channel message that does not verify reaches no handler`() {
        val genuine = alice.signed(NostrKind.CHANNEL_MESSAGE, "in the channel", listOf("e", CHANNEL_EVENT_ID, "", "root"))
        val subscription = NostrSubscriptionId.channelMessages(CHANNEL_EVENT_ID)

        relaySends(genuine.copy(pubkey = mallory.publicKeyHex), subscription)
        assertEquals(emptyList(), seenInChannel)

        relaySends(genuine, subscription)
        assertEquals(listOf(genuine), seenInChannel)
    }

    @Test
    fun `half a surrogate pair written as an escape on the wire is refused`() {
        val genuine = alice.geohashEvent("who?")
        // The six characters a relay puts in the JSON where the question mark was. On the JVM the
        // stray half is encoded as a question mark, so the altered event hashes like the real one.
        val escape = "\\" + "ud800"
        val altered = frame(genuine, GEOHASH_SUBSCRIPTION).replace("who?", "who$escape")

        listeners[0].onMessage("wss://relay-0.example", altered)
        assertEquals(emptyList(), seenInGeohash)

        relaySends(genuine, from = 1)
        assertEquals(listOf(genuine), seenInGeohash)
    }

    @Test
    fun `an event sent before anyone subscribed to it is delivered once someone does`() {
        val elsewhere = alice.signed(NostrKind.EPHEMERAL_EVENT, "hello", listOf("g", OTHER_GEOHASH))
        val seenElsewhere = mutableListOf<NostrEvent>()

        // A relay can send what nobody asked for. Were its id recorded now, the event would be a
        // duplicate by the time its subscription exists.
        relaySends(elsewhere, "a-subscription-nobody-made")
        relay.subscribe(
            subscriptionId = "geohash-$OTHER_GEOHASH",
            filter = NostrFilter(kinds = listOf(NostrKind.EPHEMERAL_EVENT), tagFilters = mapOf("g" to listOf(OTHER_GEOHASH))),
            handler = { seenElsewhere += it },
        )
        relaySends(elsewhere, "geohash-$OTHER_GEOHASH", from = 1)

        assertEquals(listOf(elsewhere), seenElsewhere)
        assertEquals(emptyList(), seenInGeohash)
    }

    @Test
    fun `events that do not verify leave one line in the log and nothing of what they claimed`() {
        val forged = (0 until 50).map { alice.geohashEvent("claimed-content-$it").copy(sig = null) }

        val printed = printedWhile { forged.forEach { relaySends(it) } }
        time += 1.minutes
        val printedLater = printedWhile { relaySends(forged.first()) }

        assertEquals(1, printed.lines().count { "failed id or signature verification" in it }, printed)
        assertEquals(1, printed.lines().count { it.isNotBlank() }, printed)
        assertTrue("dropped 50 event(s)" in printedLater, printedLater)
        assertFalse("claimed-content" in printed, printed)
        assertFalse(alice.publicKeyHex.take(16) in printed, printed)
        assertFalse(forged.any { it.id.take(16) in printed }, printed)
    }

    @Test
    fun `text a relay or an author chose cannot start a log line of its own or reach the terminal as a control sequence`() {
        val newLine = Char(10)
        val escape = Char(27)
        // A real event: its first "g" tag is the author's free text, a later one is the geohash.
        val event = alice.signed(
            NostrKind.EPHEMERAL_EVENT,
            "hello${newLine}FAKE-FROM-CONTENT$escape[2J",
            listOf("g", "u4${newLine}FAKE-FROM-AUTHOR$escape[2J"),
            listOf("g", GEOHASH),
        )

        // With the developer switch that lets message text into the log at all.
        LogPolicy.configure("1")
        val printed = try {
            printedWhile {
                listeners[0].onMessage("wss://relay-0.example", frame(event, "sub${newLine}FAKE-FROM-RELAY$escape[2J"))
                listeners[0].onMessage("wss://relay-0.example", """["NOTICE",${Json.encodeToString("slow down${newLine}FAKE-FROM-NOTICE$escape[2J")}]""")
            }
        } finally {
            LogPolicy.configure(null)
        }

        assertEquals(listOf(event), seenInGeohash)
        listOf("FAKE-FROM-CONTENT", "FAKE-FROM-AUTHOR", "FAKE-FROM-RELAY", "FAKE-FROM-NOTICE").forEach { chosen ->
            assertTrue(chosen in printed, "$chosen is still reported: $printed")
            assertFalse(printed.lines().any { it.startsWith(chosen) }, "$chosen starts a line: $printed")
        }
        assertFalse(escape in printed, printed)
    }

    private fun printedWhile(block: () -> Unit): String {
        val standardOut = System.out
        val captured = ByteArrayOutputStream()
        System.setOut(PrintStream(captured, true, Charsets.UTF_8))
        try {
            block()
        } finally {
            System.setOut(standardOut)
        }
        return captured.toString(Charsets.UTF_8)
    }

    private fun relaySends(event: NostrEvent, subscriptionId: String = GEOHASH_SUBSCRIPTION, from: Int = 0) {
        listeners[from].onMessage("wss://relay-$from.example", frame(event, subscriptionId))
    }

    private fun frame(event: NostrEvent, subscriptionId: String) =
        """["EVENT",${Json.encodeToString(subscriptionId)},${Json.encodeToString(NostrEvent.serializer(), event)}]"""

    private fun NostrIdentity.geohashEvent(content: String, vararg extraTags: List<String>): NostrEvent =
        signed(NostrKind.EPHEMERAL_EVENT, content, listOf("g", GEOHASH), listOf("n", "alice"), *extraTags)

    private fun NostrIdentity.signed(kind: Int, content: String, vararg tags: List<String>): NostrEvent = signEvent(
        NostrEvent(
            pubkey = publicKeyHex,
            createdAt = 1_780_000_000,
            kind = kind,
            tags = tags.toList(),
            content = content,
        )
    )

    private companion object {
        const val GEOHASH = "u4pruy"
        const val OTHER_GEOHASH = "9q8yy"
        const val GEOHASH_SUBSCRIPTION = "geohash-u4pruy"
        const val NOTES_SUBSCRIPTION = "notes-u4pruy"
        val CHANNEL_EVENT_ID = "c".repeat(64)
    }
}
