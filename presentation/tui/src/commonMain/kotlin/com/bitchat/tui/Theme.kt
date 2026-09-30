package com.bitchat.tui

import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf
import com.bitchat.domain.app.model.AppTheme
import com.bitchat.domain.location.model.Channel
import com.bitchat.viewvo.theme.BitchatPalette
import com.bitchat.viewvo.theme.DarkPalette
import com.bitchat.viewvo.theme.GEOHASH_COLOR
import com.bitchat.viewvo.theme.LightPalette
import com.bitchat.viewvo.theme.MESH_COLOR
import com.bitchat.viewvo.theme.OWN_MESSAGE_COLOR
import com.bitchat.viewvo.theme.peerColorHsv
import com.bitchat.viewvo.theme.rgbOf
import com.bitchat.viewvo.theme.toRgb
import com.jakewharton.mosaic.terminal.Terminal
import com.jakewharton.mosaic.ui.Color

/**
 * bitchat's colours in the terminal, from the same palette as the Compose apps
 * (`presentation:viewvo`): a phosphor green on near-black, or a darker green on near-white.
 *
 * Only foregrounds are themed. Mosaic cannot paint the screen behind the frame, so the terminal's
 * own background shows through; a theme that disagrees with it (the light theme in a black
 * terminal) still reads, but it does not look like the Compose apps. Nothing here may therefore
 * round to the background of either, which is what [consoleSafe] is for.
 *
 * [consoleSafe] is the Linux console, which has sixteen colours. Mosaic reduces a colour by
 * rounding each channel to 0 or 1, which sends everything dark to black: every peer name in the
 * light theme (value 0.35) comes out black, so all peers look alike, and in the dark theme the
 * peers near hue 240 come out in the console's unreadable dark blue. A console-safe theme
 * therefore picks peer names from named terminal colours instead ([consoleColor]).
 *
 * This is a value: two themes with the same palette are equal, which is what keeps the chat
 * screen's line cache ([LayoutKey]) from missing on every recomposition.
 */
data class TuiTheme(
    val palette: BitchatPalette,
    val isDark: Boolean,
    val consoleSafe: Boolean = false,
) {
    /** Ordinary text: the phosphor green the Compose apps write in. */
    val foreground: Color = palette.onBackground.toColor()

    /** What the screen is drawn on; also the text under the prompt's cursor. */
    val background: Color = palette.background.toColor()

    /** Titles, selected rows and other bars. */
    val accent: Color = palette.primary.toColor()

    /** Text on [accent]. */
    val onAccent: Color = palette.onPrimary.toColor()

    /**
     * Notes, hints and timestamps: the dimmer green. The light theme's rounds to black on sixteen
     * colours, and the app paints no background of its own, so on the Linux console (black) those
     * rows would disappear; a console-safe light theme asks for the normal green instead, which
     * reads on either background.
     */
    val dim: Color = if (consoleSafe && !isDark) Color(0, 204, 0) else palette.secondary.toColor()

    val error: Color = palette.error.toColor()

    /**
     * One's own messages: the apps' orange. A sixteen-colour terminal rounds that to bright
     * yellow, which is unreadable on the light theme's white, so a console-safe light theme asks
     * for the normal yellow (a brown on the Linux console) instead.
     */
    val own: Color = when {
        !consoleSafe -> OWN_MESSAGE_COLOR.toColor()
        isDark -> Color(255, 153, 0)
        else -> Color(204, 153, 0)
    }

    /**
     * The mesh, wherever it is named or counted: the blue the Compose apps badge it with. A
     * sixteen-colour terminal rounds it to bright blue, which is unreadable on the light theme's
     * white, so a console-safe light theme asks for the normal one.
     */
    val mesh: Color = when {
        !consoleSafe -> MESH_COLOR.toColor()
        isDark -> Color(0, 0, 255)
        else -> Color(0, 0, 204)
    }

    /** A location (geohash) channel: the Compose apps' green, which reads on either background. */
    val geohash: Color = if (consoleSafe) Color(0, 204, 0) else GEOHASH_COLOR.toColor()

    /** LoRa and private chats, as in the Compose apps: the colour of one's own messages. */
    val lora: Color get() = own

    /** What [channel] is named in: the same accent the Compose apps badge it with. */
    fun channel(channel: Channel?): Color = when (channel) {
        null, is Channel.NamedChannel -> accent
        is Channel.Location -> geohash
        is Channel.Mesh -> mesh
        is Channel.Meshtastic, is Channel.MeshDM, is Channel.NostrDM -> lora
    }

    /**
     * The colour of the peer identified by [seed] (see `peerColorSeed`): the colour the Compose
     * apps give them, or the nearest readable terminal colour when [consoleSafe].
     */
    fun peer(seed: String): Color {
        val hsv = peerColorHsv(seed, isDark)
        if (consoleSafe) return consoleColor(hsv.hue)
        val (r, g, b) = hsv.toRgb()
        return Color(r, g, b)
    }

    /**
     * The terminal colour nearest [hue], as a colour Mosaic reduces to it: red, green, cyan, blue
     * or magenta, bright in the dark theme (255 makes Mosaic pick the bright variant, which reads
     * on black) and normal in the light theme (204 picks the normal variant, which reads on
     * white). Never black, white or grey, each of which is invisible on one of the two.
     *
     * Yellow is left out. One's own messages are the terminal's yellow, and `peerColorHsv` already
     * steers peers away from that orange, so handing a peer yellow would undo the nudge. The
     * bounds are the midpoints between the five hues.
     */
    private fun consoleColor(hue: Float): Color {
        val full = if (isDark) 255 else 204
        val h = ((hue % 360f) + 360f) % 360f
        return when {
            h < 60f || h >= 330f -> Color(full, 0, 0)
            h < 150f -> Color(0, full, 0)
            h < 210f -> Color(0, full, full)
            h < 270f -> Color(0, 0, full)
            else -> Color(full, 0, full)
        }
    }

    private fun Long.toColor(): Color {
        val (r, g, b) = rgbOf(this)
        return Color(r, g, b)
    }
}

/** The theme the screens draw in; dark unless the app provides one. */
val LocalTuiTheme: ProvidableCompositionLocal<TuiTheme> = staticCompositionLocalOf { DarkTuiTheme }

internal val DarkTuiTheme = TuiTheme(DarkPalette, isDark = true)

/**
 * The theme for the user's [appTheme] choice. [terminal] is what the terminal says its own colours
 * are, used when the choice is [AppTheme.SYSTEM]; a terminal that says nothing (the Pi console)
 * counts as dark, which is what a console is.
 */
fun tuiTheme(
    appTheme: AppTheme,
    terminal: Terminal.Theme = Terminal.Theme.Unknown,
    consoleSafe: Boolean = false,
): TuiTheme {
    val dark = when (appTheme) {
        AppTheme.DARK -> true
        AppTheme.LIGHT -> false
        AppTheme.SYSTEM -> terminal != Terminal.Theme.Light
    }
    return TuiTheme(if (dark) DarkPalette else LightPalette, isDark = dark, consoleSafe = consoleSafe)
}
