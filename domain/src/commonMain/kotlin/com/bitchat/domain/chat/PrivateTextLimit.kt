package com.bitchat.domain.chat

import com.bitchat.domain.chat.repository.ChatRepository
import com.bitchat.domain.location.model.Channel

/**
 * How many bytes one private text for a channel may have now: what a peer's link carries in one
 * message. Asked while a line is being sent, before the caller clears its editor, so it does not
 * suspend.
 */
class PrivateTextLimit(private val chatRepository: ChatRepository) {
    operator fun invoke(channel: Channel): Int = chatRepository.privateTextLimit(channel)
}
