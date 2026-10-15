package com.bitchat.tui

import androidx.compose.runtime.Composable
import com.jakewharton.mosaic.text.AnnotatedString
import com.jakewharton.mosaic.text.SpanStyle
import com.jakewharton.mosaic.text.buildAnnotatedString
import com.jakewharton.mosaic.ui.Text

/**
 * One-line title bar: nickname on the left, the proof-of-work setting, unread DMs and peer count on
 * the right, in the bar colours across exactly [width] cells, the unread DM count a chip in the
 * colours of private chats ([headerText]). The nickname is sanitized and made console-safe
 * ([displayText]) before it is measured.
 *
 * @param powBits The difficulty proof of work is mining at, or null while it is off. It is the
 *   terminal's answer to the Compose apps' shield in the chat header: without it there is nothing
 *   on screen that says the setting is doing anything.
 * @param isMining Animates a console-safe spinner beside the difficulty while work is in progress.
 */
@Composable
fun Header(nickname: String, peerCount: Int, width: Int, unreadDms: Int = 0, powBits: Int? = null, isMining: Boolean = false) {
    val theme = LocalTuiTheme.current
    val active = isMining && powBits != null && headerRight(peerCount, width, unreadDms, powBits, '|').contains("| mining")
    val frame = rememberMiningFrame(active)
    val text = headerText(displayText(nickname), peerCount, width, unreadDms, powBits, frame.value.takeIf { active }, theme)
    // Already sanitized and exactly width cells, as a Bar would make it.
    Text(text, color = theme.onAccent, background = theme.accent)
}

/**
 * Builds the header text, exactly [width] cells (a [width] of zero or less gives an empty string).
 * The peer count wins over the nickname: the nickname is ellipsized first, then dropped. At tiny
 * widths the word "peers" goes before the number, and a number wider than the header keeps its
 * rightmost digits. Widths are terminal cells, so wide (CJK, emoji) nicknames fit too. With
 * [unreadDms] above zero, "N DM, " goes before the peer count while the whole count still fits.
 * With [powBits], "powN, " goes before those; it is the first thing dropped, then the DM count.
 * [miningFrame] adds `| mining` after the difficulty, dropped first when it cannot fit.
 */
internal fun headerLine(nickname: String, peerCount: Int, width: Int, unreadDms: Int = 0, powBits: Int? = null, miningFrame: Char? = null): String {
    if (width <= 0) return ""
    val left = " $nickname"
    val right = headerRight(peerCount, width, unreadDms, powBits, miningFrame)
    val room = width - right.cellWidth()
    return when {
        left.cellWidth() + 1 <= room -> left.padEndCells(room) + right
        room >= MIN_NICKNAME_COLUMNS -> left.truncateCells(room - 1).padEndCells(room) + right
        right.cellWidth() <= width -> right.padStartCells(width)
        else -> "$peerCount".takeLastCells(width).padStartCells(width)
    }
}

/**
 * [headerLine] with its "N DM", while shown, as a chip: [TuiTheme.onLora] on [TuiTheme.lora]. Found by
 * its place in the right part, never by searching the line, which may hold a nickname like "2 DM".
 */
internal fun headerText(
    nickname: String,
    peerCount: Int,
    width: Int,
    unreadDms: Int,
    powBits: Int?,
    miningFrame: Char?,
    theme: TuiTheme,
): AnnotatedString {
    val line = headerLine(nickname, peerCount, width, unreadDms, powBits, miningFrame)
    val right = headerRight(peerCount, width, unreadDms, powBits, miningFrame)
    val dms = "$unreadDms DM"
    val tail = ", ${peersText(peerCount)}"
    return buildAnnotatedString {
        append(line)
        // Whenever the right part is shown, the line ends with it.
        if (right.endsWith(dms + tail)) {
            val start = line.length - tail.length - dms.length
            addStyle(SpanStyle(color = theme.onLora, background = theme.lora), start, start + dms.length)
        }
    }
}

private fun peersText(peerCount: Int): String = "$peerCount ${if (peerCount == 1) "peer" else "peers"} "

private fun headerRight(peerCount: Int, width: Int, unreadDms: Int, powBits: Int?, miningFrame: Char?): String {
    val peers = peersText(peerCount)
    val withDms = if (unreadDms > 0) "$unreadDms DM, $peers" else peers
    val withPow = if (powBits != null) "pow$powBits, $withDms" else withDms
    val withMining = if (powBits != null && miningFrame != null) "pow$powBits $miningFrame mining, $withDms" else withPow
    return listOf(withMining, withPow, withDms, peers).first { it.cellWidth() <= width || it === peers }
}

private const val MIN_NICKNAME_COLUMNS = 6
