package com.bitchat.tui

import com.jakewharton.mosaic.text.SpanStyle
import com.jakewharton.mosaic.text.buildAnnotatedString
import com.jakewharton.mosaic.text.withStyle
import com.jakewharton.mosaic.ui.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import com.jakewharton.mosaic.terminal.AnsiLevel
import com.jakewharton.mosaic.testing.MosaicSnapshots
import com.jakewharton.mosaic.testing.runMosaicTest
import com.jakewharton.mosaic.ui.Column
import com.jakewharton.mosaic.ui.Text

class CellsTest {
    @Test fun cellWidthCountsWideAndZeroWidthCharacters() {
        assertEquals(3, "abc".cellWidth())
        assertEquals(4, "中文".cellWidth())
        assertEquals(1, "e\u0301".cellWidth())
        assertEquals(2, "😀".cellWidth())
        assertEquals(2, "👍🏽".cellWidth())
        assertEquals(2, "🇺🇸".cellWidth())
        assertEquals(0, "".cellWidth())
    }

    @Test fun takeCellsNeverSplitsAWideCharacter() {
        assertEquals("中", "中文字".takeCells(3))
        assertEquals("中文", "中文字".takeCells(4))
    }

    @Test fun takeCellsKeepsCombiningMarksWithTheirBase() {
        assertEquals("e\u0301", "e\u0301x".takeCells(1))
    }

    @Test fun takeCellsOfShortTextIsTheText() {
        assertEquals("abc", "abc".takeCells(5))
    }

    @Test fun takeCellsOfZeroOrLessIsEmpty() {
        assertEquals("", "abc".takeCells(0))
        assertEquals("", "abc".takeCells(-2))
    }

    @Test fun takeLastCellsKeepsTheEndWithoutSplitting() {
        assertEquals("345", "12345".takeLastCells(3))
        assertEquals("字", "中文字".takeLastCells(3))
        assertEquals("", "abc".takeLastCells(0))
    }

    @Test fun truncateCellsLeavesFittingTextAlone() {
        assertEquals("anon", "anon".truncateCells(4))
    }

    @Test fun truncateCellsEllipsizesToExactlyTheWidth() {
        assertEquals("avery...", "averylongname".truncateCells(8))
    }

    @Test fun truncateCellsCountsWideCharacters() {
        assertEquals("中文...", "中文中文中文".truncateCells(7)) // 4 + 3 cells.
    }

    @Test fun truncateCellsNarrowerThanTheEllipsisCutsPlainly() {
        assertEquals("ab", "abcdef".truncateCells(2))
    }

    @Test fun padEndAndPadStartCountCells() {
        assertEquals("中  ", "中".padEndCells(4))
        assertEquals("  中", "中".padStartCells(4))
        assertEquals("abcdef", "abcdef".padEndCells(3))
    }

    @Test fun fitCellsIsExactlyTheWidth() {
        assertEquals("中文 ", "中文字".fitCells(5))
        assertEquals("ab   ", "ab".fitCells(5))
        assertEquals("", "ab".fitCells(0))
    }

    @Test fun wrapBreaksAtSpaces() {
        assertEquals(listOf("hello world", "foo"), wrapCells("hello world foo", 11))
    }

    @Test fun wrapBreaksWordsLongerThanTheWidth() {
        assertEquals(listOf("abcd", "efgh", "ij"), wrapCells("abcdefghij", 4))
    }

    @Test fun wrapCountsWideCharacters() {
        assertEquals(listOf("中文", "中文", "中"), wrapCells("中文中文中", 4))
        assertEquals(listOf("中", "文"), wrapCells("中文", 3))
    }

    @Test fun wrapKeepsInteriorSpaces() {
        assertEquals(listOf("a  b"), wrapCells("a  b", 10))
    }

    @Test fun wrapOfEmptyTextIsOneEmptyLine() {
        assertEquals(listOf(""), wrapCells("", 10))
    }

    @Test fun wrapAlwaysMakesProgress() {
        assertEquals(listOf("中", "文"), wrapCells("中文", 1))
        assertEquals(listOf("a", "b"), wrapCells("ab", 0))
    }

    @Test fun wrapKeepsStyles() {
        val red = SpanStyle(color = Color.Red)
        val text = buildAnnotatedString {
            withStyle(red) { append("alice") }
            append(" says hello")
        }
        val lines = wrapCells(text, 10)
        assertEquals(listOf("alice says", "hello"), lines.map { it.text })
        assertEquals(listOf(red), lines[0].spanStyles.map { it.item })
        assertEquals(0, lines[0].spanStyles.single().start)
        assertEquals(5, lines[0].spanStyles.single().end)
        assertEquals(emptyList(), lines[1].spanStyles)
    }

    @Test fun hardWrapIgnoresWords() {
        assertEquals(listOf("hello wo", "rld"), hardWrapCells("hello world", 8))
        assertEquals(listOf("中文", "中"), hardWrapCells("中文中", 5))
        assertEquals(listOf(""), hardWrapCells("", 5))
    }

    @Test fun hardWrapKeepsStyles() {
        val red = SpanStyle(color = Color.Red)
        val text = buildAnnotatedString {
            append("ab")
            withStyle(red) { append("cd") }
        }
        val lines = hardWrapCells(text, 3)
        assertEquals(listOf("abc", "d"), lines.map { it.text })
        assertEquals(2, lines[0].spanStyles.single().start)
        assertEquals(0, lines[1].spanStyles.single().start)
    }

    @Test fun wrappingDropsLinks() {
        val link = SpanStyle(color = Color.Red, link = "https://evil.example")
        val text = buildAnnotatedString {
            withStyle(link) { append("click here") }
            append(" now")
        }
        for (lines in listOf(wrapCells(text, 6), hardWrapCells(text, 6))) {
            assertTrue(lines.flatMap { it.spanStyles }.isNotEmpty())
            lines.flatMap { it.spanStyles }.forEach {
                assertEquals(null, it.item.link)
                assertEquals(Color.Red, it.item.color)
            }
        }
    }

    @Test fun wrappedLinksReachNoTerminal() = runTest {
        val text = buildAnnotatedString {
            withStyle(SpanStyle(link = "https://evil.example")) { append("click") }
        }
        runMosaicTest(MosaicSnapshots) {
            val ansi = setContentAndSnapshot { Column { wrapCells(text, 10).forEach { Text(it) } } }.draw().render(AnsiLevel.ANSI16, false)
            assertEquals("click", ansi)
        }
    }

    @Test fun cutsNeverSeparateASpacingMarkFromItsBase() {
        val text = "a\u093Eb" // Mosaic: a 1, the spacing mark 1, b 1.
        assertEquals("", text.takeCells(1)) // a and its mark are one 2-cell character.
        assertEquals("a\u093E", text.takeCells(2))
        assertEquals("b", text.takeLastCells(2)) // Not a detached mark before b.
        assertEquals("a\u093Eb", text.takeLastCells(3))
    }
}
