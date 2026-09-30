package com.bitchat.tui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import com.jakewharton.mosaic.LocalRenderMode
import com.jakewharton.mosaic.LocalRepaint
import com.jakewharton.mosaic.LocalTerminalState
import com.jakewharton.mosaic.RenderMode
import com.jakewharton.mosaic.layout.KeyEvent
import com.jakewharton.mosaic.layout.onPreviewKeyEvent
import com.jakewharton.mosaic.layout.size
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.terminal.Terminal
import com.jakewharton.mosaic.ui.Box
import com.jakewharton.mosaic.ui.Column
import com.jakewharton.mosaic.ui.unit.IntSize
import kotlinx.coroutines.awaitCancellation

/**
 * The screen the body shows. [TuiApp] switches between them by key. [Dm] is a private chat opened
 * from [Peers], and [Notes] the notes of a place opened from [Locations]: `Esc` goes back to the
 * screen each was opened from, and `Tab` moves on from it as from that screen.
 */
enum class Mode { Chat, Peers, Dm, Locations, Notes, Settings, Wipe }

/**
 * The bitchat terminal UI's root: a [Header] row, the [body] for the current [mode], and a
 * one-line [Footer] of key hints for that mode, in a frame of exactly [size] cells.
 *
 * Keys: `Ctrl+P` peers, `Ctrl+G` locations, `Ctrl+S` settings, `Tab` next mode (chat, peers,
 * locations, settings) and `Shift+Tab` the one before, `Esc` back (to peers from a DM, else to
 * chat), `Ctrl+L` repaints the screen as it does in a shell, and `Ctrl+D` three times in a row
 * opens the emergency wipe (which then asks). A screen sees each key
 * first (through `screenKeys`) and the shell routes it only when the screen declines. Keys are
 * dispatched by [navigation]'s current mode, not by the last frame's tree, so a batch of keys that
 * changes the screen part way through still reaches the right screens (see [KeyRouter]).
 * Everything else is left unhandled on purpose: `Alt+digit` and `Ctrl+]` are reserved for a future
 * launcher that hosts this app, and an unhandled `Ctrl+C` is how Mosaic exits.
 *
 * The mode is hoisted in [navigation]; body callbacks that open a screen set it directly. The app
 * never exits on its own (see below).
 *
 * @param unreadDms How many peers have unread private messages; shown in the header when non-zero.
 * @param powBits The proof-of-work difficulty, or null while the setting is off; shown in the header.
 * @param size The frame, by default the terminal's (see [frameSize]); a host may pass a smaller area.
 * @param onRepaint What `Ctrl+L` does: by default it asks the running Mosaic to draw the next
 *   frame in full, which is what repairs a screen something else has written over. A host that
 *   draws the terminal itself may do its own thing instead.
 * @param body Draws the current mode within the given size. Mosaic does not clip children, so a
 *   body must stay within its size or it draws over the footer.
 */
@Composable
fun TuiApp(
    nickname: String,
    peerCount: Int,
    navigation: TuiNavigation,
    unreadDms: Int = 0,
    powBits: Int? = null,
    size: IntSize = frameSize(LocalTerminalState.current.size, LocalRenderMode.current),
    onRepaint: () -> Unit = LocalRepaint.current::request,
    body: @Composable (mode: Mode, size: IntSize) -> Unit,
) {
    // Mosaic returns as soon as the composition has no running effects, so hold one for as long as
    // the app is shown; the process ends with an unhandled Ctrl+C.
    LaunchedEffect(Unit) { awaitCancellation() }
    val bodySize = IntSize(size.width, size.height - HEADER_ROWS - FOOTER_ROWS)
    val router = remember(navigation) { KeyRouter(navigation) }
    val wipe = remember { WipeShortcut() }
    val footer = remember { FooterSlot() }
    val mode = navigation.mode
    Column(
        Modifier
            .size(size.width, size.height)
            .onPreviewKeyEvent { event ->
                // `Ctrl+L` repaints, as it does in a shell: the full-screen renderer sends only
                // the cells that changed, so a screen something else has written over would stay
                // wrong. Never routed on, whatever the screen is.
                if (isRepaintKey(event)) {
                    onRepaint()
                    return@onPreviewKeyEvent true
                }
                when (wipe.consume(event)) {
                    // Nothing is erased here: the third press only opens the screen that asks.
                    WipeShortcut.Consumed.Opens -> {
                        navigation.mode = Mode.Wipe
                        true
                    }
                    WipeShortcut.Consumed.Counted -> true
                    WipeShortcut.Consumed.No -> router.dispatch(event)
                }
            },
    ) {
        Header(nickname, peerCount, size.width, unreadDms, powBits)
        Box(Modifier.size(bodySize.width, bodySize.height)) {
            val scope = remember(router, mode) { KeyScope(router, mode) }
            CompositionLocalProvider(LocalKeyScope provides scope, LocalFooterSlot provides footer) { body(mode, bodySize) }
        }
        Footer(footer.hints ?: footerHints(mode), size.width)
    }
    // Runs once the body for [mode] is composed and has registered its handlers: replays held keys.
    LaunchedEffect(router, mode) { router.mounted(mode) }
}

/**
 * The frame for a terminal of [terminal] size in [renderMode]: its columns, and all of its rows
 * on the alternate screen ([RenderMode.FullScreen], which addresses every row), but one fewer in
 * [RenderMode.Inline], where Mosaic ends every frame with a newline and a full-height frame would
 * scroll the screen by a line. Clamped to [MIN_FRAME_COLUMNS] x [MIN_FRAME_ROWS]; a smaller
 * terminal wraps or scrolls.
 */
fun frameSize(terminal: Terminal.Size, renderMode: RenderMode = RenderMode.Inline): IntSize = IntSize(
    terminal.columns.coerceAtLeast(MIN_FRAME_COLUMNS),
    (if (renderMode == RenderMode.FullScreen) terminal.rows else terminal.rows - 1).coerceAtLeast(MIN_FRAME_ROWS),
)

/** `Ctrl+L`: the shell's repaint, handled by [TuiApp] itself rather than by a screen. */
internal fun isRepaintKey(event: KeyEvent): Boolean = event.ctrl && !event.alt && event.key == "l"

/** The mode [event] switches to from [mode], or null when the shell leaves the key alone. */
internal fun routeKey(mode: Mode, event: KeyEvent): Mode? = when {
    event.alt -> null
    event.ctrl && event.key == "p" -> Mode.Peers
    event.ctrl && event.key == "s" -> Mode.Settings
    event.ctrl && event.key == "g" -> Mode.Locations
    event.ctrl -> null
    event.key == "Escape" && mode == Mode.Dm -> Mode.Peers
    event.key == "Escape" && mode == Mode.Notes -> Mode.Locations
    event.key == "Escape" && mode == Mode.Wipe -> Mode.Chat
    event.key == "Escape" && mode != Mode.Chat -> Mode.Chat
    // The same event whether the terminal sends the legacy `CSI Z` or Kitty's `CSI 9;2u`.
    event.key == "Tab" -> tabbed(mode, if (event.shift) -1 else 1)
    else -> null
}

/** The mode [by] places from [mode] in [TabOrder], wrapping at either end. */
private fun tabbed(mode: Mode, by: Int): Mode =
    TabOrder[(TabOrder.indexOf(tabHome(mode)) + by).mod(TabOrder.size)]

/** The tabbed screen a mode belongs to: the one it was opened from, for those that are not in the order. */
private fun tabHome(mode: Mode): Mode = when (mode) {
    Mode.Dm -> Mode.Peers
    Mode.Notes -> Mode.Locations
    Mode.Wipe -> Mode.Chat
    else -> mode
}

/** The modes `Tab` cycles through; [Mode.Dm] and [Mode.Notes] are reached from their own screens. */
private val TabOrder = listOf(Mode.Chat, Mode.Peers, Mode.Locations, Mode.Settings)

/** The narrowest frame: fits the shortest footer hint and a truncated header. */
const val MIN_FRAME_COLUMNS = 20

/** The shortest frame: header, three body rows, footer. */
const val MIN_FRAME_ROWS = 5

private const val HEADER_ROWS = 1
private const val FOOTER_ROWS = 1
