package com.bitchat.mediautils.voice

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import javax.sound.sampled.AudioFileFormat
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.TargetDataLine
import kotlin.math.abs

@Composable
actual fun rememberVoiceRecorder(): VoiceRecorder {
    return remember { DesktopVoiceRecorder() }
}

/**
 * Desktop VoiceRecorder implementation using Java Sound API.
 * Records to WAV format (PCM) since Java Sound doesn't natively support AAC.
 */
class DesktopVoiceRecorder : VoiceRecorder {

    private var targetLine: TargetDataLine? = null
    private var outputPath: String? = null
    private var recordingStartTime: Long = 0L
    private var recordingThread: Thread? = null
    private val audioBuffer = ByteArrayOutputStream()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
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

    // Audio format: 16kHz, 16-bit, mono, signed, little-endian
    private val audioFormat = AudioFormat(
        VoiceConstants.SAMPLE_RATE.toFloat(),
        16,     // sample size in bits
        VoiceConstants.CHANNELS,
        true,   // signed
        false   // little-endian
    )

    override suspend fun startRecording(): Result<Unit> = withContext(Dispatchers.IO) {
        // Clear any previous result
        _lastRecordingResult.value = null

        try {
            // Create output directory
            val userHome = System.getProperty("user.home")
            val voiceNotesDir = File(userHome, ".bitchat/voicenotes/outgoing")
            if (!voiceNotesDir.exists()) {
                voiceNotesDir.mkdirs()
            }

            // Generate unique filename
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val outputFile = File(voiceNotesDir, "voice_$timestamp.wav")
            outputPath = outputFile.absolutePath

            // Clear the audio buffer
            audioBuffer.reset()

            // Get and open the target data line
            val info = DataLine.Info(TargetDataLine::class.java, audioFormat)
            if (!AudioSystem.isLineSupported(info)) {
                return@withContext Result.failure(Exception("Audio line not supported"))
            }

            val line = AudioSystem.getLine(info) as TargetDataLine
            line.open(audioFormat)
            line.start()
            targetLine = line

            recordingStartTime = System.currentTimeMillis()
            _recordingState.value = RecordingState.Recording
            _elapsedTimeMs.value = 0L
            _amplitude.value = 0

            // Start recording thread
            recordingThread = Thread {
                val buffer = ByteArray(4096)
                try {
                    while (targetLine?.isOpen == true && _recordingState.value == RecordingState.Recording) {
                        val bytesRead = line.read(buffer, 0, buffer.size)
                        if (bytesRead > 0) {
                            audioBuffer.write(buffer, 0, bytesRead)

                            // Calculate amplitude from PCM samples
                            val amp = calculateAmplitude(buffer, bytesRead)
                            _amplitude.value = amp
                            _elapsedTimeMs.value = System.currentTimeMillis() - recordingStartTime
                        }
                    }
                } catch (e: Exception) {
                    // Recording stopped or error
                }
            }.apply {
                isDaemon = true
                start()
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

    override suspend fun stopRecording(): Result<RecordingResult> = withContext(Dispatchers.IO) {
        if (_recordingState.value != RecordingState.Recording) {
            return@withContext Result.failure(IllegalStateException("Not recording"))
        }

        try {
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

            // Cancel auto-stop timer
            autoStopJob?.cancel()
            autoStopJob = null

            val duration = System.currentTimeMillis() - recordingStartTime

            // Stop and close the line
            targetLine?.stop()
            targetLine?.close()
            targetLine = null

            // Wait for recording thread to finish
            recordingThread?.join(1000)
            recordingThread = null

            // Write the audio data to WAV file
            val path = outputPath ?: return@withContext Result.failure(IllegalStateException("No output path"))

            val audioData = audioBuffer.toByteArray()
            val audioInputStream = AudioInputStream(
                audioData.inputStream(),
                audioFormat,
                audioData.size.toLong() / audioFormat.frameSize
            )

            val outputFile = File(path)
            AudioSystem.write(audioInputStream, AudioFileFormat.Type.WAVE, outputFile)

            audioBuffer.reset()
            outputPath = null

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
        autoStopJob?.cancel()
        autoStopJob = null

        try {
            targetLine?.stop()
            targetLine?.close()
        } catch (e: Exception) {
            // Ignore errors during cancellation
        }
        targetLine = null

        recordingThread?.interrupt()
        recordingThread = null

        audioBuffer.reset()

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
        autoStopJob?.cancel()
        autoStopJob = null

        try {
            targetLine?.stop()
            targetLine?.close()
        } catch (e: Exception) {
            // Ignore
        }
        targetLine = null

        recordingThread?.interrupt()
        recordingThread = null

        audioBuffer.reset()
        outputPath = null
    }

    /**
     * Calculate amplitude from 16-bit PCM samples.
     * Returns a value in the 0-32768 range.
     */
    private fun calculateAmplitude(buffer: ByteArray, bytesRead: Int): Int {
        var maxAmplitude = 0

        // Process 16-bit samples (2 bytes per sample, little-endian)
        var i = 0
        while (i < bytesRead - 1) {
            // Convert two bytes to a 16-bit sample (little-endian)
            val sample = (buffer[i].toInt() and 0xFF) or (buffer[i + 1].toInt() shl 8)
            // Convert to signed value
            val signedSample = if (sample > 32767) sample - 65536 else sample
            val amplitude = abs(signedSample)
            if (amplitude > maxAmplitude) {
                maxAmplitude = amplitude
            }
            i += 2
        }

        return maxAmplitude
    }
}
