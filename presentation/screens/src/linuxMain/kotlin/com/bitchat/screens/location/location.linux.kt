package com.bitchat.screens.location

import androidx.compose.runtime.Composable

/**
 * Linux stub for location settings launcher.
 *
 * On embedded Linux, there's no system settings app to launch.
 * Returns a no-op function.
 */
@Composable
actual fun rememberLocationSettingsLauncher(onReturn: () -> Unit): () -> Unit {
    return {
        // No-op on Linux - no settings app
        // Immediately call onReturn to continue the flow
        onReturn()
    }
}
