package com.bitchat.design.core

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import com.bitchat.design.chat.MessagesList
import com.bitchat.domain.chat.model.BitchatMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

class DismissKeyboardOnTapOutsideTest {
    /** Rows that take taps themselves, as the message rows do. */
    @Composable
    private fun TapRows(listState: LazyListState, keepOnTap: Boolean, onRowTap: () -> Unit, modifier: Modifier) {
        val gesture = LocalKeyboardDismissGesture.current
        LazyColumn(state = listState, modifier = modifier.testTag("list")) {
            items((0 until 50).toList()) { index ->
                Text(
                    text = "row $index",
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("row-$index")
                        .pointerInput(index) {
                            detectTapGestures(onTap = {
                                onRowTap()
                                if (keepOnTap) gesture.keep()
                            })
                        },
                )
            }
        }
    }

    @Composable
    private fun Screen(
        keyboardShowing: () -> Boolean,
        onSend: () -> Unit,
        list: @Composable (Modifier) -> Unit,
    ) {
        MaterialTheme {
            DismissKeyboardOnTapOutside(keyboardShowing = keyboardShowing) {
                Column {
                    list(Modifier.weight(1f))
                    Row(modifier = Modifier.keepKeyboardOnTap()) {
                        var text by remember { mutableStateOf("") }
                        BasicTextField(
                            value = text,
                            onValueChange = { text = it },
                            modifier = Modifier.testTag("field"),
                        )
                        // On desktop a clickable takes focus when clicked, which would hide what the dismisser does.
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .testTag("send")
                                .focusProperties { canFocus = false }
                                .clickable { onSend() }
                        )
                    }
                }
            }
        }
    }

    private fun SemanticsNodeInteraction.focusAndCheck() {
        performClick()
        assertIsFocused()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `a tap on a row that handles taps itself clears focus and the row still gets the tap`() = runComposeUiTest {
        var taps = 0
        setContent {
            Screen(keyboardShowing = { true }, onSend = {}) { modifier ->
                TapRows(rememberLazyListState(), keepOnTap = false, onRowTap = { taps++ }, modifier = modifier)
            }
        }
        onNodeWithTag("field").focusAndCheck()

        onNodeWithTag("row-0").performClick()

        onNodeWithTag("field").assertIsNotFocused()
        assertEquals(1, taps)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `a tap on the send box leaves focus alone and the click is delivered`() = runComposeUiTest {
        var sends = 0
        setContent {
            Screen(keyboardShowing = { true }, onSend = { sends++ }) { modifier ->
                TapRows(rememberLazyListState(), keepOnTap = false, onRowTap = {}, modifier = modifier)
            }
        }
        onNodeWithTag("field").focusAndCheck()

        onNodeWithTag("send").performClick()

        onNodeWithTag("field").assertIsFocused()
        assertEquals(1, sends)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `a tap on the field itself leaves it focused`() = runComposeUiTest {
        setContent {
            Screen(keyboardShowing = { true }, onSend = {}) { modifier ->
                TapRows(rememberLazyListState(), keepOnTap = false, onRowTap = {}, modifier = modifier)
            }
        }
        onNodeWithTag("field").focusAndCheck()

        onNodeWithTag("field").performClick()

        onNodeWithTag("field").assertIsFocused()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `a row that asks to keep the keyboard leaves focus alone`() = runComposeUiTest {
        var taps = 0
        setContent {
            Screen(keyboardShowing = { true }, onSend = {}) { modifier ->
                TapRows(rememberLazyListState(), keepOnTap = true, onRowTap = { taps++ }, modifier = modifier)
            }
        }
        onNodeWithTag("field").focusAndCheck()

        onNodeWithTag("row-0").performClick()

        onNodeWithTag("field").assertIsFocused()
        assertEquals(1, taps)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `dragging the list clears focus`() = runComposeUiTest {
        setContent {
            Screen(keyboardShowing = { true }, onSend = {}) { modifier ->
                TapRows(rememberLazyListState(), keepOnTap = false, onRowTap = {}, modifier = modifier)
            }
        }
        onNodeWithTag("field").focusAndCheck()

        onNodeWithTag("list").performTouchInput { swipeUp() }

        onNodeWithTag("field").assertIsNotFocused()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `a scroll the app makes itself leaves focus alone`() = runComposeUiTest {
        val listState = LazyListState()
        setContent {
            Screen(keyboardShowing = { true }, onSend = {}) { modifier ->
                TapRows(listState, keepOnTap = false, onRowTap = {}, modifier = modifier)
            }
        }
        onNodeWithTag("field").focusAndCheck()

        listState.scrollToItem(30)
        waitForIdle()

        onNodeWithTag("row-0").assertDoesNotExist()
        onNodeWithTag("field").assertIsFocused()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `with no keyboard showing a tap on a row leaves focus alone`() = runComposeUiTest {
        var taps = 0
        setContent {
            Screen(keyboardShowing = { false }, onSend = {}) { modifier ->
                TapRows(rememberLazyListState(), keepOnTap = false, onRowTap = { taps++ }, modifier = modifier)
            }
        }
        onNodeWithTag("field").focusAndCheck()

        onNodeWithTag("row-0").performClick()

        onNodeWithTag("field").assertIsFocused()
        assertEquals(1, taps)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `a tap on a message in the real message list clears focus`() = runComposeUiTest {
        val message = BitchatMessage(
            id = "m1",
            sender = "alice",
            content = "keyboard dismiss probe",
            timestamp = Instant.fromEpochSeconds(12 * 3600L),
        )
        setContent {
            Screen(keyboardShowing = { true }, onSend = {}) { modifier ->
                MessagesList(
                    messages = listOf(message),
                    currentUserNickname = "me",
                    myPeerID = "peer",
                    onTeleport = {},
                    modifier = modifier,
                )
            }
        }
        onNodeWithTag("field").focusAndCheck()

        onNodeWithText("keyboard dismiss probe", substring = true).performClick()

        onNodeWithTag("field").assertIsNotFocused()
    }
}
