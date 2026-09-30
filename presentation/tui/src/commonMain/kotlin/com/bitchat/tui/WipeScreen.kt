package com.bitchat.tui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.jakewharton.mosaic.layout.KeyEvent
import com.jakewharton.mosaic.layout.size
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.ui.Column
import com.jakewharton.mosaic.ui.Text
import com.jakewharton.mosaic.ui.TextStyle
import com.jakewharton.mosaic.ui.unit.IntSize

/**
 * The emergency wipe, as the Compose apps' triple tap on the title: everything the app knows about
 * the user goes, and a new identity takes its place.
 *
 * Nothing happens on the keystrokes alone. They only open this screen, which says what will go and
 * then waits for the word to be typed in full: three keys in a row are easy to hit by accident, and
 * none of this can be undone. `Esc`, or anything else than the word, leaves without erasing.
 */
@Composable
fun WipeScreen(
    size: IntSize,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    editor: LineEditor = remember { LineEditor(maxLength = WIPE_WORD.length + 1) },
) {
    val theme = LocalTuiTheme.current
    fun submit(line: String) {
        if (line.trim().equals(WIPE_WORD, ignoreCase = true)) onConfirm() else onCancel()
    }

    // Rows by priority: the prompt, the question, then as much of the list as fits.
    val budget = RowBudget(size.height)
    val promptRows = budget.take(1)
    val titleRows = budget.take(1)
    val listRows = budget.take(WIPE_LOSES.size)

    Column(
        modifier
            .size(size.width, size.height)
            .screenKeys { event ->
                if (event.ctrl || event.alt) return@screenKeys false
                editor.handleKey(event, ::submit)
            },
    ) {
        if (titleRows > 0) Text(" Erase everything on this device?".truncateCells(size.width), color = theme.error, textStyle = TextStyle.Bold)
        for (line in WIPE_LOSES.take(listRows)) Text("   $line".truncateCells(size.width), color = theme.foreground)
        if (promptRows > 0) {
            LinePrompt(editor, size.width, onSubmit = ::submit, prompt = "type $WIPE_WORD to erase, Esc to keep> ", handlesKeys = false)
        }
    }
}

/** What the user must type to go through with it. */
const val WIPE_WORD = "wipe"

/** What the wipe takes, in the order a reader cares about it. */
val WIPE_LOSES = listOf(
    "your identity keys: you will be someone else afterwards",
    "every message, channel and private chat on this device",
    "your nickname, favourites, blocks and bookmarks",
    "this cannot be undone, and nothing is backed up",
)

/**
 * `Ctrl+D` three times in a row opens the wipe screen, as a triple tap does in the Compose apps.
 * In a row, not within a time: any other key starts the count again, so there is no clock to get
 * wrong and nothing can creep up on the third press minutes later.
 */
internal class WipeShortcut {
    private var pressed = 0

    /** Whether [event] was the third `Ctrl+D` in a row. Every `Ctrl+D` is swallowed either way. */
    fun consume(event: KeyEvent): Consumed = when {
        !isWipeKey(event) -> {
            pressed = 0
            Consumed.No
        }
        ++pressed < PRESSES -> Consumed.Counted
        else -> {
            pressed = 0
            Consumed.Opens
        }
    }

    enum class Consumed { No, Counted, Opens }
}

private fun isWipeKey(event: KeyEvent) = event.ctrl && !event.alt && event.key == "d"

private const val PRESSES = 3
