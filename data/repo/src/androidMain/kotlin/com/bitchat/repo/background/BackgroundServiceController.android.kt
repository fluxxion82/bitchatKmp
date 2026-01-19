package com.bitchat.repo.background

import android.content.Context
import android.content.Intent

actual class BackgroundServiceController(
    private val context: Context
) {
    actual fun startForegroundService() {
        val serviceClass = Class.forName(SERVICE_CLASS_NAME)
        val intent = Intent(context, serviceClass).apply {
            action = ACTION_START
        }
        context.startForegroundService(intent)
    }

    actual fun stopForegroundService() {
        val serviceClass = Class.forName(SERVICE_CLASS_NAME)
        val intent = Intent(context, serviceClass).apply {
            action = ACTION_STOP
        }
        context.startService(intent)
    }

    companion object {
        const val SERVICE_CLASS_NAME = "com.bitchat.android.service.BitchatForegroundService"
        const val ACTION_START = "com.bitchat.ACTION_START_FOREGROUND"
        const val ACTION_STOP = "com.bitchat.ACTION_STOP_FOREGROUND"
    }
}
