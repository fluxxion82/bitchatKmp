package com.bitchat.domain.chat

import com.bitchat.domain.base.Usecase
import com.bitchat.domain.chat.repository.ChatRepository

class SendPrivateMessage(
    private val chatRepository: ChatRepository,
) : Usecase<SendPrivateMessage.Params, Unit> {
    override suspend fun invoke(param: Params) {
        chatRepository.sendPrivate(
            content = param.content,
            toPeerID = param.peerID,
            recipientNickname = param.recipientNickname,
        )
    }

    data class Params(
        val content: String,
        val peerID: String,
        val recipientNickname: String,
    )
}
