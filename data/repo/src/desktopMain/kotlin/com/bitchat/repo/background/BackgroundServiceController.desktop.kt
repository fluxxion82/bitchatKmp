package com.bitchat.repo.background

actual class BackgroundServiceController {
    actual fun startForegroundService() {
        // No-op on Desktop
    }

    actual fun stopForegroundService() {
        // No-op on Desktop
    }
}
