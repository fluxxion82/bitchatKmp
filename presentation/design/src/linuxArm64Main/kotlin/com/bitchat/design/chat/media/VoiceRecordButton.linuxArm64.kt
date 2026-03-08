package com.bitchat.design.chat.media

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.bitchat.design.icons.Icons
import com.bitchat.design.icons.filled.MicOff

/**
 * Stub VoiceRecordButton for embedded Linux.
 * Voice recording is not supported on this platform.
 * Displays a disabled microphone icon.
 */
@Composable
actual fun VoiceRecordButton(
    backgroundColor: Color,
    onRecordingStarted: () -> Unit,
    onRecordingAmplitude: (amplitude: Int, elapsedMs: Long) -> Unit,
    onRecordingFinished: (filePath: String) -> Unit,
    onRecordingCancelled: () -> Unit,
    modifier: Modifier
) {
    // Display a disabled microphone icon - voice recording not supported
    Box(
        modifier = modifier.size(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(30.dp)
                .background(
                    color = backgroundColor.copy(alpha = 0.5f),
                    shape = CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Filled.MicOff,
                contentDescription = "Voice recording not supported",
                modifier = Modifier.size(20.dp),
                tint = Color.Gray
            )
        }
    }
}
