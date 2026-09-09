package com.bitchat.design.core

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager

@Composable
fun DismissKeyboardOnTapOutside(content: @Composable () -> Unit) {
    val focusManager = LocalFocusManager.current
    Box(
        modifier = Modifier
            .fillMaxSize()
            // Deliberately a raw tap gesture rather than Modifier.clickable: clickable also installs
            // a key handler that treats Spacebar/Enter as a click, and unhandled key events from a
            // focused child bubble up to it. With a physical keyboard that made every space typed in
            // a text field clear the focus.
            .pointerInput(Unit) {
                detectTapGestures {
                    focusManager.clearFocus()
                }
            }
    ) {
        content()
    }
}
