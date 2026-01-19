package com.bitchat.domain.app.eventbus

import com.bitchat.domain.app.model.AppEvent
import com.bitchat.domain.base.CoroutinesContextFacade
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.withContext

class InMemoryAppEventBus(
    private val contextFacade: CoroutinesContextFacade,
) : AppEventBus {
    private val eventFlow = Channel<AppEvent>()

    override fun getAppEvent(): ReceiveChannel<AppEvent> = eventFlow

    override suspend fun update(event: AppEvent) = withContext(contextFacade.default) {
        eventFlow.send(event)
    }
}
