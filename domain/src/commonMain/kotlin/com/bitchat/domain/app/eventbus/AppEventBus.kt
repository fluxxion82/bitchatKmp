package com.bitchat.domain.app.eventbus

import com.bitchat.domain.app.model.AppEvent
import kotlinx.coroutines.channels.ReceiveChannel

interface AppEventBus {
    fun getAppEvent(): ReceiveChannel<AppEvent>
    suspend fun update(event: AppEvent)
}
