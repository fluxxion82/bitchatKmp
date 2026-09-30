package com.bitchat.tui

import androidx.compose.runtime.CompositionLocalProvider
import com.jakewharton.mosaic.testing.runMosaicTest
import com.jakewharton.mosaic.text.terminalWidth
import com.jakewharton.mosaic.ui.Text
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class PeerTextTest {
    private val clearScreen = "\u001B[2J\u001B[H"
    private val c1Csi = "\u009B31m"
    private val osc52 = "\u001B]52;c;cm0gLXJmIH4=\u0007"
    private val osc8 = "\u001B]8;;https://evil.example\u001B\\click\u001B]8;;\u001B\\"
    private val rlo = "\u202Etxt.exe"

    @Test fun escapeSequencesAreDefused() {
        assertEquals("?[2J?[Hhi", sanitizePeerText("$clearScreen" + "hi"))
        assertEquals("?31m", sanitizePeerText(c1Csi))
        assertEquals("?]52;c;cm0gLXJmIH4=?", sanitizePeerText(osc52))
        assertFalse(sanitizePeerText(osc8).contains('\u001B'))
    }

    @Test fun everyC0C1AndDelIsReplaced() {
        val controls = (0x00..0x1F) + 0x7F + (0x80..0x9F)
        val sanitized = sanitizePeerText(controls.joinToString("") { it.toChar().toString() })
        assertTrue(sanitized.none { it.isISOControl() }, sanitized)
        assertEquals(controls.size, sanitized.length)
    }

    @Test fun bidiOverridesAndIsolatesAreReplaced() {
        val bidi = ('\u202A'..'\u202E') + ('\u2066'..'\u2069')
        assertEquals("?".repeat(bidi.size), sanitizePeerText(bidi.joinToString("")))
        assertEquals("?txt.exe", sanitizePeerText(rlo))
    }

    @Test fun lineBreaksAndTabsInASingleLineFieldBecomeSpaces() {
        assertEquals("a b c d e", sanitizePeerText("a\nb\tc\rd\u2028e"))
    }

    @Test fun loneSurrogatesAreReplacedAndPairsKept() {
        assertEquals("?x😀", sanitizePeerText("\uD83Dx😀"))
    }

    @Test fun ordinaryTextIsUnchanged() {
        val text = "héllo 中文 👍 🇺🇸 ─│ e\u0301 [2J"
        assertEquals(text, sanitizePeerText(text))
    }

    @Test fun multiLineTextIsSplitThenSanitized() {
        assertEquals(listOf("one", "?[2Jtwo", "three", "four"), sanitizePeerLines("one\n\u001B[2Jtwo\r\nthree\rfour"))
    }

    @Test fun consoleSafeMapsWhatTheConsoleFontCannotDraw() {
        assertEquals("??", consoleSafe("中文"))
        assertEquals("?", consoleSafe("😀"))
        assertEquals("?", consoleSafe("👍🏽"))
        assertEquals("?", consoleSafe("🇺🇸"))
        assertEquals("?", consoleSafe("❤\uFE0F"))
        assertEquals("a?b", consoleSafe("a𝐀b")) // Non-BMP letter.
    }

    @Test fun consoleSafeKeepsWhatItCanDraw() {
        assertEquals("héllo ─│┌┐ ñ Ω Ж", consoleSafe("héllo ─│┌┐ ñ Ω Ж"))
    }

    @Test fun consoleSafeKeepsOneCodePointPerCell() {
        assertEquals("e", consoleSafe("e\u0301"))
        assertEquals("a??e?b", consoleSafe("a中😀e\u0301👍🏽b"))
    }

    @Test fun consoleSafeModeIsForTheLinuxConsoleOnly() {
        assertTrue(consoleSafeFor("linux"))
        assertTrue(consoleSafeFor("linux-16color"))
        assertFalse(consoleSafeFor("xterm-256color"))
        assertFalse(consoleSafeFor(null))
    }

    @Test fun displayTextMapsGlyphsOnlyInConsoleMode() = runTest {
        runMosaicTest {
            val plain = setContentAndSnapshot { Text(displayText("hi 😀\u001B[2J")) }
            assertEquals("hi 😀?[2J", plain)
            val console = setContentAndSnapshot {
                CompositionLocalProvider(LocalConsoleSafe provides true) { Text(displayText("hi 😀\u001B[2J")) }
            }
            assertEquals("hi ??[2J", console)
        }
    }

    @Test fun promptInConsoleModeDrawsOneCellPerCharacter() {
        val line = promptLine("> ", "a中😀", cursor = 1, width = 20, focused = true, consoleSafe = true)
        assertEquals("> a??", line.text)
    }

    @Test fun wideCharacterPromptInConsoleModeScrollsByOneCellPerCharacter() {
        val line = promptLine("> ", "中文中文中文中文", cursor = 16, width = 8, focused = true, consoleSafe = true)
        assertEquals("> ????? ", line.text)
    }

    // Conservative emoji policy. Expected widths are counted by hand from Unicode properties: East
    // Asian Wide/Fullwidth or Emoji_Presentation=yes is 2 cells, a flag pair 2, a combining mark or
    // variation selector 0, anything else 1. assertSanitized also checks Mosaic's own measure.
    private val man = "\uD83D\uDC68"
    private val woman = "\uD83D\uDC69"
    private val girl = "\uD83D\uDC67"
    private val zwj = "\u200D"
    private val family = "$man$zwj$woman$zwj$girl"
    private val thumbsUp = "\uD83D\uDC4D"
    private val mediumSkin = "\uD83C\uDFFD"
    private val grinning = "\uD83D\uDE00"
    private val riU = "\uD83C\uDDFA"
    private val riS = "\uD83C\uDDF8"
    private val riF = "\uD83C\uDDEB"
    private val blackFlag = "\uD83C\uDFF4"
    private val england = blackFlag + "\uDB40\uDC67\uDB40\uDC62\uDB40\uDC65\uDB40\uDC6E\uDB40\uDC67\uDB40\uDC7F"
    private val copyright = "\u00A9"
    private val pointingUp = "\u261D"
    private val heart = "\u2764"
    private val zhong = "\u4E2D"

    private fun assertSanitized(expected: String, cells: Int, input: String) {
        val result = sanitizePeerText(input)
        assertEquals(expected, result)
        assertEquals(cells, result.terminalWidth(), "Mosaic's measure of [$result]")
    }

    /** [codePoint] as a string, built by hand (no java.lang.Character). */
    private fun text(codePoint: Int): String = if (codePoint < 0x10000) {
        codePoint.toChar().toString()
    } else {
        val v = codePoint - 0x10000
        charArrayOf((0xD800 + (v shr 10)).toChar(), (0xDC00 + (v and 0x3FF)).toChar()).concatToString()
    }

    @Test fun joinersAreRemovedEvenBetweenPictographs() = assertSanitized("$copyright$copyright$copyright", 3, "$copyright$zwj$copyright$zwj$copyright")

    @Test fun familyBecomesThreePeople() = assertSanitized("$man$woman$girl", 6, family)

    @Test fun joinerBetweenLettersIsRemoved() = assertSanitized("abc", 3, "a${zwj}b${zwj}c")

    @Test fun skinTonesAreRemoved() {
        assertSanitized(pointingUp, 1, "$pointingUp$mediumSkin")
        assertSanitized(thumbsUp, 2, "$thumbsUp$mediumSkin")
        assertSanitized("a", 1, "a$mediumSkin")
        assertSanitized("x", 1, "${mediumSkin}x")
    }

    @Test fun keycapsBecomeTheirCharacter() {
        assertSanitized("1", 1, "1\u20E3")
        assertSanitized("1", 1, "1\uFE0F\u20E3")
        assertSanitized("#", 1, "#\uFE0F\u20E3")
    }

    @Test fun subdivisionFlagBecomesABlackFlag() = assertSanitized(blackFlag, 2, england)

    @Test fun flagPairIsKept() = assertSanitized("$riU$riS", 2, "$riU$riS")

    @Test fun loneRegionalIndicatorIsReplaced() {
        assertSanitized("?", 1, riU)
        assertSanitized("a?b", 3, "a${riU}b")
    }

    @Test fun oddRegionalIndicatorAfterAPairIsReplaced() = assertSanitized("$riU$riS?", 3, "$riU$riS$riF")

    @Test fun emojiSelectorIsKeptOnlyWhereItChangesNothing() {
        assertSanitized("$grinning\uFE0F", 2, "$grinning\uFE0F")
        assertSanitized("$grinning\uFE0F", 2, "$grinning\uFE0F\uFE0F")
        assertSanitized(heart, 1, "$heart\uFE0F")
        assertSanitized(copyright, 1, "$copyright\uFE0F")
        assertSanitized("a", 1, "a\uFE0F")
        assertSanitized(zhong, 2, "$zhong\uFE0F")
        assertSanitized("x", 1, "\uFE0Fx")
        assertSanitized("$riU$riS", 2, "$riU$riS\uFE0F")
    }

    @Test fun textSelectorIsRemoved() {
        assertSanitized(grinning, 2, "$grinning\uFE0E")
        assertSanitized(heart, 1, "$heart\uFE0E")
    }

    @Test fun combiningMarksAndOtherSelectorsStay() {
        assertSanitized("e\u0301", 1, "e\u0301")
        assertSanitized("$zhong\uFE00", 2, "$zhong\uFE00")
    }

    @Test fun markWithNothingBeforeItIsDropped() {
        assertSanitized("a", 1, "\u0301a")
        assertSanitized("a\u0301", 1, "a\u0301")
    }

    @Test fun softHyphenIsRemoved() = assertSanitized("ab", 2, "a\u00ADb")

    @Test fun nonCharactersBecomeThePlaceholder() {
        assertSanitized("a?b", 3, "a\uFFFFb")
        // All 66: U+FDD0-FDEF, then U+xxFFFE and U+xxFFFF in each of the 17 planes.
        val nonCharacters = (0xFDD0..0xFDEF) + (0..16).flatMap { plane -> listOf(plane * 0x10000 + 0xFFFE, plane * 0x10000 + 0xFFFF) }
        assertEquals(66, nonCharacters.size)
        for (codePoint in nonCharacters) assertEquals("a?b", sanitizePeerText("a${text(codePoint)}b"), "U+%04X".format(codePoint))
    }

    @Test fun privateUseBecomesThePlaceholder() {
        assertSanitized("?", 1, "\uE000")
        assertSanitized("?", 1, "\uF8FF")
        assertSanitized("?", 1, "\uDB80\uDC00") // U+F0000, plane 15.
        assertSanitized("?", 1, "\uDBC0\uDC00") // U+100000, plane 16.
    }

    @Test fun replacementCharacterFromAPeerIsKeptAsOneCell() = assertSanitized("\uFFFD", 1, "\uFFFD")

    // U+093E DEVANAGARI VOWEL SIGN AA is a spacing mark (Mc) that Mosaic counts as its own cell;
    // U+0301 is a nonspacing mark (Mn) that Mosaic counts as zero.
    private val aa = "\u093E"

    @Test fun markWithNoBaseIsRemoved() {
        assertSanitized("", 0, aa)
        assertSanitized("", 0, "\u0301")
        assertSanitized("a", 1, "${aa}a")
        assertSanitized("a", 1, "\u200C\u0301a") // A joiner-like format character is no base.
    }

    @Test fun marksAfterEmojiAreRemoved() {
        assertSanitized(grinning, 2, "$grinning$aa")
        assertSanitized(grinning, 2, "$grinning\u0301")
        assertSanitized("$grinning\uFE0F", 2, "$grinning\uFE0F\u0301")
        assertSanitized("$riU$riS", 2, "$riU$riS\u0301")
    }

    @Test fun marksOnOrdinaryBasesStay() {
        assertSanitized("a${aa}b", 3, "a${aa}b") // a 1, the spacing mark 1, b 1.
        assertSanitized("e\u0301", 1, "e\u0301")
        assertSanitized("$zhong\u0301", 2, "$zhong\u0301")
        assertSanitized("?\u0301", 1, "\u001B\u0301") // The placeholder is an ordinary base.
    }

    @Test fun wrappedLinesNeverStartWithAMark() {
        val text = sanitizePeerText("a${aa}b")
        assertEquals(listOf("a$aa", "b"), wrapCells(text, 2))
        assertEquals(listOf("a$aa", "b"), wrapCells(text, 1)) // Overflows rather than split.
        assertEquals(listOf("a$aa", "b"), hardWrapCells(text, 2))
        assertEquals(listOf("a$aa", "b"), wrapCells("a$aa b", 2))
    }

    // Hangul: conjoining jamo compose into precomposed syllables where NFC composes them (initial +
    // medial, optionally + final); any jamo left over becomes the placeholder. Syllables are 2 cells.
    @Test fun twoInitialJamoBecomePlaceholders() = assertSanitized("??", 2, "\u1100\u1100")

    @Test fun initialAndMedialComposeToASyllable() = assertSanitized("\uAC00", 2, "\u1100\u1161")

    @Test fun initialMedialAndFinalComposeToASyllable() = assertSanitized("\uAC01", 2, "\u1100\u1161\u11A8")

    @Test fun syllableAndFinalCompose() = assertSanitized("\uAC01", 2, "\uAC00\u11A8")

    @Test fun composedSyllableKeepsWhatFollows() = assertSanitized("\uAC00a", 3, "\u1100\u1161a")

    @Test fun precomposedSyllablesStay() = assertSanitized("\uD55C\uAE00", 4, "\uD55C\uAE00")

    @Test fun jamoThatDoNotComposeBecomePlaceholders() {
        assertSanitized("?", 1, "\u1161") // A lone medial.
        assertSanitized("?", 1, "\u11A8") // A lone final.
        assertSanitized("\uAC01?", 3, "\uAC01\u11A8") // A syllable with a final takes no second one.
        assertSanitized("??", 2, "\u1113\u1161") // An archaic initial does not compose.
        assertSanitized("?", 1, "\u115F") // The initial filler.
        assertSanitized("?", 1, "\uA960") // Jamo Extended-A.
        assertSanitized("?", 1, "\uD7B0") // Jamo Extended-B.
    }

    @Test fun joinedLettersAreFittedByTheirRealWidth() = runTest {
        runMosaicTest {
            assertEquals("a", setContentAndSnapshot { Bar("a${zwj}b${zwj}c", width = 1) })
            assertEquals("ab", setContentAndSnapshot { Bar("a${zwj}b${zwj}c", width = 2) })
        }
    }

    @Test fun familyIsFittedAsThreeTwoCellPeople() = runTest {
        runMosaicTest {
            assertEquals("$man$woman", setContentAndSnapshot { Bar("${family}ab", width = 4) })
            assertEquals("$man$woman${girl}a", setContentAndSnapshot { Bar("${family}ab", width = 7) })
        }
    }

    @Test fun nothingMosaicWouldJoinSurvivesBetweenTwoLetters() {
        for (codePoint in 0..0x10FFFF) {
            if (codePoint in 0xD800..0xDFFF) continue
            val result = sanitizePeerText("a${text(codePoint)}b")
            var i = 0
            while (i < result.length) {
                val c = result.codePointAt(i)
                val joins = c == 0x200D || c in 0x1F3FB..0x1F3FF || c == 0x20E3 || c in 0xE0020..0xE007F ||
                    c == 0xFE0E || c == 0xFE0F || c in 0x1F1E6..0x1F1FF
                assertFalse(joins, "U+%04X survives as U+%04X in [%s]".format(codePoint, c, result))
                i += if (c >= 0x10000) 2 else 1
            }
        }
    }

    @Test fun emojiPresentationCharactersMeasureTwoCellsWithOrWithoutTheSelector() {
        for (codePoint in 0..0x10FFFF) {
            if (!isEmojiPresentation(codePoint) || codePoint in 0x1F1E6..0x1F1FF) continue
            assertEquals(2, text(codePoint).terminalWidth(), "U+%04X".format(codePoint))
            assertEquals(2, (text(codePoint) + "\uFE0F").terminalWidth(), "U+%04X FE0F".format(codePoint))
        }
    }

    @Test fun consoleSafeNeverDropsJoinedLetters() {
        assertEquals("abc", consoleSafe("a${zwj}b${zwj}c"))
        assertEquals("a?", consoleSafe("a$zwj$grinning"))
    }

    @Test fun consoleSafeDrawsOneMarkPerEmojiBase() {
        assertEquals("???", consoleSafe(family))
        assertEquals("?", consoleSafe("$thumbsUp$mediumSkin"))
        assertEquals("?", consoleSafe("$riU$riS"))
        assertEquals("?", consoleSafe("$heart\uFE0F"))
        assertEquals("e", consoleSafe("e\u0301"))
    }

    @Test fun consoleModeBarKeepsJoinedLetters() = runTest {
        runMosaicTest {
            val line = setContentAndSnapshot {
                CompositionLocalProvider(LocalConsoleSafe provides true) { Bar("a${zwj}b${zwj}c$family", width = 5) }
            }
            assertEquals("abc??", line) // a b c, then the three people as ??? cut to five cells.
        }
    }

    @Test fun consoleModeLoneIndicatorIsAReplacementCharacter() = runTest {
        runMosaicTest {
            val line = setContentAndSnapshot {
                CompositionLocalProvider(LocalConsoleSafe provides true) { Text(displayText("a${riU}b$riU$riS")) }
            }
            assertEquals("a?b?", line)
        }
    }

    @Test fun combiningMarkTableMatchesJdk21() {
        assertEquals(21, Runtime.version().feature(), "UnicodeTables.kt is pinned to JDK 21 (Unicode 15.0)")
        val getType = Class.forName("java.lang.Character").getMethod("getType", Int::class.javaPrimitiveType)
        val marks = setOf(6, 7, 8) // NON_SPACING_MARK, ENCLOSING_MARK, COMBINING_SPACING_MARK.
        for (codePoint in 0..0x10FFFF) {
            val jdk = (getType.invoke(null, codePoint) as Int) in marks
            assertEquals(jdk, isCombiningMark(codePoint), "U+%04X".format(codePoint))
        }
    }

    @Test fun emojiPresentationTableMatchesJdk21() {
        // The table was generated from JDK 21 (Unicode 15.0). Another JDK has another Unicode
        // version, so this fails rather than skips: regenerate Emoji.kt or run on JDK 21.
        assertEquals(21, Runtime.version().feature(), "Emoji.kt is pinned to JDK 21 (Unicode 15.0)")
        val jdk = Class.forName("java.lang.Character").getMethod("isEmojiPresentation", Int::class.javaPrimitiveType)
        for (codePoint in 0..0x10FFFF) {
            assertEquals(jdk.invoke(null, codePoint) as Boolean, isEmojiPresentation(codePoint), "U+%04X".format(codePoint))
        }
    }
}
