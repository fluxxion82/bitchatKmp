package com.bitchat.domain.nostr.eventbus

import com.bitchat.domain.base.CoroutinesContextFacade
import com.bitchat.domain.nostr.model.NostrEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.withContext

class InMemoryNostrEventBus(
    private val contextFacade: CoroutinesContextFacade,
) : NostrEventBus {
    private val eventFlow: MutableSharedFlow<NostrEvent> = MutableSharedFlow(replay = 1)

    override fun events(): Flow<NostrEvent> = eventFlow

    override suspend fun update(event: NostrEvent) = withContext(contextFacade.default) {
        eventFlow.emit(event)
    }
}
