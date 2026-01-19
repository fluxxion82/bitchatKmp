package com.bitchat.repo.background

expect class BackgroundServiceController {
    fun startForegroundService()
    fun stopForegroundService()
}
