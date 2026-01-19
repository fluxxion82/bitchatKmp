package com.bitchat.mediautils.voice

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import kotlinx.cinterop.ExperimentalForeignApi
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
import platform.AVFAudio.AVAudioRecorder
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryPlayAndRecord
import platform.AVFAudio.AVAudioSessionModeDefault
import platform.AVFAudio.AVEncoderAudioQualityKey
import platform.AVFAudio.AVEncoderBitRateKey
import platform.AVFAudio.AVFormatIDKey
import platform.AVFAudio.AVNumberOfChannelsKey
import platform.AVFAudio.AVSampleRateKey
import platform.AVFAudio.setActive
import platform.CoreAudioTypes.kAudioFormatMPEG4AAC
import platform.Foundation.NSDate
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSURL
import platform.Foundation.NSUserDomainMask
import platform.Foundation.timeIntervalSince1970
import kotlin.math.pow

@Composable
actual fun rememberVoiceRecorder(): VoiceRecorder {
    return remember { IosVoiceRecorder() }
}

/**
 * iOS VoiceRecorder implementation using AVAudioRecorder.
 */
@OptIn(ExperimentalForeignApi::class)
class IosVoiceRecorder : VoiceRecorder {

    private var audioRecorder: AVAudioRecorder? = null
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
            // Configure audio session
            val audioSession = AVAudioSession.sharedInstance()
            audioSession.setCategory(
                AVAudioSessionCategoryPlayAndRecord,
                mode = AVAudioSessionModeDefault,
                options = 0u,
                error = null
            )
            audioSession.setActive(true, null)

            // Create output directory and file
            val documentsDir = NSFileManager.defaultManager.URLsForDirectory(
                NSDocumentDirectory,
                NSUserDomainMask
            ).firstOrNull() as? NSURL ?: return Result.failure(Exception("Cannot find documents directory"))

            val voiceNotesDir = documentsDir.URLByAppendingPathComponent("voicenotes/outgoing")
            voiceNotesDir?.path?.let { path ->
                NSFileManager.defaultManager.createDirectoryAtPath(
                    path,
                    withIntermediateDirectories = true,
                    attributes = null,
                    error = null
                )
            }

            val timestamp = currentTimeMillis()
            val fileName = "voice_$timestamp.m4a"
            val fileUrl = voiceNotesDir?.URLByAppendingPathComponent(fileName)
                ?: return Result.failure(Exception("Cannot create file URL"))

            outputPath = fileUrl.path

            // Configure recorder settings
            val settings = mapOf<Any?, Any?>(
                AVFormatIDKey to kAudioFormatMPEG4AAC,
                AVSampleRateKey to VoiceConstants.SAMPLE_RATE.toDouble(),
                AVNumberOfChannelsKey to VoiceConstants.CHANNELS,
                AVEncoderBitRateKey to VoiceConstants.BIT_RATE,
                AVEncoderAudioQualityKey to 1 // AVAudioQualityMedium
            )

            // Create and configure recorder
            val recorder = AVAudioRecorder(fileUrl, settings, null)
            if (recorder == null) {
                return Result.failure(Exception("Failed to create audio recorder"))
            }

            recorder.setMeteringEnabled(true)

            if (!recorder.prepareToRecord()) {
                return Result.failure(Exception("Failed to prepare recorder"))
            }

            if (!recorder.record()) {
                return Result.failure(Exception("Failed to start recording"))
            }

            audioRecorder = recorder
            recordingStartTime = currentTimeMillis()
            _recordingState.value = RecordingState.Recording
            _elapsedTimeMs.value = 0L
            _amplitude.value = 0

            // Start amplitude polling
            amplitudeJob = scope.launch {
                while (isActive && _recordingState.value == RecordingState.Recording) {
                    try {
                        audioRecorder?.let { rec ->
                            rec.updateMeters()
                            // Convert dB to linear amplitude (0-32768 range like Android)
                            val avgPower = rec.averagePowerForChannel(0u)
                            val linearAmp = dbToLinearAmplitude(avgPower.toDouble())
                            _amplitude.value = linearAmp
                            _elapsedTimeMs.value = currentTimeMillis() - recordingStartTime
                        }
                    } catch (e: Exception) {
                        // Ignore metering errors
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
            val currentDuration = currentTimeMillis() - recordingStartTime

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

            val duration = currentTimeMillis() - recordingStartTime

            // Stop and release recorder
            audioRecorder?.stop()
            audioRecorder = null

            _recordingState.value = RecordingState.Idle

            val path = outputPath ?: return Result.failure(IllegalStateException("No output path"))
            outputPath = null

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
            audioRecorder?.stop()
        } catch (e: Exception) {
            // Ignore errors during cancellation
        }
        audioRecorder = null

        // Delete the file if it exists
        outputPath?.let { path ->
            try {
                NSFileManager.defaultManager.removeItemAtPath(path, null)
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
            audioRecorder?.stop()
        } catch (e: Exception) {
            // Ignore
        }
        audioRecorder = null
        outputPath = null
    }

    private fun dbToLinearAmplitude(db: Double): Int {
        // Clamp to reasonable range
        val clampedDb = db.coerceIn(-160.0, 0.0)
        // Convert from dB to linear: amplitude = 10^(dB/20)
        val linear = 10.0.pow(clampedDb / 20.0)
        // Scale to 0-32768 range
        return (linear * 32768).toInt().coerceIn(0, 32768)
    }

    private fun currentTimeMillis(): Long {
        return (NSDate().timeIntervalSince1970 * 1000).toLong()
    }
}
