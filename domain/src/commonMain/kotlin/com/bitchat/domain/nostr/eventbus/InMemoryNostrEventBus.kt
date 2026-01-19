package com.bitchat.domain.nostr.eventbus

import com.bitchat.domain.base.CoroutinesContextFacade
import com.bitchat.domain.nostr.model.NostrEvent
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.withContext

class InMemoryNostrEventBus(
    private val contextFacade: CoroutinesContextFacade,
) : NostrEventBus {
    private val eventFlow = Channel<NostrEvent>()

    override fun getNostrEvent(): ReceiveChannel<NostrEvent> = eventFlow

    override suspend fun update(event: NostrEvent) = withContext(contextFacade.default) {
        eventFlow.send(event)
    }
}
