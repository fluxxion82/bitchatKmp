package com.bitchat.domain.chat.model

import com.bitchat.domain.chat.model.failure.CommandFailure

sealed interface CommandResult {
    data class Parsed(val command: ChatCommand) : CommandResult
    data class Invalid(val failure: CommandFailure) : CommandResult
    object NotACommand : CommandResult
}
