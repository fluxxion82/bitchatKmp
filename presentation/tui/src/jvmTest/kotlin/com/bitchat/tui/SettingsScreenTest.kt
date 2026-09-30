package com.bitchat.tui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import com.bitchat.domain.lora.model.LoRaProtocolType
import com.bitchat.domain.lora.model.LoRaRegion
import com.bitchat.domain.lora.model.LoRaTxPower
import com.bitchat.domain.tor.model.TorAvailability
import com.bitchat.domain.tor.model.TorMode
import com.bitchat.viewvo.settings.LoRaSwitchStatus
import com.bitchat.viewvo.settings.SettingsState
import com.bitchat.viewvo.settings.ThemePreference
import com.jakewharton.mosaic.terminal.AnsiLevel
import com.jakewharton.mosaic.terminal.KeyboardEvent
import com.jakewharton.mosaic.testing.MosaicSnapshots
import com.jakewharton.mosaic.testing.runMosaicTest
import com.jakewharton.mosaic.ui.Column
import com.jakewharton.mosaic.ui.Text
import com.jakewharton.mosaic.ui.unit.IntSize
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest

/** Rows are written by hand: a space, the label padded to 17 cells, then "  value" or "< value >" when selected. */
class SettingsScreenTest {
    private val esc = "\u001B"
    private val bar = "$esc[30;102m" // A selected row: the dark theme's black on bright green.
    private val note = "$esc[32;2m" // A disabled row or a status line, in the dimmer green.
    private val reset = "$esc[0m"
    private val theme = " Theme" + " ".repeat(14) + "terminal"
    private val erase = " Erase everything" + " ".repeat(3) + WIPE_KEYS
    private val ready = SettingsState(loraAvailable = true, torAvailability = TorAvailability.AVAILABLE)
    private val changes = mutableStateListOf<String>()

    @Composable
    private fun Screen(state: SettingsState, size: IntSize) {
        Column {
            Text("changes ${changes.size}") // Every callback redraws, so each key gets a frame.
            SettingsScreen(
                state = state,
                size = size,
                onLoRaEnabled = { changes += "lora=$it" },
                onLoRaProtocol = { changes += "protocol=$it" },
                onLoRaRegion = { changes += "region=$it" },
                onLoRaTxPower = { changes += "power=$it" },
                onLoRaShowPeers = { changes += "peers=$it" },
                onTor = { changes += "tor=$it" },
                onProofOfWork = { changes += "pow=$it" },
                onPowDifficulty = { changes += "difficulty=$it" },
                onTheme = { changes += "theme=$it" },
            )
        }
    }

    private suspend fun render(level: AnsiLevel, state: SettingsState, size: IntSize): List<String> =
        runMosaicTest(MosaicSnapshots) {
            setContentAndSnapshot { Screen(state, size) }.draw().render(level, false).split("\n").drop(1)
        }

    private suspend fun keys(state: SettingsState, vararg codes: Int): List<String> {
        runMosaicTest {
            setContentAndSnapshot { Screen(state, IntSize(40, 9)) }
            // All keys land in one frame (each sequence moves the selection, so the frame is drawn).
            for (code in codes) sendKeyEvent(KeyboardEvent(code))
            awaitSnapshot()
        }
        return changes.toList()
    }

    @Test fun settingsAt40x9() = runTest {
        assertEquals(
            listOf(
                " Settings",
                " LoRa radio" + " ".repeat(7) + "< on >" + " ".repeat(16),
                " Protocol" + " ".repeat(11) + "BitChat",
                " Region" + " ".repeat(13) + "US 915 MHz",
                " TX power" + " ".repeat(11) + "Medium 17 dBm",
                " Show LoRa peers" + " ".repeat(4) + "on",
                " Tor" + " ".repeat(16) + "off",
                " Proof of work" + " ".repeat(6) + "off",
                " PoW difficulty" + " ".repeat(5) + "16 bits",
            ),
            render(AnsiLevel.NONE, ready, IntSize(40, 9)),
        )
    }

    @Test fun settingsAt85x22() = runTest {
        val rows = render(AnsiLevel.NONE, ready, IntSize(85, 22))
        assertEquals(" LoRa radio" + " ".repeat(7) + "< on >" + " ".repeat(61), rows[1])
        assertEquals(" PoW difficulty" + " ".repeat(5) + "16 bits", rows[8])
        assertEquals(theme, rows[9]) // The theme follows the app's own setting.
        assertEquals(erase, rows[10]) // And the emergency wipe is written down under it.
        assertEquals(List(11) { "" }, rows.drop(11))
    }

    @Test fun selectedRowIsInTheAccentColoursAndDisabledRowsAreDim() = runTest {
        val rows = render(AnsiLevel.ANSI16, ready, IntSize(30, 9))
        assertEquals("$bar LoRa radio" + " ".repeat(7) + "< on >" + " ".repeat(6) + reset, rows[1])
        // Difficulty is dim while proof of work is off.
        assertEquals("$note PoW difficulty" + " ".repeat(5) + "16 bits$reset", rows[8])
    }

    @Test fun leftAndRightChangeEachValue() = runTest {
        val down = KeyboardEvent.Down
        val right = KeyboardEvent.Right
        val left = KeyboardEvent.Left
        assertEquals(
            listOf(
                "lora=false",
                "protocol=MESHTASTIC", "protocol=BITCHAT", // Right then Left comes back to where it started.
                "region=EU_868", "region=US_915",
                "power=HIGH", "power=MEDIUM",
                "peers=false",
                "tor=true",
                "pow=true",
            ),
            keys(ready, right, down, right, left, down, right, left, down, right, left, down, right, down, right, down, right),
        )
    }

    @Test fun repeatedStepsAddUpBeforeTheStateCatchesUp() = runTest {
        val down = KeyboardEvent.Down
        val right = KeyboardEvent.Right
        assertEquals(
            listOf("lora=false", "lora=true", "protocol=MESHTASTIC", "protocol=MESHCORE"),
            keys(ready, right, right, down, right, right),
        )
    }

    @Test fun thePendingValueIsShown() = runTest {
        runMosaicTest {
            setContentAndSnapshot { Screen(ready, IntSize(40, 9)) }
            sendKeyEvent(KeyboardEvent(KeyboardEvent.Down))
            sendKeyEvent(KeyboardEvent(KeyboardEvent.Right))
            assertEquals(" Protocol" + " ".repeat(9) + "< Meshtastic >" + " ".repeat(8), awaitSnapshot().lines()[3])
        }
    }

    @Test fun acknowledgedStateTakesOver() = runTest {
        val state = androidx.compose.runtime.mutableStateOf(ready)
        runMosaicTest {
            setContentAndSnapshot {
                Column {
                    Text("changes ${changes.size}")
                    SettingsScreen(
                        state = state.value,
                        size = IntSize(40, 9),
                        onLoRaEnabled = {},
                        onLoRaProtocol = {
                            changes += "protocol=$it"
                            state.value = state.value.copy(loraProtocol = it) // The view model acknowledges.
                        },
                        onLoRaRegion = {},
                        onLoRaTxPower = {},
                        onLoRaShowPeers = {},
                        onTor = {},
                        onProofOfWork = {},
                        onPowDifficulty = {},
                        onTheme = {},
                    )
                }
            }
            sendKeyEvent(KeyboardEvent(KeyboardEvent.Down))
            sendKeyEvent(KeyboardEvent(KeyboardEvent.Right))
            awaitSnapshot()
            sendKeyEvent(KeyboardEvent(KeyboardEvent.Right))
            awaitSnapshot()
            // A state the view model rejects: it moves back to BitChat, and the screen follows it.
            state.value = state.value.copy(loraProtocol = LoRaProtocolType.BITCHAT)
            awaitSnapshot()
            sendKeyEvent(KeyboardEvent(KeyboardEvent.Right))
            awaitSnapshot()
        }
        assertEquals(listOf("protocol=MESHTASTIC", "protocol=MESHCORE", "protocol=MESHTASTIC"), changes)
    }

    @Test fun aRetiredRequestDoesNotComeBackWithItsBase() = runTest {
        // On, ask for off, the view model says off, then something else turns it on again.
        val state = androidx.compose.runtime.mutableStateOf(ready)
        runMosaicTest {
            setContentAndSnapshot {
                Column {
                    Text("changes ${changes.size}")
                    SettingsScreen(state.value, IntSize(40, 9), { changes += "lora=$it" }, {}, {}, {}, {}, {}, {}, {}, {})
                }
            }
            sendKeyEvent(KeyboardEvent(KeyboardEvent.Right))
            awaitSnapshot()
            state.value = state.value.copy(loraEnabled = false)
            awaitSnapshot()
            state.value = state.value.copy(loraEnabled = true)
            assertEquals(" LoRa radio" + " ".repeat(7) + "< on >" + " ".repeat(16), awaitSnapshot().lines()[2])
            sendKeyEvent(KeyboardEvent(KeyboardEvent.Right))
            awaitSnapshot()
        }
        assertEquals(listOf("lora=false", "lora=false"), changes)
    }

    @Test fun difficultyStepsByOneWhenProofOfWorkIsOn() = runTest {
        val on = ready.copy(proofOfWorkEnabled = true, powDifficulty = 32)
        val down = KeyboardEvent.Down
        assertEquals(
            listOf("difficulty=31"), // 33 is past the maximum, so Right does nothing.
            keys(on, down, down, down, down, down, down, down, KeyboardEvent.Right, KeyboardEvent.Left),
        )
    }

    @Test fun rowsWithoutARadioOrTorAreDisabled() = runTest {
        val none = SettingsState(loraAvailable = false, torAvailability = TorAvailability.NATIVE_LIBRARY_MISSING)
        val down = KeyboardEvent.Down
        val right = KeyboardEvent.Right
        // LoRa radio, protocol, then down to Tor: none of them may change.
        assertEquals(emptyList(), keys(none, right, down, right, down, down, down, down, right))
    }

    @Test fun aBuildThatCannotProxySaysTorIsOnAndUnusable() = runTest {
        // Stored mode on, but this build's HTTP engine ignores the SOCKS proxy. The row used to
        // read "not supported" while every chat said "Tor is on", which reads as a contradiction,
        // and it was disabled, so the one thing that would unblock Nostr could not be done here.
        val noProxy = SettingsState(torAvailability = TorAvailability.NO_PROXY_SUPPORT, requestedTorMode = TorMode.ON)
        val rows = render(AnsiLevel.NONE, noProxy, IntSize(80, 12))
        assertEquals(" Tor" + " ".repeat(16) + "on, unusable", rows[6])
        assertEquals(
            " Tor: this build cannot run it, so nothing connects. Turn it off to use geoha...",
            rows[11],
        )
    }

    @Test fun aBuildThatCannotProxyStillLetsTorBeSwitchedOff() = runTest {
        // The dead end: nothing reaches a relay while this is on, and this screen is the only place
        // it can be turned off.
        val noProxy = SettingsState(torAvailability = TorAvailability.NO_PROXY_SUPPORT, requestedTorMode = TorMode.ON)
        val down = KeyboardEvent.Down
        assertEquals(listOf("tor=false"), keys(noProxy, down, down, down, down, down, KeyboardEvent.Right))
    }

    @Test fun aBuildThatCannotProxyWillNotSwitchTorBackOn() = runTest {
        // Off on a build that cannot carry it: the row is a fact about the build, not a choice.
        val noProxy = SettingsState(torAvailability = TorAvailability.NO_PROXY_SUPPORT, requestedTorMode = TorMode.OFF)
        val rows = render(AnsiLevel.NONE, noProxy, IntSize(80, 12))
        assertEquals(" Tor" + " ".repeat(16) + "not supported", rows[6])
        assertEquals(
            " Tor: this build cannot run it; geohash channels and Nostr DMs connect withou...",
            rows[11],
        )
        val down = KeyboardEvent.Down
        assertEquals(emptyList(), keys(noProxy, down, down, down, down, down, KeyboardEvent.Right, KeyboardEvent.Left))
    }

    @Test fun torCanAlwaysBeSwitchedOff() = runTest {
        val stuckOn = SettingsState(torAvailability = TorAvailability.NATIVE_LIBRARY_MISSING, requestedTorMode = TorMode.ON)
        val down = KeyboardEvent.Down
        assertEquals(listOf("tor=false"), keys(stuckOn, down, down, down, down, down, KeyboardEvent.Right))
    }

    @Test fun statusLinesExplainRadioAndTor() = runTest {
        val state = ready.copy(
            loraSwitchStatus = LoRaSwitchStatus.FAILED,
            loraSwitchError = "radio busy",
            torAvailability = TorAvailability.AVAILABLE,
            requestedTorMode = TorMode.ON,
            torRunning = true,
            torBootstrapPercent = 45,
        )
        val rows = render(AnsiLevel.NONE, state, IntSize(40, 12))
        assertEquals(theme, rows[9])
        assertEquals(listOf(" LoRa: failed, radio busy", " Tor: running, 45%"), rows.drop(10))
    }

    @Test fun unavailableTorSaysWhy() = runTest {
        val rows = render(AnsiLevel.NONE, ready.copy(torAvailability = TorAvailability.NATIVE_LIBRARY_MISSING), IntSize(40, 12))
        assertEquals(" Tor: native library missing", rows[11])
    }

    @Test fun rowsScrollToKeepTheSelectionVisible() = runTest {
        runMosaicTest {
            setContentAndSnapshot { Screen(ready, IntSize(40, 5)) }
            repeat(5) { sendKeyEvent(KeyboardEvent(KeyboardEvent.Down)) }
            val rows = awaitSnapshot().lines().drop(1)
            assertEquals(" Show LoRa peers" + " ".repeat(4) + "on", rows[3])
            assertEquals(" Tor" + " ".repeat(14) + "< off >" + " ".repeat(15), rows[4])
        }
    }

    @Test fun theThemeRowStepsThroughThePreferences() = runTest {
        val down = KeyboardEvent.Down
        val right = KeyboardEvent.Right
        // Eight rows down is the theme, the last one; it steps SYSTEM, LIGHT, DARK and wraps.
        assertEquals(
            listOf("theme=LIGHT", "theme=DARK", "theme=SYSTEM"),
            keys(ready, down, down, down, down, down, down, down, down, right, right, right),
        )
    }

    @Test fun themeNames() {
        assertEquals("terminal", themeLabel(ThemePreference.SYSTEM))
        assertEquals("light", themeLabel(ThemePreference.LIGHT))
        assertEquals("dark", themeLabel(ThemePreference.DARK))
    }

    @Test fun valueLabels() {
        assertEquals("AS 923 MHz", regionLabel(LoRaRegion.AS_923))
        assertEquals("Low 10 dBm", txPowerLabel(LoRaTxPower.LOW))
        assertEquals("MeshCore", LoRaProtocolType.MESHCORE.displayName)
    }

    @Test fun theSmallestBodyKeepsTheSelectedRowAndTheErrors() = runTest {
        // Three rows, the least a body gets (a 5-row frame). A sentinel under the screen shows
        // whether anything was drawn past its bottom.
        val state = ready.copy(
            loraSwitchStatus = LoRaSwitchStatus.FAILED,
            loraSwitchError = "radio busy",
            requestedTorMode = TorMode.ON,
            torRunning = true,
            torBootstrapPercent = 45,
        )
        val rows = runMosaicTest(MosaicSnapshots) {
            setContentAndSnapshot {
                Column {
                    SettingsScreen(state, IntSize(40, 3), {}, {}, {}, {}, {}, {}, {}, {}, {})
                    Text("^")
                }
            }.draw().render(AnsiLevel.NONE, false).split("\n")
        }
        assertEquals(
            listOf(" LoRa radio" + " ".repeat(7) + "< on >" + " ".repeat(16), " LoRa: failed, radio busy", " Tor: running, 45%", "^"),
            rows,
        )
    }
}
