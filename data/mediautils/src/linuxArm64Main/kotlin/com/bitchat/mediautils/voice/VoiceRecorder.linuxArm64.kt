package com.bitchat.mediautils.voice

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

@Composable
actual fun rememberVoiceRecorder(): VoiceRecorder {
    return remember { StubVoiceRecorder() }
}

/**
 * Stub VoiceRecorder implementation for embedded Linux.
 * Voice recording is not supported on this platform.
 * All operations return failure or no-op.
 */
class StubVoiceRecorder : VoiceRecorder {
    private val _recordingState = MutableStateFlow<RecordingState>(RecordingState.Idle)
    override val recordingState: StateFlow<RecordingState> = _recordingState.asStateFlow()

    private val _amplitude = MutableStateFlow(0)
    override val amplitude: StateFlow<Int> = _amplitude.asStateFlow()

    private val _elapsedTimeMs = MutableStateFlow(0L)
    override val elapsedTimeMs: StateFlow<Long> = _elapsedTimeMs.asStateFlow()

    private val _lastRecordingResult = MutableStateFlow<RecordingResult?>(null)
    override val lastRecordingResult: StateFlow<RecordingResult?> = _lastRecordingResult.asStateFlow()

    override suspend fun startRecording(): Result<Unit> {
        _recordingState.value = RecordingState.Error("Voice recording not supported on embedded platform")
        return Result.failure(UnsupportedOperationException("Voice recording not supported on embedded platform"))
    }

    override suspend fun stopRecording(): Result<RecordingResult> {
        return Result.failure(UnsupportedOperationException("Voice recording not supported on embedded platform"))
    }

    override fun cancelRecording() {
        _recordingState.value = RecordingState.Idle
    }

    override fun clearLastResult() {
        _lastRecordingResult.value = null
    }
}
