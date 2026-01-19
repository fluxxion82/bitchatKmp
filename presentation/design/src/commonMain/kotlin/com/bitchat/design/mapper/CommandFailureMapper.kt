package com.bitchat.design.mapper

import com.bitchat.domain.chat.model.failure.CommandFailure

fun CommandFailure.toMessage(): String = when (this) {
    CommandFailure.MissingTarget -> "command requires a target"
    CommandFailure.RequiresChannel -> "command requires a channel"
    CommandFailure.RequiresLocation -> "command only available in location channels"
    CommandFailure.RequiresMesh -> "command only available from mesh channel"
    CommandFailure.RequiresNamedChannel -> "command only available in named channels"
    is CommandFailure.Unknown -> reason.ifBlank { "invalid command" }
}
