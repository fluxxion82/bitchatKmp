package com.bitchat.mediautils.audio

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

@Composable
actual fun rememberAudioPlayer(): AudioPlayer {
    return remember { StubAudioPlayer() }
}

/**
 * Stub AudioPlayer implementation for embedded Linux.
 * Audio playback is not supported on this platform.
 * All operations are no-ops.
 */
class StubAudioPlayer : AudioPlayer {
    private val _isPlaying = MutableStateFlow(false)
    override val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _currentPositionMs = MutableStateFlow(0L)
    override val currentPositionMs: StateFlow<Long> = _currentPositionMs.asStateFlow()

    private val _durationMs = MutableStateFlow(0L)
    override val durationMs: StateFlow<Long> = _durationMs.asStateFlow()

    override fun prepare(path: String) {
        println("AudioPlayer linuxArm64: Audio playback not supported on embedded platform")
    }

    override fun play(path: String) {
        println("AudioPlayer linuxArm64: Audio playback not supported on embedded platform")
    }

    override fun pause() {
        _isPlaying.value = false
    }

    override fun stop() {
        _isPlaying.value = false
        _currentPositionMs.value = 0L
    }

    override fun seekTo(positionMs: Long) {
        // No-op
    }

    override fun release() {
        stop()
    }
}
