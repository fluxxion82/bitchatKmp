package com.bitchat.tui

import androidx.compose.runtime.mutableStateListOf
import com.jakewharton.mosaic.layout.KeyEvent
import com.jakewharton.mosaic.layout.onKeyEvent
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.terminal.AnsiLevel
import com.jakewharton.mosaic.terminal.KeyboardEvent
import com.jakewharton.mosaic.testing.MosaicSnapshots
import com.jakewharton.mosaic.testing.runMosaicTest
import com.jakewharton.mosaic.ui.Column
import com.jakewharton.mosaic.ui.Text
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class LinePromptTest {
    private val editor = LineEditor()
    private val submitted = ArrayList<String>()

    @Test fun showsThePromptAndTheText() = runTest {
        editor.insert("hi")
        runMosaicTest {
            val line = setContentAndSnapshot { LinePrompt(editor, width = 20, onSubmit = {}) }.lines().single()
            assertEquals("> hi ", line)
        }
    }

    @Test fun cursorAtTheEndIsABlankInTheReversedThemeColours() = runTest {
        editor.insert("hi")
        runMosaicTest(MosaicSnapshots) {
            val ansi = setContentAndSnapshot { LinePrompt(editor, width = 20, onSubmit = {}) }.draw().render(AnsiLevel.ANSI16, false)
            // The line is the dark theme's phosphor green (92); the cell under the cursor swaps
            // that for the background (black on bright green).
            assertEquals("\u001B[92m> hi\u001B[30;102m \u001B[0m", ansi, ansi.replace("\u001B", "ESC"))
        }
    }

    @Test fun cursorInTheMiddleReversesTheCharacterUnderIt() = runTest {
        editor.insert("abc")
        editor.handleKey(KeyEvent("Home")) {}
        editor.handleKey(KeyEvent("ArrowRight")) {}
        runMosaicTest(MosaicSnapshots) {
            val ansi = setContentAndSnapshot { LinePrompt(editor, width = 20, onSubmit = {}) }.draw().render(AnsiLevel.ANSI16, false)
            assertEquals("\u001B[92m> a\u001B[30;102mb\u001B[92;49mc\u001B[0m", ansi, ansi.replace("\u001B", "ESC"))
        }
    }

    @Test fun keysEditTheLine() = runTest {
        runMosaicTest {
            setContentAndSnapshot { LinePrompt(editor, width = 20, onSubmit = { submitted += it }) }
            sendKeyEvent(KeyboardEvent('h'.code))
            sendKeyEvent(KeyboardEvent('i'.code))
            assertEquals("> hi ", awaitSnapshot().lines().single())
        }
    }

    @Test fun enterSubmitsAndClears() = runTest {
        runMosaicTest {
            setContentAndSnapshot { LinePrompt(editor, width = 20, onSubmit = { submitted += it }) }
            sendKeyEvent(KeyboardEvent('o'.code))
            sendKeyEvent(KeyboardEvent('k'.code))
            sendKeyEvent(KeyboardEvent(13))
            assertEquals(">  ", awaitSnapshot().lines().single()) // Prompt, then the cursor block.
            assertEquals(listOf("ok"), submitted)
        }
    }

    @Test fun insertedTextIsCleanOnScreenAndWhenSubmitted() = runTest {
        editor.insert("a\uD800b\u2028c\u200Dd")
        runMosaicTest {
            assertEquals("> abcd ", setContentAndSnapshot { LinePrompt(editor, width = 20, onSubmit = { submitted += it }) })
            sendKeyEvent(KeyboardEvent(13))
            awaitSnapshot()
            assertEquals(listOf("abcd"), submitted)
        }
    }

    @Test fun longLineScrollsToKeepTheCursorVisible() {
        val line = promptLine("> ", "abcdefghijklmnopqrst", cursor = 20, width = 10, focused = true)
        assertEquals("> nopqrst ", line.text) // Prompt 2, seven letters, cursor block 1.
    }

    @Test fun longLineShowsTheStartWhenTheCursorIsThere() {
        val line = promptLine("> ", "abcdefghijklmnopqrst", cursor = 0, width = 10, focused = true)
        assertEquals("> abcdefgh", line.text)
    }

    @Test fun wideCharactersCountAsTwoCellsWhenScrolling() {
        val line = promptLine("> ", "中文中文中文", cursor = 6, width = 9, focused = true)
        assertEquals("> 文中文 ", line.text) // Prompt 2, three wide characters 6, cursor block 1.
    }

    @Test fun wideCursorCharacterDropsThePromptWhenBothDoNotFit() {
        assertEquals("中", promptLine("> ", "中", cursor = 0, width = 3, focused = true).text)
    }

    @Test fun wideCursorCharacterKeepsThePromptWhenBothFit() {
        assertEquals("> 中", promptLine("> ", "中", cursor = 0, width = 4, focused = true).text)
    }

    @Test fun wideCursorCharacterInOneCellIsAReversedPlaceholder() {
        val line = promptLine("> ", "中", cursor = 0, width = 1, focused = true)
        assertEquals("?", line.text)
        assertEquals(0, line.spanStyles.single().start)
        assertEquals(1, line.spanStyles.single().end)
    }

    @Test fun cursorBlockInOneCellDropsThePrompt() {
        assertEquals(" ", promptLine("> ", "ab", cursor = 2, width = 1, focused = true).text)
    }

    @Test fun zeroOrNegativeWidthDrawsNothing() {
        for (width in listOf(0, -3)) {
            assertEquals("", promptLine("> ", "abc", cursor = 3, width = width, focused = true).text)
            assertEquals("", promptLine("> ", "abc", cursor = 0, width = width, focused = false).text)
        }
    }

    @Test fun unfocusedPromptHasNoCursor() = runTest {
        editor.insert("hi")
        runMosaicTest(MosaicSnapshots) {
            val mosaic = setContentAndSnapshot { LinePrompt(editor, width = 20, onSubmit = {}, focused = false) }
            assertFalse(mosaic.draw().render(AnsiLevel.ANSI16, false).contains("\u001B[7m"))
        }
    }

    @Test fun unfocusedPromptLeavesKeysAlone() = runTest {
        editor.insert("hi")
        runMosaicTest {
            val passed = mutableStateListOf<String>()
            setContentAndSnapshot {
                Column(Modifier.onKeyEvent { passed += it.key; true }) {
                    Text("passed ${passed.size}")
                    LinePrompt(editor, width = 20, onSubmit = {}, focused = false)
                }
            }
            sendKeyEvent(KeyboardEvent('x'.code))
            awaitSnapshot()
            assertEquals(listOf("x"), passed)
            assertEquals("hi", editor.text)
        }
    }

    // A raw family cluster (the editor never holds one; promptLine takes any text) is ??? in console
    // mode: three cells, so the prompt must account for three.
    private val rawFamily = "\uD83D\uDC68\u200D\uD83D\uDC69\u200D\uD83D\uDC67"

    @Test fun consolePromptCountsTheCellsItShows() {
        assertEquals("> ???", promptLine("> ", rawFamily, cursor = 0, width = 5, focused = true, consoleSafe = true).text)
        assertEquals("???", promptLine("> ", rawFamily, cursor = 0, width = 4, focused = true, consoleSafe = true).text)
        assertEquals("???", promptLine("> ", rawFamily, cursor = 0, width = 3, focused = true, consoleSafe = true).text)
        assertEquals("?", promptLine("> ", rawFamily, cursor = 0, width = 2, focused = true, consoleSafe = true).text)
    }

    @Test fun consolePromptScrollsByTheCellsItShows() {
        val end = rawFamily.length
        assertEquals(">  ", promptLine("> ", rawFamily, cursor = end, width = 3, focused = true, consoleSafe = true).text)
        assertEquals("> ??? ", promptLine("> ", rawFamily, cursor = end, width = 6, focused = true, consoleSafe = true).text)
    }
}
