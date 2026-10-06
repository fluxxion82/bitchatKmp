package com.bitchat.repo.repositories

import com.bitchat.domain.chat.model.BitchatMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class ChatRepoAuthenticatedMeshPrivateTest {

    @Test
    fun genericMeshReceiveRejectsPrivateMessageWithoutCreatingUnreadState() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)

        try {
            val chatRepo = chatRepo(scope, dispatcher, mutableListOf())

            chatRepo.didReceiveMessage(privateMessage())
            runCurrent()

            assertTrue(chatRepo.getPrivateChats().isEmpty())
            assertTrue(chatRepo.getUnreadPrivatePeers().isEmpty())
            // Refused, not rerouted: a private message must not surface in the public channel either.
            assertTrue(chatRepo.getMeshMessages().isEmpty())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun authenticatedMeshPrivateReceiveStoresOneMessageInTheClaimedConversation() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)

        try {
            val chatRepo = chatRepo(scope, dispatcher, mutableListOf())

            chatRepo.didReceiveAuthenticatedPrivateMessage(privateMessage())
            runCurrent()

            assertEquals(
                listOf(privateMessage().copy(sender = PEER_ID.take(12))),
                chatRepo.getPrivateChats().getValue(PEER_ID),
            )
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun genericMeshReceiveStillStoresPublicMessageInTheMeshChannel() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)

        try {
            val chatRepo = chatRepo(scope, dispatcher, mutableListOf())
            val publicMessage = privateMessage().copy(isPrivate = false)

            chatRepo.didReceiveMessage(publicMessage)
            runCurrent()

            assertEquals(listOf(publicMessage), chatRepo.getMeshMessages())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun meshReceiptsDoNotRewriteAnyConversation() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)

        try {
            val chatRepo = chatRepo(scope, dispatcher, mutableListOf())
            chatRepo.didReceiveAuthenticatedPrivateMessage(privateMessage())
            runCurrent()
            val before = chatRepo.getPrivateChats()

            // Receipts are not applied on the mesh yet (the conversation lists have no writer lock),
            // so neither the chat's own peer nor another one can change a stored message with them.
            chatRepo.didReceiveAuthenticatedDeliveryAck(privateMessage().id, PEER_ID)
            chatRepo.didReceiveAuthenticatedReadReceipt(privateMessage().id, PEER_ID)
            chatRepo.didReceiveAuthenticatedDeliveryAck(privateMessage().id, OTHER_PEER_ID)
            chatRepo.didReceiveAuthenticatedReadReceipt(privateMessage().id, OTHER_PEER_ID)
            runCurrent()

            assertEquals(before, chatRepo.getPrivateChats())
        } finally {
            scope.cancel()
        }
    }

    private fun privateMessage() = BitchatMessage(
        id = "private-message-id",
        sender = "alice",
        senderPeerID = PEER_ID,
        content = "authenticated message",
        timestamp = Instant.fromEpochSeconds(1),
        isPrivate = true,
    )

    private companion object {
        const val PEER_ID = "1111111111111111"
        const val OTHER_PEER_ID = "2222222222222222"
    }
}
