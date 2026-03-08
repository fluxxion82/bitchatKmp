package com.bitchat.domain.app.eventbus

import com.bitchat.domain.app.model.AppEvent
import com.bitchat.domain.base.CoroutinesContextFacade
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.withContext

class InMemoryAppEventBus(
    private val contextFacade: CoroutinesContextFacade,
) : AppEventBus {
    private val eventFlow = MutableSharedFlow<AppEvent>(replay = 1)

    override fun getAppEvent(): SharedFlow<AppEvent> = eventFlow

    override suspend fun update(event: AppEvent) = withContext(contextFacade.default) {
        eventFlow.emit(event)
    }
}
