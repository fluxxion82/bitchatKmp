package com.bitchat.tui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.jakewharton.mosaic.layout.onKeyEvent
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.terminal.KeyboardEvent
import com.jakewharton.mosaic.RenderMode
import com.jakewharton.mosaic.terminal.Terminal
import com.jakewharton.mosaic.testing.TestMosaic
import com.jakewharton.mosaic.testing.runMosaicTest
import com.jakewharton.mosaic.ui.Box
import com.jakewharton.mosaic.ui.Column
import com.jakewharton.mosaic.ui.Text
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull

class TuiAppTest {
    private val navigation = TuiNavigation()
    private var mode: Mode
        get() = navigation.mode
        set(value) {
            navigation.mode = value
        }

    private fun TestMosaic<String>.app(columns: Int, rows: Int): String {
        state.size.value = Terminal.Size(columns, rows)
        return setContentAndSnapshot {
            TuiApp(nickname = "anon1234", peerCount = 3, navigation = navigation) { mode, size ->
                Text("body ${mode.name} ${size.width}x${size.height}")
            }
        }
    }

    /** Snapshots until nothing changes any more (bounded), returning the last one. */
    private suspend fun TestMosaic<String>.settle(): List<String> {
        var last = awaitSnapshot()
        repeat(10) {
            last = try {
                awaitSnapshot()
            } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
                return last.lines()
            }
        }
        return last.lines()
    }

    private fun TestMosaic<*>.press(codepoint: Int, modifiers: Int = 0) =
        sendKeyEvent(KeyboardEvent(codepoint, modifiers = modifiers))

    @Test fun aFullScreenFrameUsesEveryRowAndAnInlineOneLeavesTheLast() {
        // Inline ends each frame with a newline, so a full-height frame would scroll the screen;
        // the alternate screen addresses every row and has no such line.
        assertEquals(12, frameSize(Terminal.Size(40, 12), RenderMode.FullScreen).height)
        assertEquals(11, frameSize(Terminal.Size(40, 12), RenderMode.Inline).height)
        assertEquals(11, frameSize(Terminal.Size(40, 12)).height, "inline by default")
        assertEquals(40, frameSize(Terminal.Size(40, 12), RenderMode.FullScreen).width)
        // Both are clamped to the smallest frame the app draws in.
        assertEquals(MIN_FRAME_ROWS, frameSize(Terminal.Size(4, 1), RenderMode.FullScreen).height)
        assertEquals(MIN_FRAME_COLUMNS, frameSize(Terminal.Size(4, 1), RenderMode.FullScreen).width)
    }

    /** Counts repaint requests; `Ctrl+L` draws no frame of its own, so a key that does follows it. */
    private suspend fun repaintsAt(columns: Int, rows: Int): Int {
        val repaints = ArrayList<Unit>()
        runMosaicTest {
            state.size.value = Terminal.Size(columns, rows)
            setContentAndSnapshot { TuiApp("anon", 0, navigation, onRepaint = { repaints += Unit }) { _, _ -> } }
            press('l'.code, KeyboardEvent.ModifierCtrl)
            press('p'.code, KeyboardEvent.ModifierCtrl) // Something that does draw one.
            awaitSnapshot()
        }
        return repaints.size
    }

    @Test fun ctrlLAsksForARepaintAndOpensNothing() = runTest {
        assertEquals(1, repaintsAt(40, 12))
        assertEquals(Mode.Peers, mode, "the key after it is the one that moved: Ctrl+L opened nothing")
    }

    @Test fun ctrlLRepaintsTheSmallestFrameToo() = runTest {
        // The frame the app clamps to, where it has no row to spare.
        assertEquals(1, repaintsAt(20, MIN_FRAME_ROWS))
    }

    @Test fun frameAt40x12IsOneRowShortOfTheTerminal() = runTest {
        runMosaicTest {
            val lines = app(40, 12).lines()
            assertEquals(11, lines.size, lines.joinToString("\n"))
        }
    }

    @Test fun headerAndFooterAt40x12() = runTest {
        runMosaicTest {
            val lines = app(40, 12).lines()
            assertTrue(lines.first().contains("anon1234"), lines.first())
            assertTrue(lines.first().contains("3 peers"), lines.first())
            assertEquals(40, lines.first().length) // ASCII only: one cell per char.
            assertTrue(lines.last().contains("^P peers"), lines.last())
            assertTrue(lines.last().length <= 40, lines.last())
        }
    }

    @Test fun bodyGetsTheRemainingHeightAt40x12() = runTest {
        runMosaicTest {
            assertEquals("body Chat 40x9", app(40, 12).lines()[1].trimEnd())
        }
    }

    @Test fun headerFooterAndBodyAt128x37() = runTest {
        runMosaicTest {
            val lines = app(128, 37).lines()
            assertEquals(36, lines.size)
            assertEquals(128, lines.first().length)
            assertTrue(lines.first().contains("anon1234"), lines.first())
            assertEquals("body Chat 128x34", lines[1].trimEnd())
            assertTrue(lines.last().contains("^S settings"), lines.last())
        }
    }

    @Test fun tinyTerminalIsClampedToTheMinimumFrame() = runTest {
        runMosaicTest {
            val lines = app(8, 3).lines()
            assertEquals(MIN_FRAME_ROWS, lines.size)
            assertEquals(MIN_FRAME_COLUMNS, lines.first().length)
            assertEquals("body Chat ${MIN_FRAME_COLUMNS}x${MIN_FRAME_ROWS - 2}", lines[1].trimEnd())
        }
    }

    @Test fun ctrlPShowsPeersAndEscGoesBackToChat() = runTest {
        runMosaicTest {
            app(40, 12)
            press('p'.code, KeyboardEvent.ModifierCtrl)
            assertEquals("body Peers 40x9", awaitSnapshot().lines()[1].trimEnd())
            press(27)
            assertEquals("body Chat 40x9", awaitSnapshot().lines()[1].trimEnd())
        }
    }

    @Test fun ctrlSShowsSettings() = runTest {
        runMosaicTest {
            app(40, 12)
            press('s'.code, KeyboardEvent.ModifierCtrl)
            assertEquals("body Settings 40x9", awaitSnapshot().lines()[1].trimEnd())
        }
    }

    @Test fun footerFollowsTheMode() = runTest {
        runMosaicTest {
            app(80, 12)
            press('p'.code, KeyboardEvent.ModifierCtrl)
            assertTrue(awaitSnapshot().lines().last().contains("Esc back"))
        }
    }

    @Test fun tabCyclesThroughTheModes() = runTest {
        runMosaicTest {
            app(40, 12)
            press(9)
            assertEquals("body Peers 40x9", awaitSnapshot().lines()[1].trimEnd())
            press(9)
            assertEquals("body Locations 40x9", awaitSnapshot().lines()[1].trimEnd())
            press(9)
            assertEquals("body Settings 40x9", awaitSnapshot().lines()[1].trimEnd())
            press(9)
            assertEquals("body Chat 40x9", awaitSnapshot().lines()[1].trimEnd())
        }
    }

    @Test fun keysArrivingInOneFrameRouteFromEachOther() = runTest {
        runMosaicTest {
            app(40, 12)
            press('p'.code, KeyboardEvent.ModifierCtrl)
            press(9) // Held until the peers body is mounted, then routed from there.
            assertEquals("body Locations 40x9", settle()[1].trimEnd())
        }
    }

    @Test fun shiftTabGoesBackThroughTheModes() = runTest {
        runMosaicTest {
            app(40, 12)
            // A shifted Tab reaches the app as Tab with shift set, whether the terminal sent the
            // legacy CSI Z or Kitty's CSI 9;2u.
            press(9, KeyboardEvent.ModifierShift)
            settle()
            assertEquals(Mode.Settings, mode, "back one from chat wraps to the last mode")
            press(9, KeyboardEvent.ModifierShift)
            settle()
            assertEquals(Mode.Locations, mode)
            press(9) // Plain Tab still goes forward.
            settle()
            assertEquals(Mode.Settings, mode)
        }
    }

    @Test fun shiftTabLeavesADmTheWayTabDoes() = runTest {
        runMosaicTest {
            app(40, 12)
            mode = Mode.Dm
            settle()
            press(9, KeyboardEvent.ModifierShift)
            settle()
            assertEquals(Mode.Chat, mode, "a DM counts as the peers list it was opened from")
        }
    }

    @Test fun altTabGoesBackThroughEveryTabbedMode() {
        for (from in listOf(Mode.Chat, Mode.Peers, Mode.Dm, Mode.Locations, Mode.Notes, Mode.Settings, Mode.Wipe)) {
            assertEquals(
                routeKey(from, com.jakewharton.mosaic.layout.KeyEvent("Tab", shift = true)),
                routeKey(from, com.jakewharton.mosaic.layout.KeyEvent("Tab", alt = true)),
                from.name,
            )
        }
    }

    @Test fun otherAltModifiedKeysAreLeftAlone() {
        assertNull(routeKey(Mode.Chat, com.jakewharton.mosaic.layout.KeyEvent("p", alt = true)))
        assertNull(routeKey(Mode.Chat, com.jakewharton.mosaic.layout.KeyEvent("Tab", alt = true, ctrl = true)))
    }

    @Test fun ctrlGShowsLocations() = runTest {
        runMosaicTest {
            app(40, 12)
            press('g'.code, KeyboardEvent.ModifierCtrl)
            assertEquals("body Locations 40x9", awaitSnapshot().lines()[1].trimEnd())
        }
    }

    @Test fun escFromADmGoesBackToPeers() = runTest {
        mode = Mode.Dm
        runMosaicTest {
            app(40, 12)
            press(27)
            assertEquals("body Peers 40x9", awaitSnapshot().lines()[1].trimEnd())
        }
    }

    @Test fun tabFromADmMovesOnLikeFromPeers() {
        assertEquals(Mode.Locations, routeKey(Mode.Dm, com.jakewharton.mosaic.layout.KeyEvent("Tab")))
    }

    @Test fun escFromEveryTopLevelScreenGoesToChat() {
        for (from in listOf(Mode.Peers, Mode.Locations, Mode.Settings)) {
            assertEquals(Mode.Chat, routeKey(from, com.jakewharton.mosaic.layout.KeyEvent("Escape")), from.name)
        }
    }

    @Test fun footerNamesEachScreensKeys() {
        assertTrue(footerText(footerHints(Mode.Dm), 80).text.contains("Esc peers"))
        assertTrue(footerText(footerHints(Mode.Locations), 80).text.contains("g grid"))
        assertTrue(footerText(footerHints(Mode.Settings), 80).text.contains("Left/Right change"))
        assertTrue(footerText(footerHints(Mode.Chat), 128).text.contains("^G places"))
    }

    @Test fun headerShowsUnreadDms() = runTest {
        runMosaicTest {
            state.size.value = Terminal.Size(40, 12)
            val header = setContentAndSnapshot {
                TuiApp(nickname = "anon1234", peerCount = 3, navigation = navigation, unreadDms = 2) { _, _ -> }
            }.lines().first()
            assertEquals(" anon1234" + " ".repeat(17) + "2 DM, 3 peers ", header)
        }
    }

    @Test fun footerHintsThatDoNotFitAreDropped() {
        val hints = footerHints(Mode.Chat)
        assertEquals(" Enter send", footerText(hints, 20).text) // The next hint would make it 21.
        val wide = footerText(hints, 200)
        hints.forEach { assertTrue(wide.text.contains("${it.key} ${it.label}"), wide.text) }
    }

    @Test fun footerHintsAreSanitized() {
        val text = footerText(listOf(KeyHint("\u001B[31mX", "\u009Fy\u009C")), 40)
        assertEquals(" ?[31mX ?y?", text.text)
        assertEquals(1, text.spanStyles.single().start)
        assertEquals(7, text.spanStyles.single().end) // The key's six chars after the leading space.
    }

    @Test fun footerHintsAreMeasuredAfterSanitizing() {
        // " ^P abc" is seven cells once the joiners are gone; unsanitized, Mosaic would count five.
        val hint = listOf(KeyHint("^P", "a\u200Db\u200Dc"))
        assertEquals(" ^P abc", footerText(hint, 7).text)
        assertEquals("", footerText(hint, 6).text)
    }

    @Test fun footerHintsAreConsoleSafeInConsoleMode() {
        assertEquals(" ^P ?", footerText(listOf(KeyHint("^P", "\u4E2D")), 10, consoleSafe = true).text)
    }

    @Test fun footerKeysAreReverseVideo() {
        val text = footerText(listOf(KeyHint("^P", "peers")), 40)
        val span = text.spanStyles.single()
        assertEquals("^P", text.text.substring(span.start, span.end))
    }

    @Test fun hostKeysAndCtrlCPassThroughUnhandled() = runTest {
        runMosaicTest {
            val passed = mutableStateListOf<String>()
            state.size.value = Terminal.Size(40, 12)
            setContentAndSnapshot {
                // Stands in for a launcher (or Mosaic's own Ctrl+C exit): it sees what the app leaves.
                Column(Modifier.onKeyEvent { passed += it.toString(); true }) {
                    Text("passed ${passed.size}")
                    TuiApp(nickname = "anon1234", peerCount = 3, navigation = navigation) { _, _ -> }
                }
            }
            press('1'.code, KeyboardEvent.ModifierAlt)
            awaitSnapshot()
            press(']'.code, KeyboardEvent.ModifierCtrl)
            awaitSnapshot()
            press('c'.code, KeyboardEvent.ModifierCtrl)
            awaitSnapshot()
            assertEquals(3, passed.size, passed.toString())
            assertEquals(Mode.Chat, mode)
        }
    }

    @Test fun escInChatIsLeftForTheHost() {
        assertNull(routeKey(Mode.Chat, com.jakewharton.mosaic.layout.KeyEvent("Escape")))
    }

    @Test fun appStaysAliveWithNothingElseRunning() = runTest {
        runMosaicTest {
            app(40, 12)
            assertNull(withTimeoutOrNull(5.seconds) { awaitComplete() })
        }
    }

    @Test fun tapIsANoOpSeamForNow() = runTest {
        runMosaicTest {
            var tapped = false
            val plain = setContentAndSnapshot { Box { Text("row") } }
            val tappable = setContentAndSnapshot { Box(Modifier.tap { tapped = true }) { Text("row") } }
            assertEquals(plain, tappable)
            assertEquals(false, tapped)
        }
    }
}
