package com.bitchat.screens.bluetooth

import androidx.compose.runtime.Composable

/**
 * Linux stub for Bluetooth enabler.
 *
 * On embedded Linux, Bluetooth is typically managed at the system level
 * (e.g., via bluetoothctl or system services).
 */
@Composable
actual fun rememberBluetoothEnabler(onGranted: () -> Unit, onDenied: () -> Unit): () -> Unit {
    return {
        // On Linux, Bluetooth management is at system level
        // Report as denied since we can't enable it programmatically
        onDenied()
    }
}
