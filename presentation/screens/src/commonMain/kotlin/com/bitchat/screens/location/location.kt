package com.bitchat.screens.location

import androidx.compose.runtime.Composable

@Composable
expect fun rememberLocationSettingsLauncher(onReturn: () -> Unit): () -> Unit
