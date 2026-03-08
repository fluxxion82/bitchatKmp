package com.bitchat.domain.app.eventbus

import com.bitchat.domain.app.model.AppEvent
import kotlinx.coroutines.flow.SharedFlow

interface AppEventBus {
    fun getAppEvent(): SharedFlow<AppEvent>
    suspend fun update(event: AppEvent)
}
