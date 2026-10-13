package com.bitchat.design.core

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager

/** One pointer gesture as the dismisser sees it. A child calls [keep] to leave the keyboard up. */
class KeyboardDismissGesture {
    internal var kept = false

    fun keep() {
        kept = true
    }
}

val LocalKeyboardDismissGesture = staticCompositionLocalOf { KeyboardDismissGesture() }

/**
 * Clears focus on a tap or drag inside while the on-screen keyboard is up. It watches on the Initial
 * pass and consumes nothing. Not Modifier.clickable: its Spacebar/Enter handling cleared focus on every space typed.
 */
@Composable
fun DismissKeyboardOnTapOutside(
    keyboardShowing: () -> Boolean = rememberKeyboardShowing(),
    content: @Composable () -> Unit,
) {
    val focusManager = LocalFocusManager.current
    val gesture = remember { KeyboardDismissGesture() }
    val currentKeyboardShowing = rememberUpdatedState(keyboardShowing)

    fun dismiss() {
        if (!gesture.kept && currentKeyboardShowing.value()) focusManager.clearFocus()
    }

    CompositionLocalProvider(LocalKeyboardDismissGesture provides gesture) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(gesture) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        gesture.kept = false
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) {
                                // Children act on a tap in the main pass and may ask to keep the keyboard.
                                awaitPointerEvent(PointerEventPass.Final)
                                dismiss()
                                break
                            }
                            if ((change.position - down.position).getDistance() > viewConfiguration.touchSlop) {
                                dismiss()
                                break
                            }
                        }
                    }
                }
        ) {
            content()
        }
    }
}

/** Marks a region whose taps and drags must leave the keyboard alone: the input bar, a text field. */
@Composable
fun Modifier.keepKeyboardOnTap(): Modifier {
    val gesture = LocalKeyboardDismissGesture.current
    return pointerInput(gesture) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            gesture.keep()
        }
    }
}

@Composable
private fun rememberKeyboardShowing(): () -> Boolean {
    val showing = rememberUpdatedState(WindowInsets.ime.getBottom(LocalDensity.current) > 0)
    return remember { { showing.value } }
}
