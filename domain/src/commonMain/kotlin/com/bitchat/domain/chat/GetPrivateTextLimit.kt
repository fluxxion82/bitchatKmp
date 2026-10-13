package com.bitchat.domain.chat

import com.bitchat.domain.base.Usecase
import com.bitchat.domain.chat.repository.ChatRepository
import com.bitchat.domain.location.model.Channel

/** How many bytes one private text for a channel may have now: what a peer's link carries in one message. */
class GetPrivateTextLimit(private val chatRepository: ChatRepository) : Usecase<Channel, Int> {
    override suspend fun invoke(param: Channel): Int = chatRepository.privateTextLimit(param)
}
