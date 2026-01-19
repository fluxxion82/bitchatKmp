package com.bitchat.domain.chat

import com.bitchat.domain.base.Usecase
import com.bitchat.domain.base.model.Outcome
import com.bitchat.domain.chat.eventbus.ChatEventBus
import com.bitchat.domain.chat.model.ChannelInfo
import com.bitchat.domain.chat.model.ChatEvent
import com.bitchat.domain.chat.model.failure.ChannelFailure
import com.bitchat.domain.chat.repository.ChatRepository

class JoinChannel(
    private val chatRepository: ChatRepository,
    private val chatEventBus: ChatEventBus,
) : Usecase<JoinChannel.Params, Outcome<JoinChannel.JoinChannelResult>> {

    data class Params(
        val channelName: String,
        val password: String? = null
    )

    data class JoinChannelResult(
        val channelInfo: ChannelInfo,
        val isNewChannel: Boolean
    )

    override suspend fun invoke(param: Params): Outcome<JoinChannelResult> {
        val normalizedName = normalizeChannelName(param.channelName)

        val joinedChannels = chatRepository.getJoinedChannelsList()
        if (joinedChannels.contains(normalizedName)) {
            val channels = chatRepository.getAvailableChannels()
            val existingChannel = channels.find { it.name == normalizedName }

            val channelInfo = existingChannel ?: ChannelInfo(
                name = normalizedName,
                isProtected = false,
                memberCount = 0,
                creatorNpub = null,
                keyCommitment = chatRepository.getChannelKeyCommitment(normalizedName),
                isOwner = chatRepository.isChannelOwner(normalizedName),
                nostrEventId = null
            )

            if (existingChannel != null) {
                chatRepository.ensureNamedChannelMetadata(existingChannel)
            }
            chatEventBus.update(ChatEvent.ChannelChanged)
            return Outcome.Success(JoinChannelResult(channelInfo, isNewChannel = false))
        }

        val availableChannels = chatRepository.getAvailableChannels()
        var existingChannel = availableChannels.find { it.name == normalizedName }

        if (existingChannel == null) {
            existingChannel = chatRepository.discoverNamedChannel(normalizedName)
        }

        return if (existingChannel != null) {
            joinExistingChannel(normalizedName, param.password, existingChannel)
        } else {
            createNewChannel(normalizedName, param.password)
        }
    }

    private suspend fun joinExistingChannel(
        channelName: String,
        password: String?,
        existingChannel: ChannelInfo
    ): Outcome<JoinChannelResult> {
        chatRepository.ensureNamedChannelMetadata(existingChannel)
        if (existingChannel.isProtected) {
            if (password == null) {
                return Outcome.Error(
                    "Password required for protected channel",
                    ChannelFailure.WrongPassword
                )
            }

            val key = chatRepository.deriveChannelKey(channelName, password)
            val commitment = chatRepository.calculateKeyCommitment(key)

            if (existingChannel.keyCommitment != null && commitment != existingChannel.keyCommitment) {
                return Outcome.Error(
                    "Incorrect password",
                    ChannelFailure.WrongPassword
                )
            }

            chatRepository.setChannelPassword(channelName, password)
        }

        val success = chatRepository.joinChannel(channelName, password)
        if (!success) {
            return Outcome.Error(
                "Failed to join channel",
                ChannelFailure.CreationFailed("Join operation failed")
            )
        }

        val updatedChannels = chatRepository.getAvailableChannels()
        val updatedChannel = updatedChannels.find { it.name == channelName }
            ?: existingChannel

        chatEventBus.update(ChatEvent.ChannelJoined)
        chatEventBus.update(ChatEvent.ChannelListUpdated)

        return Outcome.Success(JoinChannelResult(updatedChannel, isNewChannel = false))
    }

    private suspend fun createNewChannel(
        channelName: String,
        password: String?
    ): Outcome<JoinChannelResult> {
        return try {
            val channelInfo = chatRepository.createNamedChannel(channelName, password)
            Outcome.Success(JoinChannelResult(channelInfo, isNewChannel = true))
        } catch (e: Exception) {
            Outcome.Error(
                "Failed to create channel: ${e.message}",
                ChannelFailure.CreationFailed(e.message ?: "Unknown error")
            )
        }
    }

    private fun normalizeChannelName(name: String): String {
        return if (name.startsWith("#")) name else "#$name"
    }
}
