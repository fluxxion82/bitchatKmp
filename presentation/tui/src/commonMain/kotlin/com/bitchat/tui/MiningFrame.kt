package com.bitchat.tui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import kotlinx.coroutines.delay

/** One-cell ASCII frames; no timer runs when the mining indicator is absent or offscreen. */
@Composable
internal fun rememberMiningFrame(active: Boolean): State<Char> {
    val frame = remember(active) { mutableStateOf('|') }
    if (active) {
        LaunchedEffect(frame) {
            var index = 0
            while (true) {
                delay(150)
                index = (index + 1) % MINING_FRAMES.length
                frame.value = MINING_FRAMES[index]
            }
        }
    }
    return frame
}

private const val MINING_FRAMES = "|/-\\"

/** Shared by the visible message rows; only their body text recomposes on each 100 ms tick. */
@Composable
internal fun rememberScrambleStep(active: Boolean): State<Int> {
    val step = remember(active) { mutableStateOf(0) }
    if (active) {
        LaunchedEffect(step) {
            while (true) {
                delay(100)
                step.value = (step.value + 1) % SCRAMBLE_STEPS
            }
        }
    }
    return step
}

internal const val SCRAMBLE_STEPS = 40
