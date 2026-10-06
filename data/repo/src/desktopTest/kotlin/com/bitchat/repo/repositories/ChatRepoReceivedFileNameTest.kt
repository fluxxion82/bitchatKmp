package com.bitchat.repo.repositories

import com.bitchat.domain.chat.model.BitchatFilePacket
import com.bitchat.domain.chat.model.BitchatMessage
import com.bitchat.repo.utils.ReceivedFileBudget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.readBytes
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What a file received over the mesh becomes on disk. Its name is the sender's choice and mesh senders are not
 * authenticated, so these go through the real desktop `saveFileToLocal` under a temporary home directory.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatRepoReceivedFileNameTest {

    @Test
    fun savesAReceivedFileUnderItsSafeNameInADirectoryOfItsOwn() = withChatRepo { chatRepo, home ->
        val bytes = byteArrayOf(1, 2, 3)

        chatRepo.didReceivePublicFile("peer", packet("../../../escaped.txt", bytes))
        awaitMeshMessages(chatRepo, 1)

        val saved = regularFiles(home).single()
        assertEquals("_.._.._escaped.txt", saved.fileName.toString())
        // Directly under the incoming directory, in a directory this app named.
        assertEquals(home.resolve(".bitchat/files/incoming"), saved.parent.parent)
        assertTrue(UUID.matches(saved.parent.fileName.toString()), saved.parent.fileName.toString())
        assertContentEquals(bytes, saved.readBytes())
        assertEquals(saved.toString(), chatRepo.getMeshMessages().single().content)
    }

    @Test
    fun aLaterFileWithTheSameNameDoesNotReplaceTheOneAMessageShows() = withChatRepo { chatRepo, home ->
        val first = byteArrayOf(1, 1, 1)
        val second = byteArrayOf(2, 2, 2, 2)

        chatRepo.didReceivePublicFile("alice", packet("photo.jpg", first))
        awaitMeshMessages(chatRepo, 1)
        chatRepo.didReceivePublicFile("mallory", packet("photo.jpg", second))
        awaitMeshMessages(chatRepo, 2)

        val paths = chatRepo.getMeshMessages().map { Path.of(it.content) }
        assertEquals(2, paths.toSet().size)
        assertEquals(listOf("photo.jpg", "photo.jpg"), paths.map { it.fileName.toString() })
        assertContentEquals(first, paths[0].readBytes())
        assertContentEquals(second, paths[1].readBytes())
        assertEquals(2, regularFiles(home).size)
        assertEquals(2, chatRepo.getMeshMessages().map { it.id }.toSet().size)
    }

    @Test
    fun receivedFilesStayRefusedAfterTheRunBudgetIsReached() = withChatRepo(
        receivedFileBudget = ReceivedFileBudget(limitBytes = 40L * 1024),
    ) { chatRepo, home ->
        repeat(3) { chatRepo.didReceivePublicFile("peer", packet("$it.bin", byteArrayOf(it.toByte()))) }
        awaitMeshMessages(chatRepo, 2)
        assertNull(waitForMeshMessages(chatRepo, 3), "the third minimum-charge file must not be saved")
        chatRepo.didReceivePublicFile("peer", packet("later.bin", byteArrayOf(9)))
        assertNull(waitForMeshMessages(chatRepo, 3), "a full run budget remains full")
        assertEquals(2, regularFiles(home).size)
    }

    @Test
    fun aPrivateFileOutOfANoiseSessionIsSavedTheSameWay() = withChatRepo { chatRepo, home ->
        val bytes = byteArrayOf(4, 5, 6)

        // A peer that holds a session still chooses the name; private files share the public save path.
        chatRepo.didReceiveAuthenticatedPrivateFile("peer", packet("../../../escaped.txt", bytes))
        withContext(Dispatchers.Default) {
            withTimeout(5_000) { while (chatRepo.getPrivateChats()["peer"].isNullOrEmpty()) delay(10) }
        }

        val saved = regularFiles(home).single()
        assertEquals("_.._.._escaped.txt", saved.fileName.toString())
        assertEquals(home.resolve(".bitchat/files/incoming"), saved.parent.parent)
        assertContentEquals(bytes, saved.readBytes())
        assertEquals(saved.toString(), chatRepo.getPrivateChats().getValue("peer").single().content)
        assertTrue(chatRepo.getMeshMessages().isEmpty())
    }

    private fun packet(name: String, bytes: ByteArray) =
        BitchatFilePacket(name, bytes.size.toLong(), "application/octet-stream", bytes)

    private fun regularFiles(home: Path): List<Path> =
        Files.walk(home).use { paths -> paths.filter { it.isRegularFile() }.toList() }

    /** Saving runs on `Dispatchers.IO`, outside the test scheduler: wait for its result, with a bound. */
    private suspend fun awaitMeshMessages(chatRepo: ChatRepo, count: Int) = withContext(Dispatchers.Default) {
        withTimeout(5_000) {
            while (chatRepo.getMeshMessages().size != count) delay(10)
        }
    }

    private suspend fun waitForMeshMessages(chatRepo: ChatRepo, count: Int): List<BitchatMessage>? =
        withContext(Dispatchers.Default) {
            withTimeoutOrNull(200) {
                while (chatRepo.getMeshMessages().size < count) delay(10)
                chatRepo.getMeshMessages()
            }
        }

    private fun withChatRepo(
        receivedFileBudget: ReceivedFileBudget = ReceivedFileBudget(),
        block: suspend TestScope.(ChatRepo, Path) -> Unit,
    ) = runTest {
        val originalUserHome = System.getProperty("user.home")
        val temporaryHome = createTempDirectory("chat-repo-received-file-name-test")
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        try {
            System.setProperty("user.home", temporaryHome.toString())
            block(chatRepo(scope, dispatcher, mutableListOf(), receivedFileBudget = receivedFileBudget), temporaryHome)
        } finally {
            scope.cancel()
            System.setProperty("user.home", originalUserHome)
            Files.walk(temporaryHome).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach(Files::delete)
            }
        }
    }

    private companion object {
        val UUID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    }
}
