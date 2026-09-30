package com.bitchat.tui

import androidx.compose.runtime.mutableStateListOf
import com.bitchat.viewvo.location.LocationChannelsState
import com.jakewharton.mosaic.terminal.KeyboardEvent
import com.jakewharton.mosaic.terminal.Terminal
import com.jakewharton.mosaic.testing.TestMosaic
import com.jakewharton.mosaic.testing.runMosaicTest
import com.jakewharton.mosaic.ui.Text
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.test.runTest

/**
 * Keys sent back to back without a snapshot between them reach Mosaic as one batch, before any
 * recomposition. Screen changes made by one key must still decide where the next key goes.
 */
class BatchedKeysTest {
    private val navigation = TuiNavigation(Mode.Peers)
    private val sent = mutableStateListOf<String>()
    private val teleported = mutableStateListOf<String>()
    private val bob = PeerEntry("b0b", "bob", PeerTransport.Direct)

    private fun TestMosaic<String>.app() {
        state.size.value = Terminal.Size(40, 12)
        setContentAndSnapshot {
            TuiApp(nickname = "anon", peerCount = 1, navigation = navigation) { mode, size ->
                when (mode) {
                    Mode.Peers -> PeersScreen(listOf(bob), size, onOpenDm = { navigation.mode = Mode.Dm }, onToggleFavorite = {})
                    Mode.Dm -> DmScreen("bob", emptyList(), "anon", size, onSend = { sent += it })
                    Mode.Locations -> LocationsScreen(LocationChannelsState(), size, {}, {}, {}, onTeleport = { teleported += it })
                    else -> Text("screen ${mode.name}")
                }
            }
        }
    }

    private fun TestMosaic<*>.press(vararg codes: Int) = codes.forEach { sendKeyEvent(KeyboardEvent(it)) }

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

    @Test fun enterOnAPeerThenEscEndsBackOnPeers() = runTest {
        runMosaicTest {
            app()
            press(13, 27)
            assertEquals(" People (1)", settle()[1])
        }
        assertEquals(Mode.Peers, navigation.mode)
    }

    @Test fun enterOnAPeerThenTypingGoesToTheDm() = runTest {
        runMosaicTest {
            app()
            press(13, 'h'.code, 'i'.code, 13)
            assertEquals(" DM with bob", settle()[1])
        }
        assertEquals(listOf("hi"), sent)
        assertEquals(Mode.Dm, navigation.mode)
    }

    @Test fun ctrlGThenTeleportKeysInOneBatch() = runTest {
        runMosaicTest {
            app()
            sendKeyEvent(KeyboardEvent('g'.code, modifiers = KeyboardEvent.ModifierCtrl))
            press('t'.code, '9'.code, 'q'.code, 13)
            settle()
        }
        assertEquals(listOf("9q"), teleported)
    }

    @Test fun heldKeysGoOutWhenTheModeReturnsWithoutARemount() {
        val navigation = TuiNavigation(Mode.Chat)
        val router = KeyRouter(navigation)
        val seen = ArrayList<String>()
        router.register(Mode.Chat) { seen += it.key; true }
        router.mounted(Mode.Chat)
        navigation.mode = Mode.Peers // Set elsewhere; the peers screen never gets composed.
        router.dispatch(com.jakewharton.mosaic.layout.KeyEvent("x"))
        assertEquals(emptyList(), seen)
        navigation.mode = Mode.Chat // Back to the mounted screen, and no further key.
        assertEquals(listOf("x"), seen)
    }

    @Test fun aHandlerThatChangesTheModeDoesNotReplayHeldKeysOutOfOrder() {
        val navigation = TuiNavigation(Mode.Chat)
        val router = KeyRouter(navigation)
        val seen = ArrayList<String>()
        router.register(Mode.Chat) { event ->
            seen += event.key
            if (event.key == "a") {
                navigation.mode = Mode.Peers
                navigation.mode = Mode.Chat // Out and back inside one handler.
            }
            true
        }
        router.mounted(Mode.Chat)
        router.dispatch(com.jakewharton.mosaic.layout.KeyEvent("a"))
        router.dispatch(com.jakewharton.mosaic.layout.KeyEvent("b"))
        assertEquals(listOf("a", "b"), seen)
    }

    @Test fun hostKeysAreNeverHeld() {
        val navigation = TuiNavigation(Mode.Chat)
        val router = KeyRouter(navigation) // Nothing mounted yet: ordinary keys would wait.
        assertEquals(false, router.dispatch(com.jakewharton.mosaic.layout.KeyEvent("1", alt = true)))
        assertEquals(false, router.dispatch(com.jakewharton.mosaic.layout.KeyEvent("c", ctrl = true)))
        assertEquals(true, router.dispatch(com.jakewharton.mosaic.layout.KeyEvent("a")))
    }
}
