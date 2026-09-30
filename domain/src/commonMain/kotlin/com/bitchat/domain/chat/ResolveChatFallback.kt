package com.bitchat.domain.chat

import com.bitchat.domain.app.model.ActiveState
import com.bitchat.domain.app.model.UserState
import com.bitchat.domain.base.Usecase
import com.bitchat.domain.chat.repository.ChatRepository
import com.bitchat.domain.location.model.Channel
import com.bitchat.domain.user.repository.UserRepository

/**
 * Where to go when the chat is left (the parameter is the conversation being left; null means the
 * one the user is in): the chat the user came from, when they can still be in it, else the mesh.
 *
 * The chat being left is never the answer, whatever it is spelled like, and neither is a named
 * channel the user is no longer in: a channel left earlier still sits in the saved history (`/j a`,
 * `/j b`, `/leave`, `/leave`), and going back to it would show a channel with no subscription or
 * key. Shared by `/leave`, the Compose leave button and back, so all three agree. Back has no
 * channel to name, so it passes null and the chat in view is the one guarded against: a saved
 * state whose current and previous chats are the same one would otherwise trap the user in it.
 */
class ResolveChatFallback(
    private val userRepository: UserRepository,
    private val chatRepository: ChatRepository,
) : Usecase<Channel?, Channel> {
    override suspend fun invoke(param: Channel?): Channel {
        val chat = (userRepository.getUserState() as? UserState.Active)
            ?.activeState
            ?.let { it as? ActiveState.Chat }
            ?: return Channel.Mesh
        val leaving = param ?: chat.channel
        val previous = chat.previousChannel ?: return Channel.Mesh
        if (previous.isSameConversation(leaving)) return Channel.Mesh
        if (previous is Channel.NamedChannel && !isJoined(previous)) return Channel.Mesh
        return previous
    }

    private suspend fun isJoined(channel: Channel.NamedChannel): Boolean {
        val wanted = normalizeChannelName(channel.channelName)
        return chatRepository.getJoinedChannelsList().any { normalizeChannelName(it) == wanted }
    }
}
