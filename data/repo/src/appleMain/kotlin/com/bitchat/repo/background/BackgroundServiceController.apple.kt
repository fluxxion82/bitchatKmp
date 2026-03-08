package com.bitchat.repo.background

actual class BackgroundServiceController {
    actual fun startForegroundService() {
        // No-op on iOS/macOS - background modes are handled via Info.plist
    }

    actual fun stopForegroundService() {
        // No-op on iOS/macOS
    }
}
