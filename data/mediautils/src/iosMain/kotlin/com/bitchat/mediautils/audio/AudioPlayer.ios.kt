package com.bitchat.mediautils.audio

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import platform.AVFAudio.AVAudioPlayer
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryPlayback
import platform.AVFAudio.setActive
import platform.Foundation.NSDate
import platform.Foundation.NSURL
import platform.Foundation.timeIntervalSince1970

@Composable
actual fun rememberAudioPlayer(): AudioPlayer {
    val player = remember { IosAudioPlayer() }

    DisposableEffect(Unit) {
        onDispose {
            player.release()
        }
    }

    return player
}

@OptIn(ExperimentalForeignApi::class)
class IosAudioPlayer : AudioPlayer {
    private var audioPlayer: AVAudioPlayer? = null
    private var currentPath: String? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var progressJob: Job? = null

    private val _isPlaying = MutableStateFlow(false)
    override val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _currentPositionMs = MutableStateFlow(0L)
    override val currentPositionMs: StateFlow<Long> = _currentPositionMs.asStateFlow()

    private val _durationMs = MutableStateFlow(0L)
    override val durationMs: StateFlow<Long> = _durationMs.asStateFlow()

    override fun prepare(path: String) {
        // If same file already prepared, do nothing
        if (path == currentPath && audioPlayer != null) {
            return
        }

        // Stop any existing playback
        stop()

        try {
            // Configure audio session for playback
            val audioSession = AVAudioSession.sharedInstance()
            audioSession.setCategory(AVAudioSessionCategoryPlayback, null)
            audioSession.setActive(true, null)

            currentPath = path
            val url = NSURL.fileURLWithPath(path)
            audioPlayer = AVAudioPlayer(url, null)?.apply {
                prepareToPlay()
                _durationMs.value = (duration * 1000).toLong()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            release()
        }
    }

    override fun play(path: String) {
        // If same file, just resume or start
        if (path == currentPath && audioPlayer != null) {
            audioPlayer?.play()
            _isPlaying.value = true
            startProgressUpdates()
            return
        }

        // Prepare the file first
        prepare(path)

        // Start playback
        audioPlayer?.play()
        _isPlaying.value = true
        startProgressUpdates()
    }

    override fun pause() {
        audioPlayer?.pause()
        _isPlaying.value = false
        stopProgressUpdates()
    }

    override fun stop() {
        stopProgressUpdates()
        try {
            audioPlayer?.stop()
        } catch (e: Exception) {
            // Ignore
        }
        audioPlayer = null
        currentPath = null
        _isPlaying.value = false
        _currentPositionMs.value = 0L
        _durationMs.value = 0L
    }

    override fun seekTo(positionMs: Long) {
        audioPlayer?.currentTime = positionMs / 1000.0
        _currentPositionMs.value = positionMs
    }

    override fun release() {
        stop()
    }

    private fun startProgressUpdates() {
        stopProgressUpdates()
        progressJob = scope.launch {
            while (isActive && _isPlaying.value) {
                audioPlayer?.let { player ->
                    _currentPositionMs.value = (player.currentTime * 1000).toLong()

                    // Check if playback finished
                    if (!player.isPlaying()) {
                        _isPlaying.value = false
                        _currentPositionMs.value = 0L
                        stopProgressUpdates()
                    }
                }
                delay(100) // Update every 100ms
            }
        }
    }

    private fun stopProgressUpdates() {
        progressJob?.cancel()
        progressJob = null
    }

    private fun currentTimeMillis(): Long {
        return (NSDate().timeIntervalSince1970 * 1000).toLong()
    }
}
