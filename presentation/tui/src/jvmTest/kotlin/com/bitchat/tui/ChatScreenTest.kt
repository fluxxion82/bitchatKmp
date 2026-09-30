package com.bitchat.tui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateListOf
import com.bitchat.domain.chat.model.BitchatFilePacket
import com.bitchat.domain.chat.model.BitchatMessage
import com.bitchat.domain.chat.model.BitchatMessageType
import com.bitchat.domain.location.model.Channel
import com.bitchat.domain.location.model.GeohashChannelLevel
import com.jakewharton.mosaic.terminal.AnsiLevel
import com.jakewharton.mosaic.terminal.KeyboardEvent
import com.jakewharton.mosaic.testing.MosaicSnapshots
import com.jakewharton.mosaic.testing.TestMosaic
import com.jakewharton.mosaic.testing.runMosaicTest
import com.jakewharton.mosaic.ui.unit.IntSize
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest

/**
 * Expected rows are written by hand. Colours are the 16-colour SGR codes Mosaic emits for the dark
 * theme: ordinary text is its phosphor green (92), notes and timestamps its dimmer green with the
 * dim style (32;2), and a sender takes the hue `peerColorHsv` gives them, reduced to a terminal
 * colour (computed independently: alice 299 degrees is magenta 35, bob 80 degrees is yellow 33).
 * Own messages are orange, which reduces to bright yellow, and bold (93;1).
 */
class ChatScreenTest {
    private val esc = "\u001B"
    private val fg = "$esc[92m" // Ordinary text.
    private val note = "$esc[32;2m" // A timestamp or a system line.
    private val back = "$esc[92;22m" // Back to ordinary text after a dim or bold span.
    private val reset = "$esc[0m"

    private fun msg(
        sender: String,
        content: String,
        minute: Int,
        type: BitchatMessageType = BitchatMessageType.Message,
        peer: String? = null,
        file: BitchatFilePacket? = null,
        id: String = "$sender-$minute",
    ) = BitchatMessage(
        id = id,
        sender = sender,
        content = content,
        type = type,
        timestamp = Instant.fromEpochSeconds(12 * 3600L + minute * 60L),
        senderPeerID = peer,
        filePacket = file,
    )

    private val conversation = listOf(msg("alice", "hi", 0), msg("bob", "yo", 1), msg("anon", "me", 2))

    private suspend fun render(level: AnsiLevel, consoleSafe: Boolean = false, content: @Composable () -> Unit): List<String> =
        runMosaicTest(MosaicSnapshots) {
            setContentAndSnapshot { CompositionLocalProvider(LocalConsoleSafe provides consoleSafe) { content() } }
                .draw().render(level, false).split("\n")
        }

    private fun TestMosaic<*>.press(codepoint: Int, modifiers: Int = 0) =
        sendKeyEvent(KeyboardEvent(codepoint, modifiers = modifiers))

    @Test fun conversationIsBottomAnchoredAboveThePromptAt40x9() = runTest {
        val rows = render(AnsiLevel.NONE) {
            ChatScreen(conversation, nickname = "anon", size = IntSize(40, 9), onSend = {}, title = "#mesh")
        }
        assertEquals(
            listOf(" #mesh", "", "", "", "", "12:00 <alice> hi", "12:01 <bob> yo", "12:02 <anon> me", ">  "),
            rows,
        )
    }

    @Test fun conversationAt85x22() = runTest {
        val rows = render(AnsiLevel.NONE) {
            ChatScreen(conversation, nickname = "anon", size = IntSize(85, 22), onSend = {}, title = "#mesh")
        }
        assertEquals(listOf(" #mesh") + List(17) { "" } + listOf("12:00 <alice> hi", "12:01 <bob> yo", "12:02 <anon> me", ">  "), rows)
    }

    @Test fun sendersAreColouredAndOwnMessagesAreBoldYellow() = runTest {
        val rows = render(AnsiLevel.ANSI16) {
            ChatScreen(conversation, nickname = "anon", size = IntSize(40, 4), onSend = {})
        }
        assertEquals("${note}12:00$back $esc[35m<alice>$fg hi$reset", rows[0])
        assertEquals("${note}12:01$back $esc[33m<bob>$fg yo$reset", rows[1])
        assertEquals("${note}12:02$back $esc[93;1m<anon>$back me$reset", rows[2])
    }

    @Test fun titleIsBoldInTheAccentColour() = runTest {
        val rows = render(AnsiLevel.ANSI16) { ChatScreen(emptyList(), nickname = "anon", size = IntSize(20, 3), onSend = {}, title = "#mesh") }
        assertEquals("$esc[92;1m #mesh$reset", rows[0])
    }

    @Test fun ownMessagesAreFoundByPeerIdOrNicknameSuffix() {
        assertEquals(true, isOwnMessage(msg("someone", "x", 0, peer = "p1"), nickname = "anon", myPeerId = "p1"))
        assertEquals(true, isOwnMessage(msg("anon#1a2b", "x", 0), nickname = "anon", myPeerId = null))
        assertEquals(false, isOwnMessage(msg("anonymous", "x", 0), nickname = "anon", myPeerId = null))
    }

    @Test fun longMessagesWrapByCells() = runTest {
        val rows = render(AnsiLevel.NONE) {
            ChatScreen(listOf(msg("carol", "the quick brown fox jumps over the lazy dog again", 5)), "anon", IntSize(40, 9), onSend = {})
        }
        // "12:05 <carol> the quick brown fox jumps" is 39 cells; " over" would make 44.
        assertEquals(List(6) { "" } + listOf("12:05 <carol> the quick brown fox jumps", "over the lazy dog again", ">  "), rows)
    }

    @Test fun multiLineBodiesIndentTheirFollowingLines() {
        val lines = messageLines(msg("alice", "one\ntwo", 0), "anon", null, 40, consoleSafe = false, mediaSize = null, formatTime = ::utcClockTime)
        assertEquals(listOf("12:00 <alice> one", "  two"), lines.map { it.text })
    }

    @Test fun proofOfWorkIsMarkedOnTheMessagesThatCarryIt() {
        fun lines(message: BitchatMessage, width: Int = 60) =
            messageLines(message, "anon", null, width, consoleSafe = false, mediaSize = null, formatTime = ::utcClockTime)
                .map { it.text }
        // Only a geohash message ever carries one: the work is mined into the Nostr event, so
        // nothing that went over the mesh has a difficulty to show.
        assertEquals(listOf("12:00 <alice> hi pow16"), lines(msg("alice", "hi", 0).copy(powDifficulty = 16)))
        assertEquals(listOf("12:00 <alice> hi"), lines(msg("alice", "hi", 0)))
        assertEquals(listOf("12:00 <alice> hi"), lines(msg("alice", "hi", 0).copy(powDifficulty = 0)))
        // Once per message, on its last line, as the Compose apps put their shield after the time.
        assertEquals(
            listOf("12:00 <alice> one", "  two pow8"),
            lines(msg("alice", "one\ntwo", 0).copy(powDifficulty = 8)),
        )
    }

    @Test fun theProofOfWorkMarkIsAsciiSoTheConsoleFontCanDrawIt() {
        // The console has no shield glyph, and a missing one takes the wrong number of cells.
        assertEquals(" pow16", powMark(16))
        assertEquals(null, powMark(0))
        assertEquals(null, powMark(null))
    }

    @Test fun systemMessagesAreDimmed() = runTest {
        val rows = render(AnsiLevel.ANSI16) {
            ChatScreen(listOf(msg("system", "alice joined", 3)), "anon", IntSize(40, 2), onSend = {})
        }
        assertEquals("${note}12:03 * alice joined$reset", rows[0])
    }

    @Test fun mediaIsShownAsPlaceholders() {
        fun line(message: BitchatMessage, size: Long?) =
            messageLines(message, "anon", null, 80, consoleSafe = false, mediaSize = size, formatTime = ::utcClockTime).single().text
        assertEquals("12:04 <alice> [image foo.jpg 34KB]", line(msg("alice", "/data/media/foo.jpg", 4, BitchatMessageType.Image), 34816))
        assertEquals("12:04 <alice> [image foo.jpg]", line(msg("alice", "/data/media/foo.jpg", 4, BitchatMessageType.Image), null))
        assertEquals("12:05 <bob> [voice 18KB]", line(msg("bob", "/data/voice/v1.m4a", 5, BitchatMessageType.Audio), 18432))
        val pdf = BitchatFilePacket("report.pdf", 122880, "application/pdf", ByteArray(0))
        assertEquals("12:06 <carol> [file report.pdf 120KB]", line(msg("carol", "", 6, BitchatMessageType.File, file = pdf), null))
    }

    @Test fun mediaSizesComeFromStateByMessageId() = runTest {
        val image = msg("alice", "/data/media/foo.jpg", 4, BitchatMessageType.Image, id = "img")
        val rows = render(AnsiLevel.NONE) {
            ChatScreen(listOf(image), "anon", IntSize(40, 2), onSend = {}, mediaSizes = mapOf("img" to 34816L))
        }
        assertEquals("12:04 <alice> [image foo.jpg 34KB]", rows[0])
    }

    @Test fun sizesAreShortened() {
        assertEquals("500B", formatSize(500))
        assertEquals("1KB", formatSize(1024))
        assertEquals("34KB", formatSize(34816))
        assertEquals("1.5MB", formatSize(1572864))
    }

    @Test fun pageUpShowsOlderMessagesAndEndReturnsToLive() = runTest {
        val many = (0..19).map { msg("alice", "m$it", it) }
        runMosaicTest {
            setContentAndSnapshot { ChatScreen(many, "anon", IntSize(40, 9), onSend = {}) }
            press(KeyboardEvent.PageUp)
            // Eight list rows; a page is seven, so the newest seven go below and a marker takes the last row.
            assertEquals((5..11).map { "12:%02d <alice> m$it".format(it) } + listOf("-- more below (End) --", ">  "), awaitSnapshot().lines())
            press(KeyboardEvent.PageUp)
            assertEquals((0..6).map { "12:%02d <alice> m$it".format(it) } + listOf("-- more below (End) --", ">  "), awaitSnapshot().lines())
            press(KeyboardEvent.End)
            assertEquals((12..19).map { "12:%02d <alice> m$it".format(it) } + listOf(">  "), awaitSnapshot().lines())
        }
    }

    @Test fun keysArrivingInOneFrameEachCount() = runTest {
        val many = (0..19).map { msg("alice", "m$it", it) }
        runMosaicTest {
            setContentAndSnapshot { ChatScreen(many, "anon", IntSize(40, 9), onSend = {}) }
            press(KeyboardEvent.PageUp)
            press(KeyboardEvent.PageUp)
            assertEquals((0..6).map { "12:%02d <alice> m$it".format(it) } + listOf("-- more below (End) --", ">  "), awaitSnapshot().lines())
        }
    }

    @Test fun pageDownReturnsTowardsLive() = runTest {
        val many = (0..19).map { msg("alice", "m$it", it) }
        runMosaicTest {
            setContentAndSnapshot { ChatScreen(many, "anon", IntSize(40, 9), onSend = {}) }
            press(KeyboardEvent.PageUp)
            awaitSnapshot()
            press(KeyboardEvent.PageDown)
            assertEquals((12..19).map { "12:%02d <alice> m$it".format(it) } + listOf(">  "), awaitSnapshot().lines())
        }
    }

    @Test fun newMessagesWhileScrolledKeepTheView() = runTest {
        val messages = mutableStateListOf<BitchatMessage>().apply { addAll((0..19).map { msg("alice", "m$it", it) }) }
        runMosaicTest {
            setContentAndSnapshot { ChatScreen(messages, "anon", IntSize(40, 9), onSend = {}) }
            press(KeyboardEvent.PageUp)
            val scrolled = (5..11).map { "12:%02d <alice> m$it".format(it) } + listOf("-- more below (End) --", ">  ")
            assertEquals(scrolled, awaitSnapshot().lines())
            messages += msg("bob", "new", 30)
            assertEquals(scrolled, awaitSnapshot().lines())
            press(KeyboardEvent.End)
            assertEquals((13..19).map { "12:%02d <alice> m$it".format(it) } + listOf("12:30 <bob> new", ">  "), awaitSnapshot().lines())
        }
    }

    @Test fun clearingTheConversationWhileScrolledReturnsToLive() = runTest {
        val messages = mutableStateListOf<BitchatMessage>().apply { addAll((0..19).map { msg("alice", "m$it", it) }) }
        runMosaicTest {
            setContentAndSnapshot { ChatScreen(messages, "anon", IntSize(40, 9), onSend = {}) }
            press(KeyboardEvent.PageUp)
            awaitSnapshot()
            messages.clear() // What /clear does.
            awaitSnapshot()
            messages += (0..19).map { msg("bob", "n$it", 40 + it) }
            assertEquals((12..19).map { "12:%02d <bob> n$it".format(40 + it) } + ">  ", awaitSnapshot().lines())
        }
    }

    @Test fun aReplacedConversationReturnsToLive() = runTest {
        val messages = mutableStateListOf<BitchatMessage>().apply { addAll((0..19).map { msg("alice", "m$it", it) }) }
        runMosaicTest {
            setContentAndSnapshot { ChatScreen(messages, "anon", IntSize(40, 9), onSend = {}) }
            press(KeyboardEvent.PageUp)
            awaitSnapshot()
            val other = (0..19).map { msg("carol", "c$it", it) }
            messages.clear()
            messages.addAll(other)
            assertEquals((12..19).map { "12:%02d <carol> c$it".format(it) } + ">  ", awaitSnapshot().lines())
        }
    }

    @Test fun historyLoadedAboveKeepsTheView() = runTest {
        val messages = mutableStateListOf<BitchatMessage>().apply { addAll((10..29).map { msg("alice", "m$it", it) }) }
        runMosaicTest {
            setContentAndSnapshot { ChatScreen(messages, "anon", IntSize(40, 9), onSend = {}) }
            press(KeyboardEvent.PageUp)
            val scrolled = awaitSnapshot().lines()
            messages.addAll(0, (0..9).map { msg("alice", "m$it", it) })
            assertEquals(scrolled, awaitSnapshot().lines())
        }
    }

    @Test fun redrawsLayOutOnlyTheMessagesOnScreen() = runTest {
        // A bounded micro-benchmark by work, not time: 5,000 messages, eight list rows.
        val many = (0 until 5000).map { msg("alice", "m$it", it % 60, id = "id$it") }
        val layouts = MessageLayouts()
        runMosaicTest {
            setContentAndSnapshot { ChatScreenContent(many, "anon", IntSize(40, 9), onSend = {}, layouts = layouts) }
            assertEquals(8, layouts.layoutCalls, "first frame")
            press(KeyboardEvent.PageUp)
            awaitSnapshot()
            assertEquals(8 + 7, layouts.layoutCalls, "one page back: seven more messages")
            press('x'.code)
            awaitSnapshot()
            press(KeyboardEvent.PageDown)
            awaitSnapshot()
            assertEquals(8 + 7, layouts.layoutCalls, "typing and paging back down reuse the layouts")
        }
    }

    /** A message list that counts element reads. */
    private class CountingList(private val backing: List<BitchatMessage>) : AbstractList<BitchatMessage>() {
        var reads = 0
        override val size get() = backing.size
        override fun get(index: Int): BitchatMessage {
            reads++
            return backing[index]
        }
    }

    @Test fun aRedrawWithAnOldAnchorReadsOnlyTheMessagesOnScreen() = runTest {
        val first = (0..19).map { msg("alice", "m$it", it % 60, id = "id$it") }
        val messages = androidx.compose.runtime.mutableStateOf<List<BitchatMessage>>(first)
        runMosaicTest {
            setContentAndSnapshot { ChatScreen(messages.value, "anon", IntSize(40, 9), onSend = {}) }
            press(KeyboardEvent.PageUp)
            press(KeyboardEvent.PageUp) // Anchored near the oldest message.
            awaitSnapshot()
            val big = CountingList(first + (20 until 5000).map { msg("alice", "m$it", it % 60, id = "id$it") })
            messages.value = big
            awaitSnapshot() // The new list is indexed once.
            big.reads = 0
            press(KeyboardEvent.PageDown)
            awaitSnapshot()
            // Eight rows on screen, a page of seven walked: a few dozen reads, not thousands.
            assertTrue(big.reads <= 40, "read ${big.reads} messages")
        }
    }

    @Test fun anEditedMessageIsLaidOutAgain() = runTest {
        val messages = mutableStateListOf(msg("alice", "one", 0, id = "x"))
        val layouts = MessageLayouts()
        runMosaicTest {
            setContentAndSnapshot { ChatScreenContent(messages, "anon", IntSize(40, 3), onSend = {}, layouts = layouts) }
            messages[0] = msg("alice", "two", 0, id = "x")
            assertEquals("12:00 <alice> two", awaitSnapshot().lines()[1])
        }
        assertEquals(2, layouts.layoutCalls)
    }

    @Test fun enterSendsTheRawLineUninterpreted() = runTest {
        val sent = ArrayList<String>()
        val editor = LineEditor()
        runMosaicTest {
            setContentAndSnapshot { ChatScreen(conversation, "anon", IntSize(40, 9), onSend = { sent += it }, editor = editor) }
            "/msg bob hi".forEach { press(it.code) }
            press(13)
            press('x'.code) // Typing after the send leaves something new to draw.
            assertEquals("> x ", awaitSnapshot().lines().last())
        }
        assertEquals(listOf("/msg bob hi"), sent)
        assertEquals("x", editor.text)
    }

    @Test fun endMovesTheInputCursorWhenLive() = runTest {
        val editor = LineEditor()
        runMosaicTest {
            setContentAndSnapshot { ChatScreen(conversation, "anon", IntSize(40, 9), onSend = {}, editor = editor) }
            press('a'.code)
            press('b'.code)
            press(KeyboardEvent.Home)
            awaitSnapshot()
            press(KeyboardEvent.End)
            awaitSnapshot()
        }
        assertEquals(2, editor.cursor)
    }

    @Test fun consoleSafeModeMapsWideText() = runTest {
        val rows = render(AnsiLevel.NONE, consoleSafe = true) {
            ChatScreen(listOf(msg("\u4E2D\u6587", "hi \uD83D\uDE00", 0)), "anon", IntSize(40, 2), onSend = {}, title = null)
        }
        assertEquals("12:00 <??> hi ?", rows[0])
    }

    @Test fun errorRowSitsAboveThePrompt() = runTest {
        val rows = render(AnsiLevel.NONE) {
            ChatScreen(conversation, "anon", IntSize(40, 5), onSend = {}, errorMessage = "send failed")
        }
        assertEquals(listOf("12:01 <bob> yo", "12:02 <anon> me", "send failed", ">  "), rows.drop(1))
    }

    /**
     * The owner's report: after leaving `#test` the title said `#mesh` but the list still showed
     * `#test`. The screen follows whatever list and title it is given, a new list instance each
     * time as the view model's state flow delivers them, even while scrolled back.
     */
    @Test fun switchingChannelsShowsTheNewListAndTitle() = runTest {
        val test = listOf(msg("anon", "only in #test", 5))
        val mesh = (0..9).map { msg("alice", "mesh $it", it) }
        val shown = androidx.compose.runtime.mutableStateOf(mesh to "#mesh")
        fun show(list: List<BitchatMessage>, title: String) {
            shown.value = list to title
            androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
        }
        runMosaicTest {
            setContentAndSnapshot { ChatScreen(shown.value.first, "anon", IntSize(40, 6), onSend = {}, title = shown.value.second) }
            press(KeyboardEvent.PageUp) // scrolled back in #mesh
            awaitSnapshot()
            show(test, "#test")
            assertEquals(listOf(" #test", "", "", "", "12:05 <anon> only in #test", ">  "), awaitSnapshot().lines())
            show(mesh, "#mesh")
            assertEquals(listOf(" #mesh", "12:06 <alice> mesh 6", "12:07 <alice> mesh 7", "12:08 <alice> mesh 8", "12:09 <alice> mesh 9", ">  "), awaitSnapshot().lines())
        }
    }

    @Test fun channelTitlesNameTheChannel() {
        assertEquals("#mesh", channelTitle(Channel.Mesh))
        assertEquals("#9q8yy (city)", channelTitle(Channel.Location(GeohashChannelLevel.CITY, "9q8yy")))
        assertEquals("#tech", channelTitle(Channel.NamedChannel("tech")))
        assertEquals("DM with bob", channelTitle(Channel.MeshDM("0011223344556677", "bob")))
    }

    @Test fun theSmallestBodyKeepsThePromptTheErrorAndALine() = runTest {
        val rows = render(AnsiLevel.NONE) {
            com.jakewharton.mosaic.ui.Column {
                ChatScreen(conversation, "anon", IntSize(40, 3), onSend = {}, title = "#mesh", errorMessage = "send failed")
                com.jakewharton.mosaic.ui.Text("^")
            }
        }
        // The title goes before the newest message does.
        assertEquals(listOf("12:02 <anon> me", "send failed", ">  ", "^"), rows)
    }
}
