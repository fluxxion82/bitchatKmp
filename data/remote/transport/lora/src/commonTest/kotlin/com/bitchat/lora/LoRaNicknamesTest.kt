package com.bitchat.lora

import kotlin.test.Test
import kotlin.test.assertEquals

class LoRaNicknamesTest {
    @Test fun keepsAsciiUpToTwentyFourBytes() {
        assertEquals("abcdefghijklmnopqrstuvwx", loRaHeartbeatNickname("abcdefghijklmnopqrstuvwxy"))
    }

    @Test fun cutsTwoThreeAndFourByteCharactersBetweenCharacters() {
        assertEquals("é".repeat(12), loRaHeartbeatNickname("é".repeat(13)))
        assertEquals("€".repeat(8), loRaHeartbeatNickname("€".repeat(9)))
        assertEquals("😀".repeat(6), loRaHeartbeatNickname("😀".repeat(7)))
    }

    @Test fun doesNotSplitASurrogatePairAtTheBoundary() {
        assertEquals("a".repeat(23), loRaHeartbeatNickname("a".repeat(23) + "😀"))
    }

    @Test fun aHeardNameOfTwentyOneToTwentyFourBytesMayBeACutAndNoOtherCanBe() {
        // Whatever is cut leaves less than one character of the 24 bytes unused.
        for (name in listOf("a".repeat(30), "a".repeat(21) + "😀", "a".repeat(22) + "€", "a".repeat(23) + "é", "€".repeat(9))) {
            assertEquals(true, mayBeCutLoRaNickname(loRaHeartbeatNickname(name)), name)
        }
        assertEquals(false, mayBeCutLoRaNickname("a".repeat(20)))
        assertEquals(true, mayBeCutLoRaNickname("a".repeat(21)))
        assertEquals(true, mayBeCutLoRaNickname("a".repeat(24)))
        // Longer than a heartbeat of this version carries: an older one's whole name.
        assertEquals(false, mayBeCutLoRaNickname("a".repeat(25)))
        assertEquals(false, mayBeCutLoRaNickname(""))
    }

    @Test fun keepsEmptyAndExactlyTwentyFourByteNames() {
        assertEquals("", loRaHeartbeatNickname(""))
        assertEquals("€".repeat(8), loRaHeartbeatNickname("€".repeat(8)))
    }
}
