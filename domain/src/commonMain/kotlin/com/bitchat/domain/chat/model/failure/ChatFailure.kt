package com.bitchat.domain.chat.model.failure

import com.bitchat.domain.base.model.failure.Failure

sealed class ChatFailure {
    data object SendMessageFailure : Failure.FeatureFailure()
}
