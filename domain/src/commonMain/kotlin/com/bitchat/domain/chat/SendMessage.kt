package com.bitchat.domain.chat

import com.bitchat.domain.base.Usecase
import com.bitchat.domain.chat.model.BitchatMessageType
import com.bitchat.domain.chat.repository.ChatRepository
import com.bitchat.domain.location.model.Channel

class SendMessage(
    private val chatRepository: ChatRepository,
) : Usecase<SendMessage.Params, Unit> {

    data class Params(
        val content: String,
        val channel: Channel,
        val sender: String,
        val messageType: BitchatMessageType = BitchatMessageType.Message
    )

    override suspend fun invoke(param: Params) {
        chatRepository.sendMessage(
            content = param.content,
            channel = param.channel,
            sender = param.sender,
            messageType = param.messageType
        )
    }
}
