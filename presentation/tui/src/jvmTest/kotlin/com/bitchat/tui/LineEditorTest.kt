package com.bitchat.tui

import com.jakewharton.mosaic.layout.KeyEvent
import com.jakewharton.mosaic.text.forEachTerminalCell
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LineEditorTest {
    private val editor = LineEditor()
    private val submitted = ArrayList<String>()

    private fun key(key: String, ctrl: Boolean = false, alt: Boolean = false) =
        editor.handleKey(KeyEvent(key, alt = alt, ctrl = ctrl)) { submitted += it }

    private fun type(text: String) = text.forEach { key(it.toString()) }

    @Test fun typingInsertsAtTheCursor() {
        type("helo")
        key("ArrowLeft")
        type("l")
        assertEquals("hello", editor.text)
        assertEquals(4, editor.cursor)
    }

    @Test fun backspaceDeletesBeforeTheCursor() {
        type("abc")
        key("ArrowLeft")
        key("Backspace")
        assertEquals("ac", editor.text)
        assertEquals(1, editor.cursor)
    }

    @Test fun backspaceAtTheStartDoesNothing() {
        type("abc")
        key("Home")
        key("Backspace")
        assertEquals("abc", editor.text)
        assertEquals(0, editor.cursor)
    }

    @Test fun deleteRemovesTheCharacterUnderTheCursor() {
        type("abc")
        key("Home")
        key("Delete")
        assertEquals("bc", editor.text)
        key("End")
        key("Delete")
        assertEquals("bc", editor.text)
    }

    @Test fun homeEndLeftAndRightMoveTheCursor() {
        type("abc")
        key("Home")
        assertEquals(0, editor.cursor)
        key("ArrowRight")
        assertEquals(1, editor.cursor)
        key("End")
        assertEquals(3, editor.cursor)
        key("ArrowRight")
        assertEquals(3, editor.cursor)
        key("Home")
        key("ArrowLeft")
        assertEquals(0, editor.cursor)
    }

    @Test fun ctrlAAndCtrlEGoToTheStartAndEnd() {
        type("abc")
        key("a", ctrl = true)
        assertEquals(0, editor.cursor)
        key("e", ctrl = true)
        assertEquals(3, editor.cursor)
        assertEquals("abc", editor.text)
    }

    @Test fun ctrlUDeletesToTheStartOfTheLine() {
        type("hello world")
        repeat(5) { key("ArrowLeft") }
        key("u", ctrl = true)
        assertEquals("world", editor.text)
        assertEquals(0, editor.cursor)
    }

    @Test fun ctrlWDeletesTheWordBeforeTheCursor() {
        type("hello big world")
        key("w", ctrl = true)
        assertEquals("hello big ", editor.text)
        key("w", ctrl = true)
        assertEquals("hello ", editor.text)
        assertEquals(6, editor.cursor)
    }

    @Test fun enterSubmitsTheLineAndClearsIt() {
        type("hi there")
        key("Enter")
        assertEquals(listOf("hi there"), submitted)
        assertEquals("", editor.text)
        assertEquals(0, editor.cursor)
    }

    @Test fun enterOnABlankLineSubmitsNothing() {
        type("   ")
        key("Enter")
        assertEquals(emptyList(), submitted)
        assertEquals("", editor.text)
    }

    @Test fun submitReturnsTheLine() {
        editor.insert("hello")
        assertEquals("hello", editor.submit())
        assertEquals("", editor.text)
    }

    @Test fun upAndDownWalkTheHistory() {
        type("one")
        key("Enter")
        type("two")
        key("Enter")
        key("ArrowUp")
        assertEquals("two", editor.text)
        assertEquals(3, editor.cursor)
        key("ArrowUp")
        assertEquals("one", editor.text)
        key("ArrowUp")
        assertEquals("one", editor.text)
        key("ArrowDown")
        assertEquals("two", editor.text)
        key("ArrowDown")
        assertEquals("", editor.text)
    }

    @Test fun historyKeepsTheUnsentDraft() {
        type("one")
        key("Enter")
        type("dra")
        key("ArrowUp")
        assertEquals("one", editor.text)
        key("ArrowDown")
        assertEquals("dra", editor.text)
    }

    @Test fun repeatedLinesAreStoredOnce() {
        repeat(2) {
            type("same")
            key("Enter")
        }
        key("ArrowUp")
        key("ArrowUp")
        assertEquals("same", editor.text)
        key("ArrowDown")
        assertEquals("", editor.text)
    }

    @Test fun lineIsCappedAt512Chars() {
        editor.insert("x".repeat(600))
        assertEquals(MAX_LINE_LENGTH, editor.text.length)
        type("y")
        assertEquals("x".repeat(MAX_LINE_LENGTH), editor.text)
    }

    @Test fun capNeverSplitsASurrogatePair() {
        editor.insert("x".repeat(MAX_LINE_LENGTH - 1))
        editor.insert("😀")
        assertEquals(MAX_LINE_LENGTH - 1, editor.text.length)
    }

    @Test fun cursorMovesOverWholeCharacters() {
        type("a")
        key("😀")
        type("b")
        key("ArrowLeft")
        assertEquals(3, editor.cursor)
        key("ArrowLeft")
        assertEquals(1, editor.cursor)
        key("ArrowRight")
        key("Backspace")
        assertEquals("ab", editor.text)
    }

    @Test fun backspaceDeletesACombiningSequenceWhole() {
        editor.insert("e\u0301")
        key("Backspace")
        assertEquals("", editor.text)
    }

    @Test fun insertDropsControlCharacters() {
        editor.insert("a\u001B[2Jb\u009Bc\nd")
        assertEquals("a[2Jbcd", editor.text)
    }

    @Test fun insertDropsBidiOverridesAndIsolates() {
        // A prefilled peer nickname (say, for a DM command) must not reorder the operator's line.
        editor.insert("a\u202Eb\u2066c\u2069")
        assertEquals("abc", editor.text)
    }

    @Test fun insertDropsLoneSurrogatesAndLineSeparators() {
        editor.insert("a\uD800b\uDC00c\u2028d\u2029e")
        assertEquals("abcde", editor.text)
        assertEquals(5, editor.cursor)
    }

    @Test fun insertDropsSoftHyphensNonCharactersAndPrivateUse() {
        editor.insert("a\u00ADb\uFFFFc\uE000d\uDB80\uDC00")
        assertEquals("abcd", editor.text)
    }

    @Test fun insertKeepsSurrogatePairs() {
        editor.insert("x\uD83D\uDE00")
        assertEquals("x\uD83D\uDE00", editor.text)
    }

    @Test fun insertDropsJoinersBetweenLetters() {
        editor.insert("a\u200Db")
        assertEquals("ab", editor.text)
    }

    @Test fun typedJoinerBetweenLettersIsDropped() {
        key("a")
        key("\u200D")
        key("b")
        assertEquals("ab", editor.text)
        assertEquals(2, editor.cursor)
    }

    @Test fun insertSplitsAnEmojiZwjSequenceIntoItsEmoji() {
        editor.insert("\uD83D\uDC68\u200D\uD83D\uDC69")
        assertEquals("\uD83D\uDC68\uD83D\uDC69", editor.text)
        assertEquals(4, editor.cursor)
    }

    @Test fun joinerInsertedBetweenEmojiIsRemovedAndDeleteLeavesACleanLine() {
        // The re-review's path: insert two people, Left, insert a ZWJ, Delete.
        editor.insert("\uD83D\uDC68\uD83D\uDC69")
        key("ArrowLeft")
        editor.insert("\u200D")
        assertEquals("\uD83D\uDC68\uD83D\uDC69", editor.text)
        key("Delete")
        assertEquals("\uD83D\uDC68", editor.text)
        assertEquals(editor.text, sanitizePeerText(editor.text))
    }

    @Test fun markWithNothingBeforeItIsDropped() {
        // Found by the property test: a leading accent would join the next character typed before it.
        editor.insert("\u4E2D")
        key("Home")
        editor.insert("\u0301")
        assertEquals("\u4E2D", editor.text)
        editor.insert("\u4E2D")
        assertEquals("\u4E2D\u4E2D", editor.text)
        assertEquals(1, editor.cursor)
    }

    @Test fun accentTypedAfterALetterIsKept() {
        type("e")
        key("\u0301")
        assertEquals("e\u0301", editor.text)
        assertEquals(2, editor.cursor)
    }

    @Test fun cursorMovesOverABaseAndItsSpacingMarkTogether() {
        editor.insert("a\u093Eb")
        key("ArrowLeft")
        assertEquals(2, editor.cursor)
        key("ArrowLeft")
        assertEquals(0, editor.cursor)
        key("Delete")
        assertEquals("b", editor.text)
    }

    @Test fun backspaceRemovesASpacingMarkWithItsBase() {
        editor.insert("xa\u093E")
        key("Backspace")
        assertEquals("x", editor.text)
    }

    @Test fun insertedJamoComposeAndTypedJamoAreDropped() {
        editor.insert("\u1100\u1161\u11A8")
        assertEquals("\uAC01", editor.text)
        key("\u1100")
        assertEquals("\uAC01", editor.text)
    }

    @Test fun editsKeepTheLineSanitizedAndTheCursorOnACharacterBoundary() {
        val tokens = listOf(
            "a", "b", " ", "1", "#", "\u4E2D", "\u00A9", "\u261D", "\u2764", "\uD83D\uDE00", "\uD83D\uDC68",
            "\u200D", "\uFE0F", "\uFE0E", "\uD83C\uDFFD", "\uD83C\uDDFA", "\uD83C\uDDF8", "\u20E3",
            "\uDB40\uDC67", "\u0301", "\u001B", "\u009B", "\uD800", "\u202E", "\u2028", "\n", "\t",
            "\u093E", "\u00AD", "\uFFFF", "\uE000", "\u1100", "\u1161", "\u11A8", "\uAC00",
        )
        val keys = listOf("Backspace", "Delete", "ArrowLeft", "ArrowRight", "Home", "End", "ArrowUp", "ArrowDown", "Enter")
        for (seed in 1..25) {
            val random = kotlin.random.Random(seed)
            val editor = LineEditor(maxLength = 24)
            repeat(400) { step ->
                when (random.nextInt(4)) {
                    0, 1 -> editor.insert(List(1 + random.nextInt(4)) { tokens.random(random) }.joinToString(""))
                    2 -> editor.handleKey(KeyEvent(keys.random(random))) {}
                    else -> editor.handleKey(KeyEvent(listOf("a", "e", "u", "w").random(random), ctrl = true)) {}
                }
                val where = "seed $seed step $step [${editor.text}] cursor ${editor.cursor}"
                assertEquals(sanitizePeerText(editor.text), editor.text, where)
                assertTrue(editor.cursor in boundaries(editor.text), where)
                assertTrue(editor.text.length <= 24, where)
            }
        }
    }

    /** 0, the end, and every cluster edge, from Mosaic's own clustering. */
    private fun boundaries(text: String): Set<Int> {
        val edges = mutableSetOf(0, text.length)
        text.forEachTerminalCell { start, end, _, _ ->
            edges += start
            edges += end
        }
        return edges
    }

    @Test fun unboundKeysAreLeftForOthers() {
        type("abc")
        assertFalse(key("1", alt = true))
        assertFalse(key("]", ctrl = true))
        assertFalse(key("c", ctrl = true))
        assertFalse(key("p", ctrl = true))
        assertFalse(key("Tab"))
        assertFalse(key("Escape"))
        assertFalse(key("PageUp"))
        assertFalse(key("F1"))
        assertEquals("abc", editor.text)
    }

    @Test fun editingKeysAreConsumed() {
        assertTrue(key("x"))
        assertTrue(key("Backspace"))
        assertTrue(key("ArrowUp"))
        assertTrue(key("Enter"))
    }
}
