package com.bitchat.tui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import com.jakewharton.mosaic.layout.KeyEvent
import com.jakewharton.mosaic.layout.onKeyEvent
import com.jakewharton.mosaic.modifier.Modifier

/**
 * Which screen [TuiApp] shows. Hoisted, and changed synchronously: a body callback that opens a
 * screen (say, a DM from the peers list) sets [mode] right away, and the keys still waiting in the
 * same batch are then routed to that screen, not to the one on display. Set it on the thread that
 * runs the UI: setting it back to the screen on display replays held keys at once.
 */
class TuiNavigation(initial: Mode = Mode.Chat) {
    private var current by mutableStateOf(initial)
    private val listeners = ArrayList<(from: Mode, to: Mode) -> Unit>()

    /** Told of every change, by the [KeyRouter] of the [TuiApp] showing this navigation. */
    internal var onChange: (() -> Unit)? = null

    var mode: Mode
        get() = current
        set(value) {
            val from = current
            current = value
            if (from != value) for (listener in listeners.toList()) listener(from, value)
            onChange?.invoke()
        }

    /**
     * Calls [listener] synchronously on every actual change of [mode], before any held key is
     * replayed: what it does on leaving a screen happens before the next key in the batch.
     */
    fun addModeListener(listener: (from: Mode, to: Mode) -> Unit) {
        listeners += listener
    }
}

/**
 * Routes keys for [TuiApp] without trusting the node tree of the last frame.
 *
 * Mosaic hands every key that arrived since the last frame to the tree composed for that frame,
 * before recomposing. When a key changes the screen, the rest of the batch would reach the old
 * screen's handlers. So [TuiApp] takes every key first (a preview handler at its root) and sends
 * it to the handlers the screen for the current [TuiNavigation.mode] registered. While that
 * screen is not mounted yet, keys wait in a queue and are replayed, in order, once it is; if one
 * of them changes the screen again, the rest wait again. When the mode is set back to the mounted
 * screen before another one is composed, the held keys are replayed right then. Keys a launcher or
 * Mosaic itself needs (any `Alt` key, `Ctrl+C`, `Ctrl+]`) are never queued: they go on unhandled.
 */
internal class KeyRouter(private val navigation: TuiNavigation) {
    private val handlers = HashMap<Mode, MutableList<(KeyEvent) -> Boolean>>()
    private val queue = ArrayDeque<KeyEvent>()
    private var mounted: Mode? = null

    /** True while a handler runs: a mode change inside one must not replay later keys before it ends. */
    private var handling = false

    init {
        navigation.onChange = ::flush
    }

    /** Returns whether [event] was used (or queued); false lets it go on to the host. */
    fun dispatch(event: KeyEvent): Boolean {
        flush() // Normally a no-op: mode changes replay held keys themselves.
        if (queue.isNotEmpty() || mounted != navigation.mode) {
            if (event.isHostKey()) return false
            queue.addLast(event)
            return true
        }
        return handle(event)
    }

    /** The screen for [mode] has been composed and has registered its handlers. */
    fun mounted(mode: Mode) {
        mounted = mode
        flush()
    }

    /** Replays held keys, in order, while the mode is the mounted screen's. */
    private fun flush() {
        if (handling) return
        while (queue.isNotEmpty() && mounted == navigation.mode) handle(queue.removeFirst())
    }

    fun register(mode: Mode, handler: (KeyEvent) -> Boolean) {
        handlers.getOrPut(mode) { ArrayList() } += handler
    }

    fun unregister(mode: Mode, handler: (KeyEvent) -> Boolean) {
        handlers[mode]?.remove(handler)
    }

    private fun handle(event: KeyEvent): Boolean {
        handling = true
        try {
            val mode = navigation.mode
            if (handlers[mode]?.any { it(event) } == true) return true
            val next = routeKey(mode, event) ?: return false
            navigation.mode = next
            return true
        } finally {
            handling = false
        }
    }

    private fun KeyEvent.isHostKey() = alt || (ctrl && (key == "c" || key == "]"))
}

/** The router and the mode a body was composed for, provided by [TuiApp] to its body. */
internal class KeyScope(private val router: KeyRouter, private val mode: Mode) {
    fun register(handler: (KeyEvent) -> Boolean) = router.register(mode, handler)
    fun unregister(handler: (KeyEvent) -> Boolean) = router.unregister(mode, handler)
}

internal val LocalKeyScope: ProvidableCompositionLocal<KeyScope?> = staticCompositionLocalOf { null }

/**
 * A screen's key handler. Inside [TuiApp] it is registered with the app's [KeyRouter], so keys
 * reach it by the synchronous screen state rather than the last frame's tree; on its own (tests,
 * other hosts) it is attached to this node. [handler] should read its state when the key arrives:
 * several keys can come before the next recomposition.
 */
@Composable
internal fun Modifier.screenKeys(handler: (KeyEvent) -> Boolean): Modifier {
    val scope = LocalKeyScope.current
    val latest by rememberUpdatedState(handler)
    if (scope == null) return onKeyEvent { latest(it) }
    DisposableEffect(scope) {
        val registered: (KeyEvent) -> Boolean = { latest(it) }
        scope.register(registered)
        onDispose { scope.unregister(registered) }
    }
    return this
}
