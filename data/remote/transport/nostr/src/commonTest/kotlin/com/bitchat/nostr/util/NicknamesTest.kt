package com.bitchat.nostr.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NicknamesTest {
    @Test
    fun removesTerminalAndBidiControls() {
        assertEquals("alice", sanitizedNickname("a\u0007l\u202Eice\u2066"))
    }

    @Test
    fun removesEveryBidirectionalControlIncludingTheMarks() {
        // Arabic letter mark, left-to-right mark, right-to-left mark, then embeddings, overrides, isolates.
        val controls = "\u061C\u200E\u200F\u202A\u202B\u202C\u202D\u202E\u2066\u2067\u2068\u2069"
        assertEquals("ab", sanitizedNickname("a${controls}b"))
        assertEquals(null, sanitizedNickname(controls))
    }

    @Test
    fun dropsHalfSurrogatePairsAndKeepsWholeOnes() {
        assertEquals("ab", sanitizedNickname("a\uD800b"))
        assertEquals("ab", sanitizedNickname("a\uDC00b"))
        assertEquals("ab", sanitizedNickname("a\uDC00\uD800b"), "a pair the wrong way round is two halves")
        assertEquals("a\uD83D\uDE00b", sanitizedNickname("a\uD83D\uDE00b"))
        assertEquals(null, sanitizedNickname("\uD800"))
    }

    @Test
    fun removesLineAndParagraphSeparators() {
        assertEquals("ab", sanitizedNickname("a\u2028\u2029b"))
        assertEquals("ab", sanitizedNickname("a\nb\r"))
    }

    @Test
    fun trimsAndLimitsNames() {
        assertEquals("a".repeat(MAX_NICKNAME_CHARS), sanitizedNickname("  ${"a".repeat(60)}  "))
    }

    @Test
    fun keepsEmojiSequencesWithZeroWidthJoiners() {
        assertEquals("dev \uD83D\uDC69\u200D\uD83D\uDCBB", sanitizedNickname("dev \uD83D\uDC69\u200D\uD83D\uDCBB"))
    }

    @Test
    fun doesNotLeaveALoneHighSurrogateAtTheLimit() {
        assertEquals("a".repeat(49), sanitizedNickname("${"a".repeat(49)}\uD83D\uDE00"))
    }

    @Test
    fun returnsNullForBlankAndNullNames() {
        assertNull(sanitizedNickname(" \n\t "))
        assertNull(sanitizedNickname(null))
    }
}
