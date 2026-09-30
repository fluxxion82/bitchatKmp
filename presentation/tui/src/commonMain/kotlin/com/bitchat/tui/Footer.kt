package com.bitchat.tui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import com.jakewharton.mosaic.text.AnnotatedString
import com.jakewharton.mosaic.text.SpanStyle
import com.jakewharton.mosaic.text.buildAnnotatedString
import com.jakewharton.mosaic.text.withStyle
import com.jakewharton.mosaic.ui.Text
import com.jakewharton.mosaic.ui.TextStyle

/** One footer entry: [key] as typed (`^P` means Ctrl+P) and what it does. */
class KeyHint(val key: String, val label: String)

/**
 * One line of key hints, nano style: each key in reverse video followed by its label. Hints are
 * kept in order while they fit in [width] cells; the rest are dropped, so list the most useful first.
 * Both fields go through [displayText] before they are measured, so hints may carry peer text.
 */
@Composable
fun Footer(hints: List<KeyHint>, width: Int) {
    val theme = LocalTuiTheme.current
    Text(footerText(hints, width, LocalConsoleSafe.current, theme), color = theme.dim)
}

/** The key hints for [mode], most useful first. ASCII only, so the console font draws them all. */
internal fun footerHints(mode: Mode): List<KeyHint> = when (mode) {
    Mode.Chat -> listOf(
        KeyHint("Enter", "send"),
        KeyHint("^P", "peers"),
        KeyHint("^G", "places"),
        KeyHint("^S", "settings"),
        KeyHint("PgUp/PgDn", "scroll"),
        KeyHint("Tab", "next"),
    )
    Mode.Peers -> listOf(
        KeyHint("Enter", "DM"),
        KeyHint("Up/Down", "select"),
        KeyHint("f", "favourite"),
        KeyHint("Esc", "back"),
        KeyHint("Tab", "next"),
    )
    Mode.Dm -> listOf(
        KeyHint("Enter", "send"),
        KeyHint("Esc", "peers"),
        KeyHint("PgUp/PgDn", "scroll"),
        KeyHint("Tab", "next"),
    )
    Mode.Notes -> listOf(
        KeyHint("Enter", "post"),
        KeyHint("Esc", "places"),
        KeyHint("PgUp/PgDn", "scroll"),
        KeyHint("Tab", "next"),
    )
    Mode.Locations -> listOf(
        KeyHint("Enter", "join"),
        KeyHint("Up/Down", "select"),
        KeyHint("b", "bookmark"),
        KeyHint("g", "grid"),
        KeyHint("t", "teleport"),
        KeyHint("n", "notes"),
        KeyHint("Esc", "back"),
    )
    Mode.Wipe -> listOf(
        KeyHint("Enter", "erase"),
        KeyHint("Esc", "keep everything"),
    )
    Mode.Settings -> listOf(
        KeyHint("Left/Right", "change"),
        KeyHint("Up/Down", "select"),
        KeyHint("Esc", "back"),
        KeyHint("Tab", "next"),
    )
}

internal fun footerText(
    hints: List<KeyHint>,
    width: Int,
    consoleSafe: Boolean = false,
    theme: TuiTheme = DarkTuiTheme,
): AnnotatedString = buildAnnotatedString {
    var used = 0
    for (hint in hints) {
        val key = displayText(hint.key, consoleSafe)
        val label = displayText(hint.label, consoleSafe)
        val gap = if (used == 0) " " else "  "
        val cells = gap.length + key.cellWidth() + 1 + label.cellWidth()
        if (used + cells > width) break
        append(gap)
        withStyle(SpanStyle(color = theme.onAccent, background = theme.accent)) { append(key) }
        append(" ")
        append(label)
        used += cells
    }
}

/** The footer's hints while a screen replaces its mode's (see [FooterHints]); null shows the mode's. */
internal class FooterSlot {
    var hints: List<KeyHint>? by mutableStateOf(null)
}

internal val LocalFooterSlot: ProvidableCompositionLocal<FooterSlot?> = staticCompositionLocalOf { null }

/**
 * Shows [hints] in the [TuiApp] footer instead of the mode's own for as long as this is composed
 * with them (null: the mode's). For a screen state with keys of its own, such as an open list.
 */
@Composable
internal fun FooterHints(hints: List<KeyHint>?) {
    val slot = LocalFooterSlot.current ?: return
    DisposableEffect(slot, hints) {
        slot.hints = hints
        onDispose { if (slot.hints === hints) slot.hints = null }
    }
}
