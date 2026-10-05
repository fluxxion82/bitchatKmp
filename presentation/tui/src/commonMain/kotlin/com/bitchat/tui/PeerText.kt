package com.bitchat.tui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import com.jakewharton.mosaic.text.codePointWidth
import com.jakewharton.mosaic.text.forEachTerminalCell

// Text from remote peers (nicknames, messages, channel and geohash names) is attacker-controlled.
// Mosaic writes every code point of a Text straight to the terminal, so an ESC or a C1 control in a
// nickname would let any peer move the cursor, clear the operator's screen, plant an OSC 8 link,
// set the clipboard (OSC 52) or the window title. Rule for every screen: a peer string reaches a
// Text only through displayText() (or sanitizePeerText/sanitizePeerLines plus consoleSafe), and
// before any width fitting, because sanitizing can change a string's width. Sanitized strings stay
// sanitized when concatenated, so they can be combined freely before measuring. The width
// assumptions this rests on are listed on sanitizePeerText.

/**
 * What a defused character is drawn as: plain ASCII, so it is one cell in every terminal and font
 * (U+FFFD is East Asian Ambiguous and two cells in ambiguous-wide terminals).
 */
const val PLACEHOLDER = '?'

/**
 * Makes one line of peer text inert and measurable:
 * - tab, CR, LF and U+2028/U+2029 become spaces;
 * - every other C0 control, DEL, every C1 control (U+0080-U+009F), the bidi embeddings, overrides
 *   and isolates (U+202A-U+202E, U+2066-U+2069), unpaired surrogates, the 66 noncharacters and
 *   private-use characters become [PLACEHOLDER]; the soft hyphen U+00AD is removed;
 * - emoji sequences are reduced to single emoji: joiners, skin tones, keycaps, tags and U+FE0E are
 *   dropped, U+FE0F survives only where it changes no width, and a lone regional indicator becomes
 *   [PLACEHOLDER];
 * - combining marks with no base before them, or after an emoji or a flag, are dropped;
 * - Hangul conjoining jamo are composed into syllables where NFC composes them, the rest become
 *   [PLACEHOLDER].
 *
 * Everything else, including wide characters, marks on ordinary bases and flag pairs, is kept.
 * Idempotent. Use for single-line fields; split multi-line bodies with [sanitizePeerLines]. The
 * details are on [cleanTerminalText].
 *
 * Width assumptions, by design (terminal width differences beyond these are not chased further):
 * - Mosaic's bundled Unicode width table is authoritative. Text is laid out by it, and the rules
 *   above only remove sequences whose width terminals disagree on.
 * - East Asian Ambiguous characters are one cell.
 * - A terminal on a different Unicode version, or in an ambiguous-wide mode (iTerm2 and others can
 *   draw ambiguous characters two cells wide), may misalign lines that contain such characters.
 * - On the Linux console, console-safe mode ([LocalConsoleSafe], [consoleSafe]) is the real
 *   guarantee: every character drawn is then one cell.
 */
fun sanitizePeerText(text: String): String {
    if (text.all { it in ' '..'~' }) return text // Printable ASCII needs nothing.
    return cleanTerminalText(text, replace = true).text
}

/** Splits a multi-line peer body on CR LF, LF or CR, then sanitizes each line. */
fun sanitizePeerLines(text: String): List<String> = text.lines().map(::sanitizePeerText)

/**
 * Maps what the Linux console font cannot draw to `?`, one cell each, so that Mosaic's layout
 * matches what fbcon draws (it draws a wide character in one cell while Mosaic reserves two).
 * Each base character becomes `?` when it is wide or outside the BMP and is kept otherwise, even
 * when a joiner glued it to the previous one; the marks that only modify a base (combining accents,
 * variation selectors, joiners, skin tones, the second half of a flag) are dropped. So a family
 * emoji becomes `???`, a flag or a thumbs up with skin tone `?`, and no printable base is lost.
 * Every character of the result is one cell. Idempotent. Applied only when [LocalConsoleSafe] is on.
 */
fun consoleSafe(text: String): String {
    val out = StringBuilder(text.length)
    text.forEachTerminalCell { start, end, _, width ->
        var i = start
        while (i < end) {
            val codePoint = text.codePointAt(i)
            val base = i == start
            val flagHalf = !base && isRegionalIndicator(codePoint) && isRegionalIndicator(text.codePointAt(start))
            when {
                base -> out.append(if (width == 2 || codePoint >= 0x10000) '?' else text[i])
                codePointWidth(codePoint) == 0 || isEmojiModifier(codePoint) || flagHalf -> Unit
                else -> out.append(if (codePointWidth(codePoint) == 2 || codePoint >= 0x10000) '?' else text[i])
            }
            i += if (codePoint >= 0x10000) 2 else 1
        }
    }
    return out.toString()
}

/**
 * Whether text should be drawn console-safe ([consoleSafe]). Decided once at start from `TERM`
 * (see [consoleSafeFor]) and provided by the app's entry point; false by default.
 */
val LocalConsoleSafe: ProvidableCompositionLocal<Boolean> = staticCompositionLocalOf { false }

/** Whether [term] is the Linux virtual console, or console-safe rendering is [forced]. */
fun consoleSafeFor(term: String?, forced: Boolean): Boolean = forced || term == "linux" || term?.startsWith("linux-") == true

/** [text] sanitized and, when [LocalConsoleSafe] is on, console-safe: ready for a Text. */
@Composable
@ReadOnlyComposable
fun displayText(text: String): String = displayText(text, LocalConsoleSafe.current)

/** [text] sanitized and, when [consoleSafe] is true, console-safe. */
internal fun displayText(text: String, consoleSafe: Boolean): String {
    val safe = sanitizePeerText(text)
    return if (consoleSafe) consoleSafe(safe) else safe
}

/** The result of [cleanTerminalText]: the text, and the index in it that input index `mark` maps to. */
internal class CleanText(val text: String, val mark: Int)

/**
 * The single pass behind [sanitizePeerText] and [LineEditor.insert]. Walks [text] by code point:
 * - tab, CR, LF, U+2028/U+2029: a space, or dropped unless [replace];
 * - C0, DEL, C1, bidi embeddings/overrides/isolates, unpaired surrogates, noncharacters and
 *   private-use characters: [PLACEHOLDER], or dropped unless [replace];
 * - the soft hyphen U+00AD: dropped (terminals disagree on whether it takes a cell);
 * - emoji sequence glue, always dropped: U+200D ZERO WIDTH JOINER, skin tones (U+1F3FB-U+1F3FF),
 *   the combining keycap U+20E3, tag characters (U+E0020-U+E007F) and U+FE0E;
 * - U+FE0F: kept only right after an Emoji_Presentation=yes character (other than a regional
 *   indicator), where it changes nothing because that character is two cells already;
 * - regional indicators: kept in complete pairs (a flag, two cells); a lone or odd one becomes
 *   [PLACEHOLDER], or is dropped unless [replace];
 * - combining marks (general category Mn, Mc, Me) with no base before them, or after an emoji
 *   (an Emoji_Presentation character or a flag pair): dropped. A base is any character written that
 *   is neither a mark nor zero-width. Marks on ordinary bases stay;
 * - Hangul conjoining jamo: composed into a precomposed syllable where NFC composes them (initial +
 *   medial, optionally + final, or an LV syllable + final); any other jamo becomes [PLACEHOLDER], or
 *   is dropped unless [replace]. Terminals disagree on jamo widths; syllables are always 2 cells;
 * - other zero-width code points with nothing before them: dropped. Mosaic drops them when drawing
 *   anyway, and in the editor one would attach to the next character typed before it.
 *
 * Why the emoji rules: Mosaic's clustering joins whatever follows a ZWJ, attaches a skin tone to
 * any character, widens any character followed by U+FE0F and pairs any two indicators, while
 * terminals draw emoji sequences in widths that differ from one terminal to the next. Keeping only
 * single emoji makes Mosaic's measure match what every terminal draws: families become their
 * people, a keycap its digit, a subdivision flag the black flag.
 *
 * [mark] is an input char index on a code point boundary; [CleanText.mark] is where it lands. With
 * [context] false, U+FE0F and marks are kept for a later pass that sees the text around them.
 */
internal fun cleanTerminalText(text: String, replace: Boolean, mark: Int = text.length, context: Boolean = true): CleanText {
    val out = StringBuilder(text.length)
    var marked = -1
    var last = -1 // The last code point written.
    var base = -1 // The last character written that a mark can sit on: no mark, not zero-width.
    var i = 0
    while (i < text.length) {
        if (i >= mark && marked < 0) marked = out.length
        val codePoint = text.codePointAt(i)
        val size = if (codePoint >= 0x10000) 2 else 1
        if (isRegionalIndicator(codePoint)) {
            val next = if (i + size < text.length) text.codePointAt(i + size) else -1
            if (isRegionalIndicator(next)) {
                out.append(text, i, i + 4)
                last = next
                base = next
                i += 4
            } else {
                if (replace) {
                    out.append(PLACEHOLDER)
                    last = PLACEHOLDER.code
                    base = PLACEHOLDER.code
                }
                i += size
            }
            continue
        }
        if (codePoint in 0x1100..0x1112 || isLvSyllable(codePoint)) {
            // Compose Hangul as NFC does: initial + medial [+ final], or an LV syllable + final.
            var syllable = codePoint
            var end = i + 1
            if (codePoint <= 0x1112) {
                val medial = if (end < text.length) text[end].code else -1
                syllable = if (medial in 0x1161..0x1175) 0xAC00 + ((codePoint - 0x1100) * 21 + (medial - 0x1161)) * 28 else -1
                if (syllable != -1) end++
            }
            if (syllable != -1) {
                val final = if (end < text.length) text[end].code else -1
                if (final in 0x11A8..0x11C2) {
                    syllable += final - 0x11A7
                    end++
                }
                out.append(syllable.toChar())
                last = syllable
                base = syllable
                i = end
                continue
            }
        }
        val kept: Int? = when {
            codePoint in 0xD800..0xDFFF -> PLACEHOLDER.code.takeIf { replace }
            isConjoiningJamo(codePoint) -> PLACEHOLDER.code.takeIf { replace }
            isLineBreakOrTab(codePoint) -> ' '.code.takeIf { replace }
            codePoint < 0x20 || codePoint in 0x7F..0x9F || isBidiOverride(codePoint) ||
                isNonCharacter(codePoint) || isPrivateUse(codePoint) -> PLACEHOLDER.code.takeIf { replace }
            codePoint == SOFT_HYPHEN -> null
            isEmojiGlue(codePoint) -> null
            context && last == -1 && codePointWidth(codePoint) == 0 -> null
            codePoint == VS16 -> codePoint.takeIf { !context || (isEmojiPresentation(last) && !isRegionalIndicator(last)) }
            context && isCombiningMark(codePoint) && (base == -1 || isEmojiPresentation(base)) -> null
            else -> codePoint
        }
        if (kept != null) {
            if (kept == codePoint) out.append(text, i, i + size) else out.append(kept.toChar())
            last = kept
            if (!isCombiningMark(kept) && codePointWidth(kept) > 0) base = kept
        }
        i += size
    }
    return CleanText(out.toString(), if (marked < 0) out.length else marked)
}

/** The bidi embeddings and overrides (U+202A-U+202E) and isolates (U+2066-U+2069). */
internal fun isBidiOverride(codePoint: Int): Boolean = codePoint in 0x202A..0x202E || codePoint in 0x2066..0x2069

/** The code point at char [index], or the lone surrogate there. */
internal fun String.codePointAt(index: Int): Int {
    val high = this[index]
    if (high.isHighSurrogate() && index + 1 < length) {
        val low = this[index + 1]
        if (low.isLowSurrogate()) return 0x10000 + ((high.code - 0xD800) shl 10) + (low.code - 0xDC00)
    }
    return high.code
}

private const val ZWJ = 0x200D
private const val VS15 = 0xFE0E
private const val VS16 = 0xFE0F
private const val COMBINING_KEYCAP = 0x20E3
private const val SOFT_HYPHEN = 0x00AD

/** Hangul conjoining jamo: U+1100-11FF, U+A960-A97F (Extended-A), U+D7B0-D7FF (Extended-B). */
private fun isConjoiningJamo(codePoint: Int): Boolean =
    codePoint in 0x1100..0x11FF || codePoint in 0xA960..0xA97F || codePoint in 0xD7B0..0xD7FF

/** A precomposed Hangul syllable without a final consonant, which NFC composes with a final jamo. */
private fun isLvSyllable(codePoint: Int): Boolean = codePoint in 0xAC00..0xD7A3 && (codePoint - 0xAC00) % 28 == 0

/** U+FDD0-FDEF and the last two code points of each plane (U+xxFFFE, U+xxFFFF): 66 in all. */
private fun isNonCharacter(codePoint: Int): Boolean = codePoint in 0xFDD0..0xFDEF || (codePoint and 0xFFFE) == 0xFFFE

/** The private-use area U+E000-F8FF and planes 15 and 16. */
private fun isPrivateUse(codePoint: Int): Boolean = codePoint in 0xE000..0xF8FF || codePoint >= 0xF0000

/** ZWJ, skin tones, the combining keycap, tag characters and U+FE0E: never kept. */
private fun isEmojiGlue(codePoint: Int): Boolean = codePoint == ZWJ || isEmojiModifier(codePoint) ||
    codePoint == COMBINING_KEYCAP || codePoint in 0xE0020..0xE007F || codePoint == VS15

private fun isLineBreakOrTab(codePoint: Int): Boolean =
    codePoint == 0x09 || codePoint == 0x0A || codePoint == 0x0D || codePoint == 0x2028 || codePoint == 0x2029

private fun isRegionalIndicator(codePoint: Int): Boolean = codePoint in 0x1F1E6..0x1F1FF
