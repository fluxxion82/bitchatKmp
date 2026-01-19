package com.bitchat.domain.tor.eventbus

import com.bitchat.domain.base.CoroutinesContextFacade
import com.bitchat.domain.tor.model.TorEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.withContext

class InMemoryTorEventBus(
    private val contextFacade: CoroutinesContextFacade,
) : TorEventBus {
    private val eventFlow: MutableSharedFlow<TorEvent> = MutableSharedFlow(replay = 1)

    override fun events(): Flow<TorEvent> = eventFlow

    override suspend fun update(event: TorEvent) = withContext(contextFacade.default) {
        eventFlow.emit(event)
    }
}
