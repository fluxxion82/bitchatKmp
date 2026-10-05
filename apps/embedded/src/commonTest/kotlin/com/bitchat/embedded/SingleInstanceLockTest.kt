@file:OptIn(ExperimentalForeignApi::class)

package com.bitchat.embedded

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
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
import platform.posix.O_RDONLY
import platform.posix.access
import platform.posix.chmod
import platform.posix.close
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fputs
import platform.posix.getenv
import platform.posix.link
import platform.posix.mkdir
import platform.posix.mkdtemp
import platform.posix.open
import platform.posix.read
import platform.posix.stat
import platform.posix.symlink

/**
 * Runs on the build host (macosArm64Test) against the same `flock` code the boards run. Two opens
 * of one file in one process contend exactly as two processes do: a `flock` lock belongs to the
 * open file description, not to the process.
 */
class SingleInstanceLockTest {
    private val directory = temporaryDirectory() + "/.bitchat"
    private val tui = Holder("bitchat-tui", 812, "/dev/tty1")
    private val compose = Holder("bitchat-embedded", 4321, null)

    @Test
    fun secondAttemptIsRefusedAndNamesTheHolder() {
        val first = assertIs<Attempt.Acquired>(SingleInstanceLock.tryAcquire(directory, tui))

        val second = assertIs<Attempt.Held>(SingleInstanceLock.tryAcquire(directory, compose))

        assertEquals(tui, second.holder)
        close(first.descriptor)
    }

    @Test
    fun theLockIsFreeOnceItsDescriptorCloses() {
        val first = assertIs<Attempt.Acquired>(SingleInstanceLock.tryAcquire(directory, tui))

        // All that is left of a holder that exited, crashed or lost power: the kernel closed its descriptors.
        close(first.descriptor)

        val second = assertIs<Attempt.Acquired>(SingleInstanceLock.tryAcquire(directory, compose))
        assertEquals("name=bitchat-embedded\npid=4321\n", readFile("$directory/instance.lock"))
        close(second.descriptor)
    }

    @Test
    fun aLockFileNobodyHoldsDoesNotBlock() {
        val first = assertIs<Attempt.Acquired>(SingleInstanceLock.tryAcquire(directory, tui))
        close(first.descriptor)
        writeFile("$directory/instance.lock", "name=bitchat-tui\npid=1\ntty=/dev/tty1\nleft over by a longer line\n")

        val second = assertIs<Attempt.Acquired>(SingleInstanceLock.tryAcquire(directory, compose))

        assertEquals("name=bitchat-embedded\npid=4321\n", readFile("$directory/instance.lock"))
        close(second.descriptor)
    }

    @Test
    fun theLockFileAndItsDirectoryArePrivateToTheirOwner() {
        val lock = assertIs<Attempt.Acquired>(SingleInstanceLock.tryAcquire(directory, tui))

        assertEquals("700", permissions(directory))
        assertEquals("600", permissions("$directory/instance.lock"))
        close(lock.descriptor)
    }

    @Test
    fun aLockFileOtherUsersCouldOpenIsRemovedNotTrusted() {
        check(mkdir(directory, 0x1C0.convert()) == 0) { "cannot create $directory" }
        writeFile("$directory/instance.lock", "")
        chmod("$directory/instance.lock", 0x1A4.convert()) // 0644
        // What any user who can read such a file can do: hold its lock, with no app running.
        val theirs = open("$directory/instance.lock", O_RDONLY)
        check(lockWithoutWaiting(theirs) == 0) { "cannot lock through a read-only descriptor" }

        val attempt = assertIs<Attempt.Unavailable>(SingleInstanceLock.tryAcquire(directory, tui))

        assertEquals("$directory/instance.lock was open to other users and has been removed", attempt.reason)
        // The next start makes a file of its own, which that descriptor has no hold on.
        val next = assertIs<Attempt.Acquired>(SingleInstanceLock.tryAcquire(directory, tui))
        assertEquals("600", permissions("$directory/instance.lock"))
        close(next.descriptor)
        close(theirs)
    }

    @Test
    fun aDirectoryOthersCanWriteIsNotTrusted() {
        check(mkdir(directory, 0x1C0.convert()) == 0) { "cannot create $directory" }
        chmod(directory, 0x1FF.convert()) // 0777: anyone could move any file to the lock file's name.

        val attempt = assertIs<Attempt.Unavailable>(SingleInstanceLock.tryAcquire(directory, tui))

        assertEquals("$directory is not a directory only this user can write", attempt.reason)
        assertTrue(access("$directory/instance.lock", F_OK) != 0, "a lock file was created")
    }

    @Test
    fun aSymbolicLinkWhereTheLockFileGoesIsNotWrittenThrough() {
        val target = temporaryDirectory() + "/some-other-file"
        writeFile(target, "keep me")
        check(mkdir(directory, 0x1C0.convert()) == 0) { "cannot create $directory" }
        check(symlink(target, "$directory/instance.lock") == 0) { "cannot link $target" }

        val attempt = assertIs<Attempt.Unavailable>(SingleInstanceLock.tryAcquire(directory, tui))

        assertTrue(attempt.reason.startsWith("cannot open $directory/instance.lock: "), attempt.reason)
        assertEquals("keep me", readFile(target))
    }

    @Test
    fun aSecondNameOfAnotherFileWhereTheLockFileGoesIsNotWritten() {
        val target = temporaryDirectory() + "/some-other-file"
        writeFile(target, "keep me")
        check(mkdir(directory, 0x1C0.convert()) == 0) { "cannot create $directory" }
        check(link(target, "$directory/instance.lock") == 0) { "cannot link $target" }

        val attempt = assertIs<Attempt.Unavailable>(SingleInstanceLock.tryAcquire(directory, tui))

        assertEquals("$directory/instance.lock is not a plain file of this user alone", attempt.reason)
        assertEquals("keep me", readFile(target))
    }

    @Test
    fun refusalIsOneLineAndTheRefusedExitStatus() {
        val holder = assertIs<Attempt.Acquired>(SingleInstanceLock.tryAcquire(directory, tui))
        val lines = mutableListOf<String>()
        val statuses = mutableListOf<Int>()

        SingleInstanceLock.acquireOrExit("bitchat-embedded", directory, stderr = { lines += it }, exit = { statuses += it })

        assertEquals(listOf("another bitchat embedded app is running (bitchat-tui, pid 812 on /dev/tty1); attach with ~/bitchat-tui-attach"), lines)
        assertEquals(listOf(75), statuses)
        assertEquals(75, SingleInstanceLock.REFUSED_EXIT_STATUS)
        close(holder.descriptor)
    }

    @Test
    fun acquiringSaysNothingAndDoesNotExit() {
        val lines = mutableListOf<String>()
        val statuses = mutableListOf<Int>()

        SingleInstanceLock.acquireOrExit("bitchat-tui", directory, stderr = { lines += it }, exit = { statuses += it })

        assertEquals(emptyList(), lines)
        assertEquals(emptyList(), statuses)
        // The descriptor is kept for the life of the process, so the board is still held.
        assertIs<Attempt.Held>(SingleInstanceLock.tryAcquire(directory, compose))
    }

    @Test
    fun aLockThatCannotBeSetUpWarnsAndStartsAnyway() {
        val file = temporaryDirectory() + "/file"
        writeFile(file, "not a directory")
        val lines = mutableListOf<String>()
        val statuses = mutableListOf<Int>()

        SingleInstanceLock.acquireOrExit("bitchat-tui", "$file/.bitchat", stderr = { lines += it }, exit = { statuses += it })

        assertEquals(1, lines.size)
        assertTrue(lines.single().startsWith("bitchat-tui: no instance lock (cannot create $file/.bitchat: "), lines.single())
        assertTrue(lines.single().endsWith("); starting without one"), lines.single())
        assertEquals(emptyList(), statuses)
    }

    @Test
    fun holderSurvivesItsOwnEncoding() {
        assertEquals(tui, Holder.parse(tui.encode()))
        assertEquals(compose, Holder.parse(compose.encode()))
    }

    @Test
    fun holderKeepsOnlyPlainFields() {
        val escape = 27.toChar()
        val parsed = Holder.parse("name=$escape[2Jbitchat-tui\npid=12 34\ntty=/dev/pts/0\n")

        // What is read here is printed on a terminal: nothing but the expected shapes gets through.
        assertEquals(Holder(null, null, "/dev/pts/0"), parsed)
        assertNull(Holder.parse("tty=/etc/passwd\npid=-4\npid=99999999999\nname=\n"))
        assertNull(Holder.parse(""))
    }

    @Test
    fun refusalLineSaysOnlyWhatIsKnown() {
        assertEquals("another bitchat embedded app is running", refusalLine(null))
        assertEquals("another bitchat embedded app is running (bitchat-embedded, pid 4321)", refusalLine(compose))
        assertEquals("another bitchat embedded app is running (pid 7 on /dev/pts/0)", refusalLine(Holder(null, 7, "/dev/pts/0")))
    }

    private fun temporaryDirectory(): String {
        val root = getenv("TMPDIR")?.toKString()?.trimEnd('/')?.takeIf { it.isNotEmpty() } ?: "/tmp"
        val template = "$root/bitchat-lock-test.XXXXXX".encodeToByteArray() + 0.toByte()
        return template.usePinned { mkdtemp(it.addressOf(0))?.toKString() } ?: error("mkdtemp failed in $root")
    }

    private fun writeFile(path: String, text: String) {
        val file = fopen(path, "w") ?: error("cannot write $path")
        fputs(text, file)
        fclose(file)
    }

    private fun readFile(path: String): String {
        val fd = open(path, O_RDONLY)
        check(fd >= 0) { "cannot read $path" }
        val bytes = ByteArray(512)
        val count = bytes.usePinned { read(fd, it.addressOf(0), bytes.size.convert()) }
        close(fd)
        return bytes.decodeToString(0, count.toInt().coerceAtLeast(0))
    }

    /** The permission bits of [path], in octal. */
    private fun permissions(path: String): String = memScoped {
        val info = alloc<stat>()
        check(stat(path, info.ptr) == 0) { "cannot stat $path" }
        (info.st_mode.convert<Int>() and 0x1FF).toString(8)
    }
}
