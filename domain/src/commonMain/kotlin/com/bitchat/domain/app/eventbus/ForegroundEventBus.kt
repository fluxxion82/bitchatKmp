package com.bitchat.domain.app.eventbus

import kotlinx.coroutines.channels.ReceiveChannel

interface ForegroundEventBus {
    fun getForegroundEvent(): ReceiveChannel<Boolean>
    suspend fun update(foreground: Boolean)
}
