package com.bitchat.desktop.tui

import com.sun.jna.Library
import com.sun.jna.Native
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Assume.assumeTrue

class JvmTuiLogTest {
    @Test
    fun rotationUsesTwoNumberedBackupsAtFiveMegabytes() {
        assertEquals(5L * 1024 * 1024, JvmTuiLog.maxBytes)
        assertEquals(listOf("desktop-tui.log.2", "desktop-tui.log.1", "desktop-tui.log"), JvmTuiLog.rotationNames("desktop-tui.log"))
        assertFalse(JvmTuiLog.shouldRotate(JvmTuiLog.maxBytes - 1))
        assertTrue(JvmTuiLog.shouldRotate(JvmTuiLog.maxBytes))
    }

    @Test
    fun rotationMovesExistingFilesInOrder() {
        val directory = Files.createTempDirectory("bitchat-tui-log")
        try {
            val log = directory.resolve("desktop-tui.log")
            Files.writeString(log, "current")
            Files.writeString(directory.resolve("desktop-tui.log.1"), "previous")
            JvmTuiLog.rotateFiles(log)
            assertEquals("current", Files.readString(directory.resolve("desktop-tui.log.1")))
            assertEquals("previous", Files.readString(directory.resolve("desktop-tui.log.2")))
            assertFalse(Files.exists(log))
        } finally {
            Files.walk(directory).sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
    }

    // The expected numbers are the libc header values: macOS <fcntl.h> (checked with clang on macOS arm64) and
    // glibc <fcntl.h> (checked with gcc on Linux x86_64; arm64 Linux uses the same asm-generic values).
    @Test
    fun macosConstantsMatchTheLibcHeaders() {
        val constants = JvmTuiLog.Constants.forOs("Mac OS X")
        assertEquals(0x1, constants.wronly)
        assertEquals(0x2, constants.rdwr)
        assertEquals(0x200, constants.creat)
        assertEquals(0x20000, constants.noctty)
        assertEquals(0x8, constants.append)
        assertEquals(0x1000000, constants.cloexec)
        assertEquals(67, constants.dupFdCloexec)
    }

    @Test
    fun linuxConstantsMatchTheLibcHeaders() {
        val constants = JvmTuiLog.Constants.forOs("Linux")
        assertEquals(0x1, constants.wronly)
        assertEquals(0x2, constants.rdwr)
        assertEquals(0x40, constants.creat)
        assertEquals(0x100, constants.noctty)
        assertEquals(0x400, constants.append)
        assertEquals(0x80000, constants.cloexec)
        assertEquals(1030, constants.dupFdCloexec)
    }

    @Test
    fun anUnknownOsFailsTheTableLookupWithAClearMessage() {
        val error = assertFailsWith<IllegalStateException> { JvmTuiLog.Constants.forOs("Plan 9") }
        assertTrue(error.message.orEmpty().contains("unsupported OS"), error.message)
        assertTrue(error.message.orEmpty().contains("Plan 9"), error.message)
    }

    private interface TestLibC : Library {
        fun fcntl(fd: Int, command: Int, vararg argument: Any?): Int
        fun close(fd: Int): Int
    }

    // The table is only right if the real libc agrees: open a log and read its flags back.
    @Test
    fun theLogIsOpenedAppendOnlyCloseOnExecAndOwnerOnlyFromTheStart() {
        val os = System.getProperty("os.name")
        assumeTrue(os.contains("mac", ignoreCase = true) || os.contains("linux", ignoreCase = true))
        val libc = Native.load("c", TestLibC::class.java)
        val directory = Files.createTempDirectory("bitchat-tui-open")
        try {
            val file = directory.resolve("desktop-tui.log")
            val fd = JvmTuiLog.openLog(file)
            assertTrue(fd >= 0, "open failed")
            try {
                val fdFlags = libc.fcntl(fd, 1) // F_GETFD
                assertEquals(1, fdFlags and 1, "FD_CLOEXEC is set")
                val statusFlags = libc.fcntl(fd, 3) // F_GETFL
                val constants = JvmTuiLog.Constants.forOs(os)
                assertEquals(constants.append, statusFlags and constants.append, "O_APPEND is set")
                assertEquals(constants.wronly, statusFlags and 3, "write only")
                assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file)))
                // F_DUPFD_CLOEXEC from the table gives a close-on-exec descriptor at or above the minimum.
                val copy = libc.fcntl(fd, constants.dupFdCloexec, 3)
                assertTrue(copy >= 3, "dup gave $copy")
                assertEquals(1, libc.fcntl(copy, 1) and 1, "the duplicate is close-on-exec")
                libc.close(copy)
            } finally {
                libc.close(fd)
            }
        } finally {
            Files.walk(directory).sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
    }
}
