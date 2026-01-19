package com.bitchat.design.chat.media

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

@Composable
expect fun VoiceRecordButton(
    backgroundColor: Color,
    onRecordingStarted: () -> Unit,
    onRecordingAmplitude: (amplitude: Int, elapsedMs: Long) -> Unit,
    onRecordingFinished: (filePath: String) -> Unit,
    onRecordingCancelled: () -> Unit,
    modifier: Modifier = Modifier
)
