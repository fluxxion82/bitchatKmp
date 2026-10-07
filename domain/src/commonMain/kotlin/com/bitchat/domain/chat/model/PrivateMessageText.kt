package com.bitchat.domain.chat.model

/**
 * What a private text message can carry. The private message encoding shared with the upstream
 * clients gives the content a length of one byte, on the mesh and inside a Nostr DM alike, so one
 * message holds at most [MAX_BYTES] bytes of UTF-8. A longer text is sent as several such
 * messages, [MAX_PARTS] at most; to the other side they are that many messages in a row.
 */
object PrivateMessageText {
    const val MAX_BYTES: Int = 255
    const val MAX_PARTS: Int = 8

    /** A piece ends after a space only where that leaves it at least this full. */
    private const val MIN_BYTES_AT_A_SPACE = MAX_BYTES / 2

    private const val ZERO_WIDTH_JOINER = 0x200D

    /**
     * How a private message starts when it is not text but tells the other side that it was
     * marked, or unmarked, as a favourite. Every client takes a private message that starts like
     * this (this app and upstream's Android after trimming it) and does not show it.
     */
    private val CONTROL_PREFIXES = listOf("[FAVORITED]", "[UNFAVORITED]")

    /** Why [content] cannot be sent as a private message, or null when it can. */
    fun refusal(content: String): String? {
        val pieces = split(content)
        return when {
            pieces.size > MAX_PARTS ->
                "a private message is sent in at most $MAX_PARTS parts of $MAX_BYTES bytes, this one needs ${pieces.size}"
            pieces.drop(1).any { readsAsControlMessage(it, 0) } ->
                "this private message cannot be sent in parts: one of them would start with ${CONTROL_PREFIXES.first { it in content }}"
            else -> null
        }
    }

    /**
     * [content] in the pieces it is sent as: itself when it fits one message, otherwise pieces of
     * at most [MAX_BYTES] bytes each that put together are [content] again, character for
     * character. A piece ends after a space where that leaves it at least half full. It never
     * ends inside a character, between a character and an accent, variation selector or skin
     * tone that belongs to it, inside an emoji made of joined ones or between the two letters of
     * a flag, unless such a run is longer than a message by itself. Where ending at spaces takes
     * more than [MAX_PARTS] pieces, the text is cut as tightly as it can be instead.
     *
     * No piece after the first starts the way a favourite notification does: the other side would
     * take it for one and not show it. A cut that would fall right before such a place is made
     * elsewhere. Where nothing but blank space precedes it for a whole message, no cut helps, and
     * [refusal] says so.
     */
    fun split(content: String): List<String> {
        // Measured as the encoding measures it: what one message carries goes out as that message.
        if (content.encodeToByteArray().size <= MAX_BYTES) return listOf(content)
        val atSpaces = cut(content, atSpaces = true)
        return if (atSpaces.size <= MAX_PARTS) atSpaces else cut(content, atSpaces = false)
    }

    private fun cut(content: String, atSpaces: Boolean): List<String> {
        val pieces = ArrayList<String>()
        var start = 0
        while (start < content.length) {
            val limit = limitFrom(content, start)
            var end = limit
            if (limit < content.length) {
                val last = lastWholeCut(content, start, limit)
                end = if (atSpaces) cutAfterSpace(content, start, last) ?: last else last
                if (readsAsControlMessage(content, end)) end = lastCutLeavingNoControlMessage(content, start, last) ?: end
            }
            pieces += content.substring(start, end)
            start = end
        }
        return pieces
    }

    /** The end of the longest run of whole code points from [start] that fits one message. */
    private fun limitFrom(content: String, start: Int): Int {
        var index = start
        var bytes = 0
        while (index < content.length) {
            val units = unitsAt(content, index)
            val size = utf8Size(content[index], units)
            if (bytes + size > MAX_BYTES) break
            bytes += size
            index += units
        }
        return index
    }

    /** The last place in ([start], [limit]] that takes nothing apart; [limit] when there is none. */
    private fun lastWholeCut(content: String, start: Int, limit: Int): Int {
        var index = limit
        while (index > start) {
            if (!takesApart(content, index)) return index
            index--
        }
        return limit
    }

    /**
     * The cut after the last space of the piece [start] until [end], when the piece would
     * otherwise end inside a word and is still at least half full there.
     */
    private fun cutAfterSpace(content: String, start: Int, end: Int): Int? {
        if (content[end].isWhitespace() || content[end - 1].isWhitespace()) return null
        var index = end - 1
        while (index > start) {
            if (content[index - 1].isWhitespace() && !takesApart(content, index)) {
                return if (utf8Size(content, start, index) >= MIN_BYTES_AT_A_SPACE) index else null
            }
            index--
        }
        return null
    }

    /**
     * The last place in ([start], [last]] that takes nothing apart and from which the rest of the
     * text does not read as a control message, or null when this piece has no such place.
     */
    private fun lastCutLeavingNoControlMessage(content: String, start: Int, last: Int): Int? {
        var index = last
        while (index > start) {
            if (!takesApart(content, index) && !readsAsControlMessage(content, index)) return index
            index--
        }
        return null
    }

    /** Whether [content] from [from] on would be taken for a control message by a receiver. */
    private fun readsAsControlMessage(content: String, from: Int): Boolean {
        var index = from
        while (index < content.length && content[index].isWhitespace()) index++
        return CONTROL_PREFIXES.any { content.startsWith(it, index) }
    }

    /** Whether a cut before [index] separates things that are read as one. */
    private fun takesApart(content: String, index: Int): Boolean {
        if (content[index].isLowSurrogate() && content[index - 1].isHighSurrogate()) return true
        val next = codePointAt(content, index)
        if (belongsToWhatPrecedes(next)) return true
        val previous = codePointBefore(content, index)
        if (previous == ZERO_WIDTH_JOINER) return true
        if (isRegionalIndicator(next) && isRegionalIndicator(previous)) {
            // Flags are pairs of these, counted from where the run starts: an odd number before
            // the cut means it falls inside a pair.
            var indicators = 0
            var before = index
            while (before >= 2 && isRegionalIndicator(codePointBefore(content, before))) {
                indicators++
                before -= 2
            }
            return indicators % 2 == 1
        }
        return false
    }

    private fun belongsToWhatPrecedes(codePoint: Int): Boolean =
        codePoint == ZERO_WIDTH_JOINER ||
            codePoint in 0xFE00..0xFE0F || // variation selectors
            codePoint in 0xE0100..0xE01EF ||
            codePoint in 0x1F3FB..0x1F3FF || // skin tones
            codePoint in 0xE0020..0xE007F || // tags, as in the flags of regions
            (codePoint <= 0xFFFF && isCombiningMark(Char(codePoint)))

    private fun isCombiningMark(char: Char): Boolean = when (char.category) {
        CharCategory.NON_SPACING_MARK, CharCategory.COMBINING_SPACING_MARK, CharCategory.ENCLOSING_MARK -> true
        else -> false
    }

    private fun isRegionalIndicator(codePoint: Int): Boolean = codePoint in 0x1F1E6..0x1F1FF

    /** How many UTF-16 units the code point at [index] takes. */
    private fun unitsAt(content: String, index: Int): Int =
        if (content[index].isHighSurrogate() && index + 1 < content.length && content[index + 1].isLowSurrogate()) 2 else 1

    /**
     * The bytes of UTF-8 for a code point whose first unit is [first]. Half of a pair that has no
     * other half counts as three: no platform's encoder writes more for it, some write fewer.
     */
    private fun utf8Size(first: Char, units: Int): Int = when {
        units == 2 -> 4
        first.code < 0x80 -> 1
        first.code < 0x800 -> 2
        else -> 3
    }

    private fun utf8Size(content: String, from: Int, to: Int): Int {
        var index = from
        var bytes = 0
        while (index < to) {
            val units = unitsAt(content, index)
            bytes += utf8Size(content[index], units)
            index += units
        }
        return bytes
    }

    private fun codePointAt(content: String, index: Int): Int {
        val first = content[index]
        if (unitsAt(content, index) == 2) return pair(first, content[index + 1])
        return first.code
    }

    private fun codePointBefore(content: String, index: Int): Int {
        val last = content[index - 1]
        if (last.isLowSurrogate() && index >= 2 && content[index - 2].isHighSurrogate()) {
            return pair(content[index - 2], last)
        }
        return last.code
    }

    private fun pair(high: Char, low: Char): Int = 0x10000 + ((high.code - 0xD800) shl 10) + (low.code - 0xDC00)
}
