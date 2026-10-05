@file:OptIn(ExperimentalForeignApi::class)

package com.bitchat.local.statedir

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import platform.posix.F_OK
import platform.posix.O_CREAT
import platform.posix.O_RDWR
import platform.posix.access
import platform.posix.chmod
import platform.posix.close
import platform.posix.geteuid
import platform.posix.getenv
import platform.posix.mkdir
import platform.posix.mkdtemp
import platform.posix.open
import platform.posix.stat
import platform.posix.symlink
import platform.posix.umask

class StateDirectoryTest {
    @Test
    fun homeFromRejectsMissingAndRelativeHomeWithoutATmpFallback() {
        val missing = assertFailsWith<StateDirectoryException> { StateDirectory.homeFrom(null) }
        val empty = assertFailsWith<StateDirectoryException> { StateDirectory.homeFrom("") }
        val relative = assertFailsWith<StateDirectoryException> { StateDirectory.homeFrom("relative/home") }

        assertTrue(missing.message!!.contains("HOME is not set"))
        assertTrue(empty.message!!.contains("HOME is not set"))
        assertTrue(relative.message!!.contains("not an absolute path"))
        assertEquals("/home/u", StateDirectory.homeFrom("/home/u"))
    }

    @Test
    fun ensureOwnCreatesExactly0700RegardlessOfUmask() {
        val root = temporaryDirectory()
        withUmask(0u) {
            StateDirectory.ensureOwn("$root/umask-zero")
        }
        withUmask(0x3Fu) {
            StateDirectory.ensureOwn("$root/umask-077")
        }

        assertEquals("700", permissions("$root/umask-zero"))
        assertEquals("700", permissions("$root/umask-077"))
    }

    @Test
    fun ensureOwnTightensExistingOwnDirectories() {
        val root = temporaryDirectory()
        val readable = directoryWithMode("$root/readable", 0x1ED) // 0755
        val writable = directoryWithMode("$root/writable", 0x1FF) // 0777

        StateDirectory.ensureOwn(readable)
        StateDirectory.ensureOwn(writable)

        assertEquals("700", permissions(readable))
        assertEquals("700", permissions(writable))
    }

    @Test
    fun aDirectoryStillWritableByOthersAfterTheChmodIsRefused() {
        // A filesystem on which chmod changes nothing (FAT, some FUSE mounts): what counts is what is left.
        val root = temporaryDirectory()
        val writable = directoryWithMode("$root/writable", 0x1FF) // 0777
        val groupWritable = directoryWithMode("$root/group-writable", 0x1F8) // 0770
        val readable = directoryWithMode("$root/readable", 0x1ED) // 0755

        val error = assertFailsWith<StateDirectoryException> {
            StateDirectory.ensureOwn(writable, geteuid(), tighten = {})
        }
        assertFailsWith<StateDirectoryException> { StateDirectory.ensureOwn(groupWritable, geteuid(), tighten = {}) }
        assertFailsWith<StateDirectoryException> { StateDirectory.ownIfPresent(writable, geteuid(), tighten = {}) }

        assertTrue(error.message!!.contains("$writable is writable by other users (mode 777)"), error.message)
        // Readable by others is not writable by others: this user's files in it stay where they are.
        StateDirectory.ensureOwn(readable, geteuid(), tighten = {})
        assertEquals("755", permissions(readable))
    }

    @Test
    fun ensureOwnRefusesAnotherOwnerBeforeChangingItsMode() {
        val path = directoryWithMode(temporaryDirectory() + "/not-us", 0x1ED) // 0755

        val error = assertFailsWith<StateDirectoryException> {
            StateDirectory.ensureOwn(path, geteuid() + 1u)
        }

        assertTrue(error.message!!.contains(path))
        assertEquals("755", permissions(path))
    }

    @Test
    fun ensureOwnRefusesASymbolicLinkAndNeverCreatesInItsTarget() {
        val root = temporaryDirectory()
        val target = "$root/target"
        val link = "$root/link"
        check(mkdir(target, 0x1C0.convert()) == 0)
        check(symlink(target, link) == 0)

        assertFailsWith<StateDirectoryException> { StateDirectory.ensureOwn(link) }

        assertFalse(access("$target/created", F_OK) == 0)
    }

    @Test
    fun ensureOwnRefusesARegularFileAndAMissingParent() {
        val root = temporaryDirectory()
        val file = "$root/file"
        val descriptor = open(file, O_CREAT or O_RDWR, 0x180)
        check(descriptor >= 0)
        close(descriptor)

        assertFailsWith<StateDirectoryException> { StateDirectory.ensureOwn(file) }
        val error = assertFailsWith<StateDirectoryException> { StateDirectory.ensureOwn("$root/missing/child") }
        assertTrue(error.message!!.contains("cannot create"))
    }

    @Test
    fun ownIfPresentDoesNotCreateButRepairsAndRejectsUnsafeDirectories() {
        val root = temporaryDirectory()
        val missing = "$root/missing"
        assertFalse(StateDirectory.ownIfPresent(missing))
        assertFalse(access(missing, F_OK) == 0)

        val loose = directoryWithMode("$root/loose", 0x1ED) // 0755
        assertTrue(StateDirectory.ownIfPresent(loose))
        assertEquals("700", permissions(loose))

        val target = "$root/target"
        val link = "$root/link"
        check(mkdir(target, 0x1C0.convert()) == 0)
        check(symlink(target, link) == 0)
        assertFailsWith<StateDirectoryException> { StateDirectory.ownIfPresent(link) }

        val wrong = directoryWithMode("$root/wrong", 0x1ED) // 0755
        assertFailsWith<StateDirectoryException> { StateDirectory.ownIfPresent(wrong, geteuid() + 1u) }
        assertEquals("755", permissions(wrong))
    }

    @Test
    fun ownBelowCreatesPrivateParentsAndRejectsNonPlainNamesWithoutCreatingThem() {
        val base = temporaryDirectory() + "/state"
        val result = StateDirectory.ownBelow(base, listOf("images", "incoming"))

        assertEquals("$base/images/incoming", result)
        assertEquals("700", permissions(base))
        assertEquals("700", permissions("$base/images"))
        assertEquals("700", permissions(result))

        for (bad in listOf("..", ".", "", "a/b", "bad" + 0.toChar() + "name")) {
            val rejectedBase = temporaryDirectory() + "/rejected"
            assertFailsWith<StateDirectoryException> { StateDirectory.ownBelow(rejectedBase, listOf(bad)) }
            assertFalse(access(rejectedBase, F_OK) == 0, "created base for $bad")
        }
    }

    @Test
    fun ownBelowStopsAtALinkOnTheWayAndCreatesNothingBehindIt() {
        val root = temporaryDirectory()
        val base = "$root/state"
        val elsewhere = directoryWithMode("$root/elsewhere", 0x1C0)
        StateDirectory.ensureOwn(base)
        check(symlink(elsewhere, "$base/images") == 0)

        assertFailsWith<StateDirectoryException> { StateDirectory.ownBelow(base, listOf("images", "incoming")) }

        assertFalse(access("$elsewhere/incoming", F_OK) == 0, "a directory was created behind the link")
    }

    @Test
    fun presentBelowCreatesNothingAndTrustsOnlyOwnDirectories() {
        val root = temporaryDirectory()
        val base = "$root/state"

        assertEquals(null, StateDirectory.presentBelow(base, listOf("settings")))
        assertFalse(access(base, F_OK) == 0, "the state directory was created")

        StateDirectory.ensureOwn(base)
        assertEquals(null, StateDirectory.presentBelow(base, listOf("settings")))
        assertFalse(access("$base/settings", F_OK) == 0, "the settings directory was created")

        directoryWithMode("$base/settings", 0x1ED)
        assertEquals("$base/settings", StateDirectory.presentBelow(base, listOf("settings")))
        assertEquals(base, StateDirectory.presentBelow(base, emptyList()))

        // A settings directory someone linked to a place of their own is not read from.
        val elsewhere = directoryWithMode("$root/elsewhere", 0x1C0)
        check(symlink(elsewhere, "$base/linked") == 0)
        assertFailsWith<StateDirectoryException> { StateDirectory.presentBelow(base, listOf("linked")) }
        assertFailsWith<StateDirectoryException> { StateDirectory.presentBelow(base, listOf("settings"), geteuid() + 1u) }
        assertFailsWith<StateDirectoryException> { StateDirectory.presentBelow(base, listOf("..")) }
    }

    @Test
    fun isPlainNameAcceptsOnlyOneOrdinaryPathComponent() {
        assertTrue(StateDirectory.isPlainName("photo 1.final.jpg"))
        for (bad in listOf("..", ".", "", "a/b", "bad" + 0.toChar() + "name")) {
            assertFalse(StateDirectory.isPlainName(bad), bad)
        }
    }

    private fun temporaryDirectory(): String {
        val root = getenv("TMPDIR")?.toKString()?.trimEnd('/')?.takeIf { it.isNotEmpty() } ?: "/tmp"
        val template = "$root/bitchat-statedir-test.XXXXXX".encodeToByteArray() + 0.toByte()
        return template.usePinned { mkdtemp(it.addressOf(0))?.toKString() } ?: error("mkdtemp failed in $root")
    }

    /** Creates [path] with exactly [mode], whatever the umask, and returns it. */
    private fun directoryWithMode(path: String, mode: Int): String {
        check(mkdir(path, 0x1C0.convert()) == 0) { "cannot create $path" }
        check(chmod(path, mode.convert()) == 0) { "cannot chmod $path" }
        check(permissions(path) == mode.toString(8)) { "$path is ${permissions(path)}, not ${mode.toString(8)}" }
        return path
    }

    private fun withUmask(value: UInt, block: () -> Unit) {
        val previous = umask(value.convert())
        try {
            block()
        } finally {
            umask(previous)
        }
    }

    private fun permissions(path: String): String = memScoped {
        val info = alloc<stat>()
        check(stat(path, info.ptr) == 0) { "cannot stat $path" }
        (info.st_mode.convert<Int>() and 0x1FF).toString(8)
    }
}
