package com.bitchat.domain.chat

import com.bitchat.domain.base.Usecase
import com.bitchat.domain.chat.model.ChannelInfo
import com.bitchat.domain.chat.repository.ChatRepository

class GetAvailableNamedChannels(
    private val chatRepository: ChatRepository,
) : Usecase<Unit, List<ChannelInfo>> {
    override suspend fun invoke(param: Unit): List<ChannelInfo> {
        return chatRepository.getAvailableChannels()
    }
}
