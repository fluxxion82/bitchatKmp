package com.bitchat.nostr.util

/** The longest nickname kept from a peer: upstream iOS's limit for one's own nickname. */
const val MAX_NICKNAME_CHARS = 50

/**
 * What is kept of a nickname a peer chose, or null when nothing is left of it.
 *
 * A nickname is shown in lists and in a terminal, and whoever sent it chose every character. Dropped:
 * control characters (they move a terminal's cursor), line and paragraph separators, every
 * bidirectional control (marks, embeddings, overrides and isolates: they reorder what stands beside
 * the name) and half surrogate pairs (not characters at all). The rest is trimmed and cut to
 * [MAX_NICKNAME_CHARS] without splitting a pair. One pass over the input. Callers keep and show only
 * this, never the raw value.
 */
fun sanitizedNickname(raw: String?): String? {
    if (raw == null) return null
    val kept = StringBuilder()
    var index = 0
    while (index < raw.length) {
        val char = raw[index]
        if (char.isHighSurrogate() && index + 1 < raw.length && raw[index + 1].isLowSurrogate()) {
            kept.append(char).append(raw[index + 1])
            index += 2
            continue
        }
        if (!char.isSurrogate() && !char.isISOControl() && !char.reordersOrBreaksText()) kept.append(char)
        index++
    }

    val trimmed = kept.trim()
    if (trimmed.isEmpty()) return null
    var end = minOf(trimmed.length, MAX_NICKNAME_CHARS)
    // Every surrogate left is half of a whole pair, so a high one at the cut has lost its other half.
    if (end < trimmed.length && trimmed[end - 1].isHighSurrogate()) end--
    return trimmed.substring(0, end).trim().takeIf { it.isNotEmpty() }
}

/** Line and paragraph separators, and the bidirectional controls of Unicode's bidi algorithm. */
private fun Char.reordersOrBreaksText(): Boolean =
    this == '\u2028' || this == '\u2029' ||                 // line, paragraph separator
        this == '\u061C' || this == '\u200E' || this == '\u200F' ||   // Arabic letter, left-to-right, right-to-left mark
        this in '\u202A'..'\u202E' ||                      // embeddings, pop, overrides
        this in '\u2066'..'\u2069'                         // isolates, pop
