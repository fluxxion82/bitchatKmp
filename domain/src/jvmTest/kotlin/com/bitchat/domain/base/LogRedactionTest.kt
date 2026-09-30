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
