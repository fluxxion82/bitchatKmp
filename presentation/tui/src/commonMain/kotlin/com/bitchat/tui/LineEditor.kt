package com.bitchat.tui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.jakewharton.mosaic.layout.KeyEvent

/** The longest line a [LineEditor] holds, in UTF-16 chars. */
const val MAX_LINE_LENGTH = 512

/**
 * A single-line text editor with history, as plain state: no composition needed, so every key
 * sequence is testable directly. [text] and [cursor] are snapshot state, so a [LinePrompt] showing
 * them redraws on every edit.
 *
 * The cursor always moves over whole characters (Mosaic's clusters: surrogate pairs, combining
 * sequences, flag pairs). After every edit the line equals its own sanitized form and the cursor
 * sits on a character boundary (see [insert]); the line never exceeds [maxLength] chars. Submitted
 * non-blank lines join the history (a repeat of the newest entry is stored once, the oldest drops
 * beyond [historyLimit]); walking the history keeps the unsent line as a draft at the bottom.
 */
class LineEditor(
    private val maxLength: Int = MAX_LINE_LENGTH,
    private val historyLimit: Int = 100,
) {
    var text: String by mutableStateOf("")
        private set

    /** The insertion point, a char index into [text]. */
    var cursor: Int by mutableIntStateOf(0)
        private set

    /**
     * How many times the line has been edited. A screen that remembers something about the line
     * as typed (a dismissed suggestion list, a highlighted entry) keys it on this, so retyping
     * the same text is a new line rather than the old one.
     */
    var revision: Int by mutableIntStateOf(0)
        private set

    private val history = ArrayList<String>()
    private var historyIndex = 0
    private var draft = ""

    /**
     * Inserts [value] at the cursor, cleaned by the peer-text rules ([cleanTerminalText]) in drop
     * mode: controls, bidi overrides, unpaired surrogates, line separators and emoji glue go, and
     * the text around the cursor is re-judged so the line always equals its own sanitized form.
     * A value too long for the cap is cut at a character boundary. Safe for prefilling peer text
     * such as a nickname. Joiners never survive and a lone regional indicator is dropped, so flags
     * can be inserted whole but not typed one indicator at a time.
     */
    fun insert(value: String) {
        val clean = cleanTerminalText(value, replace = false, context = false).text
        val room = maxLength - text.length
        val take = if (clean.length <= room) clean.length else clean.clusters().lastOrNull { it.end <= room }?.end ?: 0
        if (take <= 0) return
        update(text.substring(0, cursor) + clean.substring(0, take) + text.substring(cursor), cursor + take)
    }

    /** Replaces the whole line with [value] (cleaned as by [insert]), the cursor at its end. */
    fun set(value: String) {
        update("", 0)
        insert(value)
    }

    fun backspace() {
        if (cursor == 0) return
        val start = previousBoundary()
        update(text.removeRange(start, cursor), start)
    }

    fun delete() {
        if (cursor >= text.length) return
        update(text.removeRange(cursor, nextBoundary()), cursor)
    }

    fun left() {
        cursor = previousBoundary()
    }

    fun right() {
        cursor = nextBoundary()
    }

    fun home() {
        cursor = 0
    }

    fun end() {
        cursor = text.length
    }

    /** Ctrl+U, as in readline: deletes from the start of the line to the cursor. */
    fun deleteToStart() {
        update(text.substring(cursor), 0)
    }

    /** Ctrl+W: deletes the whitespace-delimited word before the cursor. */
    fun deleteWordBefore() {
        var start = cursor
        while (start > 0 && text[start - 1].isWhitespace()) start--
        while (start > 0 && !text[start - 1].isWhitespace()) start--
        update(text.removeRange(start, cursor), start)
    }

    fun historyUp() {
        if (historyIndex == 0) return
        if (historyIndex == history.size) draft = text
        historyIndex--
        replace(history[historyIndex])
    }

    fun historyDown() {
        if (historyIndex >= history.size) return
        historyIndex++
        replace(if (historyIndex == history.size) draft else history[historyIndex])
    }

    /**
     * Forgets everything: the line being typed, the draft held behind the history, and the history
     * itself. For the emergency wipe, after which `ArrowUp` must not bring anything back.
     */
    fun reset() {
        history.clear()
        historyIndex = 0
        draft = ""
        replace("")
    }

    /** Returns the line and clears it, adding it to the history when it is not blank. */
    fun submit(): String {
        val line = text
        if (line.isNotBlank() && history.lastOrNull() != line) {
            history += line
            if (history.size > historyLimit) history.removeAt(0)
        }
        historyIndex = history.size
        draft = ""
        replace("")
        return line
    }

    /**
     * Applies [event] and returns whether it was used. Enter submits and passes a non-blank line
     * to [onSubmit]. Bound: printable characters, Backspace, Delete, Left/Right, Home/End, Up/Down
     * (history), Ctrl+A/E/U/W. Everything else, including other Ctrl and all Alt combinations
     * (`Alt+digit` and `Ctrl+]` are reserved for a host launcher, `Ctrl+C` exits), is left alone.
     */
    fun handleKey(event: KeyEvent, onSubmit: (String) -> Unit): Boolean {
        val key = event.key
        when {
            event.alt -> return false
            event.ctrl -> when (key) {
                "a" -> home()
                "e" -> end()
                "u" -> deleteToStart()
                "w" -> deleteWordBefore()
                else -> return false
            }
            key == "Enter" -> submit().let { if (it.isNotBlank()) onSubmit(it) }
            key == "Backspace" -> backspace()
            key == "Delete" -> delete()
            key == "ArrowLeft" -> left()
            key == "ArrowRight" -> right()
            key == "Home" -> home()
            key == "End" -> end()
            key == "ArrowUp" -> historyUp()
            key == "ArrowDown" -> historyDown()
            key.isTypedCharacter() -> insert(key)
            else -> return false
        }
        return true
    }

    private fun replace(value: String) {
        update(value, value.length)
    }

    /**
     * Every edit ends here: the line is re-cleaned in context (drop mode) so it always equals its
     * own sanitized form, and [newCursor] is carried to where it lands in the cleaned line.
     */
    private fun update(newText: String, newCursor: Int) {
        val clean = cleanTerminalText(newText, replace = false, mark = newCursor)
        if (clean.text != text) revision++
        text = clean.text
        cursor = clean.mark
    }

    private fun previousBoundary(): Int = text.clusters().lastOrNull { it.start < cursor }?.start ?: 0

    private fun nextBoundary(): Int = text.clusters().firstOrNull { it.end > cursor }?.end ?: text.length
}

/** Whether a key name is one typed character (one code point) rather than a named key like `Tab`. */
private fun String.isTypedCharacter(): Boolean {
    val single = length == 1 && !this[0].isSurrogate()
    val pair = length == 2 && this[0].isHighSurrogate() && this[1].isLowSurrogate()
    return (single || pair) && !this[0].isISOControl()
}
