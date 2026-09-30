package com.bitchat.tui

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.snapshots.Snapshot
import com.jakewharton.mosaic.terminal.KeyboardEvent
import com.jakewharton.mosaic.terminal.Terminal
import com.jakewharton.mosaic.testing.TestMosaic
import com.jakewharton.mosaic.testing.runMosaicTest
import com.jakewharton.mosaic.ui.Text
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.test.runTest
import kotlin.time.Duration.Companion.hours
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineScope

/**
 * A DM opened, typed into and left in single key batches, against a view model stand-in that
 * switches its active chat only when the test says so (the real one does it in another coroutine).
 * A line must reach the DM, never whatever chat the view model still has active.
 */
class DmFlowTest {
    private val navigation = TuiNavigation(Mode.Peers)
    private val calls = mutableStateListOf<String>()
    private lateinit var session: DmSession
    private lateinit var ui: CoroutineScope
    private val bob = PeerEntry("b0b", "bob", PeerTransport.Direct)
    private val editor = LineEditor()

    /** The chat the stand-in view model has active: null is the public mesh chat. */
    private var viewModelChat: String? = null

    /** Lines delivered, with the chat they went to. */
    private val delivered = mutableStateListOf<Pair<String, String?>>()

    /** The session, on the test's scope; the stand-in finishes every request at once. */
    private fun TestScope.newSession() {
        ui = CoroutineScope(coroutineContext + Job())
        session = DmSession(
            navigation, ui,
            start = { calls += "start ${it.id}"; true },
            leave = { calls += "leave" },
            describe = { key -> PeerEntry(key, "bob", PeerTransport.Direct) },
            openTimeout = 1.hours,
        )
    }

    private fun TestMosaic<String>.app() {
        state.size.value = Terminal.Size(40, 12)
        setContentAndSnapshot {
            TuiApp(nickname = "anon", peerCount = 1, navigation = navigation) { mode, size ->
                when (mode) {
                    Mode.Peers -> PeersScreen(listOf(bob), size, onOpenDm = session::open, onToggleFavorite = {})
                    Mode.Dm -> DmScreen(
                        peerName = "bob",
                        messages = emptyList(),
                        nickname = "anon",
                        size = size,
                        // As the binding does: re-checked against the view model at the moment of sending.
                        onSend = { line -> if (session.canSend(viewModelChat)) delivered += line to viewModelChat else editor.insert(line) },
                        editor = editor,
                        sendHold = { session.sendHold },
                    )
                    else -> Text("screen ${mode.name}")
                }
            }
        }
    }

    private fun TestMosaic<*>.press(vararg codes: Int) = codes.forEach { sendKeyEvent(KeyboardEvent(it)) }

    /** The view model finished switching to [chat] and reports it. */
    private fun viewModelSelects(chat: String?) {
        viewModelChat = chat
        session.onSelectedPeer(chat)
    }

    /** Snapshots until nothing changes any more (bounded), returning the last one. */
    private suspend fun TestMosaic<String>.settle(): List<String> {
        var last = awaitSnapshot()
        repeat(10) {
            last = try {
                awaitSnapshot()
            } catch (_: TimeoutCancellationException) {
                return last.lines()
            }
        }
        return last.lines()
    }

    @Test fun openTypeAndSendInOneBatchSendsNothingUntilTheDmIsOpen() = runTest {
        newSession()
        runMosaicTest {
            app()
            press(13, 'h'.code, 'i'.code, 13)
            val screen = settle()
            assertEquals(" DM with bob", screen[1])
            assertEquals("opening... hi", screen[screen.lastIndex - 1].trimEnd())
            assertEquals(emptyList<Pair<String, String?>>(), delivered.toList(), "the view model still had the public chat active")

            viewModelSelects("b0b")
            press(13)
            settle()
        }
        assertEquals(listOf<Pair<String, String?>>("hi" to "b0b"), delivered.toList())
        assertEquals(listOf("start b0b"), calls)
        ui.cancel()
    }

    @Test fun openAndEscInOneBatchLeavesTheDmWhenItLandsAndStaysOnPeers() = runTest {
        newSession()
        runMosaicTest {
            app()
            press(13, 27)
            assertEquals(" People (1)", settle()[1])
            // The leave is queued behind the start, so it cannot finish first.
            assertEquals(listOf("start b0b", "leave"), calls)

            viewModelSelects("b0b") // the switch is reported after the user left
            navigation.mode = session.modeForChat(navigation.mode) // and the view model says "show the chat"
        }
        assertEquals(listOf("start b0b", "leave"), calls)
        assertEquals(Mode.Peers, navigation.mode)
        assertEquals(emptyList<Pair<String, String?>>(), delivered.toList())
        ui.cancel()
    }

    @Test fun sendThenEscInOneBatchSendsToTheDmThenLeavesIt() = runTest {
        newSession()
        runMosaicTest {
            app()
            press(13)
            settle()
            viewModelSelects("b0b")
            press('o'.code, 'k'.code, 13, 27)
            assertEquals(" People (1)", settle()[1])
        }
        assertEquals(listOf<Pair<String, String?>>("ok" to "b0b"), delivered.toList())
        assertEquals(listOf("start b0b", "leave"), calls)
        ui.cancel()
    }

    @Test fun aDmOpenedWithMsgTakesTheNextLine() = runTest {
        newSession()
        navigation.mode = Mode.Chat
        runMosaicTest {
            app()
            session.onChatLine("/msg bob") // the user sent it from the chat
            viewModelSelects("b0b") // and the view model switched to that DM
            Snapshot.sendApplyNotifications() // as the app's frame loop does for state changed outside a frame
            assertEquals(" DM with bob", settle()[1])
            press('h'.code, 'i'.code, 13)
            // Typed and sent in one batch, the screen ends as it was: nothing new to draw.
            try {
                awaitSnapshot()
            } catch (_: TimeoutCancellationException) {
            }
        }
        assertEquals(listOf<Pair<String, String?>>("hi" to "b0b"), delivered.toList())
        assertEquals(emptyList<String>(), calls.toList(), "adopted, not started again")
        ui.cancel()
    }
}
