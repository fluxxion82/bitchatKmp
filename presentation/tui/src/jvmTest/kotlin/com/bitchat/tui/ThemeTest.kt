package com.bitchat.tui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import com.bitchat.domain.app.model.AppTheme
import com.bitchat.domain.chat.model.BitchatMessage
import com.bitchat.domain.location.model.Channel
import com.bitchat.domain.location.model.GeohashChannelLevel
import com.bitchat.viewvo.theme.DarkPalette
import com.bitchat.viewvo.theme.LightPalette
import com.jakewharton.mosaic.terminal.AnsiLevel
import com.jakewharton.mosaic.terminal.Terminal
import com.jakewharton.mosaic.testing.MosaicSnapshots
import com.jakewharton.mosaic.testing.runMosaicTest
import com.jakewharton.mosaic.ui.Color
import com.jakewharton.mosaic.ui.Text
import com.jakewharton.mosaic.ui.unit.IntSize
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest

/**
 * The themed colours as they reach the terminal. Rows are written by hand from the palettes in
 * `presentation:viewvo`: the dark theme is #39FF14 on #000000 with #2ECB10 for notes, the light
 * theme #008000 on #FFFFFF with #006600.
 */
class ThemeTest {
    private val esc = "\u001B"
    private val dark = TuiTheme(DarkPalette, isDark = true)
    private val light = TuiTheme(LightPalette, isDark = false)
    private val hi = BitchatMessage(id = "m", sender = "alice", content = "hi", timestamp = Instant.fromEpochSeconds(12 * 3600L))

    private suspend fun render(theme: TuiTheme, level: AnsiLevel, content: @Composable () -> Unit): List<String> =
        runMosaicTest(MosaicSnapshots) {
            setContentAndSnapshot { CompositionLocalProvider(LocalTuiTheme provides theme) { content() } }
                .draw().render(level, false).split("\n")
        }

    /** The SGR parameters Mosaic emits for one cell of [color], such as `92` or `38;2;57;255;20`. */
    private suspend fun code(color: Color, level: AnsiLevel): String =
        runMosaicTest(MosaicSnapshots) {
            setContentAndSnapshot { Text("x", color = color) }.draw().render(level, false)
        }.removePrefix("$esc[").substringBefore("m")

    @Test fun theSystemChoiceFollowsTheTerminalAndAnUnknownTerminalIsDark() {
        assertEquals(true, tuiTheme(AppTheme.SYSTEM, Terminal.Theme.Unknown).isDark)
        assertEquals(true, tuiTheme(AppTheme.SYSTEM, Terminal.Theme.Dark).isDark)
        assertEquals(false, tuiTheme(AppTheme.SYSTEM, Terminal.Theme.Light).isDark)
    }

    @Test fun anExplicitChoiceIgnoresTheTerminal() {
        assertEquals(true, tuiTheme(AppTheme.DARK, Terminal.Theme.Light).isDark)
        assertEquals(false, tuiTheme(AppTheme.LIGHT, Terminal.Theme.Dark).isDark)
    }

    @Test fun equalThemesAreEqualSoTheLineCacheStaysWarm() {
        assertEquals(tuiTheme(AppTheme.DARK), tuiTheme(AppTheme.DARK))
        assertTrue(tuiTheme(AppTheme.DARK) != tuiTheme(AppTheme.LIGHT))
        assertTrue(tuiTheme(AppTheme.DARK) != tuiTheme(AppTheme.DARK, consoleSafe = true))
    }

    @Test fun barsTakeTheirColoursFromTheTheme() = runTest {
        // onPrimary on primary: black on phosphor green, or white on the darker green.
        assertEquals(
            listOf("${esc}[38;2;0;0;0;48;2;57;255;20mhi  ${esc}[0m"),
            render(dark, AnsiLevel.TRUECOLOR) { Bar("hi", width = 4) },
        )
        assertEquals(
            listOf("${esc}[38;2;255;255;255;48;2;0;128;0mhi  ${esc}[0m"),
            render(light, AnsiLevel.TRUECOLOR) { Bar("hi", width = 4) },
        )
    }

    @Test fun aChatLineTakesItsColoursFromTheTheme() = runTest {
        // A dim timestamp, then the sender's own colour, then the body in ordinary text.
        assertEquals(
            "${esc}[38;2;46;203;16;2m12:00${esc}[38;2;57;255;20;22m " +
                "${esc}[38;2;215;108;217m<alice>${esc}[38;2;57;255;20m hi${esc}[0m",
            render(dark, AnsiLevel.TRUECOLOR) { ChatScreen(listOf(hi), "anon", IntSize(40, 2), onSend = {}) }[0],
        )
        assertEquals(
            "${esc}[38;2;0;102;0;2m12:00${esc}[38;2;0;128;0;22m " +
                "${esc}[38;2;88;27;89m<alice>${esc}[38;2;0;128;0m hi${esc}[0m",
            render(light, AnsiLevel.TRUECOLOR) { ChatScreen(listOf(hi), "anon", IntSize(40, 2), onSend = {}) }[0],
        )
    }

    @Test fun onSixteenColoursTheThemeKeepsItsGreens() = runTest {
        // Dark: bright green text over the dimmer green's 32. Light: 32 over a secondary that
        // rounds all the way to black, which is still readable on the light theme's white.
        assertEquals(
            "$esc[32;2m12:00$esc[92;22m $esc[35m<alice>$esc[92m hi$esc[0m",
            render(dark, AnsiLevel.ANSI16) { ChatScreen(listOf(hi), "anon", IntSize(40, 2), onSend = {}) }[0],
        )
        assertEquals(
            "$esc[30;2m12:00$esc[32;22m $esc[30m<alice>$esc[32m hi$esc[0m",
            render(light, AnsiLevel.ANSI16) { ChatScreen(listOf(hi), "anon", IntSize(40, 2), onSend = {}) }[0],
        )
    }

    // Five nicknames whose hues land in five different sectors of the colour circle, so a
    // console-safe theme must give them five different terminal colours.
    private val nicknames = listOf("trent", "bob", "carol", "frank", "alice")

    @Test fun withoutTheConsolePaletteEveryLightThemePeerIsTheSameBlack() = runTest {
        val codes = nicknames.map { code(light.peer(it), AnsiLevel.ANSI16) }
        assertEquals(List(nicknames.size) { "30" }, codes)
    }

    @Test fun theConsolePaletteGivesEachPeerItsOwnReadableColour() = runTest {
        // Bright red, green, cyan, blue and magenta on a dark background.
        val onDark = nicknames.map { code(TuiTheme(DarkPalette, isDark = true, consoleSafe = true).peer(it), AnsiLevel.ANSI16) }
        assertEquals(listOf("91", "92", "96", "94", "95"), onDark)
        // The normal ones on a light background, where the bright ones wash out.
        val onLight = nicknames.map { code(TuiTheme(LightPalette, isDark = false, consoleSafe = true).peer(it), AnsiLevel.ANSI16) }
        assertEquals(listOf("31", "32", "36", "34", "35"), onLight)
    }

    @Test fun channelsTakeTheAccentTheComposeAppsBadgeThemWith() = runTest {
        val mesh = Channel.Mesh
        val place = Channel.Location(GeohashChannelLevel.CITY, "9q8yy")
        assertEquals(dark.mesh, dark.channel(mesh))
        assertEquals(dark.geohash, dark.channel(place))
        assertEquals(dark.own, dark.channel(Channel.MeshDM("b0b")), "a private chat is orange, like one's own words")
        assertEquals(dark.accent, dark.channel(Channel.NamedChannel("#test")), "a named channel has no badge of its own")
        assertEquals(dark.accent, dark.channel(null))
        // Blue and green, and each still readable where the theme is drawn.
        assertEquals("94", code(dark.mesh, AnsiLevel.ANSI16))
        assertEquals("32", code(dark.geohash, AnsiLevel.ANSI16))
        assertEquals("34", code(TuiTheme(LightPalette, isDark = false, consoleSafe = true).mesh, AnsiLevel.ANSI16))
        assertEquals("94", code(TuiTheme(DarkPalette, isDark = true, consoleSafe = true).mesh, AnsiLevel.ANSI16))
        assertEquals("32", code(TuiTheme(LightPalette, isDark = false, consoleSafe = true).geohash, AnsiLevel.ANSI16))
    }

    @Test fun theConsoleNotesAreNeverBlackOnTheConsolesOwnBlack() = runTest {
        // The app paints no background, and the light theme's dimmer green rounds to black, which
        // is exactly what the Linux console shows behind it.
        assertEquals("30", code(light.dim, AnsiLevel.ANSI16), "the plain light theme's own problem")
        assertEquals("32", code(TuiTheme(LightPalette, isDark = false, consoleSafe = true).dim, AnsiLevel.ANSI16))
        assertEquals("32", code(TuiTheme(DarkPalette, isDark = true, consoleSafe = true).dim, AnsiLevel.ANSI16))
    }

    @Test fun theConsolePaletteIsNeverBlackWhiteGreyOrTheColourOfOwnMessages() = runTest {
        val unreadable = setOf("30", "37", "90", "97")
        for (isDark in listOf(true, false)) {
            val theme = TuiTheme(if (isDark) DarkPalette else LightPalette, isDark = isDark, consoleSafe = true)
            val own = code(theme.own, AnsiLevel.ANSI16)
            // Many seeds, not only the five above: no hue may land on a colour that cannot be read.
            for (seed in (0..200).map { "peer$it" }) {
                val peer = code(theme.peer(seed), AnsiLevel.ANSI16)
                assertTrue(peer !in unreadable, "$seed is $peer")
                assertTrue(peer != own, "$seed has the colour of own messages")
            }
        }
    }
}
