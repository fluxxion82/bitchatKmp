package com.bitchat.tui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import com.bitchat.domain.chat.model.MeshChannelPerson
import com.bitchat.domain.chat.model.MeshChannelTransport
import com.bitchat.domain.chat.model.BitchatMessage
import com.bitchat.domain.location.model.GeoPerson
import com.jakewharton.mosaic.terminal.AnsiLevel
import com.jakewharton.mosaic.terminal.KeyboardEvent
import com.jakewharton.mosaic.terminal.Terminal
import com.jakewharton.mosaic.testing.MosaicSnapshots
import com.jakewharton.mosaic.testing.runMosaicTest
import com.jakewharton.mosaic.ui.unit.IntSize
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
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

    @Test fun aLoRaSelectionExplainsThatItCannotOpenADm() = runTest {
        val entries = listOf(
            PeerEntry("lora", "radio", PeerTransport.LoRa),
            PeerEntry("direct", "bob", PeerTransport.Direct),
        )
        val navigation = TuiNavigation(Mode.Peers)

        runMosaicTest {
            state.size.value = Terminal.Size(85, 12)
            setContentAndSnapshot {
                TuiApp(nickname = "anon", peerCount = entries.size, navigation = navigation) { mode, size ->
                    if (mode == Mode.Peers) PeersScreen(entries, size, onOpenDm = {}, onToggleFavorite = {})
                }
            }
            val loraFooter = awaitSnapshot().lines().last()
            assertTrue(loraFooter.contains("no DM over LoRa"), loraFooter)
            assertFalse(loraFooter.contains("Enter DM"), loraFooter)

            sendKeyEvent(KeyboardEvent(KeyboardEvent.Down))
            // The footer follows the selection a frame later: a screen hands its hints over in an effect.
            var directFooter = awaitSnapshot().lines().last()
            if (!directFooter.contains("Enter DM")) directFooter = awaitSnapshot().lines().last()
            assertTrue(directFooter.contains("Enter DM"), directFooter)
        }
    }

    @Test fun peersFooterHintsReplaceTheModeHintsOnlyForLoRa() {
        val lora = footerText(peersFooterHints(PeerEntry("lora", "radio", PeerTransport.LoRa)) ?: emptyList(), 120).text
        assertTrue(lora.contains("Enter no DM over LoRa"), lora)
        assertEquals(null, peersFooterHints(PeerEntry("lora", "radio", PeerTransport.LoRa, dmPeerId = "a1b2")))
        assertEquals(null, peersFooterHints(PeerEntry("direct", "bob", PeerTransport.Direct)))
        assertEquals(null, peersFooterHints(null))
    }

    @Test fun narrowRowsEllipsizeTheNameThenDropTheTag() {
        assertEquals(pad(" !* averylongn...", "direct ", 25), peerRow(bob.copy(name = "averylongnickname"), 25, consoleSafe = false))
        assertEquals(" !* bob", peerRow(bob, 10, consoleSafe = false))
    }

    @Test fun consoleSafeRowsMapWideNames() {
        assertEquals(pad("    ??", "routed ", 20), peerRow(alice.copy(name = "\u4E2D\u6587"), 20, consoleSafe = true))
    }

    @Test fun claimedNamesAreShownSafelyWithoutHidingTheTransport() {
        val claimed = PeerEntry("alice", "alice#1a2b", PeerTransport.Direct, claims = "bob")
        assertEquals("alice#1a2b (now: bob)", peerRow(claimed, 40, consoleSafe = false).trimStart().substringBeforeLast("direct").trimEnd())
        assertTrue(peerRow(claimed, 28, consoleSafe = false).endsWith("direct "))
        assertTrue(peerRow(claimed.copy(claims = "bo\u0007b"), 40, consoleSafe = false).contains("now: bo?b"))
    }

    @Test fun aClaimNeverShortensTheNameTheChatIsFixedTo() {
        val fixed = PeerEntry("alice", "alice#1a2b", PeerTransport.Direct)
        val claimed = fixed.copy(claims = "b".repeat(50))
        // From the width the name alone just fits at, up to one with room to spare: the row with the claim
        // always shows the whole name and the tag, and is never wider than asked.
        for (width in 22..60) {
            val row = peerRow(claimed, width, consoleSafe = false)
            assertTrue(row.startsWith("    alice#1a2b"), "width $width: $row")
            assertTrue(row.endsWith("direct "), "width $width: $row")
            assertEquals(width, row.length, "width $width: $row")
        }
        // Too little room to say anything: the row is the one without a claim.
        assertEquals(peerRow(fixed, 22, consoleSafe = false), peerRow(claimed, 22, consoleSafe = false))
        assertEquals(peerRow(fixed, 33, consoleSafe = false), peerRow(claimed, 33, consoleSafe = false))
        // Room for a little: the start of the claim and an ellipsis.
        assertEquals("    alice#1a2b (now: bb... direct ", peerRow(claimed, 34, consoleSafe = false))
        // A name that does not fit itself is cut as it always was, and no claim is added.
        assertEquals(peerRow(fixed, 18, consoleSafe = false), peerRow(claimed, 18, consoleSafe = false))
    }

    @Test fun meshPeopleSortUnreadThenFavouritesThenNameBeforeLoRaOnlyPeople() {
        val entries = meshChannelPeerEntries(
            people = listOf(
                MeshChannelPerson("id-carol", "carol", setOf(MeshChannelTransport.MESH), false, null),
                MeshChannelPerson("id-bob", "Bob", setOf(MeshChannelTransport.MESH), false, null),
                MeshChannelPerson("id-alice", "alice", setOf(MeshChannelTransport.MESH), false, null),
                MeshChannelPerson("id-dave00000000000", "id-dave00000", setOf(MeshChannelTransport.MESH), false, null),
                MeshChannelPerson("lora", "radio", setOf(MeshChannelTransport.LORA), false, Instant.fromEpochSeconds(0)),
            ),
            peerNicknames = emptyMap(),
            favoritePeers = setOf("id-carol"),
            unreadPeers = setOf("id-alice"),
        )
        assertEquals(
            listOf(
                PeerEntry("id-alice", "alice", PeerTransport.Direct, favorite = false, unread = true),
                PeerEntry("id-carol", "carol", PeerTransport.Direct, favorite = true, unread = false),
                PeerEntry("id-bob", "Bob", PeerTransport.Direct),
                PeerEntry("id-dave00000000000", "id-dave00000", PeerTransport.Direct),
                PeerEntry("lora", "radio", PeerTransport.LoRa),
            ),
            entries,
        )
    }

    @Test fun meshPeopleKeepTheirCurrentClaimAlongsideTheFixedName() {
        val person = MeshChannelPerson(
            "id-alice", "alice#1a2b", setOf(MeshChannelTransport.MESH), true, null,
            nameIsFixed = true, claimedName = "bob",
        )

        assertEquals(
            listOf(PeerEntry("id-alice", "alice#1a2b", PeerTransport.Direct, claims = "bob")),
            meshChannelPeerEntries(listOf(person), emptyMap(), emptySet(), emptySet()),
        )
    }

    @Test fun meshPeopleRenderTheNewTransportTags() {
        assertEquals(pad("    both", "direct+lora ", 30), peerRow(PeerEntry("both", "both", PeerTransport.DirectLoRa), 30, false))
        assertEquals(pad("    mesh", "direct ", 30), peerRow(PeerEntry("mesh", "mesh", PeerTransport.Direct), 30, false))
        assertEquals(pad("    lora", "lora ", 30), peerRow(PeerEntry("lora", "lora", PeerTransport.LoRa), 30, false))
        assertEquals(pad("    private", "offline ", 30), peerRow(PeerEntry("private", "private", PeerTransport.Offline), 30, false))
        assertEquals(pad("    nostr", "nostr ", 30), peerRow(PeerEntry("nostr", "nostr", PeerTransport.Nostr), 30, false))
    }

    @Test fun aRadioOnlyRowCarriesTheMeshIdItsHeartbeatAnnounces() {
        val person = MeshChannelPerson(
            "lora-radio", "radio", setOf(MeshChannelTransport.LORA), false, Instant.fromEpochSeconds(0), dmPeerId = "a1b2",
        )

        assertEquals(
            PeerEntry("lora-radio", "radio", PeerTransport.LoRa, dmPeerId = "a1b2"),
            meshChannelPeerEntries(listOf(person), emptyMap(), emptySet(), emptySet()).single(),
        )
    }

    @Test fun aPrivateChatAndTheRadioPersonThatAnnouncesItsIdStayTwoRows() {
        val chat = MeshChannelPerson("a1b2", "alice#a1b2", emptySet(), true, null, nameIsFixed = true)
        val radio = MeshChannelPerson(
            "lora-radio", "mallory", setOf(MeshChannelTransport.LORA), false, Instant.fromEpochSeconds(0), dmPeerId = "a1b2",
        )

        assertEquals(
            listOf(
                PeerEntry("a1b2", "alice#a1b2", PeerTransport.Offline),
                PeerEntry("lora-radio", "mallory", PeerTransport.LoRa, dmPeerId = "a1b2"),
            ),
            meshChannelPeerEntries(listOf(chat, radio), emptyMap(), emptySet(), emptySet()),
        )
    }

    @Test fun aRadioRowIsOpenedAsTheMeshPeerItAnnouncesAndNeverUnderTheHeartbeatsName() {
        val id = "a1b2c3d4e5f60718"
        val row = PeerEntry("lora-radio", "mallory", PeerTransport.LoRa, dmPeerId = id)

        // No chat with that id yet: known by the start of the id.
        assertEquals(PeerEntry(id, "a1b2c3d4e5f6", PeerTransport.LoRa, dmPeerId = id), dmTarget(row, emptyMap()))
        // There is one: known by the name it already has.
        assertEquals(
            PeerEntry(id, "alice#a1b2", PeerTransport.LoRa, dmPeerId = id),
            dmTarget(row, mapOf(id to "alice#a1b2", "lora-radio" to "not this")),
        )
        assertEquals(id, dmConversationKey(dmTarget(row, emptyMap())))
    }

    @Test fun everyOtherRowIsTheChatItOpens() {
        val direct = PeerEntry("b0b", "bob", PeerTransport.Direct, claims = "robert", favorite = true)
        val foreign = PeerEntry("lora-!a1b2", "node", PeerTransport.LoRa)
        // A row that is not a radio row keeps its own id whatever else it carries.
        val odd = PeerEntry("c4r0l", "carol", PeerTransport.Offline, dmPeerId = "ffff")

        assertEquals(direct, dmTarget(direct, mapOf("b0b" to "other")))
        assertEquals(foreign, dmTarget(foreign, emptyMap()))
        assertEquals(odd, dmTarget(odd, emptyMap()))
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
