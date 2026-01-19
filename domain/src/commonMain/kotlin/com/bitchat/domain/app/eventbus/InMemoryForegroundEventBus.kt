package com.bitchat.domain.app.eventbus

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel

class InMemoryForegroundEventBus : ForegroundEventBus {

    private val foregroundEvent = Channel<Boolean>()

    override fun getForegroundEvent(): ReceiveChannel<Boolean> = foregroundEvent

    override suspend fun update(foreground: Boolean) {
        foregroundEvent.send(foreground)
    }
}
