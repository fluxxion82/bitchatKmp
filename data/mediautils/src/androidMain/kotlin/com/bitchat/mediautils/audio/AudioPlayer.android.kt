package com.bitchat.mediautils.audio

import android.media.MediaPlayer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
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

@Composable
actual fun rememberAudioPlayer(): AudioPlayer {
    val player = remember { AndroidAudioPlayer() }

    DisposableEffect(Unit) {
        onDispose {
            player.release()
        }
    }

    return player
}

class AndroidAudioPlayer : AudioPlayer {

    private var mediaPlayer: MediaPlayer? = null
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
        if (path == currentPath && mediaPlayer != null) {
            return
        }

        // Stop any existing playback
        stop()

        try {
            currentPath = path
            mediaPlayer = MediaPlayer().apply {
                setDataSource(path)
                prepare()
                _durationMs.value = duration.toLong()

                setOnCompletionListener {
                    _isPlaying.value = false
                    _currentPositionMs.value = 0L
                    stopProgressUpdates()
                    // Reset position for replay
                    seekTo(0)
                }

                setOnErrorListener { _, _, _ ->
                    _isPlaying.value = false
                    stopProgressUpdates()
                    true
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            release()
        }
    }

    override fun play(path: String) {
        // If same file, just resume or start
        if (path == currentPath && mediaPlayer != null) {
            mediaPlayer?.start()
            _isPlaying.value = true
            startProgressUpdates()
            return
        }

        // Prepare the file first
        prepare(path)

        // Start playback
        mediaPlayer?.start()
        _isPlaying.value = true
        startProgressUpdates()
    }

    override fun pause() {
        mediaPlayer?.pause()
        _isPlaying.value = false
        stopProgressUpdates()
    }

    override fun stop() {
        stopProgressUpdates()
        try {
            mediaPlayer?.stop()
            mediaPlayer?.release()
        } catch (e: Exception) {
            // Ignore
        }
        mediaPlayer = null
        currentPath = null
        _isPlaying.value = false
        _currentPositionMs.value = 0L
        _durationMs.value = 0L
    }

    override fun seekTo(positionMs: Long) {
        mediaPlayer?.seekTo(positionMs.toInt())
        _currentPositionMs.value = positionMs
    }

    override fun release() {
        stop()
    }

    private fun startProgressUpdates() {
        stopProgressUpdates()
        progressJob = scope.launch {
            while (isActive && _isPlaying.value) {
                mediaPlayer?.let { player ->
                    _currentPositionMs.value = player.currentPosition.toLong()
                }
                delay(100) // Update every 100ms
            }
        }
    }

    private fun stopProgressUpdates() {
        progressJob?.cancel()
        progressJob = null
    }
}
