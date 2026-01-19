package com.bitchat.mediautils.voice

import kotlinx.coroutines.flow.StateFlow

/**
 * Interface for platform-specific voice recording implementations.
 * Supports 10-second maximum recording with amplitude monitoring.
 */
interface VoiceRecorder {
    /** Current state of the recorder */
    val recordingState: StateFlow<RecordingState>

    /** Current amplitude (0-32768 raw value) for waveform visualization */
    val amplitude: StateFlow<Int>

    /** Elapsed recording time in milliseconds */
    val elapsedTimeMs: StateFlow<Long>

    /**
     * Result of the last completed recording.
     * Null when no recording has completed or after being consumed.
     * This is set when recording completes (either via stopRecording or auto-stop).
     */
    val lastRecordingResult: StateFlow<RecordingResult?>

    /**
     * Start recording audio.
     * Recording will auto-stop after [VoiceConstants.MAX_DURATION_MS].
     */
    suspend fun startRecording(): Result<Unit>

    /**
     * Stop recording and return the result.
     * Includes a buffer delay of [VoiceConstants.BUFFER_AFTER_RELEASE_MS] before finalizing.
     */
    suspend fun stopRecording(): Result<RecordingResult>

    /**
     * Cancel the current recording without saving.
     */
    fun cancelRecording()

    /**
     * Clear the last recording result after it has been consumed.
     */
    fun clearLastResult()
}

sealed class RecordingState {
    data object Idle : RecordingState()
    data object Recording : RecordingState()
    data object Processing : RecordingState()
    data class Error(val message: String) : RecordingState()
}

data class RecordingResult(
    val filePath: String,
    val durationMs: Long
)
