package com.bitchat.screens.bluetooth

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

@Composable
actual fun rememberBluetoothEnabler(onGranted: () -> Unit, onDenied: () -> Unit): () -> Unit {
    return remember { {} }
}
