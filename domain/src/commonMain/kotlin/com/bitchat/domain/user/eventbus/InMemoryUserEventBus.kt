package com.bitchat.domain.user.eventbus

import com.bitchat.domain.base.CoroutinesContextFacade
import com.bitchat.domain.user.model.UserEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.withContext

class InMemoryUserEventBus(
    private val contextFacade: CoroutinesContextFacade,
) : UserEventBus {
    private val eventFlow: MutableSharedFlow<UserEvent> = MutableSharedFlow(replay = 1)

    override fun events(): Flow<UserEvent> = eventFlow

    override suspend fun update(event: UserEvent) = withContext(contextFacade.default) {
        eventFlow.emit(event)
    }
}
