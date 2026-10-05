package com.bitchat.desktop.tui

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LogRotationTest {
    private lateinit var directory: Path
    private lateinit var log: Path
    private lateinit var backup: Path

    private val events = mutableListOf<String>()
    private val notices = mutableListOf<String?>()
    private val reports = mutableListOf<String>()

    /** Attaches fail while [failures] is above zero; a success creates the log, as opening it does. */
    private var failures = 0
    private var attachCalls = 0

    @BeforeTest
    fun setUp() {
        directory = Files.createTempDirectory("bitchat-tui-rotation")
        log = directory.resolve("desktop-tui.log")
        backup = directory.resolve("desktop-tui.log.1")
    }

    @AfterTest
    fun tearDown() {
        Files.walk(directory).sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
    }

    private fun rotation(limit: Long = 10) = LogRotation(
        file = log,
        limit = limit,
        flush = { events += "flush" },
        attach = {
            attachCalls++
            events += "attach"
            if (failures > 0) {
                failures--
                throw java.io.IOException("cannot open $it")
            }
            if (!Files.exists(it)) Files.createFile(it)
        },
        discard = { events += "discard" },
        notice = { notices += it },
        report = { reports += it },
    )

    @Test
    fun aSmallLogIsLeftAlone() {
        Files.writeString(log, "short")
        rotation().tick()
        assertEquals(emptyList(), events)
        assertTrue(Files.exists(log))
    }

    @Test
    fun aLargeLogIsRenamedAndReplaced() {
        Files.writeString(log, "0123456789ab")
        rotation().tick()
        assertEquals(listOf("flush", "attach"), events)
        assertEquals("0123456789ab", Files.readString(backup))
        assertEquals(0L, Files.size(log))
        assertEquals(emptyList(), notices)
    }

    @Test
    fun aFailedReopenIsRetriedOnTheNextTickEvenThoughTheLogFileIsGone() {
        Files.writeString(log, "0123456789ab")
        failures = 1 // the open fails once, after the rename succeeded
        val rotation = rotation()

        rotation.tick()
        assertFalse(Files.exists(log), "the rename happened and the reopen failed")
        assertEquals(1, attachCalls)
        assertEquals(listOf<String?>("Log rotation failed (cannot open $log)"), notices)
        assertEquals(1, reports.size)

        rotation.tick()
        assertEquals(2, attachCalls, "the next tick retried, although there was no log file to size")
        assertTrue(Files.exists(log))
        assertEquals(listOf("Log rotation failed (cannot open $log)", null), notices, "the notice is cleared on recovery")
        assertEquals(1, reports.size, "reported once, not on every tick")

        Files.writeString(log, "0123456789ab")
        rotation.tick()
        assertEquals("0123456789ab", Files.readString(backup), "rotation works again afterwards")
    }

    @Test
    fun aStrandedFileThatKeepsGrowingIsCutOffAndOutputDiscardedUntilTheLogReturns() {
        Files.writeString(log, "0123456789ab")
        failures = 100
        val rotation = rotation(limit = 10)

        rotation.tick() // renamed, reopen failed: the streams are still on the .1 file
        rotation.tick() // still failing, but the stranded file has not grown past the limit
        assertFalse("discard" in events)

        Files.writeString(backup, Files.readString(backup) + "x".repeat(10)) // what the streams would have written
        rotation.tick()
        assertEquals(1, events.count { it == "discard" }, "the stranded file hit the limit")
        assertTrue(notices.last().orEmpty().startsWith("Log unavailable"), notices.toString())

        rotation.tick()
        assertEquals(1, events.count { it == "discard" }, "discarding once is enough")

        failures = 0
        rotation.tick()
        assertTrue(Files.exists(log))
        assertNull(notices.last(), "recovered")

        Files.writeString(log, "0123456789ab")
        rotation.tick()
        assertEquals("0123456789ab", Files.readString(backup), "and it rotates normally again")
    }

    @Test
    fun aLogDeletedWhileRunningIsReopened() {
        Files.writeString(log, "short")
        val rotation = rotation()
        Files.delete(log)
        rotation.tick()
        assertEquals(listOf("attach"), events)
        assertTrue(Files.exists(log))
    }

    @Test
    fun aFailedReopenOfADeletedLogIsRetriedToo() {
        failures = 1
        val rotation = rotation()
        rotation.tick()
        assertEquals(1, reports.size)
        rotation.tick()
        assertEquals(2, attachCalls)
        assertTrue(Files.exists(log))
    }
}
