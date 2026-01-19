package com.bitchat.domain.chat

import com.bitchat.domain.base.Usecase
import com.bitchat.domain.chat.eventbus.ChatEventBus
import com.bitchat.domain.chat.model.ChatEvent
import com.bitchat.domain.chat.repository.ChatRepository

class LeaveChannel(
    private val chatRepository: ChatRepository,
    private val chatEventBus: ChatEventBus,
) : Usecase<String, Unit> {

    override suspend fun invoke(param: String) {
        chatRepository.leaveChannel(param)
        chatEventBus.update(ChatEvent.ChannelLeft)
    }
}
