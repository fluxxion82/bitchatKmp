package com.bitchat.design.core

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.bitchat.mediautils.audio.rememberAudioPlayer

@Composable
fun VoiceNotePlayer(
    path: String,
    progressOverride: Float? = null,
    progressColor: Color? = null
) {
    val audioPlayer = rememberAudioPlayer()
    val isPlaying by audioPlayer.isPlaying.collectAsState()
    val currentPosition by audioPlayer.currentPositionMs.collectAsState()
    val duration by audioPlayer.durationMs.collectAsState()

    DisposableEffect(path) {
        audioPlayer.prepare(path)
        onDispose {
            audioPlayer.release()
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(
            onClick = {
                if (isPlaying) {
                    audioPlayer.pause()
                } else {
                    audioPlayer.play(path)
                }
            },
            modifier = Modifier.size(40.dp)
        ) {
            Icon(
                imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                contentDescription = if (isPlaying) "Pause" else "Play",
                tint = progressColor ?: MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp)
            )
        }

        val progress = progressOverride ?: if (duration > 0) {
            (currentPosition.toFloat() / duration).coerceIn(0f, 1f)
        } else {
            0f
        }

        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier
                .weight(1f)
                .height(4.dp),
            color = progressColor ?: MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.surfaceVariant
        )

        Text(
            text = formatDuration(currentPosition, duration),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private fun formatDuration(currentMs: Long, totalMs: Long): String {
    val currentSec = (currentMs / 1000).toInt()
    val totalSec = (totalMs / 1000).toInt()

    val currentMin = currentSec / 60
    val currentSecRemainder = currentSec % 60
    val totalMin = totalSec / 60
    val totalSecRemainder = totalSec % 60

    return if (totalMin > 0 || currentMin > 0) {
        "$currentMin:${currentSecRemainder.toString().padStart(2, '0')} / $totalMin:${totalSecRemainder.toString().padStart(2, '0')}"
    } else {
        "${currentSec}s / ${totalSec}s"
    }
}
