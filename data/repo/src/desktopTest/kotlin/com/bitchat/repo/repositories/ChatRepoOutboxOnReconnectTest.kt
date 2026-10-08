package com.bitchat.repo.repositories

import com.bitchat.bluetooth.model.PeerInfo
import com.bitchat.bluetooth.service.BluetoothMeshService
import com.bitchat.domain.chat.model.BitchatMessageType
import com.bitchat.domain.chat.model.PrivateMessageText
import com.bitchat.domain.location.model.Channel
import com.bitchat.domain.user.model.FavoriteRelationship
import com.bitchat.local.prefs.UserPreferences
import com.bitchat.lora.LoRaPeer
import com.bitchat.lora.LoRaProtocol
import com.bitchat.lora.radio.LoRaConfig
import com.bitchat.nostr.NostrTransport
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * A private text for a mesh peer that cannot go at once waits in a queue. A handshake that completes
 * sends the queue. The return of a peer whose session never went away must send it too: the mesh
 * service starts no handshake over a standing session, so none will complete for it. A peer returns
 * on the mesh's peer list, or, when the radio is its way out, among the peers the radio hears.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatRepoOutboxOnReconnectTest {

    @Test
    fun whatWasQueuedWhileAPeerWithASessionWasGoneGoesOutInOrderWhenThePeerIsBack() = withRepo { repo, mesh, _, peers ->
        // The session stands; the peer has dropped out of the mesh's table.
        every { mesh.hasEstablishedSession(PEER) } returns true
        val sent = mesh.recordPrivateSends()

        repo.sendMessage("first", Channel.MeshDM(PEER), "me", BitchatMessageType.Message)
        repo.sendMessage("second", Channel.MeshDM(PEER), "me", BitchatMessageType.Message)

        assertTrue(sent.isEmpty(), "the peer is gone: both wait")
        // Asked for, and with a session standing the service starts nothing for it.
        verify(exactly = 2) { mesh.initiateNoiseHandshake(PEER) }

        peers[PEER] = peer(connected = true)
        repo.didUpdatePeerList(listOf(PEER))

        val rows = repo.getPrivateChats().getValue(PEER)
        assertEquals(listOf("first", "second"), rows.map { it.content })
        assertEquals(rows.map { Sent(it.content, PEER, it.id) }, sent)

        repo.didUpdatePeerList(listOf(PEER))
        assertEquals(2, sent.size, "each went out once")
    }

    @Test
    fun whatWasQueuedWhileAPeerWithASessionWasMarkedDisconnectedGoesOutWhenItIsConnectedAgain() = withRepo { repo, mesh, _, peers ->
        every { mesh.hasEstablishedSession(PEER) } returns true
        peers[PEER] = peer(connected = false)
        val sent = mesh.recordPrivateSends()

        repo.sendMessage("hello", Channel.MeshDM(PEER), "me", BitchatMessageType.Message)
        // The list changes for some other reason while the peer is still away.
        repo.didUpdatePeerList(listOf(PEER))
        assertTrue(sent.isEmpty(), "the peer is still away")

        peers[PEER] = peer(connected = true)
        repo.didUpdatePeerList(listOf(PEER))

        assertEquals(listOf("hello"), sent.map { it.content })
    }

    @Test
    fun aPeerThatIsBackWithoutASessionStillWaitsForItsHandshake() = withRepo(reachedThroughARelayToo()) { repo, mesh, nostr, peers ->
        peers[PEER] = peer(connected = true)
        val sent = mesh.recordPrivateSends()

        repo.sendMessage("hello", Channel.MeshDM(PEER), "me", BitchatMessageType.Message)
        repo.didUpdatePeerList(listOf(PEER))

        // Not over the mesh without a session, and not through a relay in its place.
        assertTrue(sent.isEmpty())
        verify(exactly = 0) { nostr.sendPrivateMessage(any(), any(), any(), any(), any()) }

        every { mesh.hasEstablishedSession(PEER) } returns true
        repo.onSessionEstablished(PEER)
        assertEquals(listOf("hello"), sent.map { it.content })
    }

    @Test
    fun aPeerThatIsListedButNotConnectedGetsNothingOfWhatIsQueued() = withRepo(reachedThroughARelayToo()) { repo, mesh, nostr, peers ->
        every { mesh.hasEstablishedSession(PEER) } returns true
        peers[PEER] = peer(connected = false)
        val sent = mesh.recordPrivateSends()

        repo.sendMessage("hello", Channel.MeshDM(PEER), "me", BitchatMessageType.Message)
        repo.didUpdatePeerList(listOf(PEER))

        assertTrue(sent.isEmpty())
        verify(exactly = 0) { nostr.sendPrivateMessage(any(), any(), any(), any(), any()) }
    }

    @Test
    fun whatWasQueuedWhileARadioPeerWithASessionWasNotHeardGoesOutInOrderWhenItIsHeardAgain() {
        val radio = RadioStack()
        withRepo(lora = radio) { repo, mesh, _, _ ->
            // The session stands; the radio has not heard the peer for a while and the mesh never had it.
            every { mesh.hasEstablishedSession(PEER) } returns true
            every { mesh.reachesByRadio(PEER) } returns false
            val sent = mesh.recordPrivateSends()

            repo.sendMessage("first", Channel.MeshDM(PEER), "me", BitchatMessageType.Message)
            repo.sendMessage("second", Channel.MeshDM(PEER), "me", BitchatMessageType.Message)
            // Someone else is heard meanwhile.
            radio.hears(OTHER)
            assertTrue(sent.isEmpty(), "the peer is not heard: both wait")

            every { mesh.reachesByRadio(PEER) } returns true
            radio.hears(OTHER, PEER)

            val rows = repo.getPrivateChats().getValue(PEER)
            assertEquals(listOf("first", "second"), rows.map { it.content })
            assertEquals(rows.map { Sent(it.content, PEER, it.id) }, sent)

            // Its next heartbeat.
            radio.hears(PEER, OTHER)
            assertEquals(2, sent.size, "each went out once")
        }
    }

    @Test
    fun aRadioPeerThatIsHeardAgainWithoutASessionStillWaitsForItsHandshake() {
        val radio = RadioStack()
        withRepo(reachedThroughARelayToo(), lora = radio) { repo, mesh, nostr, _ ->
            every { mesh.reachesByRadio(PEER) } returns false
            val sent = mesh.recordPrivateSends()
            repo.sendMessage("hello", Channel.MeshDM(PEER), "me", BitchatMessageType.Message)

            every { mesh.reachesByRadio(PEER) } returns true
            radio.hears(PEER)

            // Not over the radio without a session, and not through a relay in its place.
            assertTrue(sent.isEmpty())
            verify(exactly = 0) { nostr.sendPrivateMessage(any(), any(), any(), any(), any()) }

            every { mesh.hasEstablishedSession(PEER) } returns true
            repo.onSessionEstablished(PEER)
            assertEquals(listOf("hello"), sent.map { it.content })
        }
    }

    @Test
    fun aChangeOfWhatTheRadioHearsIsToldToTheMeshService() {
        val radio = RadioStack()
        withRepo(lora = radio) { _, mesh, _, _ ->
            // Once for what the radio heard when the repository began to listen: nobody.
            verify(exactly = 1) { mesh.onRadioHearsChanged() }

            radio.hears(PEER)
            verify(exactly = 2) { mesh.onRadioHearsChanged() }
        }
    }

    private data class Sent(val content: String, val to: String, val id: String?)

    private fun BluetoothMeshService.recordPrivateSends(): List<Sent> {
        val sent = mutableListOf<Sent>()
        every { sendPrivateMessage(any(), any(), any(), any()) } answers {
            sent += Sent(firstArg(), secondArg(), arg(3))
            true
        }
        return sent
    }

    private fun peer(connected: Boolean) = PeerInfo(PEER, "friend", connected, connected, null, null, false, Clock.System.now())

    /**
     * A mutual favourite with a Nostr key: what is queued for such a peer goes through a relay when
     * the queue is sent while the mesh cannot carry it.
     */
    private fun reachedThroughARelayToo(): UserPreferences = StatefulFavoritePreferences(
        mapOf(PEER to FavoriteRelationship(PEER, "npub1friend", "friend", isFavorite = true, theyFavoritedUs = true, favoritedAt = 1, lastUpdated = 1)),
    ).preferences

    private fun withRepo(
        userPreferences: UserPreferences = emptyUserPreferences(),
        lora: LoRaProtocol? = null,
        block: suspend TestScope.(ChatRepo, BluetoothMeshService, NostrTransport, MutableMap<String, PeerInfo>) -> Unit,
    ) = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val mesh = mockk<BluetoothMeshService>(relaxed = true)
        // A relaxed mock would answer 0: these peers are reached over Bluetooth, where a message carries 255 bytes.
        every { mesh.privateTextLimitFor(any()) } returns PrivateMessageText.MAX_BYTES
        // And false, which the real service says only of a message it cannot take.
        every { mesh.sendPrivateMessage(any(), any(), any(), any()) } returns true
        val peers = mutableMapOf<String, PeerInfo>()
        every { mesh.getPeerInfo(any()) } answers { peers[firstArg<String>()] }
        val nostr = mockk<NostrTransport>(relaxed = true)
        try {
            block(chatRepo(scope, dispatcher, mutableListOf(), lora = lora, mesh = mesh, nostr = nostr, userPreferences = userPreferences), mesh, nostr, peers)
        } finally {
            scope.cancel()
        }
    }

    /** A LoRa stack of which only the peers it hears matter here; what the mesh makes of them is the mesh mock's to say. */
    private class RadioStack : LoRaProtocol {
        private var heartbeats = 0L

        override val peers = MutableStateFlow<List<LoRaPeer>>(emptyList())

        /** The peers heard now, each with a heartbeat later than any before it. */
        fun hears(vararg deviceIds: String) {
            val now = Instant.fromEpochSeconds(++heartbeats)
            peers.value = deviceIds.map { LoRaPeer(it, "friend", now, -80, 6f) }
        }

        override val incomingMessages: Flow<ByteArray> = emptyFlow()
        override val isReady = true
        override val protocolName = "test"
        override var deviceId = ""
        override var nickname = ""
        override suspend fun start(config: LoRaConfig) = true
        override suspend fun stop() = Unit
        override suspend fun send(data: ByteArray) = true
    }

    private companion object {
        const val PEER = "0102030405060708"
        const val OTHER = "1112131415161718"
    }
}
