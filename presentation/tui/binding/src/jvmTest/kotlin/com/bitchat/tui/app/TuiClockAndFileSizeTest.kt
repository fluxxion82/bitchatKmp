package com.bitchat.tui.app

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.datetime.TimeZone
import kotlin.time.Instant

class TuiClockAndFileSizeTest {
    @Test
    fun localClockTimeFormatsInTheInjectedZone() {
        assertEquals(
            "01:05",
            localClockTime(Instant.parse("2026-09-30T08:05:00Z"), TimeZone.of("America/Los_Angeles")),
        )
    }

    @Test
    fun fileSizeReturnsOnlyRegularFileLengths() {
        val file = Files.createTempFile("bitchat-tui", ".txt")
        val directory = Files.createTempDirectory("bitchat-tui")
        try {
            Files.writeString(file, "hello")
            assertEquals(5L, fileSize(file.toString()))
            assertNull(fileSize(file.resolveSibling("missing").toString()))
            assertNull(fileSize(directory.toString()))
        } finally {
            Files.deleteIfExists(file)
            Files.deleteIfExists(directory)
        }
    }
}
