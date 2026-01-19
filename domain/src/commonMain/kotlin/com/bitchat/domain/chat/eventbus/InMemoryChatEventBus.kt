package com.bitchat.domain.chat.eventbus

import com.bitchat.domain.base.CoroutinesContextFacade
import com.bitchat.domain.chat.model.ChatEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.withContext

class InMemoryChatEventBus(
    private val contextFacade: CoroutinesContextFacade,
) : ChatEventBus {
    private val flow = MutableSharedFlow<ChatEvent>(replay = 1)

    override fun events(): Flow<ChatEvent> = flow

    override suspend fun update(event: ChatEvent) {
        withContext(contextFacade.io) {
            flow.emit(event)
        }
    }
}
