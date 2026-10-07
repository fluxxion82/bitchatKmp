package com.bitchat.domain.chat.model

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PrivateMessageTextTest {

    @Test
    fun aPieceEndsAfterASpaceWhereThatLeavesItHalfFullOfTheLimitInUse() {
        // 101 bytes up to and including the space: more than half of 141, less than half of 255.
        val content = "a".repeat(100) + " " + "b".repeat(100)
        assertEquals(listOf("a".repeat(100) + " ", "b".repeat(100)), PrivateMessageText.split(content, maxBytes = 141))
    }

    @Test
    fun aLimitThatCouldNotHoldEveryCharacterIsRefused() {
        assertFailsWith<IllegalArgumentException> { PrivateMessageText.split("abc", maxBytes = 3) }
        assertEquals(listOf("ab", "cd"), PrivateMessageText.split("abcd", maxBytes = 4).let { listOf(it.joinToString("").take(2), it.joinToString("").drop(2)) })
        assertEquals(listOf("😀", "😀"), PrivateMessageText.split("😀😀", maxBytes = 4))
    }

    @Test
    fun aRadioSizedLimitKeepsEveryPieceWithinThatLimit() {
        val content = ("small words make a message that needs more than one packet ").repeat(12)
        val pieces = PrivateMessageText.split(content, maxBytes = 141)

        assertEquals(content, pieces.joinToString(""))
        assertTrue(pieces.all { bytes(it) <= 141 })
        assertNull(PrivateMessageText.refusal(content, maxBytes = 141))
    }

    @Test
    fun whatFitsOneMessageIsLeftAsItIs() {
        for (content in listOf("", "hello", "x".repeat(255), EURO.repeat(85), GRIN.repeat(63), "  spaced  ")) {
            assertEquals(listOf(content), PrivateMessageText.split(content))
            assertNull(PrivateMessageText.refusal(content))
        }
    }

    @Test
    fun oneByteMoreMakesASecondPiece() {
        assertEquals(listOf("x".repeat(255), "x"), PrivateMessageText.split("x".repeat(256)))
    }

    @Test
    fun thePiecesPutTogetherAreTheTextAgain() {
        val sentence = "The quick brown fox jumps over the lazy dog, and then it does so once more. "
        val content = sentence.repeat(12).trim()

        val pieces = PrivateMessageText.split(content)

        assertEquals(content, pieces.joinToString(""))
        assertEquals(4, pieces.size)
        assertTrue(pieces.all { bytes(it) in 1..255 })
        // No word is cut in two: a space ends a piece, or starts the next one where the piece
        // before it was full to the last byte.
        assertTrue(pieces.zipWithNext().all { (piece, next) -> piece.last() == ' ' || next.first() == ' ' }, pieces.toString())
    }

    @Test
    fun aPieceIsNotEndedAtASpaceThatWouldLeaveItLessThanHalfFull() {
        // The only space is two bytes in: ending there would spend a whole message on "a ".
        val content = "a " + "x".repeat(300)

        assertEquals(listOf("a " + "x".repeat(253), "x".repeat(47)), PrivateMessageText.split(content))
    }

    @Test
    fun aPieceThatEndsBetweenWordsAnywayIsNotShortened() {
        // The cut at the limit falls right before a space: it is already between two words, and
        // the space further back (far enough in to end a piece at) is left alone.
        val first = "a".repeat(200) + " " + "b".repeat(54)
        assertEquals(listOf(first, " tail"), PrivateMessageText.split("$first tail"))

        // And one whose last character is the space itself.
        val second = "a".repeat(200) + " " + "b".repeat(53) + " "
        assertEquals(listOf(second, "tail"), PrivateMessageText.split(second + "tail"))
    }

    @Test
    fun aCharacterOfSeveralBytesIsNeverCutInTwo() {
        assertEquals(listOf(EURO.repeat(85), EURO), PrivateMessageText.split(EURO.repeat(86)))
        // Four bytes in two UTF-16 units: 63 of them are 252 bytes, and the 64th does not fit.
        assertEquals(listOf(GRIN.repeat(63), GRIN), PrivateMessageText.split(GRIN.repeat(64)))
    }

    @Test
    fun anAccentStaysWithItsLetter() {
        // 254 + "e" is 255 bytes; the combining accent after it is two more.
        val accented = "e" + point(0x0301)

        assertEquals(listOf("x".repeat(254), accented), PrivateMessageText.split("x".repeat(254) + accented))
    }

    @Test
    fun aJoinedEmojiIsNotTakenApart() {
        val joiner = point(0x200D)
        val family = point(0x1F468) + joiner + point(0x1F469) + joiner + point(0x1F467)
        assertEquals(18, bytes(family))

        // The first person alone would still fit; the family goes to the next piece whole.
        assertEquals(listOf("x".repeat(250), family), PrivateMessageText.split("x".repeat(250) + family))
        // So would the first person and the joiner after it.
        assertEquals(listOf("x".repeat(247), family), PrivateMessageText.split("x".repeat(247) + family))
    }

    @Test
    fun aVariationSelectorAndASkinToneStayWithTheirSymbol() {
        val heart = point(0x2764) + point(0xFE0F)
        assertEquals(listOf("x".repeat(252), heart), PrivateMessageText.split("x".repeat(252) + heart))

        val thumb = point(0x1F44D) + point(0x1F3FD)
        assertEquals(listOf("x".repeat(251), thumb), PrivateMessageText.split("x".repeat(251) + thumb))
    }

    @Test
    fun aFlagIsNotCutBetweenItsTwoLetters() {
        val germany = point(0x1F1E9) + point(0x1F1EA)
        val france = point(0x1F1EB) + point(0x1F1F7)

        // One letter of the first flag would fit.
        assertEquals(
            listOf("x".repeat(248), germany + france),
            PrivateMessageText.split("x".repeat(248) + germany + france),
        )
        // Between two flags is a fine place.
        assertEquals(
            listOf("x".repeat(247) + germany, france),
            PrivateMessageText.split("x".repeat(247) + germany + france),
        )
    }

    @Test
    fun aRunOfMarksLongerThanAMessageIsStillSent() {
        // One letter with 200 accents is 401 bytes: there is no place to cut that keeps it whole.
        val content = "e" + point(0x0301).repeat(200)

        val pieces = PrivateMessageText.split(content)

        assertEquals(content, pieces.joinToString(""))
        assertEquals(listOf(255, 146), pieces.map(::bytes))
    }

    @Test
    fun halvesOfCharactersThatWereNeverWholeDoNotBreakIt() {
        // Unpaired surrogates. The JVM writes one byte for each and Kotlin/Native three, so the
        // pieces are measured at three: this test only means something where it runs natively.
        val content = Char(0xD83D).toString().repeat(200) + Char(0xDE00).toString().repeat(200)

        val pieces = PrivateMessageText.split(content)

        assertEquals(content, pieces.joinToString(""))
        assertTrue(pieces.all { bytes(it) in 1..255 })
    }

    @Test
    fun whenCuttingAtSpacesTakesTooManyPiecesTheTextIsCutTightInstead() {
        // Eight messages' worth exactly. Ending pieces at the spaces would need a ninth.
        val content = ("a".repeat(130) + " " + "b".repeat(124)).repeat(8)
        assertEquals(8 * 255, bytes(content))

        val pieces = PrivateMessageText.split(content)

        assertEquals(content, pieces.joinToString(""))
        assertEquals(List(8) { 255 }, pieces.map(::bytes))
        assertNull(PrivateMessageText.refusal(content))
    }

    @Test
    fun aTextThatNeedsMoreThanEightPiecesIsRefusedWithTheNumberItNeeds() {
        assertNull(PrivateMessageText.refusal("x".repeat(8 * 255)))
        assertEquals(
            "a private message is sent in at most 8 parts of 255 bytes, this one needs 9",
            PrivateMessageText.refusal("x".repeat(8 * 255 + 1)),
        )
        // Counted in bytes: 680 of these characters are eight pieces, one more is a ninth.
        assertNull(PrivateMessageText.refusal(EURO.repeat(680)))
        assertEquals(
            "a private message is sent in at most 8 parts of 255 bytes, this one needs 9",
            PrivateMessageText.refusal(EURO.repeat(681)),
        )
    }

    @Test
    fun noLaterPieceStartsTheWayAFavouriteNotificationDoes() {
        // The other side takes a private message that starts like this for a notification and
        // does not show it. A cut right before it is made one character earlier.
        assertEquals(
            listOf("x".repeat(254), "x[FAVORITED]:hello"),
            PrivateMessageText.split("x".repeat(255) + "[FAVORITED]:hello"),
        )
        assertEquals(
            listOf("x".repeat(254), "x[UNFAVORITED]:hello"),
            PrivateMessageText.split("x".repeat(255) + "[UNFAVORITED]:hello"),
        )
        // Blank space before it does not hide it: two clients trim before they look.
        assertEquals(
            listOf("x".repeat(249), "x     [FAVORITED]:hello"),
            PrivateMessageText.split("x".repeat(250) + "     [FAVORITED]:hello"),
        )
        // A cut that would have fallen at the space before it is made further on instead.
        val words = "word ".repeat(40)
        assertEquals(
            listOf(words + "[FAVORITED]:" + "y".repeat(43), "y".repeat(57)),
            PrivateMessageText.split(words + "[FAVORITED]:" + "y".repeat(100)),
        )
    }

    @Test
    fun theFirstPieceMayStartThatWayBecauseTheUserWroteItSo() {
        val content = "[FAVORITED]:" + "x".repeat(300)

        assertEquals(listOf(content.take(255), content.drop(255)), PrivateMessageText.split(content))
        assertNull(PrivateMessageText.refusal(content))
    }

    @Test
    fun aTextThatCannotBeCutWithoutMakingANotificationIsRefused() {
        // More blank space than a message carries, and then the words a notification starts with.
        val content = " ".repeat(300) + "[UNFAVORITED]:hello"

        assertEquals(
            "this private message cannot be sent in parts: one of them would start with [UNFAVORITED]",
            PrivateMessageText.refusal(content),
        )
        // A letter before the blank space changes nothing: the second piece still starts with it.
        assertEquals(
            "this private message cannot be sent in parts: one of them would start with [UNFAVORITED]",
            PrivateMessageText.refusal("x" + " ".repeat(300) + "[UNFAVORITED]:hello"),
        )
        // With less blank space than a message carries there is a place to cut.
        assertNull(PrivateMessageText.refusal("x" + " ".repeat(200) + "[UNFAVORITED]:" + "y".repeat(100)))
    }

    @Test
    fun anyTextComesBackWholeInPiecesThatEachFit() {
        val random = Random(20261006)
        val joiner = point(0x200D)
        val tokens = listOf(
            "a", "word", "longerword", " ", " ", "  ", "\n", ".", EURO, GRIN, point(0x4E2D), point(0x0E01) + point(0x0E34),
            "e" + point(0x0301), point(0x1F468) + joiner + point(0x1F469), point(0x1F1E9) + point(0x1F1EA),
            point(0x2764) + point(0xFE0F), Char(0xD83D).toString(), Char(0xDE00).toString(), "x".repeat(40),
            "[FAVORITED]:", "[UNFAVORITED]:", "[",
        )

        var refusedForNotifications = 0
        repeat(2_000) { round ->
            val content = buildString { repeat(random.nextInt(1, 400)) { append(tokens[random.nextInt(tokens.size)]) } }

            val pieces = PrivateMessageText.split(content)

            assertEquals(content, pieces.joinToString(""), "round $round")
            assertTrue(pieces.all { bytes(it) in 1..255 }, "round $round: ${pieces.map(::bytes)}")
            // What is one piece stays one piece: sending a piece never splits it again.
            assertTrue(pieces.all { PrivateMessageText.split(it) == listOf(it) }, "round $round")
            if (bytes(content) <= 255) assertEquals(1, pieces.size, "round $round")
            // No piece after the first is one the other side would take for a notification,
            // unless the text is refused for that.
            val notifications = pieces.drop(1).count { it.trim().startsWith("[FAVORITED]") || it.trim().startsWith("[UNFAVORITED]") }
            if (notifications > 0) {
                assertTrue(PrivateMessageText.refusal(content) != null, "round $round")
                refusedForNotifications++
            }
        }
        // Among texts like these the cut can always be moved: none is refused for it.
        assertEquals(0, refusedForNotifications)
    }

    private fun bytes(text: String) = text.encodeToByteArray().size

    private companion object {
        /** Three bytes of UTF-8. */
        val EURO = point(0x20AC)

        /** Four bytes of UTF-8 in two UTF-16 units. */
        val GRIN = point(0x1F600)

        fun point(codePoint: Int): String {
            if (codePoint < 0x10000) return Char(codePoint).toString()
            val offset = codePoint - 0x10000
            return "${Char(0xD800 + (offset shr 10))}${Char(0xDC00 + (offset and 0x3FF))}"
        }
    }
}
