package com.bitchat.tui

import androidx.compose.runtime.Composable
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.ui.Color
import com.jakewharton.mosaic.ui.Text
import com.jakewharton.mosaic.ui.TextStyle

/**
 * One line of [text] cut or padded to exactly [width] cells, drawn in the theme's accent colour:
 * title bars and selected rows. [textStyle] adds to that (bold for an unread row, say).
 *
 * Mosaic trims trailing *unstyled* blank cells and its root is unbounded, so a background or a
 * size modifier cannot make a bar span the width. The bar is therefore one fully styled, padded
 * string. Use this for every full-width bar instead of padding by hand. The text goes through
 * [displayText] before it is fitted, so a bar is always peer-safe and console-safe.
 */
@Composable
fun Bar(text: String, width: Int, modifier: Modifier = Modifier, textStyle: TextStyle = TextStyle.Empty) {
    val theme = LocalTuiTheme.current
    Bar(text, width, theme.onAccent, theme.accent, modifier, textStyle)
}

/** A [Bar] in colours of its own. */
@Composable
fun Bar(
    text: String,
    width: Int,
    color: Color,
    background: Color,
    modifier: Modifier = Modifier,
    textStyle: TextStyle = TextStyle.Empty,
) {
    Text(displayText(text).fitCells(width), modifier = modifier, color = color, background = background, textStyle = textStyle)
}
