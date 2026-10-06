package com.bitchat.domain.user

/** The longest nickname kept from a peer: upstream iOS's limit for one's own nickname. */
const val MAX_NICKNAME_CHARS = 50

/** The nickname a mesh peer has when it announced none that survives [sanitizedNickname]. */
const val UNKNOWN_PEER_NICKNAME = "Unknown"

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

/**
 * What is kept of the nickname a MESH peer announced: [sanitizedNickname] of it without number signs.
 *
 * The app itself writes `#` and the start of a peer's id after the name of a private chat
 * ([meshChatName]). A nickname that could carry a `#` of its own could be made to read like the name of
 * somebody else's chat, so on the mesh the sign is the app's alone. The id's four characters remain a
 * hint and not a proof: an id that starts with the same four can be made, and other characters can
 * look like the sign.
 */
fun sanitizedMeshNickname(raw: String?): String? = sanitizedNickname(raw?.filterNot { it in NUMBER_SIGNS })

/** What follows the name of a mesh private chat with [peerID]: `#` and the first four characters of the id. */
fun meshChatNameSuffix(peerID: String): String = "#" + peerID.take(4).lowercase()

/** How a mesh private chat opened under [claim] (a [sanitizedMeshNickname]) is called. */
fun meshChatName(claim: String, peerID: String): String = claim + meshChatNameSuffix(peerID)

/** The number sign and the two characters Unicode has for the same sign in another width. */
private const val NUMBER_SIGNS = "#\uFF03\uFE5F"

/** Line and paragraph separators, and the bidirectional controls of Unicode's bidi algorithm. */
private fun Char.reordersOrBreaksText(): Boolean =
    this == '\u2028' || this == '\u2029' ||                 // line, paragraph separator
        this == '\u061C' || this == '\u200E' || this == '\u200F' ||   // Arabic letter, left-to-right, right-to-left mark
        this in '\u202A'..'\u202E' ||                      // embeddings, pop, overrides
        this in '\u2066'..'\u2069'                         // isolates, pop
