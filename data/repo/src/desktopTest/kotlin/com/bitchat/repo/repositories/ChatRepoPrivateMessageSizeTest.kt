package com.bitchat.repo.repositories

import com.bitchat.bluetooth.model.PeerInfo
import com.bitchat.bluetooth.service.BluetoothMeshService
import com.bitchat.domain.chat.model.BitchatMessageType
import com.bitchat.domain.chat.model.PrivateMessageText
import com.bitchat.domain.location.model.Channel
import com.bitchat.noise.model.PrivateMessagePacket
import com.bitchat.nostr.NostrEmbeddedBitChat
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
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A private text message travels as a TLV whose length is one byte, on the mesh and inside a Nostr
 * DM alike, so its content is at most 255 bytes of UTF-8. One that is longer cannot be sent at all:
 * it is refused before anything is stored, queued or handed to a transport.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatRepoPrivateMessageSizeTest {

    @Test
    fun aMeshPrivateMessageOneByteTooLongIsRefusedBeforeItIsShownOrSent() = withRepo { repo, mesh, _ ->
        mesh.hasSessionWith(FRIEND)

        val refusal = assertFailsWith<IllegalArgumentException> {
            repo.sendMessage(ONE_BYTE_TOO_LONG, Channel.MeshDM(FRIEND), "me", BitchatMessageType.Message)
        }

        assertEquals("a private message can be at most 255 bytes, this one is 256", refusal.message)
        assertTrue(repo.getPrivateChats().isEmpty(), "nothing is shown as sent")
        verify(exactly = 0) { mesh.sendPrivateMessage(any(), any(), any(), any()) }
    }

    @Test
    fun aMeshPrivateMessageOneByteTooLongIsNotQueuedForAHandshakeEither() = withRepo { repo, mesh, _ ->
        // No session yet: a message that fits is queued and a handshake is started for it.
        assertFailsWith<IllegalArgumentException> {
            repo.sendMessage(ONE_BYTE_TOO_LONG, Channel.MeshDM(FRIEND), "me", BitchatMessageType.Message)
        }

        assertTrue(repo.getPrivateChats().isEmpty())
        verify(exactly = 0) { mesh.initiateNoiseHandshake(any()) }

        // Nothing was left behind to go out once the session is there.
        mesh.hasSessionWith(FRIEND)
        repo.flushAllOutbox()
        verify(exactly = 0) { mesh.sendPrivateMessage(any(), any(), any(), any()) }
    }

    @Test
    fun aNostrPrivateMessageOneByteTooLongIsRefusedBeforeItIsShownOrSent() = withRepo { repo, _, nostr ->
        val direct = Channel.NostrDM(peerID = NOSTR_CHAT, fullPubkey = "npub1friend", sourceGeohash = null)
        val inGeohash = Channel.NostrDM(peerID = NOSTR_CHAT, fullPubkey = "c3".repeat(32), sourceGeohash = "9q8yy")

        for (channel in listOf(direct, inGeohash)) {
            assertFailsWith<IllegalArgumentException> {
                repo.sendMessage(ONE_BYTE_TOO_LONG, channel, "me", BitchatMessageType.Message)
            }
        }

        assertTrue(repo.getPrivateChats().isEmpty(), "nothing is shown as sent")
        verify(exactly = 0) { nostr.sendPrivateMessage(any(), any(), any(), any(), any()) }
        verify(exactly = 0) { nostr.sendPrivateMessageGeohash(any(), any(), any(), any()) }
    }

    @Test
    fun theOtherPrivateSendEntryRefusesItToo() = withRepo { repo, mesh, _ ->
        mesh.hasSessionWith(FRIEND)

        assertFailsWith<IllegalArgumentException> { repo.sendPrivate(ONE_BYTE_TOO_LONG, FRIEND, "friend") }

        assertTrue(repo.getPrivateChats().isEmpty())
        verify(exactly = 0) { mesh.sendPrivateMessage(any(), any(), any(), any()) }
    }

    @Test
    fun theLimitIsCountedInBytesNotInCharacters() = withRepo { repo, mesh, _ ->
        mesh.hasSessionWith(FRIEND)
        // 64 emoji of four bytes each: 256 bytes in 128 UTF-16 units, far below any character limit.
        val emoji = "\uD83D\uDE00".repeat(64)
        assertEquals(256, emoji.encodeToByteArray().size)

        assertFailsWith<IllegalArgumentException> {
            repo.sendMessage(emoji, Channel.MeshDM(FRIEND), "me", BitchatMessageType.Message)
        }

        assertTrue(repo.getPrivateChats().isEmpty())
        verify(exactly = 0) { mesh.sendPrivateMessage(any(), any(), any(), any()) }
    }

    @Test
    fun aPrivateMessageOfExactlyTheLimitIsShownAndSent() = withRepo { repo, mesh, nostr ->
        mesh.hasSessionWith(FRIEND)
        // 85 characters of three bytes each: 255 bytes.
        val longest = "\u20AC".repeat(85)
        assertEquals(255, longest.encodeToByteArray().size)

        repo.sendMessage(longest, Channel.MeshDM(FRIEND), "me", BitchatMessageType.Message)
        repo.sendMessage(longest, Channel.NostrDM(NOSTR_CHAT, "npub1friend", null), "me", BitchatMessageType.Message)

        val chats = repo.getPrivateChats()
        assertEquals(listOf(longest), chats.getValue(FRIEND).map { it.content })
        assertEquals(listOf(longest), chats.getValue(NOSTR_CHAT).map { it.content })
        verify(exactly = 1) { mesh.sendPrivateMessage(longest, FRIEND, any(), any()) }
        verify(exactly = 1) { nostr.sendPrivateMessage(longest, "npub1friend", NOSTR_CHAT, any(), any()) }
    }

    @Test
    fun theLimitIsTheOneThePrivateMessageEncodingHas() {
        // The rule lives in the domain and the encoding in the transport: neither may move alone.
        val id = "0F1E2D3C-4B5A-6978-8796-A5B4C3D2E1F0"
        assertNotNull(PrivateMessagePacket(id, "x".repeat(PrivateMessageText.MAX_BYTES)).encode())
        assertNull(PrivateMessagePacket(id, "x".repeat(PrivateMessageText.MAX_BYTES + 1)).encode())
        assertNotNull(NostrEmbeddedBitChat.encodePMForNostr("x".repeat(PrivateMessageText.MAX_BYTES), id, FRIEND, FRIEND))
        assertNull(NostrEmbeddedBitChat.encodePMForNostr("x".repeat(PrivateMessageText.MAX_BYTES + 1), id, FRIEND, FRIEND))
        assertNotNull(NostrEmbeddedBitChat.encodePMForNostrNoRecipient("x".repeat(PrivateMessageText.MAX_BYTES), id, FRIEND))
        assertNull(NostrEmbeddedBitChat.encodePMForNostrNoRecipient("x".repeat(PrivateMessageText.MAX_BYTES + 1), id, FRIEND))
    }

    @Test
    fun aFileMessageIsNotMeasuredAsText() = withRepo { repo, mesh, _ ->
        mesh.hasSessionWith(FRIEND)
        // An image or a voice note carries a path as content and travels as a file, not as this text.
        val path = "/tmp/" + "d".repeat(300) + "/missing.jpg"

        repo.sendMessage(path, Channel.MeshDM(FRIEND), "me", BitchatMessageType.Image)
        repo.sendMessage(path, Channel.MeshDM(FRIEND), "me", BitchatMessageType.Audio)

        assertEquals(2, repo.getPrivateChats().getValue(FRIEND).size)
    }

    @Test
    fun aPublicMessageIsNotHeldToThePrivateLimit() = withRepo { repo, _, _ ->
        repo.sendMessage(ONE_BYTE_TOO_LONG, Channel.Mesh, "me", BitchatMessageType.Message)

        assertEquals(listOf(ONE_BYTE_TOO_LONG), repo.getMeshMessages().map { it.content })
    }

    private fun BluetoothMeshService.hasSessionWith(peer: String) {
        every { getPeerInfo(peer) } returns mockk<PeerInfo>(relaxed = true).also { every { it.isConnected } returns true }
        every { hasEstablishedSession(peer) } returns true
    }

    private fun withRepo(
        block: suspend TestScope.(ChatRepo, BluetoothMeshService, NostrTransport) -> Unit,
    ) = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val mesh = mockk<BluetoothMeshService>(relaxed = true)
        val nostr = mockk<NostrTransport>(relaxed = true)
        try {
            block(chatRepo(scope, dispatcher, mutableListOf(), mesh = mesh, nostr = nostr), mesh, nostr)
        } finally {
            scope.cancel()
        }
    }

    private companion object {
        const val FRIEND = "0102030405060708"
        const val NOSTR_CHAT = "nostr_c3c3c3c3c3c3c3c3"

        /** One byte more than a one-byte length can say. */
        val ONE_BYTE_TOO_LONG = "x".repeat(256)
    }
}
