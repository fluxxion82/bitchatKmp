package com.bitchat.tui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import com.bitchat.domain.chat.model.BitchatMessage
import com.bitchat.domain.tor.model.TorAvailability
import com.bitchat.viewvo.location.LocationChannelsState
import com.bitchat.viewvo.settings.SettingsState
import com.jakewharton.mosaic.terminal.Terminal
import com.jakewharton.mosaic.testing.runMosaicTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest

/**
 * Every screen inside [TuiApp] on the Linux console (console-safe on), at 40x12 and at the Pi's
 * 85x25. Names carry CJK and emoji, which the console draws as `?`. Rows are written by hand.
 */
class AppScreensTest {
    private val nickname = "anon\u4E2D"
    private val bobName = "bob\u4E2D"
    private val message = BitchatMessage(id = "1", sender = bobName, content = "hi \uD83D\uDE00", timestamp = Instant.fromEpochSeconds(12 * 3600L))
    private val peers = listOf(PeerEntry("p1", bobName, PeerTransport.Direct))
    private val settings = SettingsState(loraAvailable = true, torAvailability = TorAvailability.AVAILABLE)
    private val places = LocationChannelsState(meshParticipantCount = 1, locationUnavailableReason = "no location source")
    private val notes = listOf(com.bitchat.domain.location.model.Note("n1", "abcd1234", "hello here", 43_200, "alice"))

    @Composable
    private fun App(mode: Mode) {
        CompositionLocalProvider(LocalConsoleSafe provides true) {
            TuiApp(nickname = nickname, peerCount = 1, navigation = remember { TuiNavigation(mode) }, unreadDms = 1) { current, size ->
                when (current) {
                    Mode.Chat -> ChatScreen(listOf(message), nickname, size, onSend = {}, title = "#mesh")
                    Mode.Peers -> PeersScreen(peers, size, onOpenDm = {}, onToggleFavorite = {})
                    Mode.Dm -> DmScreen(bobName, listOf(message), nickname, size, onSend = {})
                    Mode.Locations -> LocationsScreen(places, size, {}, {}, {}, {})
                    Mode.Notes -> ChatScreen(noteMessages(notes), nickname, size, onSend = {}, title = "Notes for #9q8yy")
                    Mode.Settings -> SettingsScreen(settings, size, {}, {}, {}, {}, {}, {}, {}, {}, {})
                    Mode.Wipe -> WipeScreen(size, onConfirm = {}, onCancel = {})
                }
            }
        }
    }

    private suspend fun frame(mode: Mode, columns: Int, rows: Int): List<String> {
        var frame = emptyList<String>()
        runMosaicTest {
            state.size.value = Terminal.Size(columns, rows)
            frame = setContentAndSnapshot { App(mode) }.lines()
        }
        return frame
    }

    private fun header(width: Int) = " anon?" + " ".repeat(width - 19) + "1 DM, 1 peer "

    @Test fun chatAt40x12() = runTest {
        val rows = frame(Mode.Chat, 40, 12)
        assertEquals(11, rows.size)
        assertEquals(header(40), rows[0])
        assertEquals(" #mesh", rows[1])
        assertEquals("12:00 <bob?> hi ?", rows[8])
        assertEquals(">  ", rows[9])
        assertEquals(" Enter send  ^P peers  ^G places", rows[10])
    }

    @Test fun chatAt85x25() = runTest {
        val rows = frame(Mode.Chat, 85, 25)
        assertEquals(24, rows.size)
        assertEquals(header(85), rows[0])
        assertEquals("12:00 <bob?> hi ?", rows[21])
        assertEquals(" Enter send  ^P peers  ^G places  ^S settings  PgUp/PgDn scroll  Tab next", rows[23])
    }

    @Test fun peersAt40x12() = runTest {
        val rows = frame(Mode.Peers, 40, 12)
        assertEquals(" People (1)", rows[1])
        assertEquals("    bob?" + " ".repeat(25) + "direct ", rows[2])
        assertEquals(" Enter DM  Up/Down select  f favourite", rows[10])
    }

    @Test fun peersAt85x25() = runTest {
        val rows = frame(Mode.Peers, 85, 25)
        assertEquals("    bob?" + " ".repeat(70) + "direct ", rows[2])
        assertEquals(" Enter DM  Up/Down select  f favourite  Esc back  Tab next", rows[23])
    }

    @Test fun dmAt40x12() = runTest {
        val rows = frame(Mode.Dm, 40, 12)
        assertEquals(" DM with bob?", rows[1])
        assertEquals("12:00 <bob?> hi ?", rows[8])
        assertEquals(" Enter send  Esc peers  PgUp/PgDn scroll", rows[10])
    }

    @Test fun dmAt85x25() = runTest {
        val rows = frame(Mode.Dm, 85, 25)
        assertEquals(" DM with bob?", rows[1])
        assertEquals(" Enter send  Esc peers  PgUp/PgDn scroll  Tab next", rows[23])
    }

    @Test fun locationsAt40x12() = runTest {
        val rows = frame(Mode.Locations, 40, 12)
        assertEquals(" Locations", rows[1])
        assertEquals(" > #mesh" + " ".repeat(23) + "1 person ", rows[2])
        assertEquals("   no location source", rows[3])
        assertEquals(" Enter join  Up/Down select  b bookmark", rows[10])
    }

    @Test fun locationsAt85x25() = runTest {
        val rows = frame(Mode.Locations, 85, 25)
        assertEquals(" > #mesh" + " ".repeat(68) + "1 person ", rows[2])
        assertEquals(" Enter join  Up/Down select  b bookmark  g grid  t teleport  n notes  Esc back", rows[23])
    }

    @Test fun settingsAt40x12() = runTest {
        val rows = frame(Mode.Settings, 40, 12)
        assertEquals(" Settings", rows[1])
        assertEquals(" LoRa radio" + " ".repeat(7) + "< on >" + " ".repeat(16), rows[2])
        assertEquals(" Left/Right change  Up/Down select", rows[10])
    }

    @Test fun settingsAt85x25() = runTest {
        val rows = frame(Mode.Settings, 85, 25)
        assertEquals(" PoW difficulty" + " ".repeat(5) + "16 bits", rows[9])
        assertEquals(" Left/Right change  Up/Down select  Esc back  Tab next", rows[23])
    }
}
