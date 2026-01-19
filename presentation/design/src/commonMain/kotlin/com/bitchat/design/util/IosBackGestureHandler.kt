package com.bitchat.design.util

import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker

private const val GESTURE_ACCEPTED_START = 120f
private const val GESTURE_MINIMUM_FINISH = 200f
private const val MINIMUM_VELOCITY = 300f

@Composable
fun IosBackGestureHandler(
    isEnabled: Boolean,
    onBack: () -> Unit,
    content: @Composable () -> Unit
) {
    var offsetStart by remember { mutableStateOf(-1f) }
    var offsetFinish by remember { mutableStateOf(-1f) }
    var velocityTracker by remember { mutableStateOf(VelocityTracker()) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { offset ->
                        if (offset.x <= GESTURE_ACCEPTED_START) {
                            offsetStart = offset.x
                            velocityTracker = VelocityTracker()
                        }
                    },

                    onDragEnd = {
                        val velocity = velocityTracker.calculateVelocity().x
                        val isVelocityBasedActivation = velocity > MINIMUM_VELOCITY

                        val isDistanceBasedActivation = offsetStart in 0f..GESTURE_ACCEPTED_START
                                && offsetFinish > GESTURE_MINIMUM_FINISH

                        val isOnBackGestureActivated = isEnabled
                                && (isDistanceBasedActivation || isVelocityBasedActivation)

                        if (isOnBackGestureActivated) onBack()

                        offsetStart = -1f
                        offsetFinish = -1f
                    },

                    onHorizontalDrag = { change, dragAmount ->
                        velocityTracker.addPosition(
                            change.uptimeMillis,
                            change.position
                        )
                        offsetFinish = change.position.x
                    }
                )
            }
    ) {
        content()
    }
}
