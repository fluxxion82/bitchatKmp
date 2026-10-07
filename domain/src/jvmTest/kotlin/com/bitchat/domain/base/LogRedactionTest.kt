package com.bitchat.domain.base

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LogRedactionTest {
    @AfterTest fun reset() = LogPolicy.configure(null)

    @Test fun bodiesAreRedactedByDefault() {
        assertFalse(LogPolicy.messageBodies)
        assertEquals("<11 chars>", logBody("meet at 10"+"!"))
        assertEquals("<0 chars>", logBody(""))
        assertEquals("<null>", logBody(null))
        assertEquals("<5 bytes>", logBytes(byteArrayOf(1, 2, 3, 4, 5)) { "0102030405" })
    }

    @Test fun onlyAnExplicitOptInShowsThem() {
        for (off in listOf(null, "", "0", "false", "no", "yes please")) {
            LogPolicy.configure(off)
            assertFalse(LogPolicy.messageBodies, "'$off'")
        }
        for (on in listOf("1", "true", "TRUE")) {
            LogPolicy.configure(on)
            assertTrue(LogPolicy.messageBodies, on)
        }
        assertEquals("\"meet at 10!\"", logBody("meet at 10!"))
        assertEquals("0102", logBytes(byteArrayOf(1, 2)) { "0102" })
    }

    @Test fun anOptedInBodyIsCutToThePreviewLength() {
        LogPolicy.configure("1")
        assertEquals("\"abc...\" (6 chars)", logBody("abcdef", preview = 3))
    }

    @Test fun anOptedInBodyCannotStartALineOfItsOwnOrSendTheTerminalAControlSequence() {
        LogPolicy.configure("1")
        val lineFeed = Char(0x0A); val carriageReturn = Char(0x0D); val escape = Char(0x1B); val csi = Char(0x9B)
        val lineSeparator = Char(0x2028); val paragraphSeparator = Char(0x2029); val delete = Char(0x7F); val nul = Char(0)
        val smiley = "${Char(0xD83D)}${Char(0xDE00)}"
        val body = "hi${lineFeed}FAKE$carriageReturn$escape[2J${csi}2J$lineSeparator$paragraphSeparator$delete$nul $smiley ok"

        assertEquals("\"hi?FAKE??[2J?2J???? $smiley ok\"", logBody(body))
        assertEquals("\"hi?FAKE...\" (${body.length} chars)", logBody(body, preview = 7))
        assertEquals("a?b", logPath("a${lineFeed}b"))
        assertEquals("IllegalStateException: a?b", logError(IllegalStateException("a${escape}b")))
        assertEquals("IllegalStateException: null", logError(IllegalStateException()))
    }

    @Test fun textWithNoControlCharactersIsShownAsItIs() {
        val text = "caf${Char(0xE9)} ${Char(0x65E5)}${Char(0x672C)} ${Char(0xD83D)}${Char(0xDE00)} \"quoted\" \\ tab-free"
        assertEquals(text, logSafe(text))
    }

    @Test fun pathsAndFileNamesAreRedactedByDefault() {
        assertEquals("<file>", logPath("/home/sterling/.bitchat/media/private/IMG_0042.jpg"))
        assertEquals("<none>", logPath(null))
        LogPolicy.configure("1")
        assertEquals("/tmp/a.jpg", logPath("/tmp/a.jpg"))
    }

    @Test fun exceptionsShowTheirClassOnlyByDefault() {
        val error = IllegalStateException("/home/sterling/.bitchat/media/private/IMG_0042.jpg: No such file")
        assertEquals("IllegalStateException", logError(error))
        LogPolicy.configure("1")
        assertEquals("IllegalStateException: /home/sterling/.bitchat/media/private/IMG_0042.jpg: No such file", logError(error))
    }
}
