package com.bitchat.design.chat.media

import android.Manifest
import android.view.HapticFeedbackConstants
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import com.bitchat.mediautils.voice.RecordingState
import com.bitchat.mediautils.voice.rememberVoiceRecorder
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import kotlinx.coroutines.launch

@OptIn(ExperimentalPermissionsApi::class)
@Composable
actual fun VoiceRecordButton(
    backgroundColor: Color,
    onRecordingStarted: () -> Unit,
    onRecordingAmplitude: (amplitude: Int, elapsedMs: Long) -> Unit,
    onRecordingFinished: (filePath: String) -> Unit,
    onRecordingCancelled: () -> Unit,
    modifier: Modifier
) {
    val scope = rememberCoroutineScope()
    val view = LocalView.current
    val voiceRecorder = rememberVoiceRecorder()

    val recordingState by voiceRecorder.recordingState.collectAsState()
    val amplitude by voiceRecorder.amplitude.collectAsState()
    val elapsedTimeMs by voiceRecorder.elapsedTimeMs.collectAsState()
    val lastResult by voiceRecorder.lastRecordingResult.collectAsState()

    val permissionState = rememberPermissionState(Manifest.permission.RECORD_AUDIO)
    var isPressed by remember { mutableStateOf(false) }
    var pendingStart by remember { mutableStateOf(false) }
    var hasDeliveredResult by remember { mutableStateOf(false) }

    LaunchedEffect(amplitude, elapsedTimeMs) {
        if (recordingState == RecordingState.Recording) {
            onRecordingAmplitude(amplitude, elapsedTimeMs)
        }
    }

    LaunchedEffect(lastResult) {
        lastResult?.let { result ->
            if (!hasDeliveredResult) {
                hasDeliveredResult = true
                view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                onRecordingFinished(result.filePath)
                voiceRecorder.clearLastResult()
            }
        }
    }

    LaunchedEffect(recordingState) {
        when (recordingState) {
            is RecordingState.Idle -> Unit
            is RecordingState.Recording -> {
                hasDeliveredResult = false
            }
            is RecordingState.Processing -> Unit
            is RecordingState.Error -> {
                onRecordingCancelled()
            }
        }
    }

    LaunchedEffect(permissionState.status.isGranted, pendingStart) {
        if (permissionState.status.isGranted && pendingStart && isPressed) {
            pendingStart = false
            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            onRecordingStarted()
            voiceRecorder.startRecording()
        }
    }

    Box(
        modifier = modifier
            .size(32.dp)
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        isPressed = true

                        if (permissionState.status.isGranted) {
                            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                            onRecordingStarted()
                            val startResult = voiceRecorder.startRecording()
                            if (startResult.isFailure) {
                                isPressed = false
                                onRecordingCancelled()
                                return@detectTapGestures
                            }
                        } else {
                            pendingStart = true
                            permissionState.launchPermissionRequest()
                        }

                        val released = tryAwaitRelease()
                        isPressed = false
                        pendingStart = false

                        if (released) {
                            val currentState = voiceRecorder.recordingState.value
                            if (currentState == RecordingState.Recording) {
                                scope.launch {
                                    val result = voiceRecorder.stopRecording()
                                    if (result.isFailure) {
                                        onRecordingCancelled()
                                    }
                                }
                            }
                        } else {
                            voiceRecorder.cancelRecording()
                            onRecordingCancelled()
                        }
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(30.dp)
                .background(
                    color = if (recordingState == RecordingState.Recording) {
                        Color.Red.copy(alpha = 0.8f)
                    } else {
                        backgroundColor
                    },
                    shape = CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Filled.Mic,
                contentDescription = "Record voice",
                modifier = Modifier.size(20.dp),
                tint = Color.Black
            )
        }
    }
}
