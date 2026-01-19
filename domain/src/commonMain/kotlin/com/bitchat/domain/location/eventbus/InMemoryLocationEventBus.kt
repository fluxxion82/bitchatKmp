package com.bitchat.domain.location.eventbus

import com.bitchat.domain.base.CoroutinesContextFacade
import com.bitchat.domain.location.model.LocationEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.withContext

class InMemoryLocationEventBus(
    private val contextFacade: CoroutinesContextFacade,
) : LocationEventBus {
    private val eventFlow: MutableSharedFlow<LocationEvent> = MutableSharedFlow(replay = 1)

    override fun events(): Flow<LocationEvent> = eventFlow

    override suspend fun update(event: LocationEvent) = withContext(contextFacade.default) {
        eventFlow.emit(event)
    }
}
