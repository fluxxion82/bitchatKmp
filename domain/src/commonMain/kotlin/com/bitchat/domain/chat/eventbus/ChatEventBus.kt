package com.bitchat.domain.chat.eventbus

import com.bitchat.domain.chat.model.ChatEvent
import kotlinx.coroutines.flow.Flow

interface ChatEventBus {
    fun events(): Flow<ChatEvent>
    suspend fun update(event: ChatEvent)
}
