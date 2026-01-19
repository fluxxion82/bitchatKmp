package com.bitchat.mediautils.audio

import androidx.compose.runtime.Composable
import kotlinx.coroutines.flow.StateFlow

/**
 * Cross-platform audio player interface for voice message playback.
 */
interface AudioPlayer {
    /** Whether audio is currently playing */
    val isPlaying: StateFlow<Boolean>

    /** Current playback position in milliseconds */
    val currentPositionMs: StateFlow<Long>

    /** Total duration of the audio in milliseconds */
    val durationMs: StateFlow<Long>

    /**
     * Prepare the audio file for playback without starting.
     * This loads metadata including duration.
     */
    fun prepare(path: String)

    /**
     * Start or resume playback of the audio file at the given path.
     * If a different file is already loaded, it will be stopped and the new file loaded.
     */
    fun play(path: String)

    /** Pause playback */
    fun pause()

    /** Stop playback and reset position */
    fun stop()

    /** Seek to the specified position in milliseconds */
    fun seekTo(positionMs: Long)

    /** Release resources. Should be called when the player is no longer needed. */
    fun release()
}

/**
 * Composable function to create and remember an AudioPlayer instance.
 * The player should be released when it leaves composition.
 */
@Composable
expect fun rememberAudioPlayer(): AudioPlayer
