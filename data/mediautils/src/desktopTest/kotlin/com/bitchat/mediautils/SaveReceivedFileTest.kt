package com.bitchat.mediautils

import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.readBytes
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SaveReceivedFileTest {

    @Test
    fun rejectsNamesThatCouldEscapeTheIncomingDirectory() = runTest {
        withTemporaryUserHome { home ->
            val incoming = home.resolve(".bitchat/files/incoming")

            assertNull(saveFileToLocal(byteArrayOf(1), "../../../escaped.txt", "files/incoming"))
            assertNull(saveFileToLocal(byteArrayOf(2), "/absolute-looking.txt", "files/incoming"))
            assertNull(saveFileToLocal(byteArrayOf(3), "back\\slash.txt", "files/incoming"))

            val filesOutsideIncoming = Files.walk(home).use { paths ->
                paths.filter { it.isRegularFile() && !it.startsWith(incoming) }.toList()
            }
            assertEquals(emptyList(), filesOutsideIncoming)
        }
    }

    @Test
    fun savesAPlainNameInsideTheIncomingDirectory() = runTest {
        withTemporaryUserHome { home ->
            val bytes = byteArrayOf(1, 2, 3)
            val path = saveFileToLocal(bytes, "received.txt", "files/incoming")

            assertEquals(home.resolve(".bitchat/files/incoming/received.txt").toString(), path)
            assertContentEquals(bytes, home.resolve(".bitchat/files/incoming/received.txt").readBytes())
        }
    }

    @Test
    fun doesNotReplaceAFileThatIsAlreadyThere() = runTest {
        withTemporaryUserHome { home ->
            val first = byteArrayOf(1, 2, 3)
            val path = saveFileToLocal(first, "photo.jpg", "images/incoming")

            // Whoever sends a file chooses its name; an earlier file may be one a message shows.
            assertNull(saveFileToLocal(byteArrayOf(9, 9), "photo.jpg", "images/incoming"))

            assertEquals(home.resolve(".bitchat/images/incoming/photo.jpg").toString(), path)
            assertContentEquals(first, home.resolve(".bitchat/images/incoming/photo.jpg").readBytes())
        }
    }

    private suspend fun withTemporaryUserHome(block: suspend (Path) -> Unit) {
        val originalUserHome = System.getProperty("user.home")
        val temporaryHome = createTempDirectory("save-received-file-test")
        try {
            System.setProperty("user.home", temporaryHome.toString())
            block(temporaryHome)
        } finally {
            System.setProperty("user.home", originalUserHome)
            Files.walk(temporaryHome).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach(Files::delete)
            }
        }
    }
}
