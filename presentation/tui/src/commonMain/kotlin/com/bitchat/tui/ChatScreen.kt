package com.bitchat.tui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.bitchat.domain.chat.model.BitchatMessage
import com.bitchat.viewvo.chat.CommandSuggestion
import com.bitchat.viewvo.chat.InputSuggestion
import com.bitchat.viewvo.chat.inputSuggestions
import com.jakewharton.mosaic.layout.height
import com.jakewharton.mosaic.layout.size
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.text.AnnotatedString
import com.jakewharton.mosaic.ui.Color
import com.jakewharton.mosaic.ui.Column
import com.jakewharton.mosaic.ui.Spacer
import com.jakewharton.mosaic.ui.Text
import com.jakewharton.mosaic.ui.TextStyle
import com.jakewharton.mosaic.ui.unit.IntSize
import kotlin.time.Instant

/**
 * A chat: an optional bold [title] row (in [titleColor], by default the theme's accent; the
 * binding passes the colour the channel is badged with), the [messages] bottom-anchored and
 * wrapped by cells (see
 * [messageLines]), an optional [errorMessage] row, and the input line. Used for channels and DMs.
 *
 * Keys: `PgUp`/`PgDn` scroll by a page; while scrolled back a marker takes the last list row and
 * `End` returns to live (when live, `End` goes to the input line). Scrollback is held by message
 * ID, so messages arriving below or history loaded above do not move the view, and a cleared or
 * replaced conversation returns to live. Only the messages on screen are laid out, and each once
 * per shape (see `MessageLayouts`), so a long history costs nothing per redraw.
 * `Enter` passes the line, uninterpreted, to [onSend]: slash
 * commands are the view model's business (`ChatViewModel.sendMessage`), never this screen's.
 *
 * @param size The cells this screen may use (the body size [TuiApp] passes).
 * @param editor The input line; hoist it to keep a half-typed line across mode switches.
 * @param myPeerId This device's peer ID, to recognise own messages beyond the [nickname].
 * @param mediaSizes Byte sizes of image and voice messages by message ID (file packets carry theirs).
 * @param formatTime Message time as shown; UTC `HH:mm` by default. Pass a stable function (a
 *   top-level reference or a remembered lambda): it is part of each message's layout cache key.
 * @param sendHold While it returns non-null, `Enter` keeps the line instead of passing it to
 *   [onSend], and the prompt shows the text it returns (say, "opening... " while a DM is not yet
 *   open). Editing still works. Read when each key arrives, so back it with snapshot state.
 * @param commands Slash commands to suggest while the input starts with `/` (the chat's
 *   `channelCommandSuggestions`, as the Compose chat offers them); none for a DM. While the list is
 *   open, above the input: `Tab` completes the highlighted command, `Up`/`Down` move the highlight
 *   (history otherwise), `Esc` closes the list until the input changes; the footer says so.
 * @param people The display names of whoever is in this conversation, as the peers list shows
 *   them. Once a command that takes a nickname has been typed (`/hug `, `/msg bo`), the same list
 *   offers these instead of commands, filtered by what has been typed of the name: there is no
 *   other way in a terminal to pick a person out of a chat one is reading.
 */
@Composable
fun ChatScreen(
    messages: List<BitchatMessage>,
    nickname: String,
    size: IntSize,
    onSend: (String) -> Unit,
    modifier: Modifier = Modifier,
    title: String? = null,
    editor: LineEditor = remember { LineEditor() },
    myPeerId: String? = null,
    mediaSizes: Map<String, Long> = emptyMap(),
    errorMessage: String? = null,
    focused: Boolean = true,
    formatTime: (Instant) -> String = ::utcClockTime,
    sendHold: () -> String? = NoHold,
    commands: List<CommandSuggestion> = emptyList(),
    people: List<String> = emptyList(),
    titleColor: Color? = null,
) {
    ChatScreenContent(
        messages, nickname, size, onSend, modifier, title, editor, myPeerId, mediaSizes, errorMessage, focused, formatTime,
        layouts = remember { MessageLayouts() },
        sendHold = sendHold,
        commands = commands,
        people = people,
        titleColor = titleColor,
    )
}

/** [ChatScreen] with its layout cache passed in, so tests can count the layout work. */
@Composable
internal fun ChatScreenContent(
    messages: List<BitchatMessage>,
    nickname: String,
    size: IntSize,
    onSend: (String) -> Unit,
    modifier: Modifier = Modifier,
    title: String? = null,
    editor: LineEditor = remember { LineEditor() },
    myPeerId: String? = null,
    mediaSizes: Map<String, Long> = emptyMap(),
    errorMessage: String? = null,
    focused: Boolean = true,
    formatTime: (Instant) -> String = ::utcClockTime,
    layouts: MessageLayouts,
    sendHold: () -> String? = NoHold,
    commands: List<CommandSuggestion> = emptyList(),
    people: List<String> = emptyList(),
    titleColor: Color? = null,
) {
    val index = remember { MessageIndex() }
    val theme = LocalTuiTheme.current
    val lines = ChatLines(messages, layouts, index, nickname, myPeerId, size.width, LocalConsoleSafe.current, mediaSizes, formatTime, theme)
    val suggestions = remember { Suggestions() }
    // Derived, so typing recomposes this screen only when the number of suggestion rows changes,
    // not on every keystroke: the list itself reads the line, in its own composable below.
    val suggestionRowsWanted by remember(suggestions, commands, people, editor, focused) {
        derivedStateOf { if (focused) suggestions.shown(commands, people, editor).size.coerceAtMost(MAX_SUGGESTION_ROWS) else 0 }
    }
    // Rows by priority: the input line, open suggestions, an error, one message line, the title, more messages.
    val budget = RowBudget(size.height)
    val promptRows = budget.take(1)
    val suggestionRows = budget.take(suggestionRowsWanted)
    val errorRows = if (errorMessage != null) budget.take(1) else 0
    val listMinimum = budget.take(1)
    val titleRows = if (title != null) budget.take(1) else 0
    val listRows = listMinimum + budget.take(budget.left)
    val page = (listRows - 1).coerceAtLeast(1)
    val scroll = remember { ChatScroll() }
    // A scrollback anchor whose message is gone (cleared, replaced) returns to live for good.
    val anchored = lines.resolve(scroll.anchor)
    if (scroll.anchor != null && anchored == null) scroll.anchor = null
    val bottom = anchored?.let { lines.clampBottom(it, listRows) }?.takeIf { it != lines.last() }
    layouts.trim(messages)

    Column(
        modifier
            .size(size.width, size.height)
            // One handler for scrolling and the input line, reading state when each key arrives:
            // Mosaic delivers every key since the last frame before recomposing.
            .screenKeys { event ->
                val plain = !event.ctrl && !event.alt
                // Read now, not at composition: several keys can arrive before the next frame.
                val open = if (focused && plain) suggestions.shown(commands, people, editor) else emptyList()
                when {
                    // Shift+Tab is the shell's "previous screen", never a completion.
                    open.isNotEmpty() && event.key == "Tab" && !event.shift -> {
                        editor.set(open[suggestions.highlighted(open, editor)].line)
                        true
                    }
                    open.isNotEmpty() && (event.key == "ArrowUp" || event.key == "ArrowDown") -> {
                        suggestions.move(open, editor, if (event.key == "ArrowUp") -1 else 1)
                        true
                    }
                    open.isNotEmpty() && event.key == "Escape" -> {
                        suggestions.dismiss(editor)
                        true
                    }
                    plain && event.key == "End" && scroll.anchor != null -> {
                        scroll.anchor = null
                        true
                    }
                    plain && event.key == "PageUp" -> {
                        scroll.anchor = lines.pageUp(scroll.anchor, listRows, page)
                        true
                    }
                    plain && event.key == "PageDown" -> {
                        scroll.anchor = lines.pageDown(scroll.anchor, page)
                        true
                    }
                    // Held: the line stays in the editor until sending is possible.
                    focused && plain && event.key == "Enter" && sendHold() != null -> true
                    focused -> editor.handleKey(event, onSend)
                    else -> false
                }
            },
    ) {
        if (titleRows > 0) {
            Text(
                " ${displayText(title ?: "")}".truncateCells(size.width),
                color = titleColor ?: theme.accent,
                textStyle = TextStyle.Bold,
            )
        }
        MessageList(lines.visible(bottom, listRows), listRows, scrolled = bottom != null)
        if (errorRows > 0) Text(displayText(errorMessage ?: "").truncateCells(size.width), color = theme.error)
        if (suggestionRows > 0) SuggestionList(suggestions, commands, people, editor, suggestionRows, size.width)
        if (promptRows > 0) LinePrompt(editor, size.width, onSend, prompt = sendHold() ?: "> ", focused = focused, handlesKeys = false)
    }
}

/** Footer hints while command suggestions are open. */
private val SuggestionHints = listOf(
    KeyHint("Tab", "complete"),
    KeyHint("Up/Down", "choose"),
    KeyHint("Esc", "close"),
    KeyHint("Enter", "send"),
)

/** The most suggestion rows shown at once; the list scrolls to keep the highlight in view. */
private const val MAX_SUGGESTION_ROWS = 6

/**
 * Which suggestion is highlighted, and whether the list was closed with `Esc`. Both are held
 * against the line they were chosen for ([LineEditor.revision], so retyping the same text counts
 * as a new line): any edit puts the highlight back at the top and opens the list again.
 */
internal class Suggestions {
    private var chosen by mutableIntStateOf(0)
    private var chosenAt by mutableIntStateOf(-1)
    private var closedAt by mutableIntStateOf(-1)

    /**
     * The rows to show for the line -- the commands matching it, or the people matching the
     * nickname begun after one that takes a nickname -- unless the list was closed since the line
     * was last edited. Both come from `inputSuggestions`, so the Compose chat can offer the same.
     */
    fun shown(commands: List<CommandSuggestion>, people: List<String>, editor: LineEditor): List<InputSuggestion> =
        if (commands.isEmpty() || editor.revision == closedAt) emptyList() else inputSuggestions(commands, people, editor.text)

    fun highlighted(shown: List<InputSuggestion>, editor: LineEditor): Int =
        if (chosenAt == editor.revision) chosen.coerceIn(0, (shown.size - 1).coerceAtLeast(0)) else 0

    fun move(shown: List<InputSuggestion>, editor: LineEditor, by: Int) {
        chosen = (highlighted(shown, editor) + by).mod(shown.size)
        chosenAt = editor.revision
    }

    fun dismiss(editor: LineEditor) {
        closedAt = editor.revision
    }
}

/**
 * [rows] rows of what matches the line, the highlighted one in reverse video, scrolled to keep it
 * in view. Reads the line itself, so typing redraws these rows and the prompt, not the
 * conversation above them. Also puts the list's keys in the footer. Peer names are sanitized: a
 * nickname row is the only suggestion whose text came from somebody else.
 */
@Composable
private fun SuggestionList(
    suggestions: Suggestions,
    commands: List<CommandSuggestion>,
    people: List<String>,
    editor: LineEditor,
    rows: Int,
    width: Int,
) {
    FooterHints(SuggestionHints)
    val theme = LocalTuiTheme.current
    val consoleSafe = LocalConsoleSafe.current
    val matches = suggestions.shown(commands, people, editor)
    val highlighted = suggestions.highlighted(matches, editor)
    val first = (highlighted - rows + 1).coerceAtLeast(0)
    for (i in first until (first + rows).coerceAtMost(matches.size)) {
        val match = matches[i]
        val detail = if (match.detail.isBlank()) "" else "  " + displayText(match.detail, consoleSafe)
        val row = " " + displayText(match.label, consoleSafe) + detail
        if (i == highlighted) Bar(row, width) else Text(row.truncateCells(width), color = theme.dim, textStyle = TextStyle.Dim)
    }
}

/** The default `sendHold`: `Enter` always sends. */
internal val NoHold: () -> String? = { null }

/** Exactly [rows] rows: blank rows on top, then [visible]; while [scrolled], a marker takes the last row. */
@Composable
private fun MessageList(visible: List<AnnotatedString>, rows: Int, scrolled: Boolean) {
    if (rows <= 0) return
    val theme = LocalTuiTheme.current
    Column {
        if (visible.size < rows) Spacer(Modifier.height(rows - visible.size))
        visible.forEachIndexed { index, line ->
            // The theme's foreground is the line's default; the spans a message line carries
            // (a dim time, a coloured sender) override it.
            if (scrolled && index == visible.lastIndex) {
                Text(MORE_BELOW, color = theme.dim, textStyle = TextStyle.Dim)
            } else {
                Text(line, color = theme.foreground)
            }
        }
    }
}

/**
 * Laid-out lines per message, kept while everything that shapes them is unchanged: the message
 * itself (id, content, sender, type, ...), the nickname and peer ID that make it own, the width,
 * console-safe mode, its media size, the time formatter and the theme. A redraw therefore lays out
 * only messages it has not seen in this shape; [layoutCalls] counts those layouts. [TuiTheme] is a
 * value, so an unchanged theme keeps the cache warm however often it is rebuilt.
 */
internal class MessageLayouts {
    var layoutCalls = 0
        private set
    private val cache = HashMap<String, Pair<LayoutKey, List<AnnotatedString>>>()

    fun lines(key: LayoutKey): List<AnnotatedString> {
        cache[key.message.id]?.let { (cachedKey, lines) -> if (cachedKey == key) return lines }
        layoutCalls++
        val lines = with(key) { messageLines(message, nickname, myPeerId, width, consoleSafe, mediaSize, formatTime, theme) }
        cache[key.message.id] = key to lines
        return lines
    }

    /** Drops layouts of messages no longer in [messages], once they outnumber them noticeably. */
    fun trim(messages: List<BitchatMessage>) {
        if (cache.size <= messages.size + TRIM_SLACK) return
        val ids = messages.mapTo(HashSet()) { it.id }
        cache.keys.retainAll(ids)
    }
}

internal data class LayoutKey(
    val message: BitchatMessage,
    val nickname: String,
    val myPeerId: String?,
    val width: Int,
    val consoleSafe: Boolean,
    val mediaSize: Long?,
    val formatTime: (Instant) -> String,
    val theme: TuiTheme,
)

/** A line of the conversation: message index and line within that message. */
internal data class LinePos(val message: Int, val line: Int)

/** Scrollback, held by the bottom line on screen: a message ID and a line within it. */
internal data class ScrollAnchor(val messageId: String, val line: Int)

/**
 * The conversation's lines, laid out on demand. Everything here walks outwards from the bottom line
 * on screen, so it touches only the messages on screen (and a page more when paging), never the
 * whole history: no line counts or prefix sums over all messages are needed.
 */
internal class ChatLines(
    private val messages: List<BitchatMessage>,
    private val layouts: MessageLayouts,
    private val index: MessageIndex,
    private val nickname: String,
    private val myPeerId: String?,
    private val width: Int,
    private val consoleSafe: Boolean,
    private val mediaSizes: Map<String, Long>,
    private val formatTime: (Instant) -> String,
    private val theme: TuiTheme = DarkTuiTheme,
) {
    fun lines(index: Int): List<AnnotatedString> {
        val message = messages[index]
        return layouts.lines(LayoutKey(message, nickname, myPeerId, width, consoleSafe, mediaSizes[message.id], formatTime, theme))
    }

    fun last(): LinePos? = if (messages.isEmpty()) null else LinePos(messages.lastIndex, lines(messages.lastIndex).lastIndex)

    private fun prev(at: LinePos): LinePos? = when {
        at.line > 0 -> at.copy(line = at.line - 1)
        at.message > 0 -> LinePos(at.message - 1, lines(at.message - 1).lastIndex)
        else -> null
    }

    private fun next(at: LinePos): LinePos? = when {
        at.line < lines(at.message).lastIndex -> at.copy(line = at.line + 1)
        at.message < messages.lastIndex -> LinePos(at.message + 1, 0)
        else -> null
    }

    /** Where [anchor] is now, or null when live or when its message is gone. */
    fun resolve(anchor: ScrollAnchor?): LinePos? {
        if (anchor == null) return null
        val at = index.of(messages, anchor.messageId)
        if (at < 0) return null
        return LinePos(at, anchor.line.coerceIn(0, lines(at).lastIndex))
    }

    /** [bottom], or lower if the view would otherwise start above the first line. */
    fun clampBottom(bottom: LinePos, rows: Int): LinePos {
        var top = bottom
        repeat(rows - 1) { top = prev(top) ?: return firstBottom(rows) }
        return bottom
    }

    /** The bottom line of a view that starts at the first line, or the last line if there are fewer. */
    private fun firstBottom(rows: Int): LinePos {
        var at = LinePos(0, 0)
        repeat(rows - 1) { at = next(at) ?: return at }
        return at
    }

    /** Up to [rows] lines ending at [bottom] (the last line when null), oldest first. */
    fun visible(bottom: LinePos?, rows: Int): List<AnnotatedString> {
        var at: LinePos = bottom ?: last() ?: return emptyList()
        val collected = ArrayDeque<AnnotatedString>()
        if (rows <= 0) return collected
        while (true) {
            collected.addFirst(lines(at.message)[at.line])
            if (collected.size == rows) break // Not one message more than the screen shows.
            at = prev(at) ?: break
        }
        return collected
    }

    /** The anchor one page ([page] lines) further back, clamped at the first line; null means live. */
    fun pageUp(anchor: ScrollAnchor?, rows: Int, page: Int): ScrollAnchor? {
        var at = resolve(anchor) ?: last() ?: return null
        repeat(page) { at = prev(at) ?: return@repeat }
        return anchorAt(clampBottom(at, rows))
    }

    /** The anchor one page further forward; reaching the last line means live. */
    fun pageDown(anchor: ScrollAnchor?, page: Int): ScrollAnchor? {
        var at = resolve(anchor) ?: return null
        repeat(page) { at = next(at) ?: return null }
        return anchorAt(at)
    }

    private fun anchorAt(at: LinePos): ScrollAnchor? =
        if (at == last()) null else ScrollAnchor(messages[at.message].id, at.line)
}

/**
 * Message IDs to positions, so finding the scrollback anchor reads one message, not the history.
 * Rebuilt when the list instance changes, or when a lookup finds it stale (a list changed in place);
 * the last message with an ID wins, as in the list's own order.
 */
internal class MessageIndex {
    private var source: List<BitchatMessage>? = null
    private val positions = HashMap<String, Int>()

    /** The position of the message with [id] in [messages], or -1. */
    fun of(messages: List<BitchatMessage>, id: String): Int {
        if (source !== messages) rebuild(messages)
        positions[id]?.let { at -> if (at < messages.size && messages[at].id == id) return at }
        rebuild(messages)
        return positions[id] ?: -1
    }

    private fun rebuild(messages: List<BitchatMessage>) {
        source = messages
        positions.clear()
        messages.forEachIndexed { at, message -> positions[message.id] = at }
    }
}

/** The scrollback anchor, or null while live. */
private class ChatScroll {
    var anchor by mutableStateOf<ScrollAnchor?>(null)
}

private const val MORE_BELOW = "-- more below (End) --"
private const val TRIM_SLACK = 256

