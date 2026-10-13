package com.bitchat.tor

import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import org.junit.Assume.assumeNoException
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ArtiCacheSweepSymlinkTest {
    private val fixture = ArtiStateFixture()
    private val blobs = Path(fixture.dataDir, "cache", "dir_blobs")
    private val logs = mutableListOf<String>()

    @AfterTest
    fun cleanUp() = fixture.delete()

    @Test
    fun `a link to an empty file outside the blob directory is left alone`() {
        val target = Path(fixture.dataDir, "cache", "outside-empty")
        val link = Path(blobs, "linked")
        ArtiStateFixture.write(target, "")
        SystemFileSystem.createDirectories(blobs)
        try {
            Files.createSymbolicLink(Paths.get(link.toString()), Paths.get(target.toString()))
        } catch (e: Exception) {
            assumeNoException("this platform refuses to create symbolic links: ${e.message}", e)
        }

        val removed = removeEmptyArtiCacheFiles(fixture.dataDir.toString()) { logs += it }

        assertEquals(0, removed)
        assertTrue(Files.isSymbolicLink(Paths.get(link.toString())), "the link must survive")
        assertTrue(SystemFileSystem.exists(target), "what the link points at must survive")
        assertEquals(emptyList(), logs)
    }
}
