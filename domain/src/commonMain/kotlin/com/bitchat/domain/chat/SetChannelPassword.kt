package com.bitchat.domain.chat

import com.bitchat.domain.base.Usecase
import com.bitchat.domain.base.model.Outcome
import com.bitchat.domain.chat.eventbus.ChatEventBus
import com.bitchat.domain.chat.model.ChatEvent
import com.bitchat.domain.chat.model.failure.ChannelFailure
import com.bitchat.domain.chat.repository.ChatRepository

class SetChannelPassword(
    private val chatRepository: ChatRepository,
    private val chatEventBus: ChatEventBus,
) : Usecase<SetChannelPassword.Params, Outcome<Unit>> {

    data class Params(
        val channelName: String,
        val currentPassword: String?,
        val newPassword: String
    )

    override suspend fun invoke(param: Params): Outcome<Unit> {
        val channelName = param.channelName
        val currentPassword = param.currentPassword
        val newPassword = param.newPassword

        val existingCommitment = chatRepository.getChannelKeyCommitment(channelName)

        return if (existingCommitment == null) {
            setInitialPassword(channelName, newPassword)
        } else if (currentPassword == null) {
            verifyOwnership(channelName, newPassword)
        } else {
            changePassword(channelName, currentPassword, newPassword)
        }
    }

    private suspend fun setInitialPassword(
        channelName: String,
        password: String
    ): Outcome<Unit> {
        val isOwner = chatRepository.isChannelOwner(channelName)
        if (!isOwner) {
            return Outcome.Error(
                "Only the channel creator can set the initial password",
                ChannelFailure.OnlyCreatorCanSetPassword
            )
        }

        return try {
            chatRepository.setChannelPassword(channelName, password)
            chatEventBus.update(ChatEvent.ChannelOwnershipVerified(channelName))
            Outcome.Success(Unit)
        } catch (e: Exception) {
            Outcome.Error(
                "Failed to set password: ${e.message}",
                ChannelFailure.CreationFailed(e.message ?: "Unknown error")
            )
        }
    }

    private suspend fun verifyOwnership(
        channelName: String,
        password: String
    ): Outcome<Unit> {
        val verified = chatRepository.verifyPasswordOwnership(channelName, password)

        return if (verified) {
            Outcome.Success(Unit)
        } else {
            Outcome.Error(
                "Incorrect password",
                ChannelFailure.WrongPassword
            )
        }
    }

    private suspend fun changePassword(
        channelName: String,
        currentPassword: String,
        newPassword: String
    ): Outcome<Unit> {
        val key = chatRepository.deriveChannelKey(channelName, currentPassword)
        val commitment = chatRepository.calculateKeyCommitment(key)
        val existingCommitment = chatRepository.getChannelKeyCommitment(channelName)

        if (commitment != existingCommitment) {
            return Outcome.Error(
                "Incorrect current password",
                ChannelFailure.WrongPassword
            )
        }

        val isOwner = chatRepository.isChannelOwner(channelName)
        if (!isOwner) {
            chatRepository.verifyPasswordOwnership(channelName, currentPassword)
        }

        return try {
            chatRepository.setChannelPassword(channelName, newPassword)
            chatEventBus.update(ChatEvent.ChannelOwnershipVerified(channelName))
            Outcome.Success(Unit)
        } catch (e: Exception) {
            Outcome.Error(
                "Failed to change password: ${e.message}",
                ChannelFailure.CreationFailed(e.message ?: "Unknown error")
            )
        }
    }
}
