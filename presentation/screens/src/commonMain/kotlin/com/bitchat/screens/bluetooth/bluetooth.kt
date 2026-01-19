package com.bitchat.screens.bluetooth

import androidx.compose.runtime.Composable

@Composable
expect fun rememberBluetoothEnabler(onGranted: () -> Unit, onDenied: () -> Unit): () -> Unit
