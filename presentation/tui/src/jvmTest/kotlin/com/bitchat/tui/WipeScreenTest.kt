package com.bitchat.tui

import com.jakewharton.mosaic.terminal.KeyboardEvent
import com.jakewharton.mosaic.terminal.Terminal
import com.jakewharton.mosaic.testing.TestMosaic
import com.jakewharton.mosaic.testing.runMosaicTest
import com.jakewharton.mosaic.ui.Text
import com.jakewharton.mosaic.ui.unit.IntSize
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/** The emergency wipe: three keys open a question, and only the word typed in full answers it. */
class WipeScreenTest {
    private val navigation = TuiNavigation()
    private var erased = 0
    private var kept = 0

    private fun TestMosaic<*>.press(codepoint: Int, modifiers: Int = 0) =
        sendKeyEvent(KeyboardEvent(codepoint, modifiers = modifiers))

    private fun TestMosaic<*>.type(text: String) = text.forEach { press(it.code) }

    private fun TestMosaic<*>.ctrlD() = press('d'.code, KeyboardEvent.ModifierCtrl)

    /** Snapshots until nothing changes any more (bounded), returning the last one. */
    private suspend fun TestMosaic<String>.settle(): String {
        var last = ""
        repeat(10) {
            last = try {
                awaitSnapshot()
            } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
                return last
            }
        }
        return last
    }

    private suspend fun TestMosaic<String>.app(): String {
        state.size.value = Terminal.Size(60, 12)
        return setContentAndSnapshot {
            TuiApp(nickname = "anon", peerCount = 0, navigation = navigation) { mode, size ->
                when (mode) {
                    Mode.Wipe -> WipeScreen(size, onConfirm = { erased++ }, onCancel = { kept++ })
                    else -> Text("screen ${mode.name}")
                }
            }
        }
    }

    @Test fun threeCtrlDsOpenTheQuestionAndEraseNothingByThemselves() = runTest {
        var frame = ""
        runMosaicTest {
            app()
            ctrlD()
            ctrlD()
            ctrlD()
            frame = settle()
        }
        assertEquals(Mode.Wipe, navigation.mode)
        assertEquals(0, erased, "the keys ask, they do not erase")
        assertTrue(frame.contains("Erase everything on this device?"), frame)
        assertTrue(frame.contains("identity keys"), frame)
        assertTrue(frame.contains("cannot be undone"), frame)
        assertTrue(frame.contains("type wipe to erase"), frame)
    }

    @Test fun anotherKeyInBetweenStartsTheCountAgain() = runTest {
        runMosaicTest {
            app()
            ctrlD()
            ctrlD()
            press('x'.code)
            ctrlD()
            ctrlD()
            press('x'.code)
            settle()
        }
        assertEquals(Mode.Chat, navigation.mode, "two, then two, is not three in a row")
    }

    @Test fun theWordTypedInFullErases() = runTest {
        runMosaicTest {
            app()
            ctrlD()
            ctrlD()
            ctrlD()
            settle()
            type(WIPE_WORD)
            press(13)
            settle()
        }
        assertEquals(1, erased)
        assertEquals(0, kept)
    }

    @Test fun anythingElseKeepsEverything() = runTest {
        runMosaicTest {
            app()
            ctrlD()
            ctrlD()
            ctrlD()
            settle()
            type("yes")
            press(13)
            settle()
        }
        assertEquals(0, erased, "only the word does it")
        assertEquals(1, kept)
    }

    @Test fun escapeLeavesWithoutErasing() = runTest {
        runMosaicTest {
            app()
            ctrlD()
            ctrlD()
            ctrlD()
            settle()
            press(27)
            settle()
        }
        assertEquals(0, erased)
        assertEquals(Mode.Chat, navigation.mode)
    }

    @Test fun theWipeScreenIsNotInTheTabOrder() = runTest {
        runMosaicTest {
            app()
            ctrlD()
            ctrlD()
            ctrlD()
            settle()
            press(9) // Tab moves on as it would from the chat it was opened over.
            settle()
        }
        assertEquals(Mode.Peers, navigation.mode)
    }
}
