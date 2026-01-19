package com.bitchat.domain.chat.model.failure

import com.bitchat.domain.base.model.failure.Failure

sealed class CommandFailure : Failure.FeatureFailure() {
    data object MissingTarget : CommandFailure()
    data object RequiresChannel : CommandFailure()
    data object RequiresLocation : CommandFailure()
    data object RequiresMesh : CommandFailure()
    data object RequiresNamedChannel : CommandFailure()
    data class Unknown(val reason: String = "invalid command") : CommandFailure()
}
