package com.bitchat.domain.chat

import com.bitchat.domain.base.Usecase
import com.bitchat.domain.chat.eventbus.ChatEventBus
import com.bitchat.domain.chat.model.ChatEvent
import com.bitchat.domain.chat.repository.ChatRepository
import com.bitchat.domain.location.model.Channel

/** Leaves the named channel and forgets it: its messages go, and so does its command feedback. */
class LeaveChannel(
    private val chatRepository: ChatRepository,
    private val chatEventBus: ChatEventBus,
    private val chatNotices: ChatNotices,
) : Usecase<String, Unit> {

    override suspend fun invoke(param: String) {
        chatRepository.leaveChannel(param)
        chatNotices.clear(Channel.NamedChannel(param))
        chatEventBus.update(ChatEvent.ChannelLeft)
    }
}
