package com.bitchat.tor

import kotlinx.io.IOException
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ArtiCacheSweepTest {
    private val fixture = ArtiStateFixture()
    private val cache = Path(fixture.dataDir, "cache")
    private val blobs = Path(cache, "dir_blobs")
    private val logs = mutableListOf<String>()

    @AfterTest
    fun cleanUp() = fixture.delete()

    private fun sweep() = removeEmptyArtiCacheFiles(fixture.dataDir.toString()) { logs += it }

    @Test
    fun `an empty blob is removed and a non-empty one keeps its contents`() {
        val empty = Path(blobs, "sha3-256-aa")
        val full = Path(blobs, "sha3-256-bb")
        ArtiStateFixture.write(empty, "")
        ArtiStateFixture.write(full, "consensus bytes")

        val removed = sweep()

        assertEquals(1, removed)
        assertFalse(SystemFileSystem.exists(empty), "the empty file must be gone")
        assertEquals("consensus bytes", ArtiStateFixture.read(full))
        assertEquals(1, logs.size, "one line: $logs")
    }

    @Test
    fun `a file of one byte stays`() {
        val oneByte = Path(blobs, "sha3-256-cc")
        ArtiStateFixture.write(oneByte, "x")

        assertEquals(0, sweep())

        assertEquals("x", ArtiStateFixture.read(oneByte))
        assertTrue(logs.isEmpty(), "nothing to say: $logs")
    }

    @Test
    fun `the lock file and the cache database are untouched`() {
        val lock = Path(cache, "dir.lock")
        ArtiStateFixture.write(lock, "")
        ArtiStateFixture.write(Path(blobs, "sha3-256-aa"), "")

        sweep()

        assertTrue(SystemFileSystem.exists(lock), "dir.lock is legitimately empty")
        assertEquals("consensus cache", ArtiStateFixture.read(Path(cache, "dir.sqlite3")))
        fixture.assertUntouched()
    }

    @Test
    fun `no cache directory at all removes nothing and logs nothing`() {
        ArtiStateFixture.deleteRecursively(cache)

        assertEquals(0, sweep())
        assertEquals(emptyList(), logs)
    }

    @Test
    fun `a subdirectory inside the blob directory is left alone`() {
        val sub = Path(blobs, "nested")
        val inside = Path(sub, "empty")
        val emptyDirectory = Path(blobs, "hollow")
        ArtiStateFixture.write(inside, "")
        SystemFileSystem.createDirectories(emptyDirectory)

        assertEquals(0, sweep())

        assertTrue(SystemFileSystem.exists(sub))
        assertTrue(SystemFileSystem.exists(emptyDirectory))
        assertTrue(SystemFileSystem.exists(inside), "only files directly in dir_blobs are looked at")
        assertEquals(emptyList(), logs)
    }

    @Test
    fun `nothing empty removes nothing and logs nothing`() {
        val full = Path(blobs, "sha3-256-bb")
        ArtiStateFixture.write(full, "consensus bytes")

        assertEquals(0, sweep())

        assertEquals("consensus bytes", ArtiStateFixture.read(full))
        assertEquals(emptyList(), logs)
    }

    @Test
    fun `a blob directory that is a regular file removes nothing and logs one line`() {
        ArtiStateFixture.write(blobs, "not a directory")

        assertEquals(0, sweep())

        assertEquals(1, logs.size, "one line: $logs")
        assertEquals("not a directory", ArtiStateFixture.read(blobs))
    }

    @Test
    fun `a delete that fails does not stop the sweep`() {
        val first = Path(blobs, "sha3-256-aa")
        val second = Path(blobs, "sha3-256-bb")
        ArtiStateFixture.write(first, "")
        ArtiStateFixture.write(second, "")
        var calls = 0
        val delete: (Path) -> Unit = {
            if (calls++ == 0) throw IOException("Permission denied")
            SystemFileSystem.delete(it)
        }

        val removed = removeEmptyArtiCacheFiles(fixture.dataDir.toString(), delete) { logs += it }

        assertEquals(1, removed)
        assertEquals(2, calls, "the sweep went on to the other file")
        assertEquals(1, listOf(first, second).count { !SystemFileSystem.exists(it) }, "exactly one is gone")
        assertEquals(2, logs.size, "one failure and one summary: $logs")
        assertTrue(logs.count { "Could not remove" in it && "Permission denied" in it } == 1, "$logs")
        assertTrue(logs.count { it.startsWith("Removed 1 ") } == 1, "$logs")
    }

    @Test
    fun `a log function that throws does not stop the sweep`() {
        val empty = Path(blobs, "sha3-256-aa")
        ArtiStateFixture.write(empty, "")

        val removed = removeEmptyArtiCacheFiles(fixture.dataDir.toString()) { throw IllegalStateException("log sink broke") }

        assertEquals(1, removed)
        assertFalse(SystemFileSystem.exists(empty), "the empty file must be gone")
    }
}
