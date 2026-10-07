package com.bitchat.repo.repositories

import io.mockk.coEvery
import com.bitchat.domain.chat.model.ChatEvent
import com.bitchat.domain.chat.model.PrivateMessageText
import com.bitchat.domain.chat.eventbus.ChatEventBus
import com.bitchat.bluetooth.model.PeerInfo
import com.bitchat.bluetooth.service.BluetoothMeshService
import com.bitchat.domain.chat.model.BitchatFilePacket
import com.bitchat.domain.chat.model.BitchatMessage
import com.bitchat.domain.chat.model.BitchatMessageType
import com.bitchat.domain.location.model.Channel
import com.bitchat.domain.user.sanitizedNickname
import com.bitchat.repo.utils.MessageLimits
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class ChatRepoMeshChatNamesTest {
    @Test fun aLaterAnnouncementDoesNotRenameAnAuthenticatedChat() = withRepo { repo, peers, _ ->
        peers[PEER_ID] = peer("alice")
        repo.didReceiveAuthenticatedPrivateMessage(message("first", "mallory"))
        runCurrent()
        peers[PEER_ID] = peer("bob")
        repo.didUpdatePeerList(listOf(PEER_ID))
        runCurrent()
        repo.didReceiveAuthenticatedPrivateMessage(message("second", "mallory"))
        runCurrent()

        assertEquals(NAME, repo.getPrivateChatNames()[PEER_ID])
        assertEquals(listOf(NAME, NAME), repo.getPrivateChats().getValue(PEER_ID).map { it.sender })
    }

    @Test fun theSenderFieldOfAnIncomingMessageIsIgnored() = withRepo { repo, peers, _ ->
        peers[PEER_ID] = peer("alice")
        repo.didReceiveAuthenticatedPrivateMessage(message("message", "mallory"))
        runCurrent()

        assertEquals(NAME, repo.getPrivateChatNames()[PEER_ID])
        assertEquals(NAME, repo.getPrivateChats().getValue(PEER_ID).single().sender)
    }

    @Test fun aChatOpenedBeforeAnyAnnouncementIsNamedOnceByTheFirstOne() = withRepo { repo, peers, _ ->
        repo.didReceiveAuthenticatedPrivateMessage(message("before", "mallory"))
        runCurrent()
        assertNull(repo.getPrivateChatNames()[PEER_ID])
        assertEquals(PEER_ID.take(12), repo.getPrivateChats().getValue(PEER_ID).single().sender)

        peers[PEER_ID] = peer("alice")
        repo.didUpdatePeerList(listOf(PEER_ID))
        runCurrent()
        assertEquals(NAME, repo.getPrivateChatNames()[PEER_ID])
        assertEquals(PEER_ID.take(12), repo.getPrivateChats().getValue(PEER_ID).single().sender)

        peers[PEER_ID] = peer("bob")
        repo.didUpdatePeerList(listOf(PEER_ID))
        runCurrent()
        assertEquals(NAME, repo.getPrivateChatNames()[PEER_ID])
    }

    @Test fun anAnnouncedNameIsSanitisedBeforeItNamesAChat() = withRepo { repo, peers, _ ->
        val claim = "alice\u202E\n" + "x".repeat(300)
        peers[PEER_ID] = peer(claim)
        repo.didReceiveAuthenticatedPrivateMessage(message("message", "mallory"))
        runCurrent()

        assertEquals("${sanitizedNickname(claim)}#${PEER_ID.take(4)}", repo.getPrivateChatNames()[PEER_ID])
    }

    @Test fun aPrivateFileIsStampedWithTheChatsName() = withRepo { repo, peers, _ ->
        peers[PEER_ID] = peer("alice")
        repo.didReceiveAuthenticatedPrivateMessage(message("message", "mallory"))
        runCurrent()
        peers[PEER_ID] = peer("bob")
        repo.didReceiveAuthenticatedPrivateFile(PEER_ID, BitchatFilePacket("file.txt", 1, "text/plain", byteArrayOf(1)))
        awaitPrivateMessages(repo, 2)

        assertEquals(NAME, repo.getPrivateChats().getValue(PEER_ID).last().sender)
    }

    @Test fun aChatTheUserOpenedKeepsItsName() = withRepo { repo, peers, mesh ->
        peers[PEER_ID] = peer("alice")
        every { mesh.hasEstablishedSession(PEER_ID) } returns true
        repo.sendMessage("hello", Channel.MeshDM(PEER_ID), "me", BitchatMessageType.Message)
        peers[PEER_ID] = peer("bob")
        repo.didReceiveAuthenticatedPrivateMessage(message("reply", "mallory"))
        runCurrent()

        assertEquals(NAME, repo.getPrivateChatNames()[PEER_ID])
        val rows = repo.getPrivateChats().getValue(PEER_ID)
        assertEquals(NAME, rows.single { it.id == "reply" }.sender)
        // The user's own line keeps whatever the user is called: only the other side's rows carry the chat's name.
        assertEquals(mesh.myPeerID, rows.single { it.id != "reply" }.senderPeerID)
        assertFalse(rows.single { it.id != "reply" }.sender == NAME)
    }

    @Test fun aChatTheUserOpenedIsCalledWhatTheUserSawNotWhatIsAnnouncedWhenTheyWrite() = withRepo { repo, peers, mesh ->
        // The row said "alice" when the user opened the chat; by the first line someone announces "bob" for it.
        peers[PEER_ID] = peer("bob")
        every { mesh.hasEstablishedSession(PEER_ID) } returns true
        repo.sendMessage("hello", Channel.MeshDM(PEER_ID, "alice"), "me", BitchatMessageType.Message)
        repo.didReceiveAuthenticatedPrivateMessage(message("reply", "mallory"))
        runCurrent()

        assertEquals(NAME, repo.getPrivateChatNames()[PEER_ID])
        assertEquals(NAME, repo.getPrivateChats().getValue(PEER_ID).single { it.id == "reply" }.sender)
    }

    @Test fun aChatOpenedByTheFirstLineSentIsCalledWhatItsSenderKnewThePeerAs() = withRepo { repo, peers, _ ->
        peers[PEER_ID] = peer("bob")
        repo.sendPrivate("hello", PEER_ID, "alice")

        assertEquals(NAME, repo.getPrivateChatNames()[PEER_ID])
    }

    @Test fun aChatOpenedByALineSentWithoutANameTakesWhatIsAnnounced() = withRepo { repo, peers, _ ->
        peers[PEER_ID] = peer("alice")
        repo.sendPrivate("hello", PEER_ID, "")

        assertEquals(NAME, repo.getPrivateChatNames()[PEER_ID])
    }

    @Test fun aPublicFileFromAPeerThatAnnouncedNothingIsRenamedByItsFirstAnnouncement() = withRepo { repo, peers, _ ->
        repo.didReceivePublicFile(PEER_ID, BitchatFilePacket("file.txt", 1, "text/plain", byteArrayOf(1)))
        withContext(Dispatchers.Default) {
            withTimeout(5_000) { while (repo.getMeshMessages().isEmpty()) delay(10) }
        }
        assertEquals("Unknown", repo.getMeshMessages().single().sender)

        peers[PEER_ID] = peer("alice")
        repo.didUpdatePeerList(listOf(PEER_ID))
        runCurrent()
        assertEquals("alice", repo.getMeshMessages().single().sender)
    }

    @Test fun aChatAlreadyNamedIsNotRenamedByOpeningItUnderAnotherName() = withRepo { repo, peers, mesh ->
        peers[PEER_ID] = peer("alice")
        repo.didReceiveAuthenticatedPrivateMessage(message("first", "mallory"))
        runCurrent()
        peers[PEER_ID] = peer("bob")
        every { mesh.hasEstablishedSession(PEER_ID) } returns true
        repo.sendMessage("hello", Channel.MeshDM(PEER_ID, "bob"), "me", BitchatMessageType.Message)
        repo.sendPrivate("again", PEER_ID, "bob")

        assertEquals(NAME, repo.getPrivateChatNames()[PEER_ID])
    }

    @Test fun aConnectedPeerThatAnnouncedNothingIsListedUnderThePlaceholderAsItAlwaysWas() = withRepo { repo, peers, _ ->
        // Not under the start of its id: wherever a name is looked up, that would be taken for one.
        peers[PEER_ID] = peer("Unknown")
        repo.didUpdatePeerList(listOf(PEER_ID))
        runCurrent()
        assertEquals(listOf("Unknown"), repo.getMeshPeers().map { it.displayName })

        peers[PEER_ID] = peer("alice")
        repo.didUpdatePeerList(listOf(PEER_ID))
        runCurrent()
        assertEquals(listOf("alice"), repo.getMeshPeers().map { it.displayName })
    }

    @Test fun aPeerAnnouncingTheStartOfItsOwnIdStillRenamesItsUnknownPublicRows() = withRepo { repo, peers, _ ->
        repo.didReceiveMessage(message("public", "Unknown").copy(isPrivate = false))
        runCurrent()
        peers[PEER_ID] = peer(PEER_ID.take(8))
        repo.didUpdatePeerList(listOf(PEER_ID))
        runCurrent()

        assertEquals(PEER_ID.take(8), repo.getMeshMessages().single().sender)
        assertEquals(listOf(PEER_ID.take(8)), repo.getMeshPeers().map { it.displayName })
    }

    @Test fun aNameAnnouncedWithSomeoneElsesSuffixIsNotShownWithIt() = withRepo { repo, peers, _ ->
        // A number sign is the app's own on the mesh: what follows it is always the start of the real id.
        peers[PEER_ID] = peer("alice#ffff")
        repo.didUpdatePeerList(listOf(PEER_ID))
        repo.didReceiveAuthenticatedPrivateMessage(message("message", "mallory"))
        runCurrent()

        assertEquals(listOf("aliceffff"), repo.getMeshPeers().map { it.displayName })
        assertEquals("aliceffff#1a2b", repo.getPrivateChatNames()[PEER_ID])
    }

    @Test fun aChatOpenedAgainUnderItsOwnNameIsCalledTheSame() = withRepo { repo, _, mesh ->
        // A chat that was pushed out, or the one restored at start, is opened under the name it had.
        every { mesh.hasEstablishedSession(PEER_ID) } returns true
        repo.sendMessage("hello", Channel.MeshDM(PEER_ID, NAME), "me", BitchatMessageType.Message)

        assertEquals(NAME, repo.getPrivateChatNames()[PEER_ID])
    }

    /** A bus that keeps what was published on it: the people list reads the chats' names again only on an event. */
    private val published = mutableListOf<ChatEvent>()
    private val recordingBus = mockk<ChatEventBus>(relaxed = true).also {
        coEvery { it.update(capture(published)) } returns Unit
    }
    private fun chatUpdates() = published.count { it == ChatEvent.PrivateChatsUpdated }

    @Test fun aChatNamedByAMessageThatWasAlreadyThereIsStillAnnouncedAsChanged() = withRepo(bus = recordingBus) { repo, peers, _ ->
        repo.didReceiveAuthenticatedPrivateMessage(message("same id", "mallory"))
        runCurrent()
        assertNull(repo.getPrivateChatNames()[PEER_ID])
        assertEquals(1, chatUpdates())

        // The peer has a name by now, and the same message arrives again (encrypted anew): nothing is
        // stored, but the chat gets its name, and whoever lists the chats has to hear of that.
        peers[PEER_ID] = peer("alice")
        repo.didReceiveAuthenticatedPrivateMessage(message("same id", "mallory"))
        runCurrent()
        assertEquals(NAME, repo.getPrivateChatNames()[PEER_ID])
        assertEquals(1, repo.getPrivateChats().getValue(PEER_ID).size)
        assertEquals(2, chatUpdates())

        // A third copy changes nothing and says nothing.
        repo.didReceiveAuthenticatedPrivateMessage(message("same id", "mallory"))
        runCurrent()
        assertEquals(2, chatUpdates())
    }

    @Test fun aChatNamedByALaterAnnouncementIsAnnouncedAsChanged() = withRepo(bus = recordingBus) { repo, peers, _ ->
        repo.didReceiveAuthenticatedPrivateMessage(message("first", "mallory"))
        runCurrent()
        assertEquals(1, chatUpdates())

        peers[PEER_ID] = peer("alice")
        repo.didUpdatePeerList(listOf(PEER_ID))
        runCurrent()
        assertEquals(NAME, repo.getPrivateChatNames()[PEER_ID])
        assertEquals(2, chatUpdates())

        // The next update finds the chat named: nothing changes and nothing is said.
        repo.didUpdatePeerList(listOf(PEER_ID))
        runCurrent()
        assertEquals(2, chatUpdates())
    }

    @Test fun aConversationThatIsNotAMeshChatIsNeverGivenAMeshName() = withRepo(bus = recordingBus) { repo, peers, _ ->
        // Whatever comes to be known under its key: such a conversation is named after its messages, as
        // before, and a message that was already there changes nothing about it.
        val key = "nostr_1234567890abcdef"
        repo.didReceiveAuthenticatedPrivateMessage(message("same id", "first name", peerID = key))
        runCurrent()
        peers[key] = peer("alice")
        repo.didReceiveAuthenticatedPrivateMessage(message("same id", "first name", peerID = key))
        repo.didUpdatePeerList(listOf(key))
        runCurrent()

        assertEquals("first name", repo.getPrivateChatNames()[key])
        assertEquals(1, chatUpdates())
    }

    @Test fun aNameIsGivenToAChatThatExistsNotKeptForOneThatMayCome() = withRepo(bus = recordingBus) { repo, peers, _ ->
        // Announcements for a peer without a chat leave nothing behind: the chat is named after what is
        // announced when it is opened.
        peers[PEER_ID] = peer("alice")
        repo.didUpdatePeerList(listOf(PEER_ID))
        runCurrent()
        assertEquals(0, chatUpdates())

        peers[PEER_ID] = peer("bob")
        repo.didReceiveAuthenticatedPrivateMessage(message("first", "mallory"))
        runCurrent()
        assertEquals("bob#${PEER_ID.take(4)}", repo.getPrivateChatNames()[PEER_ID])
    }

    @Test fun aChatNamedWhenItIsOpenedIsAnnouncedAsChanged() = withRepo(bus = recordingBus) { repo, peers, mesh ->
        peers[PEER_ID] = peer("alice")
        every { mesh.hasEstablishedSession(PEER_ID) } returns true
        repo.sendMessage("hello", Channel.MeshDM(PEER_ID, "alice"), "me", BitchatMessageType.Message)
        runCurrent()

        // Once for the name it got when it was opened, once for the line stored in it.
        assertEquals(NAME, repo.getPrivateChatNames()[PEER_ID])
        assertEquals(2, chatUpdates())
    }

    @Test fun theStartOfThePeersIdHandedBackAsANameNamesNothing() = withRepo { repo, peers, mesh ->
        // A list shows the start of the id for a peer without a name, and opens the chat under that.
        every { mesh.hasEstablishedSession(PEER_ID) } returns true
        repo.sendMessage("hello", Channel.MeshDM(PEER_ID, PEER_ID.take(12)), "me", BitchatMessageType.Message)
        assertNull(repo.getPrivateChatNames()[PEER_ID])

        peers[PEER_ID] = peer("alice")
        repo.didUpdatePeerList(listOf(PEER_ID))
        runCurrent()
        assertEquals(NAME, repo.getPrivateChatNames()[PEER_ID])
    }

    @Test fun aPeerAnnouncedAsUnknownNamesNothing() = withRepo { repo, peers, _ ->
        peers[PEER_ID] = peer("Unknown")
        repo.didReceiveAuthenticatedPrivateMessage(message("message", "mallory"))
        runCurrent()

        assertNull(repo.getPrivateChatNames()[PEER_ID])
        assertEquals(PEER_ID.take(12), repo.getPrivateChats().getValue(PEER_ID).single().sender)
    }

    @Test fun unknownRowsOfThePublicMeshAreStillRenamedButPrivateRowsAreNot() = withRepo { repo, peers, _ ->
        repo.didReceiveMessage(message("public", "Unknown").copy(isPrivate = false))
        repo.didReceiveAuthenticatedPrivateMessage(message("private", "Unknown"))
        runCurrent()
        peers[PEER_ID] = peer("alice")
        repo.didUpdatePeerList(listOf(PEER_ID))
        runCurrent()

        assertEquals("alice", repo.getMeshMessages().single().sender)
        assertEquals(PEER_ID.take(12), repo.getPrivateChats().getValue(PEER_ID).single().sender)
    }

    @Test fun aWipeForgetsTheNames() = withRepo { repo, peers, _ ->
        peers[PEER_ID] = peer("alice")
        repo.didReceiveAuthenticatedPrivateMessage(message("before", "mallory"))
        runCurrent()
        repo.clearData()
        peers[PEER_ID] = peer("bob")
        repo.didReceiveAuthenticatedPrivateMessage(message("after", "mallory"))
        runCurrent()

        assertEquals("bob#${PEER_ID.take(4)}", repo.getPrivateChatNames()[PEER_ID])
    }

    @Test fun aChatPushedOutByTheStrangerBudgetLosesItsName() = withRepo(
        MessageLimits(maxMessagesPerChat = 2, maxCharsPerChat = 1_000, maxStrangerMessages = 1),
    ) { repo, peers, _ ->
        peers[PEER_ID] = peer("alice")
        repo.didReceiveAuthenticatedPrivateMessage(message("first", "mallory"))
        repo.didReceiveAuthenticatedPrivateMessage(message("other", "mallory", peerID = OTHER_PEER_ID))
        runCurrent()
        assertFalse(PEER_ID in repo.getPrivateChats())

        peers[PEER_ID] = peer("bob")
        repo.didReceiveAuthenticatedPrivateMessage(message("again", "mallory"))
        runCurrent()
        assertEquals("bob#${PEER_ID.take(4)}", repo.getPrivateChatNames()[PEER_ID])
        assertTrue(repo.getPrivateChatNames().keys.all { it in repo.getPrivateChats() })
    }

    @Test fun aNostrChatIsNamedAsBefore() = withRepo { repo, _, _ ->
        val key = "nostr_1234567890abcdef"
        fun sentAt(seconds: Long, id: String, sender: String) =
            message(id, sender, peerID = key).copy(timestamp = Instant.fromEpochSeconds(seconds))
        repo.didReceiveAuthenticatedPrivateMessage(sentAt(1, "first", "first name"))
        repo.didReceiveAuthenticatedPrivateMessage(sentAt(3, "third", "third name"))
        // The message sent in between is handled last: the newest message still names the chat.
        repo.didReceiveAuthenticatedPrivateMessage(sentAt(2, "second", "second name"))
        runCurrent()

        assertEquals("third name", repo.getPrivateChatNames()[key])
        assertEquals(
            listOf("first name", "second name", "third name"),
            repo.getPrivateChats().getValue(key).map { it.sender },
        )
    }

    @Test fun sendingWhileAHandshakeIsInFlightStillAsksForItAsTheUser() = withRepo { repo, peers, mesh ->
        peers[PEER_ID] = peer("alice")
        every { mesh.hasEstablishedSession(PEER_ID) } returns false
        every { mesh.isHandshakeInFlight(PEER_ID) } returns true

        repo.sendMessage("hello", Channel.MeshDM(PEER_ID), "me", BitchatMessageType.Message)

        verify { mesh.initiateNoiseHandshake(PEER_ID) }
    }

    private fun withRepo(
        limits: MessageLimits = MessageLimits(),
        bus: ChatEventBus = mockk(relaxed = true),
        block: suspend kotlinx.coroutines.test.TestScope.(ChatRepo, MutableMap<String, PeerInfo>, BluetoothMeshService) -> Unit,
    ) = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val mesh = mockk<BluetoothMeshService>(relaxed = true)
        // A relaxed mock would answer 0: these peers are reached over Bluetooth, where a message carries 255 bytes.
        every { mesh.privateTextLimitFor(any()) } returns PrivateMessageText.MAX_BYTES
        every { mesh.sendFilePrivate(any(), any()) } returns true
        // And false, which the real service says only of a message it cannot take.
        every { mesh.sendPrivateMessage(any(), any(), any(), any()) } returns true
        val peers = mutableMapOf<String, PeerInfo>()
        every { mesh.getPeerInfo(any()) } answers { peers[firstArg<String>()] }
        // A received file is saved under the home directory: the tests get one of their own.
        val originalUserHome = System.getProperty("user.home")
        val temporaryHome = createTempDirectory("chat-repo-mesh-chat-names-test")
        try {
            System.setProperty("user.home", temporaryHome.toString())
            block(chatRepo(scope, dispatcher, mutableListOf(), mesh = mesh, messageLimits = limits, chatEventBus = bus), peers, mesh)
        } finally {
            scope.cancel()
            System.setProperty("user.home", originalUserHome)
            Files.walk(temporaryHome).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach(Files::delete)
            }
        }
    }

    private suspend fun awaitPrivateMessages(repo: ChatRepo, count: Int) = withContext(Dispatchers.Default) {
        withTimeout(5_000) {
            while ((repo.getPrivateChats()[PEER_ID]?.size ?: 0) < count) delay(10)
        }
    }

    private fun peer(name: String) = PeerInfo(PEER_ID, name, true, true, null, null, false, Clock.System.now())

    private fun message(id: String, sender: String, peerID: String = PEER_ID) = BitchatMessage(
        id = id,
        sender = sender,
        senderPeerID = peerID,
        content = id,
        timestamp = Instant.fromEpochSeconds(1),
        isPrivate = true,
    )

    private companion object {
        const val PEER_ID = "1a2b3c4d5e6f7890"
        const val OTHER_PEER_ID = "2b3c4d5e6f7890a1"
        const val NAME = "alice#1a2b"
    }
}
