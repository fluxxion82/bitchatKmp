package com.bitchat.domain.chat.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PrivateMessageTextTest {

    @Test
    fun contentUpToTheLimitIsNotRefused() {
        assertNull(PrivateMessageText.refusal(""))
        assertNull(PrivateMessageText.refusal("x".repeat(255)))
    }

    @Test
    fun oneByteMoreIsRefusedWithBothNumbers() {
        assertEquals(
            "a private message can be at most 255 bytes, this one is 256",
            PrivateMessageText.refusal("x".repeat(256)),
        )
    }

    @Test
    fun theLimitIsCountedInBytesOfUtf8() {
        // Three bytes each: 85 of them are 255 bytes, 86 are 258.
        val euro = Char(0x20AC).toString()
        assertNull(PrivateMessageText.refusal(euro.repeat(85)))
        assertEquals(
            "a private message can be at most 255 bytes, this one is 258",
            PrivateMessageText.refusal(euro.repeat(86)),
        )

        // Four bytes in two UTF-16 units: 64 of them are 128 "characters" and 256 bytes.
        val emoji = "${Char(0xD83D)}${Char(0xDE00)}"
        assertNull(PrivateMessageText.refusal(emoji.repeat(63)))
        assertEquals(
            "a private message can be at most 255 bytes, this one is 256",
            PrivateMessageText.refusal(emoji.repeat(64)),
        )
    }
}
