package com.bitchat.repo.background

/**
 * Linux implementation of BackgroundServiceController.
 *
 * On embedded Linux, the application runs as a foreground process.
 * Background management is handled by systemd or similar init systems.
 */
actual class BackgroundServiceController {
    actual fun startForegroundService() {
        // No-op on Linux - the process runs directly
        // For daemon operation, use systemd service files
    }

    actual fun stopForegroundService() {
        // No-op on Linux
    }
}
