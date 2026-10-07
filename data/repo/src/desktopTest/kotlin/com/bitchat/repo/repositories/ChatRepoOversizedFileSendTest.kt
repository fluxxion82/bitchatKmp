package com.bitchat.repo.repositories

import com.bitchat.bluetooth.model.PeerInfo
import com.bitchat.bluetooth.service.BluetoothMeshService
import com.bitchat.domain.chat.model.BitchatFilePacket
import com.bitchat.domain.chat.model.BitchatMessageType
import com.bitchat.domain.chat.model.DeliveryStatus
import com.bitchat.domain.location.model.Channel
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
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeBytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * A receiver refuses a frame over the mesh limit, so a file that could not arrive is not sent: it stays
 * on this device and its message says why. Audio is the case that matters, because it is sent as it is.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatRepoOversizedFileSendTest {
    private val limit = BitchatFilePacket.MAX_CONTENT_BYTES
    private val reason = "file is larger than ${limit / 1024} KiB"

    @Test
    fun anAudioFileOverTheLimitIsNotBroadcastAndItsMessageSaysSo() = withChatRepo { chatRepo, mesh, directory ->
        val file = audioFile(directory, limit + 1)

        chatRepo.sendMessage(file.toString(), Channel.Mesh, "me", BitchatMessageType.Audio)

        verify(exactly = 0) { mesh.sendFileBroadcast(any()) }
        val status = assertIs<DeliveryStatus.Failed>(chatRepo.getMeshMessages().single().deliveryStatus)
        assertEquals(reason, status.reason)
    }

    @Test
    fun anAudioFileOfExactlyTheLimitIsBroadcast() = withChatRepo { chatRepo, mesh, directory ->
        val file = audioFile(directory, limit)

        chatRepo.sendMessage(file.toString(), Channel.Mesh, "me", BitchatMessageType.Audio)

        verify(exactly = 1) { mesh.sendFileBroadcast(match { it.content.size == limit }) }
    }

    @Test
    fun aPrivateAudioFileOverTheLimitIsNotSentAndItsMessageSaysSo() = withChatRepo { chatRepo, mesh, directory ->
        every { mesh.getPeerInfo("peer") } returns mockk<PeerInfo>(relaxed = true).also { every { it.isConnected } returns true }
        every { mesh.hasEstablishedSession("peer") } returns true
        val file = audioFile(directory, limit + 1)

        chatRepo.sendMessage(file.toString(), Channel.MeshDM("peer"), "me", BitchatMessageType.Audio)

        verify(exactly = 0) { mesh.sendFilePrivate(any(), any()) }
        val status = assertIs<DeliveryStatus.Failed>(chatRepo.getPrivateChats().getValue("peer").single().deliveryStatus)
        assertEquals(reason, status.reason)
    }

    @Test
    fun aPrivateAudioFileOfExactlyTheLimitIsSent() = withChatRepo { chatRepo, mesh, directory ->
        every { mesh.getPeerInfo("peer") } returns mockk<PeerInfo>(relaxed = true).also { every { it.isConnected } returns true }
        every { mesh.hasEstablishedSession("peer") } returns true
        val file = audioFile(directory, limit)

        chatRepo.sendMessage(file.toString(), Channel.MeshDM("peer"), "me", BitchatMessageType.Audio)

        verify(exactly = 1) { mesh.sendFilePrivate("peer", match { it.content.size == limit }) }
        assertEquals(DeliveryStatus.Sent, chatRepo.getPrivateChats().getValue("peer").single().deliveryStatus)
    }

    @Test
    fun aPrivateVoiceNoteTheMeshServiceStartsNothingForIsShownAsNotSent() = withChatRepo { chatRepo, mesh, directory ->
        chatRepo.assertShownAsNotSentWhenTheServiceRefuses(mesh, audioFile(directory, 16), BitchatMessageType.Audio)
    }

    @Test
    fun aPrivateImageTheMeshServiceStartsNothingForIsShownAsNotSent() = withChatRepo { chatRepo, mesh, directory ->
        // Small enough to be sent as it is, whatever is in it.
        val picture = directory.resolve("picture.png").also { it.writeBytes(ByteArray(16) { 1 }) }
        chatRepo.assertShownAsNotSentWhenTheServiceRefuses(mesh, picture, BitchatMessageType.Image)
    }

    /** The mesh had the peer when the repository looked; when the service looked, its way out was the radio. */
    private suspend fun ChatRepo.assertShownAsNotSentWhenTheServiceRefuses(mesh: BluetoothMeshService, file: Path, type: BitchatMessageType) {
        every { mesh.getPeerInfo("peer") } returns mockk<PeerInfo>(relaxed = true).also { every { it.isConnected } returns true }
        every { mesh.hasEstablishedSession("peer") } returns true
        every { mesh.sendFilePrivate("peer", any()) } returns false

        sendMessage(file.toString(), Channel.MeshDM("peer"), "me", type)

        verify(exactly = 1) { mesh.sendFilePrivate("peer", any()) }
        val status = assertIs<DeliveryStatus.Failed>(getPrivateChats().getValue("peer").single().deliveryStatus)
        assertEquals("not sent over LoRa", status.reason)
    }

    private fun audioFile(directory: Path, size: Int): Path =
        directory.resolve("voice.m4a").also { it.writeBytes(ByteArray(size) { 1 }) }

    private fun withChatRepo(block: suspend TestScope.(ChatRepo, BluetoothMeshService, Path) -> Unit) = runTest {
        val directory = createTempDirectory("chat-repo-oversized-send-test")
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val mesh = mockk<BluetoothMeshService>(relaxed = true)
        // And false, which the real service says only of a file it starts nothing for.
        every { mesh.sendFilePrivate(any(), any()) } returns true
        try {
            block(chatRepo(scope, dispatcher, mutableListOf(), mesh = mesh), mesh, directory)
        } finally {
            scope.cancel()
            Files.walk(directory).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
        }
    }
}
