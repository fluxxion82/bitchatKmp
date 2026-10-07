package com.bitchat.repo.repositories

import com.bitchat.bluetooth.model.PeerInfo
import com.bitchat.bluetooth.service.BluetoothMeshService
import com.bitchat.domain.chat.model.BitchatMessageType
import com.bitchat.domain.chat.model.DeliveryStatus
import com.bitchat.domain.chat.model.PrivateMessageText
import com.bitchat.domain.location.model.Channel
import com.bitchat.lora.LoRaPeer
import com.bitchat.lora.LoRaProtocol
import com.bitchat.lora.radio.LoRaConfig
import com.bitchat.transport.MeshRadioLink
import com.bitchat.transport.RadioPurpose
import com.bitchat.transport.RadioSendResult
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * A peer that is not connected on the mesh and is heard on the LoRa radio can be written to: the mesh
 * service carries the handshake and the text over the radio, in messages of at most 141 bytes. Files
 * never go that way.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatRepoRadioPeerTest {

    @Test
    fun theRadioIsHandedToTheMeshAndWhatItReceivesAsPacketsGoesThere() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val mesh = mockk<BluetoothMeshService>(relaxed = true)
        val lora = RadioStack()
        try {
            chatRepo(scope, dispatcher, mutableListOf(), lora = lora, mesh = mesh)
            runCurrent()
            verify { mesh.radioLink = lora.link }

            val packet = byteArrayOf(1, 2, 3)
            lora.packets.emit(packet)
            runCurrent()
            verify(exactly = 1) { mesh.onLoRaPacketReceived(packet) }
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun aTextToAPeerHeardOnlyOnTheRadioWaitsForItsSessionAndThenGoesOut() = withRepo { repo, mesh ->
        mesh.hearsOnTheRadio(PEER)
        val sent = mesh.recordPrivateSends()

        repo.sendMessage("hello", Channel.MeshDM(PEER), "me", BitchatMessageType.Message)
        verify(exactly = 1) { mesh.initiateNoiseHandshake(PEER) }
        assertTrue(sent.isEmpty())

        every { mesh.hasEstablishedSession(PEER) } returns true
        repo.onSessionEstablished(PEER)
        assertEquals(listOf("hello"), sent)

        // And with the session there, the next one goes at once.
        repo.sendMessage("again", Channel.MeshDM(PEER), "me", BitchatMessageType.Message)
        assertEquals(listOf("hello", "again"), sent)
    }

    @Test
    fun aPeerThatIsNeitherOnTheMeshNorHeardOnTheRadioIsStillNotWrittenTo() = withRepo { repo, mesh ->
        every { mesh.getPeerInfo(PEER) } returns null
        every { mesh.reachesByRadio(PEER) } returns false
        every { mesh.hasEstablishedSession(PEER) } returns true
        val sent = mesh.recordPrivateSends()

        repo.sendMessage("hello", Channel.MeshDM(PEER), "me", BitchatMessageType.Message)
        repo.onSessionEstablished(PEER)
        assertTrue(sent.isEmpty())
    }

    @Test
    fun aLongTextToARadioPeerGoesOutInPiecesOneFrameCarries() = withRepo { repo, mesh ->
        mesh.hearsOnTheRadio(PEER)
        every { mesh.hasEstablishedSession(PEER) } returns true
        every { mesh.privateTextLimitFor(PEER) } returns RADIO_TEXT_BYTES
        val sent = mesh.recordPrivateSends()
        val text = "The quick brown fox jumps over the lazy dog, and then it does so once more. ".repeat(4).trim()

        repo.sendMessage(text, Channel.MeshDM(PEER), "me", BitchatMessageType.Message)

        assertTrue(sent.size > 1)
        assertTrue(sent.all { it.encodeToByteArray().size <= RADIO_TEXT_BYTES })
        assertEquals(text, sent.joinToString(""))
        assertEquals(PrivateMessageText.split(text, RADIO_TEXT_BYTES), sent)
    }

    @Test
    fun anImageForAPeerHeardOnlyOnTheRadioFailsAndNothingOfItIsSentOrQueued() = withRepo { repo, mesh ->
        mesh.hearsOnTheRadio(PEER)
        val sent = mesh.recordPrivateSends()

        repo.sendMessage("/tmp/picture.jpg", Channel.MeshDM(PEER), "me", BitchatMessageType.Image)

        val row = repo.getPrivateChats().getValue(PEER).single()
        assertEquals("not sent over LoRa", assertIs<DeliveryStatus.Failed>(row.deliveryStatus).reason)
        verify(exactly = 0) { mesh.sendFilePrivate(any(), any()) }
        verify(exactly = 0) { mesh.initiateNoiseHandshake(any()) }

        // Not waiting for a session either: when one comes, its path does not go out as a text.
        every { mesh.hasEstablishedSession(PEER) } returns true
        repo.onSessionEstablished(PEER)
        assertTrue(sent.isEmpty())
    }

    @Test
    fun aQueuedTextThatHasBecomeTooLongForItsWayOutIsShownAsFailedAndDoesNotHoldUpTheRest() = withRepo { repo, mesh ->
        // Queued while the peer was nowhere, cut for Bluetooth's 255 bytes.
        every { mesh.getPeerInfo(PEER) } returns null
        every { mesh.reachesByRadio(PEER) } returns false
        val long = "x".repeat(200)
        repo.sendMessage(long, Channel.MeshDM(PEER), "me", BitchatMessageType.Message)
        repo.sendMessage("short", Channel.MeshDM(PEER), "me", BitchatMessageType.Message)

        // Then the peer turns up on the radio and a session is made there: the mesh service takes
        // only what one frame carries.
        every { mesh.reachesByRadio(PEER) } returns true
        every { mesh.hasEstablishedSession(PEER) } returns true
        val taken = mutableListOf<String>()
        every { mesh.sendPrivateMessage(any(), any(), any(), any()) } answers {
            (firstArg<String>().length <= RADIO_TEXT_BYTES).also { fits -> if (fits) taken += firstArg<String>() }
        }
        repo.onSessionEstablished(PEER)

        assertEquals(listOf("short"), taken)
        val rows = repo.getPrivateChats().getValue(PEER).associate { it.content to it.deliveryStatus }
        assertIs<DeliveryStatus.Failed>(rows.getValue(long))
        assertEquals(DeliveryStatus.Sent, rows.getValue("short"))
    }

    @Test
    fun aTextTheMeshServiceRefusesOrLaterReportsAsNotSentIsShownAsFailed() = withRepo { repo, mesh ->
        mesh.hearsOnTheRadio(PEER)
        every { mesh.hasEstablishedSession(PEER) } returns true
        every { mesh.sendPrivateMessage(any(), any(), any(), any()) } returns false
        repo.sendMessage("refused", Channel.MeshDM(PEER), "me", BitchatMessageType.Message)
        assertIs<DeliveryStatus.Failed>(repo.getPrivateChats().getValue(PEER).single().deliveryStatus)

        every { mesh.sendPrivateMessage(any(), any(), any(), any()) } returns true
        repo.sendMessage("accepted", Channel.MeshDM(PEER), "me", BitchatMessageType.Message)
        val accepted = repo.getPrivateChats().getValue(PEER).last()
        assertEquals(DeliveryStatus.Sent, accepted.deliveryStatus)
        repo.didFailToSendPrivateMessage(accepted.id, PEER, "no time on air for it")
        assertEquals(
            "no time on air for it",
            assertIs<DeliveryStatus.Failed>(repo.getPrivateChats().getValue(PEER).last().deliveryStatus).reason,
        )
    }

    @Test
    fun anImageForAPeerThatIsNowhereIsNotRefusedForTheRadio() = withRepo { repo, mesh ->
        every { mesh.getPeerInfo(PEER) } returns null
        every { mesh.reachesByRadio(PEER) } returns false

        repo.sendMessage("/tmp/picture.jpg", Channel.MeshDM(PEER), "me", BitchatMessageType.Image)

        assertEquals(DeliveryStatus.Sent, repo.getPrivateChats().getValue(PEER).single().deliveryStatus)
    }

    @Test
    fun aTextThatWouldNeedMoreThanEightFramesIsRefusedForARadioPeer() = withRepo { repo, mesh ->
        mesh.hearsOnTheRadio(PEER)
        every { mesh.privateTextLimitFor(PEER) } returns RADIO_TEXT_BYTES
        val sent = mesh.recordPrivateSends()

        // Fits eight messages of 255 bytes, not eight of 141.
        assertFailsWith<IllegalArgumentException> {
            repo.sendMessage("x".repeat(8 * RADIO_TEXT_BYTES + 1), Channel.MeshDM(PEER), "me", BitchatMessageType.Message)
        }
        assertTrue(repo.getPrivateChats().isEmpty())
        assertTrue(sent.isEmpty())
    }

    @Test
    fun aTextTakenForBluetoothIsNotCutIntoMoreThanEightWhenItsWayOutHasBecomeTheRadio() = withRepo { repo, mesh ->
        // When the text was taken its peer had Bluetooth's limit; when it is sent, the radio's.
        mesh.hearsOnTheRadio(PEER)
        every { mesh.hasEstablishedSession(PEER) } returns true
        every { mesh.privateTextLimitFor(PEER) } returnsMany listOf(PrivateMessageText.MAX_BYTES, RADIO_TEXT_BYTES)
        val sent = mesh.recordPrivateSends()

        // Eight Bluetooth messages hold it; it would take eleven frames.
        assertFailsWith<IllegalArgumentException> {
            repo.sendMessage("x".repeat(1_500), Channel.MeshDM(PEER), "me", BitchatMessageType.Message)
        }
        assertTrue(sent.isEmpty())
    }

    @Test
    fun anImageForAPeerOnTheMeshThatIsAlsoHeardOnTheRadioIsNotRefusedForTheRadio() = withRepo { repo, mesh ->
        every { mesh.getPeerInfo(PEER) } returns mockk<PeerInfo>(relaxed = true).also { every { it.isConnected } returns true }
        every { mesh.reachesByRadio(PEER) } returns true
        every { mesh.hasEstablishedSession(PEER) } returns true

        repo.sendMessage("/tmp/picture.jpg", Channel.MeshDM(PEER), "me", BitchatMessageType.Image)

        val row = repo.getPrivateChats().getValue(PEER).single()
        assertTrue(row.deliveryStatus !is DeliveryStatus.Failed || (row.deliveryStatus as DeliveryStatus.Failed).reason != "not sent over LoRa")
    }

    private fun BluetoothMeshService.hearsOnTheRadio(peer: String) {
        every { getPeerInfo(peer) } returns null
        every { reachesByRadio(peer) } returns true
    }

    private fun BluetoothMeshService.recordPrivateSends(): List<String> {
        val sent = mutableListOf<String>()
        every { sendPrivateMessage(any(), any(), any(), any()) } answers {
            sent += firstArg<String>()
            true
        }
        return sent
    }

    private fun withRepo(block: suspend TestScope.(ChatRepo, BluetoothMeshService) -> Unit) = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val mesh = mockk<BluetoothMeshService>(relaxed = true)
        every { mesh.privateTextLimitFor(any()) } returns PrivateMessageText.MAX_BYTES
        every { mesh.sendFilePrivate(any(), any()) } returns true
        // And false, which the real service says only of a message it cannot take.
        every { mesh.sendPrivateMessage(any(), any(), any(), any()) } returns true
        try {
            block(chatRepo(scope, dispatcher, mutableListOf(), mesh = mesh), mesh)
        } finally {
            scope.cancel()
        }
    }

    /** A LoRa stack that can carry mesh packets: only its packet flow and its link matter here. */
    private class RadioStack : LoRaProtocol {
        val packets = MutableSharedFlow<ByteArray>(extraBufferCapacity = 1)
        val link = object : MeshRadioLink {
            override fun hears(peerID: String) = false
            override suspend fun send(packet: ByteArray, peerID: String, purpose: RadioPurpose) = RadioSendResult.FAILED
        }

        override val incomingMeshPackets: Flow<ByteArray> = packets
        override val meshPacketLink: MeshRadioLink = link
        override val peers: StateFlow<List<LoRaPeer>> = MutableStateFlow(emptyList())
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
        const val RADIO_TEXT_BYTES = 141
    }
}
