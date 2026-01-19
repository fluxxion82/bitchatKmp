package com.bitchat.mediautils.voice

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

@Composable
actual fun rememberVoiceRecorder(): VoiceRecorder {
    val context = LocalContext.current
    return remember { AndroidVoiceRecorder(context.applicationContext) }
}

class AndroidVoiceRecorder(
    private val context: Context
) : VoiceRecorder {

    private var mediaRecorder: MediaRecorder? = null
    private var outputPath: String? = null
    private var recordingStartTime: Long = 0L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var amplitudeJob: Job? = null
    private var autoStopJob: Job? = null

    private val _recordingState = MutableStateFlow<RecordingState>(RecordingState.Idle)
    override val recordingState: StateFlow<RecordingState> = _recordingState.asStateFlow()

    private val _amplitude = MutableStateFlow(0)
    override val amplitude: StateFlow<Int> = _amplitude.asStateFlow()

    private val _elapsedTimeMs = MutableStateFlow(0L)
    override val elapsedTimeMs: StateFlow<Long> = _elapsedTimeMs.asStateFlow()

    private val _lastRecordingResult = MutableStateFlow<RecordingResult?>(null)
    override val lastRecordingResult: StateFlow<RecordingResult?> = _lastRecordingResult.asStateFlow()

    override fun clearLastResult() {
        _lastRecordingResult.value = null
    }

    override suspend fun startRecording(): Result<Unit> {
        // Clear any previous result
        _lastRecordingResult.value = null

        return try {
            // Create output directory
            val voiceNotesDir = File(context.filesDir, "voicenotes/outgoing")
            if (!voiceNotesDir.exists()) {
                voiceNotesDir.mkdirs()
            }

            // Generate unique filename
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val outputFile = File(voiceNotesDir, "voice_$timestamp.m4a")
            outputPath = outputFile.absolutePath

            // Configure MediaRecorder
            mediaRecorder = createMediaRecorder().apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioSamplingRate(VoiceConstants.SAMPLE_RATE)
                setAudioEncodingBitRate(VoiceConstants.BIT_RATE)
                setAudioChannels(VoiceConstants.CHANNELS)
                setOutputFile(outputPath)
                prepare()
                start()
            }

            recordingStartTime = System.currentTimeMillis()
            _recordingState.value = RecordingState.Recording
            _elapsedTimeMs.value = 0L
            _amplitude.value = 0

            // Start amplitude polling
            amplitudeJob = scope.launch {
                while (isActive && _recordingState.value == RecordingState.Recording) {
                    try {
                        val amp = mediaRecorder?.maxAmplitude ?: 0
                        _amplitude.value = amp
                        _elapsedTimeMs.value = System.currentTimeMillis() - recordingStartTime
                    } catch (e: Exception) {
                        // MediaRecorder might be in invalid state
                    }
                    delay(VoiceConstants.AMPLITUDE_POLL_MS)
                }
            }

            // Start auto-stop timer
            autoStopJob = scope.launch {
                delay(VoiceConstants.MAX_DURATION_MS)
                if (_recordingState.value == RecordingState.Recording) {
                    stopRecording()
                }
            }

            Result.success(Unit)
        } catch (e: Exception) {
            cleanup()
            _recordingState.value = RecordingState.Error(e.message ?: "Failed to start recording")
            Result.failure(e)
        }
    }

    override suspend fun stopRecording(): Result<RecordingResult> {
        if (_recordingState.value != RecordingState.Recording) {
            return Result.failure(IllegalStateException("Not recording"))
        }

        return try {
            _recordingState.value = RecordingState.Processing

            // Calculate current duration
            val currentDuration = System.currentTimeMillis() - recordingStartTime

            // If recording is too short, wait until minimum duration is reached
            if (currentDuration < VoiceConstants.MIN_DURATION_MS) {
                val waitTime = VoiceConstants.MIN_DURATION_MS - currentDuration
                delay(waitTime)
            } else {
                // Add buffer delay before stopping (only if we already have enough duration)
                delay(VoiceConstants.BUFFER_AFTER_RELEASE_MS)
            }

            // Stop amplitude polling
            amplitudeJob?.cancel()
            amplitudeJob = null

            // Cancel auto-stop timer
            autoStopJob?.cancel()
            autoStopJob = null

            val duration = System.currentTimeMillis() - recordingStartTime

            // Stop and release MediaRecorder with error handling
            var stopSucceeded = false
            try {
                mediaRecorder?.stop()
                stopSucceeded = true
            } catch (stopError: Exception) {
                // Error -1007 means stop failed, possibly because no data was recorded
                // The file may still be partially valid
                println("MediaRecorder stop error: ${stopError.message}")
            }

            try {
                mediaRecorder?.release()
            } catch (releaseError: Exception) {
                // Ignore release errors
            }
            mediaRecorder = null

            val path = outputPath ?: return Result.failure(IllegalStateException("No output path"))
            outputPath = null

            // Check if the file exists and has content
            val file = File(path)
            if (!file.exists() || file.length() < 100) {
                // File is empty or too small - recording failed
                file.delete()
                _recordingState.value = RecordingState.Error("Recording failed - no audio captured")
                return Result.failure(IllegalStateException("Recording failed - no audio captured"))
            }

            _recordingState.value = RecordingState.Idle

            val result = RecordingResult(filePath = path, durationMs = duration)
            _lastRecordingResult.value = result
            Result.success(result)
        } catch (e: Exception) {
            cleanup()
            _recordingState.value = RecordingState.Error(e.message ?: "Failed to stop recording")
            Result.failure(e)
        }
    }

    override fun cancelRecording() {
        amplitudeJob?.cancel()
        amplitudeJob = null
        autoStopJob?.cancel()
        autoStopJob = null

        try {
            mediaRecorder?.apply {
                stop()
                release()
            }
        } catch (e: Exception) {
            // Ignore errors during cancellation
        }
        mediaRecorder = null

        // Delete the file if it exists
        outputPath?.let { path ->
            try {
                File(path).delete()
            } catch (e: Exception) {
                // Ignore
            }
        }
        outputPath = null

        _recordingState.value = RecordingState.Idle
        _amplitude.value = 0
        _elapsedTimeMs.value = 0L
    }

    private fun cleanup() {
        amplitudeJob?.cancel()
        amplitudeJob = null
        autoStopJob?.cancel()
        autoStopJob = null

        try {
            mediaRecorder?.release()
        } catch (e: Exception) {
            // Ignore
        }
        mediaRecorder = null
        outputPath = null
    }

    @Suppress("DEPRECATION")
    private fun createMediaRecorder(): MediaRecorder {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            MediaRecorder()
        }
    }
}
