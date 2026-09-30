package com.bitchat.tui

import com.bitchat.domain.chat.model.BitchatMessage
import com.bitchat.domain.location.model.Channel
import com.bitchat.viewvo.chat.channelCommandSuggestions
import com.jakewharton.mosaic.terminal.KeyboardEvent
import com.jakewharton.mosaic.terminal.Terminal
import com.jakewharton.mosaic.testing.TestMosaic
import com.jakewharton.mosaic.testing.runMosaicTest
import com.jakewharton.mosaic.ui.Text
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.test.runTest

/** Slash command suggestions in the chat, as the Compose chat offers them, inside the whole app. */
class SuggestionsTest {
    private val navigation = TuiNavigation(Mode.Chat)
    private val editor = LineEditor()
    private val sent = ArrayList<String>()
    private val hello = listOf(BitchatMessage(id = "1", sender = "alice", content = "hi", timestamp = Instant.fromEpochSeconds(12 * 3600L)))

    private fun TestMosaic<String>.app(
        columns: Int,
        rows: Int,
        channel: Channel = Channel.Mesh,
        people: List<String> = emptyList(),
    ) {
        state.size.value = Terminal.Size(columns, rows)
        setContentAndSnapshot {
            TuiApp(nickname = "anon", peerCount = 0, navigation = navigation) { mode, size ->
                when (mode) {
                    Mode.Chat -> ChatScreen(
                        hello, "anon", size, onSend = { sent += it }, title = "#mesh", editor = editor,
                        commands = channel.channelCommandSuggestions(null),
                        people = people,
                    )
                    else -> Text("screen ${mode.name}")
                }
            }
        }
    }

    /** The people the peers screen would show in this chat, a repeated nickname among them. */
    private val room = listOf("bob#a1b2", "bob#c3d4", "alice")

    private fun TestMosaic<*>.type(text: String) = text.forEach { sendKeyEvent(KeyboardEvent(it.code)) }
    private fun TestMosaic<*>.press(code: Int) = sendKeyEvent(KeyboardEvent(code))

    /** Snapshots until nothing changes any more (bounded), returning the last one. */
    private suspend fun TestMosaic<String>.settle(): List<String> {
        var last = ""
        try {
            last = awaitSnapshot()
        } catch (_: TimeoutCancellationException) {
            return emptyList() // nothing changed at all
        }
        repeat(10) {
            last = try {
                awaitSnapshot()
            } catch (_: TimeoutCancellationException) {
                return last.lines()
            }
        }
        return last.lines()
    }

    /** At 40x12 (an 11-row frame): six of nine commands, the input, a message line and the title all fit. */
    @Test fun aSlashOpensTheListAboveTheInputWithItsKeysInTheFooter() = runTest {
        runMosaicTest {
            app(40, 12)
            type("/")
            assertEquals(
                listOf(
                    " anon                           0 peers",
                    " #mesh",
                    "12:00 <alice> hi",
                    " /block [nickname]  block or list blocke",
                    " /channels  show all discovered channels",
                    " /clear  clear chat messages",
                    " /hug <nickname>  send someone a warm...",
                    " /j <channel>  join or create a channel",
                    " /m <nickname> [message]  send privat...",
                    "> /",
                    " Tab complete  Up/Down choose  Esc close",
                ),
                settle().map { it.trimEnd() },
            )
        }
    }

    @Test fun typingNarrowsTheListAndTabCompletesWithoutLeavingTheChat() = runTest {
        runMosaicTest {
            app(85, 25)
            type("/j")
            val open = settle().map { it.trimEnd() }
            assertEquals(" /j <channel>  join or create a channel", open[open.lastIndex - 2])
            assertEquals("> /j", open[open.lastIndex - 1])
            press(9) // Tab
            val completed = settle().map { it.trimEnd() }
            assertEquals("> /j", completed[completed.lastIndex - 1], "completed to \"/j \", the list closed")
            assertEquals(" Enter send  ^P peers  ^G places  ^S settings  PgUp/PgDn scroll  Tab next", completed.last())
        }
        assertEquals(Mode.Chat, navigation.mode)
        assertEquals("/j ", editor.text)
    }

    @Test fun upAndDownChooseAndTabTakesTheChoice() = runTest {
        runMosaicTest {
            app(85, 25)
            type("/")
            press(KeyboardEvent.Down)
            press(KeyboardEvent.Down)
            press(KeyboardEvent.Up)
            press(KeyboardEvent.Up)
            press(KeyboardEvent.Up) // wraps to the last: /w
            press(9)
            settle()
        }
        assertEquals("/w ", editor.text)
    }

    @Test fun escClosesTheListUntilTheInputChangesAndThenTabCyclesModesAgain() = runTest {
        runMosaicTest {
            app(85, 25)
            type("/c")
            settle()
            press(27) // Esc
            val closed = settle().map { it.trimEnd() }
            assertEquals("> /c", closed[closed.lastIndex - 1])
            assertEquals(" Enter send  ^P peers  ^G places  ^S settings  PgUp/PgDn scroll  Tab next", closed.last())
            type("l")
            val reopened = settle().map { it.trimEnd() }
            assertEquals(" /clear  clear chat messages", reopened[reopened.lastIndex - 2])
            press(27)
            settle()
            press(9) // nothing to complete: Tab moves on to Peers, as ever
            settle()
        }
        assertEquals(Mode.Peers, navigation.mode)
    }

    @Test fun enterSendsTheLineAsTypedAndNamedChannelsOfferLeave() = runTest {
        runMosaicTest {
            app(85, 25, channel = Channel.NamedChannel("#test"))
            type("/le")
            val open = settle().map { it.trimEnd() }
            assertEquals(" /leave  leave the channel", open[open.lastIndex - 2])
            press(13)
            settle()
        }
        assertEquals(listOf("/le"), sent)
    }

    @Test fun escClosingTheListLastsOnlyUntilTheNextEdit() = runTest {
        runMosaicTest {
            app(85, 25)
            type("/")
            settle()
            press(KeyboardEvent.Down) // highlight /channels
            press(27) // Esc closes the list
            assertEquals(" Enter send  ^P peers  ^G places  ^S settings  PgUp/PgDn scroll  Tab next", settle().last())
            type("j")
            settle()
            press(127) // Backspace: back at "/", a fresh line, so the list is open again...
            val reopened = settle().map { it.trimEnd() }
            assertEquals("> /", reopened[reopened.lastIndex - 1])
            assertEquals(" /block [nickname]  block or list blocked peers", reopened[reopened.lastIndex - 7], "...from the top again")
            press(9) // and Tab completes rather than cycling modes
            settle()
        }
        assertEquals("/block ", editor.text)
        assertEquals(Mode.Chat, navigation.mode)
    }

    /** Typing must not walk the conversation again: the prompt and the suggestion rows redraw, not the list. */
    @Test fun typingDoesNotWalkTheConversation() = runTest {
        val walked = CountingMessages((0..400).map { at(it) })
        runMosaicTest {
            state.size.value = Terminal.Size(85, 25)
            setContentAndSnapshot {
                TuiApp(nickname = "anon", peerCount = 0, navigation = navigation) { _, size ->
                    ChatScreen(walked, "anon", size, onSend = {}, title = "#mesh", editor = editor, commands = Channel.Mesh.channelCommandSuggestions(null))
                }
            }
            settle()
            walked.reads = 0
            type("hello")
            settle()
            assertEquals(0, walked.reads, "an ordinary line does not touch the conversation")
            editor.set("") // A slash opens the list only at the start of the line.
            settle()
            type("/")
            val opened = settle().suggestionRows()
            assertEquals(6, opened.size, "the list is open: $opened")
            // Opening it took rows from the conversation, which is laid out again for what is
            // left. From here on the rows stay as they are, so nothing above them may move.
            walked.reads = 0
            press(KeyboardEvent.Down)
            settle()
            assertEquals(0, walked.reads, "moving through the suggestions does not touch the conversation")
            type("j")
            assertEquals(listOf("/j <channel>  join or create a channel"), settle().suggestionRows(), "narrowed to /j")
        }
    }

    @Test fun aCommandThatTakesANicknameOffersTheRoomAboveTheInput() = runTest {
        runMosaicTest {
            app(40, 12, people = room)
            type("/hug ")
            assertEquals(
                listOf(
                    " anon                           0 peers",
                    " #mesh",
                    "",
                    "",
                    "",
                    "12:00 <alice> hi",
                    " alice",
                    " bob#a1b2",
                    " bob#c3d4",
                    "> /hug",
                    " Tab complete  Up/Down choose  Esc close",
                ),
                settle().map { it.trimEnd() },
            )
        }
    }

    @Test fun typingNarrowsTheRoomAndTabCompletesTheWholeLine() = runTest {
        runMosaicTest {
            app(85, 25, people = room)
            type("/hug bo")
            val open = settle().map { it.trimEnd() }
            assertEquals(" bob#a1b2", open[open.lastIndex - 3])
            assertEquals(" bob#c3d4", open[open.lastIndex - 2])
            press(KeyboardEvent.Down) // the second of the two Bobs
            press(9) // Tab
            settle()
        }
        // The suffix and all: that is the name the apps match without ambiguity.
        assertEquals("/hug bob#c3d4 ", editor.text)
        assertEquals(Mode.Chat, navigation.mode)
    }

    @Test fun escDismissesTheRoomAsItDismissesTheCommands() = runTest {
        runMosaicTest {
            app(85, 25, people = room)
            type("/hug al")
            assertEquals(" alice", settle().map { it.trimEnd() }.let { it[it.lastIndex - 2] })
            press(27)
            val closed = settle().map { it.trimEnd() }
            assertEquals("> /hug al", closed[closed.lastIndex - 1])
            assertEquals(" Enter send  ^P peers  ^G places  ^S settings  PgUp/PgDn scroll  Tab next", closed.last())
        }
    }

    @Test fun aCommandThatTakesNoNicknameOffersNobody() = runTest {
        runMosaicTest {
            app(85, 25, people = room)
            // A channel is not a person, and neither is the message after a complete nickname.
            type("/join ")
            assertEquals(
                " Enter send  ^P peers  ^G places  ^S settings  PgUp/PgDn scroll  Tab next",
                settle().map { it.trimEnd() }.last(),
            )
            editor.set("/hug alice ")
            assertEquals(
                " Enter send  ^P peers  ^G places  ^S settings  PgUp/PgDn scroll  Tab next",
                settle().map { it.trimEnd() }.last(),
            )
        }
    }

    /** The suggestion rows of a snapshot, trimmed: the highlighted one is padded to the width. */
    private fun List<String>.suggestionRows() = map { it.trimEnd() }.filter { it.startsWith(" /") }.map { it.drop(1) }

    private fun at(minute: Int) = BitchatMessage(
        id = "m$minute", sender = "alice", content = "m$minute",
        timestamp = Instant.fromEpochSeconds(12 * 3600L + minute * 60L),
    )

    /** A conversation that counts how many of its messages are looked at. */
    private class CountingMessages(private val backing: List<BitchatMessage>) : AbstractList<BitchatMessage>() {
        var reads = 0
        override val size get() = backing.size
        override fun get(index: Int): BitchatMessage {
            reads++
            return backing[index]
        }
    }
}
