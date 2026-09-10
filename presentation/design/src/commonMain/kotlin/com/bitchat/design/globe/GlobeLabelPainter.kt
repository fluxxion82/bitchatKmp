package com.bitchat.design.globe

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.TextUnitType

/**
 * Draws the globe's city and geohash labels.
 *
 * Upstream draws these straight onto `drawContext.canvas.nativeCanvas` with two
 * `android.graphics.Paint`s -- one stroked for the halo, one filled for the glyphs. None of that
 * exists off Android, so the same two passes go through Compose's [TextMeasurer] here: a stroked
 * pass for the halo, then a filled pass over it.
 *
 * Measuring is not free, so results are cached by text and style. The label set is small and
 * changes only when the viewport moves across city or cell boundaries, so the cache stays tiny and
 * removes measurement from the common frame entirely.
 */
class GlobeLabelPainter(private val measurer: TextMeasurer) {

    private data class Key(val text: String, val sizePx: Float, val bold: Boolean)

    private val cache = HashMap<Key, androidx.compose.ui.text.TextLayoutResult>()

    private fun layout(text: String, sizePx: Float, bold: Boolean) =
        cache.getOrPut(Key(text, sizePx, bold)) {
            measurer.measure(
                text = text,
                style = TextStyle(
                    fontSize = TextUnit(sizePx, TextUnitType.Sp),
                    fontFamily = FontFamily.Monospace,
                    fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal
                )
            )
        }

    /**
     * @param centered when true [x] is the label's horizontal midpoint, matching the upstream
     *   `Paint.Align.CENTER` used for geohash cell labels; city labels sit to the right of their dot.
     * @param y the text's vertical midpoint, so callers can position against a dot rather than a
     *   baseline -- upstream did this with `(descent + ascent) / 2`.
     */
    fun DrawScope.drawLabel(
        text: String,
        x: Float,
        y: Float,
        sizePx: Float,
        color: Color,
        haloColor: Color,
        bold: Boolean = false,
        centered: Boolean = false
    ) {
        if (text.isEmpty()) return
        val measured = layout(text, sizePx, bold)
        val left = if (centered) x - measured.size.width / 2f else x
        val top = y - measured.size.height / 2f
        val topLeft = Offset(left, top)

        drawText(
            textLayoutResult = measured,
            color = haloColor,
            topLeft = topLeft,
            drawStyle = Stroke(width = sizePx * 0.16f)
        )
        drawText(
            textLayoutResult = measured,
            color = color,
            topLeft = topLeft
        )
    }
}
