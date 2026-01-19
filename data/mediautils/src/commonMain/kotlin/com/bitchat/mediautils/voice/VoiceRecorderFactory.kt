package com.bitchat.mediautils.voice

import androidx.compose.runtime.Composable

/**
 * Composable factory to create and remember a platform-specific [VoiceRecorder] instance.
 * Uses platform-specific mechanisms for context/resources.
 */
@Composable
expect fun rememberVoiceRecorder(): VoiceRecorder
