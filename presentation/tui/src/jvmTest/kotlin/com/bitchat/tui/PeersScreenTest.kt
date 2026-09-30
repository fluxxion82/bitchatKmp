package com.bitchat.tui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import com.bitchat.domain.chat.model.BitchatMessage
import com.bitchat.domain.location.model.GeoPerson
import com.jakewharton.mosaic.terminal.AnsiLevel
import com.jakewharton.mosaic.terminal.KeyboardEvent
import com.jakewharton.mosaic.testing.MosaicSnapshots
import com.jakewharton.mosaic.testing.runMosaicTest
import com.jakewharton.mosaic.ui.unit.IntSize
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest

/**
 * Expected rows are written by hand. Every row carries a theme colour, so Mosaic keeps the blank
 * that a peer row ends with rather than trimming it.
 */
class PeersScreenTest {
    private val esc = "\u001B"
    private val bar = "$esc[30;102m" // A selected row: the dark theme's black on bright green.
    private val reset = "$esc[0m"
    private val bob = PeerEntry("b0b", "bob", PeerTransport.Direct, favorite = true, unread = true)
    private val alice = PeerEntry("a11ce", "alice", PeerTransport.Routed)
    private val carol = PeerEntry("ca401", "carol", PeerTransport.Nostr)
    private val peers = listOf(bob, alice, carol)

    private suspend fun render(level: AnsiLevel, consoleSafe: Boolean = false, content: @Composable () -> Unit): List<String> =
        runMosaicTest(MosaicSnapshots) {
            setContentAndSnapshot { CompositionLocalProvider(LocalConsoleSafe provides consoleSafe) { content() } }
                .draw().render(level, false).split("\n")
        }

    private fun pad(left: String, right: String, width: Int) = left + " ".repeat(width - left.length - right.length) + right

    @Test fun peersAt40x9() = runTest {
        val rows = render(AnsiLevel.NONE) { PeersScreen(peers, IntSize(40, 9), onOpenDm = {}, onToggleFavorite = {}) }
        assertEquals(
            listOf(
                " People (3)",
                pad(" !* bob", "direct ", 40), // Selected.
                pad("    alice", "routed ", 40),
                pad("    carol", "nostr ", 40),
            ) + List(5) { "" },
            rows,
        )
    }

    @Test fun peersAt85x22() = runTest {
        val rows = render(AnsiLevel.NONE) { PeersScreen(peers, IntSize(85, 22), onOpenDm = {}, onToggleFavorite = {}) }
        assertEquals(" People (3)", rows[0])
        assertEquals(pad(" !* bob", "direct ", 85), rows[1])
        assertEquals(pad("    alice", "routed ", 85), rows[2])
        assertEquals(22, rows.size)
    }

    @Test fun aPeerRowTakesThatPeersOwnColour() = runTest {
        // The same colour the chat gives them, so a name is recognisable in both.
        val rows = render(AnsiLevel.ANSI16) {
            PeersScreen(listOf(alice, carol), IntSize(30, 4), onOpenDm = {}, onToggleFavorite = {})
        }
        // Neither peer ID is a Noise or Nostr key, so the name is what colours them; carol's hue
        // is 198 degrees, which the terminal draws in cyan. The selected row keeps the bar's own.
        assertEquals(bar + pad("    alice", "routed ", 30) + reset, rows[1])
        assertEquals("$esc[36m" + pad("    carol", "nostr ", 30) + reset, rows[2])
    }

    @Test fun selectedRowIsReversedAndUnreadRowsAreBold() = runTest {
        val rows = render(AnsiLevel.ANSI16) {
            PeersScreen(listOf(alice, bob), IntSize(30, 4), onOpenDm = {}, onToggleFavorite = {})
        }
        assertEquals(bar + pad("    alice", "routed ", 30) + reset, rows[1])
        assertEquals("$esc[30;102;1m" + pad(" !* bob", "direct ", 30) + reset, rows[2])
    }

    @Test fun arrowsMoveTheSelectionAndEnterOpensADm() = runTest {
        val opened = ArrayList<PeerEntry>()
        runMosaicTest {
            setContentAndSnapshot { PeersScreen(peers, IntSize(40, 9), onOpenDm = { opened += it }, onToggleFavorite = {}) }
            sendKeyEvent(KeyboardEvent(KeyboardEvent.Down))
            sendKeyEvent(KeyboardEvent(KeyboardEvent.Down))
            sendKeyEvent(KeyboardEvent(KeyboardEvent.Up))
            sendKeyEvent(KeyboardEvent(13))
            awaitSnapshot()
        }
        assertEquals(listOf(alice), opened)
    }

    @Test fun selectionFollowsThePeerWhenTheListReorders() = runTest {
        val list = androidx.compose.runtime.mutableStateListOf(bob, alice, carol)
        val opened = androidx.compose.runtime.mutableStateListOf<PeerEntry>()
        runMosaicTest {
            setContentAndSnapshot {
                com.jakewharton.mosaic.ui.Column {
                    com.jakewharton.mosaic.ui.Text("opened ${opened.size}") // Makes the Enter visible, so a frame follows it.
                    PeersScreen(list, IntSize(40, 9), onOpenDm = { opened += it }, onToggleFavorite = {})
                }
            }
            sendKeyEvent(KeyboardEvent(KeyboardEvent.Down))
            awaitSnapshot()
            list.add(0, PeerEntry("n3w", "newcomer", PeerTransport.Direct))
            awaitSnapshot()
            sendKeyEvent(KeyboardEvent(13))
            awaitSnapshot()
        }
        assertEquals(listOf(alice), opened)
    }

    /** Presses [keys], then applies [change] to the list, then presses Enter: what opens? */
    private suspend fun openAfter(list: MutableList<PeerEntry>, keys: List<Int>, change: () -> Unit): List<PeerEntry> {
        val opened = androidx.compose.runtime.mutableStateListOf<PeerEntry>()
        runMosaicTest {
            setContentAndSnapshot {
                com.jakewharton.mosaic.ui.Column {
                    com.jakewharton.mosaic.ui.Text("opened ${opened.size}") // Makes the Enter visible.
                    PeersScreen(list, IntSize(40, 9), onOpenDm = { opened += it }, onToggleFavorite = {})
                }
            }
            keys.forEach { sendKeyEvent(KeyboardEvent(it)) }
            if (keys.isNotEmpty()) awaitSnapshot()
            change()
            awaitSnapshot()
            sendKeyEvent(KeyboardEvent(13))
            awaitSnapshot()
        }
        return opened.toList()
    }

    @Test fun reorderBeforeTheFirstKeyKeepsTheShownPeer() = runTest {
        val list = androidx.compose.runtime.mutableStateListOf(bob, alice)
        assertEquals(listOf(bob), openAfter(list, emptyList()) { list.reverse() })
    }

    @Test fun whenTheSelectedPeerLeavesTheOneInItsPlaceIsSelected() = runTest {
        val list = androidx.compose.runtime.mutableStateListOf(bob, alice, carol)
        assertEquals(listOf(carol), openAfter(list, listOf(KeyboardEvent.Down)) { list.remove(alice) })
    }

    @Test fun whenTheLastPeerLeavesTheNewLastIsSelected() = runTest {
        val list = androidx.compose.runtime.mutableStateListOf(bob, alice, carol)
        assertEquals(listOf(alice), openAfter(list, listOf(KeyboardEvent.Down, KeyboardEvent.Down)) { list.remove(carol) })
    }

    @Test fun fTogglesTheSelectedFavourite() = runTest {
        val toggled = ArrayList<PeerEntry>()
        runMosaicTest {
            setContentAndSnapshot { PeersScreen(peers, IntSize(40, 9), onOpenDm = {}, onToggleFavorite = { toggled += it }) }
            sendKeyEvent(KeyboardEvent(KeyboardEvent.Down))
            sendKeyEvent(KeyboardEvent('f'.code))
            awaitSnapshot()
        }
        assertEquals(listOf(alice), toggled)
    }

    @Test fun longListsScrollToKeepTheSelectionVisible() = runTest {
        val many = (0..19).map { PeerEntry("p$it", "peer$it", PeerTransport.Routed) }
        runMosaicTest {
            setContentAndSnapshot { PeersScreen(many, IntSize(30, 6), onOpenDm = {}, onToggleFavorite = {}) }
            repeat(7) { sendKeyEvent(KeyboardEvent(KeyboardEvent.Down)) }
            val rows = awaitSnapshot().lines()
            // Five list rows; the selection (peer7) sits on the last one.
            assertEquals(listOf(" People (20)") + (3..7).map { pad("    peer$it", "routed ", 30) }, rows)
        }
    }

    @Test fun emptyListSaysNoOneIsConnected() = runTest {
        val rows = render(AnsiLevel.NONE) { PeersScreen(emptyList(), IntSize(30, 3), onOpenDm = {}, onToggleFavorite = {}) }
        assertEquals(listOf(" People (0)", " no one connected", ""), rows)
    }

    @Test fun narrowRowsEllipsizeTheNameThenDropTheTag() {
        assertEquals(pad(" !* averylongn...", "direct ", 25), peerRow(bob.copy(name = "averylongnickname"), 25, consoleSafe = false))
        assertEquals(" !* bob", peerRow(bob, 10, consoleSafe = false))
    }

    @Test fun consoleSafeRowsMapWideNames() {
        assertEquals(pad("    ??", "routed ", 20), peerRow(alice.copy(name = "\u4E2D\u6587"), 20, consoleSafe = true))
    }

    @Test fun meshPeersSortUnreadThenFavouritesThenName() {
        val entries = meshPeerEntries(
            connectedPeers = listOf("id-carol", "id-bob", "id-alice", "id-dave00000000000"),
            peerNicknames = mapOf("id-carol" to "carol", "id-bob" to "Bob", "id-alice" to "alice"),
            peerDirect = mapOf("id-bob" to true),
            favoritePeers = setOf("id-carol"),
            unreadPeers = setOf("id-alice"),
        )
        assertEquals(
            listOf(
                PeerEntry("id-alice", "alice", PeerTransport.Routed, favorite = false, unread = true),
                PeerEntry("id-carol", "carol", PeerTransport.Routed, favorite = true, unread = false),
                PeerEntry("id-bob", "Bob", PeerTransport.Direct),
                PeerEntry("id-dave00000000000", "id-dave00000", PeerTransport.Routed), // No nickname: the ID's first 12.
            ),
            entries,
        )
    }

    @Test fun geohashAndLoRaPeopleBecomeEntries() {
        val person = GeoPerson("npub1", "dora", Instant.fromEpochSeconds(0))
        assertEquals(
            listOf(PeerEntry("npub1", "dora", PeerTransport.Nostr, favorite = true, unread = true)),
            // Unread DMs are keyed by conversation: a geohash person's is "nostr_" and the key's first 16 characters.
            geoPeerEntries(listOf(person), PeerTransport.Nostr, favoritePeers = setOf("npub1"), unreadPeers = setOf("nostr_npub1")),
        )
        val node = GeoPerson("!a1b2c3d4", "node", Instant.fromEpochSeconds(0))
        assertEquals(
            listOf(PeerEntry("!a1b2c3d4", "node", PeerTransport.LoRa, unread = true)),
            geoPeerEntries(listOf(node), PeerTransport.LoRa, unreadPeers = setOf("!a1b2c3d4")),
        )
    }

    @Test fun dmScreenTitlesTheConversation() = runTest {
        val message = BitchatMessage(id = "1", sender = "bob", content = "hey", timestamp = Instant.fromEpochSeconds(12 * 3600L), isPrivate = true)
        val rows = render(AnsiLevel.NONE) { DmScreen("bob", listOf(message), "anon", IntSize(40, 4), onSend = {}) }
        assertEquals(listOf(" DM with bob", "", "12:00 <bob> hey", ">  "), rows)
    }
}
