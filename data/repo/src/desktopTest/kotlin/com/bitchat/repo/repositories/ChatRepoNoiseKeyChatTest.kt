package com.bitchat.repo.repositories

import com.bitchat.bluetooth.model.PeerInfo
import com.bitchat.bluetooth.service.BluetoothMeshService
import com.bitchat.domain.chat.model.BitchatMessageType
import com.bitchat.domain.chat.model.PrivateMessageText
import com.bitchat.domain.location.model.Channel
import com.bitchat.domain.user.model.FavoriteRelationship
import com.bitchat.local.prefs.UserPreferences
import com.bitchat.nostr.NostrTransport
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Clock

/**
 * The Noise key a mesh peer ANNOUNCES is checked against nothing, not even for its length, so it may
 * not say whose queued texts that peer gets or sets going: neither those of a chat kept under a
 * peer's whole Noise key (64 hex digits; nothing in the app opens one, the repository takes any key
 * it is handed) nor those of an ordinary chat, kept under a mesh id.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatRepoNoiseKeyChatTest {

    @Test
    fun aTextQueuedUnderANoiseKeyIsNotHandedToAMeshPeerBecauseThatPeerAnnouncesTheKey() = withRepo { repo, mesh, _, peers ->
        val sent = mesh.recordPrivateSends()
        repo.sendMessage("hello", Channel.MeshDM(KEY_HEX), "me", BitchatMessageType.Message)

        aPeerWithItsOwnSessionAnnouncesTheKey(mesh, peers)
        repo.onSessionEstablished(IMPOSTOR)
        repo.onPeersUpdated(listOf(IMPOSTOR))
        repo.didUpdatePeerList(listOf(IMPOSTOR))

        assertTrue(sent.isEmpty(), "a text for the holder of a key went to a peer that only claimed the key: $sent")
    }

    @Test
    fun aPeerAnnouncingANoiseKeyDoesNotMoveWhatIsQueuedUnderThatKeyToARelay() {
        // The key's holder is a mutual favourite with a Nostr key, kept under the whole Noise key.
        val holder = FavoriteRelationship(KEY_HEX, "npub1holder", "holder", isFavorite = true, theyFavoritedUs = true, favoritedAt = 1, lastUpdated = 1)
        withRepo(StatefulFavoritePreferences(mapOf(KEY_HEX to holder)).preferences) { repo, mesh, nostr, peers ->
            repo.sendMessage("hello", Channel.MeshDM(KEY_HEX), "me", BitchatMessageType.Message)

            aPeerWithItsOwnSessionAnnouncesTheKey(mesh, peers)
            repo.onSessionEstablished(IMPOSTOR)
            repo.onPeersUpdated(listOf(IMPOSTOR))
            repo.didUpdatePeerList(listOf(IMPOSTOR))

            verify(exactly = 0) { nostr.sendPrivateMessage(any(), any(), any(), any(), any()) }
        }
    }

    @Test
    fun aPeerAnnouncingAnotherPeersMeshIdAsItsNoiseKeyDoesNotMoveThatPeersQueueToARelay() {
        // An ordinary chat, under the mesh id of a mutual favourite that is away and has a Nostr key.
        val away = FavoriteRelationship(AWAY, "npub1away", "away", isFavorite = true, theyFavoritedUs = true, favoritedAt = 1, lastUpdated = 1)
        withRepo(StatefulFavoritePreferences(mapOf(AWAY to away)).preferences) { repo, mesh, nostr, peers ->
            repo.sendMessage("hello", Channel.MeshDM(AWAY), "me", BitchatMessageType.Message)

            // Nor is the length of the key an announcement carries checked: eight bytes, the id of the peer that is away.
            aPeerWithItsOwnSessionAnnounces(AWAY.chunked(2).map { it.toInt(16).toByte() }.toByteArray(), mesh, peers)
            repo.onSessionEstablished(IMPOSTOR)
            repo.onPeersUpdated(listOf(IMPOSTOR))
            repo.didUpdatePeerList(listOf(IMPOSTOR))

            verify(exactly = 0) { nostr.sendPrivateMessage(any(), any(), any(), any(), any()) }
        }
    }

    private fun aPeerWithItsOwnSessionAnnouncesTheKey(mesh: BluetoothMeshService, peers: MutableMap<String, PeerInfo>) =
        aPeerWithItsOwnSessionAnnounces(KEY, mesh, peers)

    /**
     * A peer with a session of its own under its own id. What an announcement says its Noise key is
     * is checked against nothing, so it can be somebody else's key, or anything at all.
     */
    private fun aPeerWithItsOwnSessionAnnounces(asItsKey: ByteArray, mesh: BluetoothMeshService, peers: MutableMap<String, PeerInfo>) {
        peers[IMPOSTOR] = PeerInfo(IMPOSTOR, "friend", true, true, asItsKey, null, false, Clock.System.now())
        every { mesh.hasEstablishedSession(IMPOSTOR) } returns true
    }

    private fun BluetoothMeshService.recordPrivateSends(): List<Pair<String, String>> {
        val sent = mutableListOf<Pair<String, String>>()
        every { sendPrivateMessage(any(), any(), any(), any()) } answers {
            sent += firstArg<String>() to secondArg<String>()
            true
        }
        return sent
    }

    private fun withRepo(
        userPreferences: UserPreferences = emptyUserPreferences(),
        block: suspend TestScope.(ChatRepo, BluetoothMeshService, NostrTransport, MutableMap<String, PeerInfo>) -> Unit,
    ) = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val mesh = mockk<BluetoothMeshService>(relaxed = true)
        every { mesh.privateTextLimitFor(any()) } returns PrivateMessageText.MAX_BYTES
        every { mesh.sendPrivateMessage(any(), any(), any(), any()) } returns true
        val peers = mutableMapOf<String, PeerInfo>()
        every { mesh.getPeerInfo(any()) } answers { peers[firstArg<String>()] }
        every { mesh.getPeerNicknames() } answers { peers.mapValues { it.value.nickname } }
        val nostr = mockk<NostrTransport>(relaxed = true)
        try {
            block(chatRepo(scope, dispatcher, mutableListOf(), mesh = mesh, nostr = nostr, userPreferences = userPreferences), mesh, nostr, peers)
        } finally {
            scope.cancel()
        }
    }

    private companion object {
        val KEY = ByteArray(32) { (it + 1).toByte() }
        val KEY_HEX = KEY.joinToString("") { "%02x".format(it) }
        const val IMPOSTOR = "f1f2f3f4f5f6f7f8"
        const val AWAY = "a1a2a3a4a5a6a7a8"
    }
}
