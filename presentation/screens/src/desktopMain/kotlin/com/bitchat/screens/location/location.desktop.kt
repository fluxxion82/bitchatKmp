package com.bitchat.screens.location

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

@Composable
actual fun rememberLocationSettingsLauncher(onReturn: () -> Unit): () -> Unit {
    return remember {
        {
            openLocationSettings()
            onReturn()
        }
    }
}

private fun openLocationSettings() {
    val os = System.getProperty("os.name").lowercase()
    try {
        when {
            os.contains("mac") -> {
                ProcessBuilder("open", "x-apple.systempreferences:com.apple.preference.security?Privacy_LocationServices")
                    .start()
            }
            os.contains("win") -> {
                ProcessBuilder("cmd", "/c", "start", "ms-settings:privacy-location")
                    .start()
            }
            os.contains("linux") -> {
                ProcessBuilder("gnome-control-center", "privacy")
                    .start()
            }
            else -> {
                println("Desktop location settings: unsupported OS ($os)")
            }
        }
    } catch (e: Exception) {
        println("Failed to open location settings: ${e.message}")
    }
}
