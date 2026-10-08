package com.bitchat.repo.repositories

import com.bitchat.bluetooth.model.PeerInfo
import com.bitchat.bluetooth.service.BluetoothMeshService
import com.bitchat.domain.chat.model.BitchatMessageType
import com.bitchat.domain.chat.model.DeliveryStatus
import com.bitchat.domain.chat.model.PrivateMessageText
import com.bitchat.domain.location.model.Channel
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame

/**
 * A peer's word, out of its Noise session, that a private message arrived moves the row of that
 * message forward to Delivered, and does nothing else.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatRepoDeliveryAckTest {

    @Test
    fun aMessageThatWasSentIsDeliveredOnceItsPeerSaysSo() = withRepo { repo, _ ->
        val id = repo.send("hello")
        assertEquals(DeliveryStatus.Sent, repo.statusOf(id))

        repo.didReceiveAuthenticatedDeliveryAck(id, PEER)
        runCurrent()

        assertIs<DeliveryStatus.Delivered>(repo.statusOf(id))
    }

    @Test
    fun aMessageReportedAsNotSentIsDeliveredOnceItsPeerSaysSo() = withRepo { repo, _ ->
        // The radio may call a frame failed that was on the air: the peer's word settles it.
        val id = repo.send("hello")
        repo.didFailToSendPrivateMessage(id, PEER, "the radio failed")
        assertIs<DeliveryStatus.Failed>(repo.statusOf(id))

        repo.didReceiveAuthenticatedDeliveryAck(id, PEER)

        assertIs<DeliveryStatus.Delivered>(repo.statusOf(id))
    }

    @Test
    fun aMessageThatIsDeliveredStaysAsItIsWhateverComesAfter() = withRepo { repo, _ ->
        val id = repo.send("hello")
        repo.didReceiveAuthenticatedDeliveryAck(id, PEER)
        val delivered = repo.statusOf(id)

        // Neither a second word of its arrival nor a late report that it did not go out touches it.
        repo.didReceiveAuthenticatedDeliveryAck(id, PEER)
        repo.didFailToSendPrivateMessage(id, PEER, "no time on air for it")

        assertSame(delivered, repo.statusOf(id))
    }

    @Test
    fun aMessageThatWasSentAndIsReportedAsNotSentFails() = withRepo { repo, _ ->
        val id = repo.send("hello")

        repo.didFailToSendPrivateMessage(id, PEER, "the peer is out of reach")

        assertEquals("the peer is out of reach", assertIs<DeliveryStatus.Failed>(repo.statusOf(id)).reason)
        // And the first reason given stands.
        repo.didFailToSendPrivateMessage(id, PEER, "another reason")
        assertEquals("the peer is out of reach", assertIs<DeliveryStatus.Failed>(repo.statusOf(id)).reason)
    }

    @Test
    fun aWordAboutAMessageThatIsNotInThatPeersChatChangesAndCreatesNothing() = withRepo { repo, mesh ->
        mesh.has(OTHER)
        val id = repo.send("hello")
        val before = repo.getPrivateChats()

        // An id nobody sent; this chat's message named by another peer; a peer there is no chat with.
        repo.didReceiveAuthenticatedDeliveryAck("f".repeat(36), PEER)
        repo.didReceiveAuthenticatedDeliveryAck(id, OTHER)
        repo.didReceiveAuthenticatedDeliveryAck(id, "0000000000000000")

        assertEquals(before, repo.getPrivateChats())
        assertEquals(setOf(PEER), repo.getPrivateChats().keys)
    }

    private suspend fun ChatRepo.send(text: String): String {
        sendMessage(text, Channel.MeshDM(PEER), "me", BitchatMessageType.Message)
        return getPrivateChats().getValue(PEER).single().id
    }

    private suspend fun ChatRepo.statusOf(id: String): DeliveryStatus? =
        getPrivateChats().getValue(PEER).single { it.id == id }.deliveryStatus

    private fun BluetoothMeshService.has(peer: String) {
        every { getPeerInfo(peer) } returns mockk<PeerInfo>(relaxed = true).also { every { it.isConnected } returns true }
        every { hasEstablishedSession(peer) } returns true
    }

    private fun withRepo(block: suspend TestScope.(ChatRepo, BluetoothMeshService) -> Unit) = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val mesh = mockk<BluetoothMeshService>(relaxed = true)
        every { mesh.privateTextLimitFor(any()) } returns PrivateMessageText.MAX_BYTES
        every { mesh.sendPrivateMessage(any(), any(), any(), any()) } returns true
        mesh.has(PEER)
        try {
            block(chatRepo(scope, dispatcher, mutableListOf(), mesh = mesh), mesh)
        } finally {
            scope.cancel()
        }
    }

    private companion object {
        const val PEER = "0123456789abcdef"
        const val OTHER = "fedcba9876543210"
    }
}
