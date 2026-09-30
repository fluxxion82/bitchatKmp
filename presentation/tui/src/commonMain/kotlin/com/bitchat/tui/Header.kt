package com.bitchat.tui

import androidx.compose.runtime.Composable

/**
 * One-line title bar: nickname on the left, the proof-of-work setting, unread DMs and peer count on
 * the right, in the theme's accent colour across exactly [width] cells (a [Bar]). The nickname is
 * sanitized and made console-safe ([displayText]) before it is measured.
 *
 * @param powBits The difficulty proof of work is mining at, or null while it is off. It is the
 *   terminal's answer to the Compose apps' shield in the chat header: without it there is nothing
 *   on screen that says the setting is doing anything.
 */
@Composable
fun Header(nickname: String, peerCount: Int, width: Int, unreadDms: Int = 0, powBits: Int? = null) {
    Bar(headerLine(displayText(nickname), peerCount, width, unreadDms, powBits), width)
}

/**
 * Builds the header text, exactly [width] cells (a [width] of zero or less gives an empty string).
 * The peer count wins over the nickname: the nickname is ellipsized first, then dropped. At tiny
 * widths the word "peers" goes before the number, and a number wider than the header keeps its
 * rightmost digits. Widths are terminal cells, so wide (CJK, emoji) nicknames fit too. With
 * [unreadDms] above zero, "N DM, " goes before the peer count while the whole count still fits.
 * With [powBits], "powN, " goes before those; it is the first thing dropped, then the DM count.
 */
internal fun headerLine(nickname: String, peerCount: Int, width: Int, unreadDms: Int = 0, powBits: Int? = null): String {
    if (width <= 0) return ""
    val left = " $nickname"
    val peers = "$peerCount ${if (peerCount == 1) "peer" else "peers"} "
    val withDms = if (unreadDms > 0) "$unreadDms DM, $peers" else peers
    val withPow = if (powBits != null) "pow$powBits, $withDms" else withDms
    val right = listOf(withPow, withDms, peers).first { it.cellWidth() <= width || it === peers }
    val room = width - right.cellWidth()
    return when {
        left.cellWidth() + 1 <= room -> left.padEndCells(room) + right
        room >= MIN_NICKNAME_COLUMNS -> left.truncateCells(room - 1).padEndCells(room) + right
        right.cellWidth() <= width -> right.padStartCells(width)
        else -> "$peerCount".takeLastCells(width).padStartCells(width)
    }
}

private const val MIN_NICKNAME_COLUMNS = 6
