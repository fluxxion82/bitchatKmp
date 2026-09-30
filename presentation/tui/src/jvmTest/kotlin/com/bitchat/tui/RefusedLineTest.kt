package com.bitchat.tui

import com.jakewharton.mosaic.terminal.KeyboardEvent
import com.jakewharton.mosaic.testing.runMosaicTest
import com.jakewharton.mosaic.ui.unit.IntSize
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest

/**
 * A line the view model will not take (the user's data is being wiped) must not be swallowed:
 * the prompt clears the editor on `Enter`, before anything has had a chance to refuse it.
 */
class RefusedLineTest {
    private val editor = LineEditor().apply { insert("hello") }
    private val sent = ArrayList<String>()
    private val refusal = "data is being cleared, try again"

    @Test fun aRefusedLineGoesBackIntoThePromptWithTheReasonOnScreen() = runTest {
        var frame = ""
        runMosaicTest {
            setContentAndSnapshot {
                ChatScreen(
                    emptyList(),
                    nickname = "anon",
                    size = IntSize(50, 4),
                    editor = editor,
                    errorMessage = refusal,
                    onSend = { line -> sendOrKeep(editor, line) { sent += line; false } },
                )
            }
            sendKeyEvent(KeyboardEvent(13)) // Enter: the prompt clears, then gives the line back.
            sendKeyEvent(KeyboardEvent('!'.code)) // The next keystroke adds to it, not over it.
            frame = awaitSnapshot()
        }
        val rows = frame.lines().map { it.trimEnd() }

        assertEquals(listOf("hello"), sent, "it was offered once")
        assertEquals("hello!", editor.text, "and given back, whole, before the next key")
        assertEquals("> hello!", rows.last(), "still in the prompt")
        assertEquals(refusal, rows[rows.lastIndex - 1].trim(), "with the reason above it")
    }

    @Test fun anAcceptedLineLeavesThePrompt() = runTest {
        runMosaicTest {
            setContentAndSnapshot {
                ChatScreen(
                    emptyList(),
                    nickname = "anon",
                    size = IntSize(50, 4),
                    editor = editor,
                    onSend = { line -> sendOrKeep(editor, line) { sent += line; true } },
                )
            }
            sendKeyEvent(KeyboardEvent(13))
            awaitSnapshot()
        }
        assertEquals(listOf("hello"), sent)
        assertEquals("", editor.text)
    }
}
