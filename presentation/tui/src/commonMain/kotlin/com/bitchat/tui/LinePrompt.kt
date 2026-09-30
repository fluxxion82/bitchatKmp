package com.bitchat.tui

import androidx.compose.runtime.Composable
import com.jakewharton.mosaic.layout.onKeyEvent
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.text.AnnotatedString
import com.jakewharton.mosaic.text.SpanStyle
import com.jakewharton.mosaic.text.buildAnnotatedString
import com.jakewharton.mosaic.text.withStyle
import com.jakewharton.mosaic.ui.Text
import com.jakewharton.mosaic.ui.TextStyle

/**
 * One row, at most [width] cells: [prompt] then the [editor]'s line with a block cursor (the
 * character under the cursor, or a blank after the text, in reverse video; the terminal's own
 * cursor stays hidden). A line wider than the row scrolls horizontally to keep the cursor in view.
 *
 * The prompt is peer-safe ([displayText]) and the line is clean by the same rules
 * ([LineEditor.insert]). Under [LocalConsoleSafe] the line is drawn console-safe.
 *
 * While [focused], key events reach [LineEditor.handleKey] and Enter passes a non-blank line to
 * [onSubmit]. Mosaic has no focus system and offers every key to every node, so only one prompt on
 * screen should be focused. A screen that routes keys itself passes [handlesKeys] false and calls
 * [LineEditor.handleKey] from its own handler, so the prompt never depends on being mounted.
 */
@Composable
fun LinePrompt(
    editor: LineEditor,
    width: Int,
    onSubmit: (String) -> Unit,
    modifier: Modifier = Modifier,
    prompt: String = "> ",
    focused: Boolean = true,
    handlesKeys: Boolean = focused,
) {
    val theme = LocalTuiTheme.current
    Text(
        promptLine(displayText(prompt), editor.text, editor.cursor, width, focused, LocalConsoleSafe.current, theme),
        modifier = if (handlesKeys) modifier.onKeyEvent { editor.handleKey(it, onSubmit) } else modifier,
        color = theme.foreground,
    )
}

/**
 * The prompt row, at most [width] cells (empty for zero or less). The prompt is kept only when the
 * cell under the cursor fits after it. The visible window starts at the leftmost character that
 * still keeps the cursor on screen, then fills with the text after the cursor; a wide character
 * under the cursor that does not fit shows as a one-cell `?`. With [consoleSafe], each character is
 * drawn as [consoleSafe] maps it (widths counted from that) while the cursor moves over the originals.
 */
internal fun promptLine(
    prompt: String,
    text: String,
    cursor: Int,
    width: Int,
    focused: Boolean,
    consoleSafe: Boolean = false,
    theme: TuiTheme = DarkTuiTheme,
): AnnotatedString {
    if (width <= 0) return buildAnnotatedString {}
    val clusters = text.clusters()
    val shown = clusters.map { text.substring(it.start, it.end).let { char -> if (consoleSafe) consoleSafe(char) else char } }
    // Measured from what is drawn: a console-safe cluster can be several `?` wide.
    val cells = shown.map { it.cellWidth() }
    // The character under the cursor; clusters.size means the blank cell after the text.
    val at = clusters.indexOfFirst { it.end > cursor }.let { if (it == -1) clusters.size else it }
    val cursorCells = when {
        at < clusters.size -> cells[at]
        focused -> 1
        else -> 0
    }
    val label = if (prompt.cellWidth() + cursorCells <= width) prompt else ""
    val room = width - label.cellWidth()
    var under = shown.getOrNull(at)
    var used = cursorCells
    if (cursorCells > room) {
        under = PLACEHOLDER.toString() // Stands in for a character wider than the row.
        used = 1
    }
    var first = at
    while (first > 0 && used + cells[first - 1] <= room) used += cells[--first]
    var after = if (at < clusters.size) at + 1 else at
    while (after < clusters.size && used + cells[after] <= room) used += cells[after++]

    return buildAnnotatedString {
        append(label)
        for (i in first until at) append(shown[i])
        if (focused) {
            withStyle(SpanStyle(color = theme.background, background = theme.foreground)) { append(under ?: " ") }
        } else if (under != null) {
            append(under)
        }
        for (i in at + 1 until after) append(shown[i])
    }
}
