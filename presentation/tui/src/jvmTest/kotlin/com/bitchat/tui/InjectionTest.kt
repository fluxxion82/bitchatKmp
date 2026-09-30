package com.bitchat.tui

import androidx.compose.runtime.Composable
import com.jakewharton.mosaic.terminal.AnsiLevel
import com.jakewharton.mosaic.terminal.KeyboardEvent
import com.jakewharton.mosaic.terminal.Terminal
import com.jakewharton.mosaic.testing.MosaicSnapshots
import com.jakewharton.mosaic.testing.runMosaicTest
import com.jakewharton.mosaic.ui.Column
import com.jakewharton.mosaic.ui.Text
import com.jakewharton.mosaic.ui.unit.IntSize
import com.bitchat.domain.chat.model.BitchatFilePacket
import com.bitchat.domain.chat.model.BitchatMessage
import com.bitchat.domain.chat.model.BitchatMessageType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * Hostile peer text through every public text component, checked against the exact rendered row.
 * Expected strings are written out by hand: every character that survives is one cell, and the
 * only escapes left in a row are the dark theme's own colours (see the constants below), switched
 * back inside a row or ended by the `ESC[0m` reset at its end.
 */
class InjectionTest {
    private val esc = "\u001B"

    // One of each: SGR, DCS and APC (7-bit and 8-bit), OSC 52, a C1 CSI, RLO, then a plain letter.
    private val payload =
        "\u001B[31m" + "\u001BPq\u001B\\" + "\u0090q\u009C" + "\u001B_G\u001B\\" + "\u009FG\u009C" +
            "\u001B]52;c;aGk=\u0007" + "\u009B2J" + "\u202E" + "x"

    // What sanitizing leaves: every control becomes ?, the rest is kept. 38 one-cell chars.
    private val sanitized =
        "?[31m" + "?Pq?\\" + "?q?" + "?_G?\\" + "?G?" +
            "?]52;c;aGk=?" + "?2J" + "?" + "x"

    // What the line editor keeps: controls are dropped rather than replaced. 25 chars.
    private val typed = "[31m" + "Pq\\" + "q" + "_G\\" + "G" + "]52;c;aGk=" + "2J" + "x"

    // The dark theme, as Mosaic reduces it to sixteen colours.
    private val bar = "$esc[30;102m" // A bar or a selected row: black on bright green.
    private val fg = "$esc[92m" // Ordinary text: the phosphor green.
    private val title = "$esc[92;1m" // A screen title: ordinary text, bold.
    private val hint = "$esc[32m" // The footer, in the dimmer green.
    private val place = "$esc[32m" // A location channel, in the green it is badged with.
    private val dmTitle = "$esc[93;1m" // A private chat, in the orange it is badged with.
    private val afterKey = "$esc[32;49m" // Back to the footer's own colours after a key hint.
    private val reset = "$esc[0m"

    private suspend fun ansi(content: @Composable () -> Unit): String =
        runMosaicTest(MosaicSnapshots) { setContentAndSnapshot(content).draw().render(AnsiLevel.ANSI16, false) }

    @Test fun handWrittenExpectationsHaveTheirStatedLengths() {
        assertEquals(38, sanitized.length)
        assertEquals(25, typed.length)
    }

    @Test fun sanitizedPayloadHasNoControlsLeft() {
        val result = sanitizePeerText(payload)
        assertEquals(sanitized, result)
        val live = result.filter { Character.isISOControl(it) || it in '\u202A'..'\u202E' || it in '\u2066'..'\u2069' }
        assertTrue(live.isEmpty(), live)
    }

    @Test fun displayTextInAText() = runTest {
        assertEquals(sanitized, ansi { Text(displayText(payload)) })
    }

    @Test fun bar() = runTest {
        assertEquals("$bar$sanitized  $reset", ansi { Bar(payload, width = 40) })
    }

    @Test fun header() = runTest {
        // " " + 38 + 3 spaces of padding is 42 cells, then "3 peers " makes 50.
        assertEquals("$bar $sanitized   3 peers $reset", ansi { Header(nickname = payload, peerCount = 3, width = 50) })
    }

    @Test fun footer() = runTest {
        // 1 + 38 + 1 + 38 = 78 cells, within 80.
        assertEquals("$hint $bar$sanitized$afterKey $sanitized$reset", ansi { Footer(listOf(KeyHint(payload, payload)), width = 80) })
    }

    @Test fun promptLabelAndEditorInsertion() = runTest {
        val editor = LineEditor()
        editor.insert(payload)
        assertEquals(typed, editor.text)
        val submitted = ArrayList<String>()
        runMosaicTest(MosaicSnapshots) {
            val row = setContentAndSnapshot {
                LinePrompt(editor, width = 100, onSubmit = { submitted += it }, prompt = "$payload> ")
            }.draw().render(AnsiLevel.ANSI16, false)
            assertEquals("$fg$sanitized> $typed$bar $reset", row)
            sendKeyEvent(KeyboardEvent(13))
            awaitSnapshot()
        }
        assertEquals(listOf(typed), submitted)
    }

    @Test fun wrappedMessageLines() = runTest {
        val rendered = ansi {
            Column { sanitizePeerLines("$payload\n$payload").flatMap { wrapCells(it, 20) }.forEach { Text(it) } }
        }
        val first = sanitized.substring(0, 20)
        val rest = sanitized.substring(20)
        assertEquals(listOf(first, rest, first, rest).joinToString("\n"), rendered)
    }

    @Test fun appHeaderRow() = runTest {
        runMosaicTest(MosaicSnapshots) {
            state.size.value = Terminal.Size(50, 12)
            val rows = setContentAndSnapshot {
                TuiApp(nickname = payload, peerCount = 3, navigation = TuiNavigation()) { _, _ -> }
            }.draw().render(AnsiLevel.ANSI16, false).split("\n")
            assertEquals("$bar $sanitized   3 peers $reset", rows.first())
        }
    }

    private val dim = "$esc[32;2m" // A timestamp, a note or a status line.
    private val dimOff = "$esc[92;22m" // Back to ordinary text after a dim or bold span.
    private val error = "$esc[91m"
    private val ownOn = "$esc[93;1m" // Own messages: orange, which reduces to bright yellow.
    private val ownOff = dimOff
    private val noon = kotlin.time.Instant.fromEpochSeconds(12 * 3600L)

    @Test fun chatScreen() = runTest {
        val text = BitchatMessage(id = "t", sender = payload, content = payload, timestamp = noon)
        val file = BitchatMessage(
            id = "f",
            sender = payload,
            content = "",
            type = BitchatMessageType.File,
            timestamp = noon,
            filePacket = BitchatFilePacket(payload, 1024, "text/plain", ByteArray(0)),
        )
        val rows = ansi {
            ChatScreen(
                listOf(text, file),
                nickname = payload, // Own messages: a fixed colour, whatever the payload hashes to.
                size = IntSize(120, 5),
                onSend = {},
                title = payload,
                errorMessage = payload,
            )
        }.split("\n")
        val sender = sanitized.take(17) + "..." // Sender names are cut to 20 cells.
        assertEquals(
            listOf(
                "$title $sanitized$reset",
                "${dim}12:00$dimOff $ownOn<$sender>$ownOff $sanitized$reset",
                "${dim}12:00$dimOff $ownOn<$sender>$ownOff [file $sanitized 1KB]$reset",
                "$error$sanitized$reset",
                "$fg> $bar $reset",
            ),
            rows,
        )
    }

    @Test fun peersScreen() = runTest {
        val rows = ansi {
            PeersScreen(listOf(PeerEntry("x", payload, PeerTransport.Routed)), IntSize(60, 2), onOpenDm = {}, onToggleFavorite = {})
        }.split("\n")
        // "    " + 38 is 42 cells, padded to 53, then "routed " makes 60; the only row is selected.
        assertEquals(listOf("$title People (1)$reset", "$bar    $sanitized" + " ".repeat(11) + "routed $reset"), rows)
    }

    @Test fun dmScreenTitle() = runTest {
        // Three rows: at two, one message line would outrank the title.
        val rows = ansi { DmScreen(payload, emptyList(), "anon", IntSize(60, 3), onSend = {}) }.split("\n")
        assertEquals("$dmTitle DM with $sanitized$reset", rows[0])
    }

    @Test fun settingsStatusLines() = runTest {
        val state = com.bitchat.viewvo.settings.SettingsState(
            loraAvailable = true,
            torAvailability = com.bitchat.domain.tor.model.TorAvailability.AVAILABLE,
            loraSwitchStatus = com.bitchat.viewvo.settings.LoRaSwitchStatus.FAILED,
            loraSwitchError = payload,
            torErrorMessage = payload,
        )
        val rows = ansi {
            SettingsScreen(state, IntSize(120, 11), {}, {}, {}, {}, {}, {}, {}, {}, {})
        }.split("\n")
        assertEquals(listOf("$dim LoRa: failed, $sanitized$reset", "$dim Tor: $sanitized$reset"), rows.drop(9))
    }

    @Test fun locationsList() = runTest {
        val state = com.bitchat.viewvo.location.LocationChannelsState(
            availableChannels = listOf(
                com.bitchat.domain.location.model.GeohashChannel(com.bitchat.domain.location.model.GeohashChannelLevel.CITY, "9q8yy"),
            ),
            locationNames = mapOf(com.bitchat.domain.location.model.GeohashChannelLevel.CITY to payload),
            bookmarkedGeohashes = listOf(payload),
            bookmarkNames = mapOf(payload to payload),
        )
        val rows = ansi { LocationsScreen(state, IntSize(120, 4), {}, {}, {}, {}) }.split("\n")
        // Left parts are 53 and 83 cells, padded to 111, then "0 people ": 120 cells. Every row is
        // coloured, so Mosaic keeps the blank that ends it.
        assertEquals(
            listOf(
                "$title Locations$reset",
                "$bar > #mesh" + " ".repeat(103) + "0 people $reset", // The mesh is the current channel.
                "$place   city #9q8yy $sanitized" + " ".repeat(58) + "0 people $reset",
                "$place   * #$sanitized $sanitized" + " ".repeat(28) + "0 people $reset",
            ),
            rows,
        )
    }

    @Test fun locationsUnavailableReasonAndEntryError() = runTest {
        val state = com.bitchat.viewvo.location.LocationChannelsState(locationUnavailableReason = payload, customGeohashError = payload)
        runMosaicTest(MosaicSnapshots) {
            val list = setContentAndSnapshot { LocationsScreen(state, IntSize(120, 5), {}, {}, {}, {}) }
                .draw().render(AnsiLevel.ANSI16, false).split("\n")
            assertEquals("$dim   $sanitized$reset", list[2])
            sendKeyEvent(com.jakewharton.mosaic.terminal.KeyboardEvent('t'.code))
            val entry = awaitSnapshot().draw().render(AnsiLevel.ANSI16, false).split("\n")
            assertEquals("$error $sanitized$reset", entry[3])
        }
    }
}
