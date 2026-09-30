package com.bitchat.tui

import com.jakewharton.mosaic.text.AnnotatedString
import com.jakewharton.mosaic.text.SpanStyle
import com.jakewharton.mosaic.text.buildAnnotatedString
import com.jakewharton.mosaic.text.forEachTerminalCell
import com.jakewharton.mosaic.text.terminalWidth

// Width-aware string helpers. Everything here counts terminal cells the way Mosaic lays text out
// (Mosaic's own cluster rules: wide CJK and emoji take 2 cells, combining marks and joiners 0), so
// a string sized with these helpers occupies exactly the cells Mosaic reserves for it. Never size
// terminal text with String.length, take or padEnd: they count UTF-16 chars, not cells.
// All helpers take single lines; split multi-line text first. They agree with the terminal only on
// sanitized text (displayText or sanitizePeerText): Mosaic joins anything after a ZWJ into one
// cluster, so raw peer text like a<ZWJ>b<ZWJ>c would measure as one cell and draw as three.

/** The number of terminal cells this text occupies on one line. */
internal fun CharSequence.cellWidth(): Int = terminalWidth()

/** The longest prefix of whole characters that fits in [cells]; a wide character is never split. */
internal fun String.takeCells(cells: Int): String = substring(0, prefixEnd(cells))

/** The longest suffix of whole characters that fits in [cells]; a wide character is never split. */
internal fun String.takeLastCells(cells: Int): String {
    var start = length
    var used = 0
    for (cluster in clusters().asReversed()) {
        if (used + cluster.width > cells) break
        used += cluster.width
        start = cluster.start
    }
    return substring(start)
}

/**
 * This text if it fits in [cells], else its longest fitting prefix followed by [ellipsis]. The result
 * is at most [cells] wide (one less when a wide character would straddle the cut). Below the width
 * of [ellipsis] the text is cut plainly.
 */
internal fun String.truncateCells(cells: Int, ellipsis: String = "..."): String {
    if (cellWidth() <= cells) return this
    val room = cells - ellipsis.cellWidth()
    return if (room < 0) takeCells(cells) else takeCells(room) + ellipsis
}

/** This text padded on the right with spaces to [cells]; wider text is returned unchanged. */
internal fun String.padEndCells(cells: Int): String = this + " ".repeat((cells - cellWidth()).coerceAtLeast(0))

/** This text padded on the left with spaces to [cells]; wider text is returned unchanged. */
internal fun String.padStartCells(cells: Int): String = " ".repeat((cells - cellWidth()).coerceAtLeast(0)) + this

/** This text cut or padded to exactly [cells] (an empty string for zero or less). */
internal fun String.fitCells(cells: Int): String = if (cells <= 0) "" else takeCells(cells).padEndCells(cells)

/**
 * Rows of a screen handed out by priority, so a short screen drops its least important rows instead
 * of drawing past its bottom (Mosaic does not clip). Ask for rows in priority order; each ask gets
 * what is left, up to the amount asked.
 */
internal class RowBudget(rows: Int) {
    var left = rows.coerceAtLeast(0)
        private set

    fun take(rows: Int): Int = rows.coerceIn(0, left).also { left -= it }

    /** Hands back [rows] taken but not used. */
    fun give(rows: Int) {
        left += rows.coerceAtLeast(0)
    }
}

/**
 * A list row of at most [width] cells: [left] then [right] flush right. A long [left] is ellipsized;
 * when not even six cells of it would fit beside [right], [right] goes and [left] is cut to [width].
 * Both must already be sanitized.
 */
internal fun twoColumnRow(left: String, right: String, width: Int): String {
    val room = width - right.cellWidth()
    return when {
        left.cellWidth() + 1 <= room -> left.padEndCells(room) + right
        room >= MIN_LEFT_CELLS -> left.truncateCells(room - 1).padEndCells(room) + right
        else -> left.truncateCells(width)
    }
}

private const val MIN_LEFT_CELLS = 6

/**
 * Greedily word-wraps one line of [text] to [width] cells. Lines break at spaces (the breaking
 * spaces are dropped; interior runs of spaces are kept); a word wider than a line is broken. Every
 * line takes at least one character, so a wide character in a 1-cell width overflows rather than
 * looping, and a width below 1 counts as 1.
 */
internal fun wrapCells(text: String, width: Int): List<String> =
    wrapBounds(text, width, words = true).map { text.substring(it.start, it.end) }

/** [wrapCells] for styled text; each line keeps the styles of its range except links ([withoutLinks]). */
internal fun wrapCells(text: AnnotatedString, width: Int): List<AnnotatedString> =
    text.withoutLinks().let { plain -> wrapBounds(plain, width, words = true).map { plain.subSequence(it.start, it.end) } }

/** Breaks one line of [text] into lines of at most [width] cells without regard to words. */
internal fun hardWrapCells(text: String, width: Int): List<String> =
    wrapBounds(text, width, words = false).map { text.substring(it.start, it.end) }

/** [hardWrapCells] for styled text; each line keeps the styles of its range except links ([withoutLinks]). */
internal fun hardWrapCells(text: AnnotatedString, width: Int): List<AnnotatedString> =
    text.withoutLinks().let { plain -> wrapBounds(plain, width, words = false).map { plain.subSequence(it.start, it.end) } }

/**
 * This text with every [SpanStyle.link] removed and the rest of each style kept. The wrap helpers
 * are for chat text, which is peer-derived: a link would become an OSC 8 hyperlink whose target a
 * peer chose and the operator cannot see (Mosaic percent-encodes the URL, so it cannot inject
 * escapes, but the target can differ from the visible text). Links in the TUI must come from the
 * app itself, never through these helpers.
 */
private fun AnnotatedString.withoutLinks(): AnnotatedString {
    if (spanStyles.none { it.item.link != null }) return this
    return buildAnnotatedString {
        append(this@withoutLinks.text)
        for (range in spanStyles) addStyle(range.item.copy(link = null), range.start, range.end)
    }
}

/** One character as the terminal draws it: chars `[start, end)` occupying [width] cells. */
internal class Cluster(val start: Int, val end: Int, val width: Int)

/**
 * This text split into characters, in order: Mosaic's clusters, except that a combining mark Mosaic
 * counts as a cell of its own (a spacing mark such as U+093E) stays with the character before it.
 * Widths are Mosaic's, so they still add up to its measure, and no helper, wrap or cursor movement
 * ever separates a mark from its base.
 */
internal fun CharSequence.clusters(): List<Cluster> {
    val clusters = ArrayList<Cluster>()
    forEachTerminalCell { start, end, codePoint, width ->
        val previous = clusters.lastOrNull()
        if (previous != null && previous.end == start && isCombiningMark(codePoint)) {
            clusters[clusters.lastIndex] = Cluster(previous.start, end, previous.width + width)
        } else {
            clusters += Cluster(start, end, width)
        }
    }
    return clusters
}

/** The end (exclusive, in chars) of the longest prefix of whole clusters that fits in [cells]. */
private fun CharSequence.prefixEnd(cells: Int): Int {
    var end = 0
    var used = 0
    for (cluster in clusters()) {
        if (used + cluster.width > cells) break
        used += cluster.width
        end = cluster.end
    }
    return end
}

/** One wrapped line: chars `[start, end)` of the source text. */
private class LineBounds(val start: Int, val end: Int)

private fun wrapBounds(text: CharSequence, width: Int, words: Boolean): List<LineBounds> {
    val limit = width.coerceAtLeast(1)
    val clusters = text.clusters()
    val count = clusters.size
    val lines = ArrayList<LineBounds>()
    var first = 0 // Cluster index starting the current line.
    var lineStart = 0 // Char index starting the current line (0 keeps leading zero-width chars).
    while (true) {
        var next = first
        var used = 0
        while (next < count && used + clusters[next].width <= limit) {
            used += clusters[next].width
            next++
        }
        if (next == first) next++ // Always take at least one character.
        if (next >= count) {
            lines += LineBounds(lineStart, text.length)
            break
        }
        var lineEnd = clusters[next].start
        if (words) {
            var space = next
            while (space > first && text[clusters[space].start] != ' ') space--
            if (space > first) {
                lineEnd = clusters[space].start
                next = space + 1
                while (lineEnd > lineStart && text[lineEnd - 1] == ' ') lineEnd--
                while (next < count && text[clusters[next].start] == ' ') next++
            }
        }
        lines += LineBounds(lineStart, lineEnd)
        if (next >= count) break
        first = next
        lineStart = clusters[next].start
    }
    return lines
}
