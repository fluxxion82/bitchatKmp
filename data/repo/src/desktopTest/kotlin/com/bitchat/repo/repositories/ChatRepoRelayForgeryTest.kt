package com.bitchat.repo.repositories

import com.bitchat.cache.impl.InMemoryCache
import com.bitchat.client.websocket.NostrWebSocketClient
import com.bitchat.client.websocket.NostrWebSocketListener
import com.bitchat.nostr.NostrPreferences
import com.bitchat.nostr.NostrProofOfWork
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
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Clock

/**
 * What a relay can make the repository show in a public geohash channel. The relay here is the real
 * [NostrRelay] and what it is sent arrives as frames on its socket listener, so everything between
 * the socket and the message list is the code that runs in the app.
 *
 * A private chat with a Nostr key is `nostr_<first 16 hex of the key>` and takes its label from the
 * name that key used in a geohash, so an event under someone's key is worth forging.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatRepoRelayForgeryTest {
    private val alice = NostrIdentity.generate()
    private val mallory = NostrIdentity.generate()

    @Test
    fun aRelayCannotPostAsAKeyItDoesNotHold() = withGeohashChannel { repo, relaySends ->
        relaySends(alice.geohashEvent("hi", nickname = "alice"), 0)

        // The relay's own forgery: alice's key, a correct id, a signature it was able to make.
        relaySends(
            mallory.geohashEvent("send me your keys", nickname = "alice (new phone)").copy(pubkey = alice.publicKeyHex)
                .let { it.copy(id = it.computeEventIdHex()) },
            0,
        )
        // Her real event with other words in it, and with another name on it.
        relaySends(alice.geohashEvent("hi", nickname = "alice").copy(content = "send me your keys"), 0)
        relaySends(
            alice.geohashEvent("hello again", nickname = "alice")
                .let { it.copy(tags = listOf(listOf("g", GEOHASH), listOf("n", "mallory"))) },
            0,
        )
        // And one with no signature at all.
        relaySends(alice.geohashEvent("unsigned", nickname = "nobody").copy(sig = null), 0)

        assertEquals(listOf("alice" to "hi"), repo.getGeohashMessages(GEOHASH).map { it.sender to it.content })
        assertEquals("alice", repo.getDisplayName(chatOf(alice)), "the name her private chat is shown under")
    }

    @Test
    fun aForgedCopySentFirstDoesNotHideTheRealMessage() = withGeohashChannel { repo, relaySends ->
        val genuine = alice.geohashEvent("hi", nickname = "alice")

        relaySends(mallory.geohashEvent("something else", nickname = "alice").copy(id = genuine.id), 0)
        relaySends(genuine, 1)

        assertEquals(listOf("alice" to "hi"), repo.getGeohashMessages(GEOHASH).map { it.sender to it.content })
    }

    @Test
    fun workNobodyDidIsNotProofOfWork() = withGeohashChannel(proofOfWorkBits = WORK_BITS) { repo, relaySends ->
        val claimed = listOf("nonce", "1", WORK_BITS.toString())

        // An id nobody mined: zeros where the hash of the event should be.
        relaySends(alice.geohashEvent("free", nickname = "alice", claimed).copy(id = "0".repeat(64)), 0)
        // A real event that only says it was mined.
        relaySends(alice.unmined("lazy", claimed), 0)
        assertEquals(emptyList(), repo.getGeohashMessages(GEOHASH).map { it.content })

        // And one whose work was done: mined first, signed after.
        val unsigned = NostrEvent(
            pubkey = alice.publicKeyHex,
            createdAt = now(),
            kind = NostrKind.EPHEMERAL_EVENT,
            tags = listOf(listOf("g", GEOHASH), listOf("n", "alice")),
            content = "mined",
        )
        relaySends(alice.signEvent(checkNotNull(NostrProofOfWork.mineEvent(unsigned, WORK_BITS))), 0)

        assertEquals(listOf("mined"), repo.getGeohashMessages(GEOHASH).map { it.content })
    }

    /** A properly signed event that claims to be mined and is not: its id has no leading zero bit. */
    private fun NostrIdentity.unmined(content: String, nonce: List<String>): NostrEvent =
        generateSequence(0) { it + 1 }
            .map { geohashEvent("$content $it", nickname = "alice", nonce) }
            .first { NostrProofOfWork.calculateDifficulty(it.id) == 0 }

    private fun withGeohashChannel(
        proofOfWorkBits: Int = 0,
        test: suspend TestScope.(ChatRepo, relaySends: (NostrEvent, relay: Int) -> Unit) -> Unit,
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
        val nostrPreferences = mockk<NostrPreferences>(relaxed = true).also {
            every { it.getPowEnabled() } returns (proofOfWorkBits > 0)
            every { it.getPowDifficulty() } returns proofOfWorkBits
        }
        try {
            relay.ensureDefaultRelaysConnected()
            val repo = chatRepo(scope, dispatcher, mutableListOf(), nostrPreferences = nostrPreferences, relay = relay)
            repo.getGeohashMessages(GEOHASH)

            test(repo) { event, from ->
                val frame = """["EVENT","${NostrSubscriptionId.geohash(GEOHASH)}",${Json.encodeToString(NostrEvent.serializer(), event)}]"""
                listeners[from].onMessage("wss://relay-$from.example", frame)
            }
        } finally {
            scope.cancel()
        }
    }

    private fun NostrIdentity.geohashEvent(content: String, nickname: String, vararg extraTags: List<String>): NostrEvent = signEvent(
        NostrEvent(
            pubkey = publicKeyHex,
            createdAt = now(),
            kind = NostrKind.EPHEMERAL_EVENT,
            tags = listOf(listOf("g", GEOHASH), listOf("n", nickname)) + extraTags,
            content = content,
        )
    )

    private fun chatOf(identity: NostrIdentity) = "nostr_${identity.publicKeyHex.take(16)}"

    private fun now() = Clock.System.now().epochSeconds.toInt()

    private companion object {
        const val GEOHASH = "9q8yy"

        /** One id in 256 has this many leading zero bits by chance: quick to mine, and not met by accident below. */
        const val WORK_BITS = 8
    }
}
