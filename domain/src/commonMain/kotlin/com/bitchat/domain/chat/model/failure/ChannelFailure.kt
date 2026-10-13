package com.bitchat.domain.chat.model.failure

import com.bitchat.domain.base.model.failure.Failure

sealed class ChannelFailure : Failure.FeatureFailure() {
    data object WrongPassword : ChannelFailure()
    data object OnlyCreatorCanSetPassword : ChannelFailure()
    data object JoinFailed : ChannelFailure()
    data class CreationFailed(val reason: String?) : ChannelFailure()
    data class PasswordNotSaved(val reason: String?) : ChannelFailure()
}
