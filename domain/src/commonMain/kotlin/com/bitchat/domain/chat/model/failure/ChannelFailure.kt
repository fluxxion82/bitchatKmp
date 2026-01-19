package com.bitchat.domain.chat.model.failure

import com.bitchat.domain.base.model.failure.Failure

sealed class ChannelFailure : Failure.FeatureFailure() {
    data object WrongPassword : ChannelFailure()
    data object NotOwner : ChannelFailure()
    data object ChannelNotFound : ChannelFailure()
    data object AlreadyJoined : ChannelFailure()
    data object OnlyCreatorCanSetPassword : ChannelFailure()
    data class CreationFailed(val reason: String) : ChannelFailure()
}
