package com.bitchat.repo.repositories

import com.bitchat.bluetooth.model.PeerInfo
import com.bitchat.bluetooth.service.BluetoothMeshService
import com.bitchat.domain.app.model.ActiveState
import com.bitchat.domain.app.model.UserState
import com.bitchat.domain.chat.model.BitchatMessageType
import com.bitchat.domain.chat.model.DeliveryStatus
import com.bitchat.domain.chat.model.PrivateMessageText
import com.bitchat.domain.location.model.Channel
import com.bitchat.local.prefs.UserPreferences
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
 * DM alike, so one message carries at most 255 bytes of UTF-8. A longer text goes out as several
 * such messages, in order, each shown as a message of its own; one that would take more than eight
 * is refused before anything of it is stored, queued or handed to a transport.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatRepoPrivateMessageSizeTest {

    @Test
    fun aTextLongerThanOneMessageIsShownAndSentOverTheMeshAsSeveralInOrder() = withRepo { repo, mesh, _ ->
        mesh.hasSessionWith(FRIEND)
        val sent = mesh.recordPrivateSends()

        repo.sendMessage(LONG_TEXT, Channel.MeshDM(FRIEND), "me", BitchatMessageType.Message)

        val rows = repo.getPrivateChats().getValue(FRIEND)
        assertEquals(PIECES, rows.map { it.content }, "one row for each message the other side receives")
        assertEquals(LONG_TEXT, rows.joinToString("") { it.content })
        assertTrue(rows.all { it.deliveryStatus == DeliveryStatus.Sent })
        assertEquals(rows.size, rows.map { it.id }.distinct().size)
        // Handed to the mesh in that order, each under the id of its own row.
        assertEquals(rows.map { Sent(it.content, FRIEND, it.id) }, sent)
    }

    @Test
    fun withoutASessionEveryPieceIsQueuedAndGoesOutInOrderOnceThereIsOne() = withRepo { repo, mesh, _ ->
        val sent = mesh.recordPrivateSends()
        // The first piece starts the handshake; the rest find it in flight.
        every { mesh.initiateNoiseHandshake(FRIEND) } answers { every { mesh.isHandshakeInFlight(FRIEND) } returns true }

        repo.sendMessage(LONG_TEXT, Channel.MeshDM(FRIEND), "me", BitchatMessageType.Message)

        val rows = repo.getPrivateChats().getValue(FRIEND)
        assertEquals(PIECES, rows.map { it.content })
        assertTrue(sent.isEmpty(), "nothing is sent before the session exists")
        verify(atLeast = 1) { mesh.initiateNoiseHandshake(FRIEND) }

        mesh.hasSessionWith(FRIEND)
        repo.flushAllOutbox()

        assertEquals(rows.map { Sent(it.content, FRIEND, it.id) }, sent)
    }

    @Test
    fun aSessionThatComesUpBetweenTwoPiecesDoesNotLetTheLaterOneOvertake() = withRepo { repo, mesh, _ ->
        val sent = mesh.recordPrivateSends()
        // The first piece finds no session and is queued. The handshake then completes, but the
        // queue has not been sent yet when the second piece finds the session.
        every { mesh.getPeerInfo(FRIEND) } returns mockk<PeerInfo>(relaxed = true).also { every { it.isConnected } returns true }
        every { mesh.hasEstablishedSession(FRIEND) } returns false andThenAnswer { true }

        repo.sendMessage(LONG_TEXT, Channel.MeshDM(FRIEND), "me", BitchatMessageType.Message)

        val rows = repo.getPrivateChats().getValue(FRIEND)
        assertEquals(rows.map { Sent(it.content, FRIEND, it.id) }, sent)
        assertEquals(PIECES, sent.map { it.content })
    }

    @Test
    fun whatIsQueuedGoesOutOldestFirstAndNothingPastATextThatCannotGoYet() = withRepo { repo, mesh, _ ->
        val sent = mesh.recordPrivateSends()
        repo.sendMessage(LONG_TEXT, Channel.MeshDM(FRIEND), "me", BitchatMessageType.Message)
        // The session is there for the second look only: were the first text skipped, the ones
        // behind it would go out without it.
        every { mesh.getPeerInfo(FRIEND) } returns mockk<PeerInfo>(relaxed = true).also { every { it.isConnected } returns true }
        every { mesh.hasEstablishedSession(FRIEND) } returns false andThenAnswer { true }

        repo.flushAllOutbox()
        assertTrue(sent.isEmpty(), "the first could not go, so none went")

        repo.flushAllOutbox()
        assertEquals(PIECES, sent.map { it.content })
    }

    @Test
    fun aTextLongerThanOneMessageGoesOverNostrAsSeveralInOrder() = withRepo { repo, _, nostr ->
        val direct = mutableListOf<Sent>()
        val inGeohash = mutableListOf<Sent>()
        every { nostr.sendPrivateMessage(any(), any(), any(), any(), any()) } answers { direct += Sent(firstArg(), thirdArg(), arg(3)) }
        every { nostr.sendPrivateMessageGeohash(any(), any(), any(), any()) } answers { inGeohash += Sent(firstArg(), secondArg(), thirdArg()) }

        repo.sendMessage(LONG_TEXT, Channel.NostrDM(NOSTR_CHAT, "npub1friend", null), "me", BitchatMessageType.Message)
        repo.sendMessage(LONG_TEXT, Channel.NostrDM(GEOHASH_CHAT, "d4".repeat(32), "9q8yy"), "me", BitchatMessageType.Message)

        val chats = repo.getPrivateChats()
        assertEquals(PIECES, chats.getValue(NOSTR_CHAT).map { it.content })
        assertEquals(chats.getValue(NOSTR_CHAT).map { Sent(it.content, NOSTR_CHAT, it.id) }, direct)
        assertEquals(PIECES, chats.getValue(GEOHASH_CHAT).map { it.content })
        assertEquals(chats.getValue(GEOHASH_CHAT).map { Sent(it.content, "d4".repeat(32), it.id) }, inGeohash)
    }

    @Test
    fun everyPieceTakesTheWayOutThatWasOpenWhenTheTextWasSent() {
        // The entry that is not told the conversation reads the open chat. Read once: were it read
        // for each piece, the pieces after a change of chat would go nowhere, or somewhere else.
        val preferences = emptyUserPreferences().also {
            every { it.getUserState() } returnsMany listOf(
                UserState.Active(ActiveState.Chat(Channel.MeshDM(FRIEND))),
                UserState.Active(ActiveState.Settings),
            )
        }
        withRepo(preferences) { repo, mesh, _ ->
            mesh.hasSessionWith(FRIEND)
            val sent = mesh.recordPrivateSends()

            repo.sendPrivate(LONG_TEXT, FRIEND, "friend")

            assertEquals(PIECES, sent.map { it.content })
        }
    }

    @Test
    fun aMeshTextThatNeedsMoreThanEightMessagesIsRefusedBeforeAnyOfItIsShownOrSent() = withRepo { repo, mesh, _ ->
        mesh.hasSessionWith(FRIEND)

        val refusal = assertFailsWith<IllegalArgumentException> {
            repo.sendMessage(NINE_PIECES, Channel.MeshDM(FRIEND), "me", BitchatMessageType.Message)
        }

        assertEquals("a private message is sent in at most 8 parts of 255 bytes, this one needs 9", refusal.message)
        assertTrue(repo.getPrivateChats().isEmpty(), "nothing is shown as sent")
        verify(exactly = 0) { mesh.sendPrivateMessage(any(), any(), any(), any()) }
    }

    @Test
    fun aMeshTextThatNeedsMoreThanEightMessagesIsNotQueuedForAHandshakeEither() = withRepo { repo, mesh, _ ->
        // No session yet: a text that can be sent is queued and a handshake is started for it.
        assertFailsWith<IllegalArgumentException> {
            repo.sendMessage(NINE_PIECES, Channel.MeshDM(FRIEND), "me", BitchatMessageType.Message)
        }

        assertTrue(repo.getPrivateChats().isEmpty())
        verify(exactly = 0) { mesh.initiateNoiseHandshake(any()) }

        // Nothing was left behind to go out once the session is there.
        mesh.hasSessionWith(FRIEND)
        repo.flushAllOutbox()
        verify(exactly = 0) { mesh.sendPrivateMessage(any(), any(), any(), any()) }
    }

    @Test
    fun aNostrTextThatNeedsMoreThanEightMessagesIsRefusedBeforeAnyOfItIsShownOrSent() = withRepo { repo, _, nostr ->
        val direct = Channel.NostrDM(peerID = NOSTR_CHAT, fullPubkey = "npub1friend", sourceGeohash = null)
        val inGeohash = Channel.NostrDM(peerID = GEOHASH_CHAT, fullPubkey = "d4".repeat(32), sourceGeohash = "9q8yy")

        for (channel in listOf(direct, inGeohash)) {
            assertFailsWith<IllegalArgumentException> {
                repo.sendMessage(NINE_PIECES, channel, "me", BitchatMessageType.Message)
            }
        }

        assertTrue(repo.getPrivateChats().isEmpty(), "nothing is shown as sent")
        verify(exactly = 0) { nostr.sendPrivateMessage(any(), any(), any(), any(), any()) }
        verify(exactly = 0) { nostr.sendPrivateMessageGeohash(any(), any(), any(), any()) }
    }

    @Test
    fun theOtherPrivateSendEntryRefusesItToo() = withRepo { repo, mesh, _ ->
        mesh.hasSessionWith(FRIEND)

        assertFailsWith<IllegalArgumentException> { repo.sendPrivate(NINE_PIECES, FRIEND, "friend") }

        assertTrue(repo.getPrivateChats().isEmpty())
        verify(exactly = 0) { mesh.sendPrivateMessage(any(), any(), any(), any()) }
    }

    @Test
    fun theLimitIsCountedInBytesNotInCharacters() = withRepo { repo, mesh, _ ->
        mesh.hasSessionWith(FRIEND)
        val sent = mesh.recordPrivateSends()
        // Three bytes each: 680 of them fill eight messages, the 681st would need a ninth.
        val euro = Char(0x20AC).toString()

        assertFailsWith<IllegalArgumentException> {
            repo.sendMessage(euro.repeat(681), Channel.MeshDM(FRIEND), "me", BitchatMessageType.Message)
        }
        assertTrue(repo.getPrivateChats().isEmpty())

        repo.sendMessage(euro.repeat(680), Channel.MeshDM(FRIEND), "me", BitchatMessageType.Message)
        assertEquals(List(8) { euro.repeat(85) }, sent.map { it.content })
    }

    @Test
    fun aTextThatFitsOneMessageGoesOutAsThatOneMessage() = withRepo { repo, mesh, nostr ->
        mesh.hasSessionWith(FRIEND)
        // 85 characters of three bytes each: 255 bytes, the most one message carries.
        val longest = Char(0x20AC).toString().repeat(85)
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
    fun aConversationThroughARelayIsNotCutForTheRadioWhateverTheMeshSaysOfItsPeer() = withRepo { repo, mesh, nostr ->
        // The mesh service would cut a text for this peer id to what one LoRa frame carries.
        every { mesh.privateTextLimitFor(any()) } returns 141
        val longest = Char(0x20AC).toString().repeat(85)

        repo.sendMessage(longest, Channel.NostrDM(NOSTR_CHAT, "npub1friend", null), "me", BitchatMessageType.Message)

        assertEquals(listOf(longest), repo.getPrivateChats().getValue(NOSTR_CHAT).map { it.content })
        verify(exactly = 1) { nostr.sendPrivateMessage(longest, "npub1friend", NOSTR_CHAT, any(), any()) }
    }

    @Test
    fun everyPieceIsOneTheEncodingCarries() {
        // The rule lives in the domain and the encoding in the transports: neither may move alone.
        val id = "0F1E2D3C-4B5A-6978-8796-A5B4C3D2E1F0"
        assertNotNull(PrivateMessagePacket(id, "x".repeat(PrivateMessageText.MAX_BYTES)).encode())
        assertNull(PrivateMessagePacket(id, "x".repeat(PrivateMessageText.MAX_BYTES + 1)).encode())
        assertNull(NostrEmbeddedBitChat.encodePMForNostr("x".repeat(PrivateMessageText.MAX_BYTES + 1), id, FRIEND, FRIEND))
        assertNull(NostrEmbeddedBitChat.encodePMForNostrNoRecipient("x".repeat(PrivateMessageText.MAX_BYTES + 1), id, FRIEND))

        val texts = listOf(LONG_TEXT, "x".repeat(8 * 255), Char(0x20AC).toString().repeat(680), "${Char(0xD83D)}${Char(0xDE00)}".repeat(500))
        for (piece in texts.flatMap(PrivateMessageText::split)) {
            assertNotNull(PrivateMessagePacket(id, piece).encode())
            assertNotNull(NostrEmbeddedBitChat.encodePMForNostr(piece, id, FRIEND, FRIEND))
            assertNotNull(NostrEmbeddedBitChat.encodePMForNostrNoRecipient(piece, id, FRIEND))
        }
    }

    @Test
    fun aFileMessageIsNeitherMeasuredNorCutAsText() = withRepo { repo, mesh, _ ->
        mesh.hasSessionWith(FRIEND)
        // An image or a voice note carries a path as content and travels as a file, not as this text.
        val path = "/tmp/" + "d".repeat(3_000) + "/missing.jpg"

        repo.sendMessage(path, Channel.MeshDM(FRIEND), "me", BitchatMessageType.Image)
        repo.sendMessage(path, Channel.MeshDM(FRIEND), "me", BitchatMessageType.Audio)

        assertEquals(listOf(path, path), repo.getPrivateChats().getValue(FRIEND).map { it.content })
    }

    @Test
    fun aPublicMessageIsNeitherRefusedNorCut() = withRepo { repo, _, _ ->
        repo.sendMessage(NINE_PIECES, Channel.Mesh, "me", BitchatMessageType.Message)

        assertEquals(listOf(NINE_PIECES), repo.getMeshMessages().map { it.content })
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

    private fun BluetoothMeshService.hasSessionWith(peer: String) {
        every { getPeerInfo(peer) } returns mockk<PeerInfo>(relaxed = true).also { every { it.isConnected } returns true }
        every { hasEstablishedSession(peer) } returns true
    }

    private fun withRepo(
        userPreferences: UserPreferences = emptyUserPreferences(),
        block: suspend TestScope.(ChatRepo, BluetoothMeshService, NostrTransport) -> Unit,
    ) = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val mesh = mockk<BluetoothMeshService>(relaxed = true)
        // A relaxed mock would answer 0: these peers are reached over Bluetooth, where a message carries 255 bytes.
        every { mesh.privateTextLimitFor(any()) } returns PrivateMessageText.MAX_BYTES
        every { mesh.sendFilePrivate(any(), any()) } returns true
        // And false, which the real service says only of a message it cannot take.
        every { mesh.sendPrivateMessage(any(), any(), any(), any()) } returns true
        val nostr = mockk<NostrTransport>(relaxed = true)
        try {
            val repo = chatRepo(scope, dispatcher, mutableListOf(), mesh = mesh, nostr = nostr, userPreferences = userPreferences)
            block(repo, mesh, nostr)
        } finally {
            scope.cancel()
        }
    }

    private companion object {
        const val FRIEND = "0102030405060708"
        const val NOSTR_CHAT = "nostr_c3c3c3c3c3c3c3c3"
        const val GEOHASH_CHAT = "nostr_d4d4d4d4d4d4d4d4"

        /** Three messages' worth of ordinary prose. */
        val LONG_TEXT = "The quick brown fox jumps over the lazy dog, and then it does so once more. ".repeat(8).trim()
        val PIECES = PrivateMessageText.split(LONG_TEXT).also { check(it.size == 3) { it.size } }

        /** One byte more than eight messages carry. */
        val NINE_PIECES = "x".repeat(8 * 255 + 1)
    }
}
