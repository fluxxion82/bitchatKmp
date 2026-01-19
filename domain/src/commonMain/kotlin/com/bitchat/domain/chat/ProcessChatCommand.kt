package com.bitchat.domain.chat

import com.bitchat.domain.base.Usecase
import com.bitchat.domain.chat.model.ChatCommand
import com.bitchat.domain.chat.model.CommandContext
import com.bitchat.domain.chat.model.CommandResult
import com.bitchat.domain.chat.model.failure.CommandFailure

class ProcessChatCommand : Usecase<ProcessChatCommand.ChatCommandRequest, CommandResult> {
    data class ChatCommandRequest(
        val input: String,
        val context: CommandContext
    )

    override suspend fun invoke(param: ChatCommandRequest): CommandResult {
        val trimmed = param.input.trim()
        if (trimmed.isEmpty() || !trimmed.startsWith("/")) {
            return CommandResult.NotACommand
        }

        val parts = trimmed.split(Regex("\\s+"))
        if (parts.isEmpty()) return CommandResult.NotACommand

        val command = parts.first().lowercase()
        val target = parts.getOrNull(1)?.removePrefix("@")?.trim().orEmpty()
        val item = parts.drop(2).joinToString(" ").trim()

        fun requireTarget(): String? = target.ifBlank { null }
        fun slapItem(): String = item.ifBlank { "large trout" }

        return when (command) {
            "/block" -> CommandResult.Parsed(ChatCommand.Block(requireTarget()))
            "/channels" -> CommandResult.Parsed(ChatCommand.Channels)
            "/clear" -> CommandResult.Parsed(ChatCommand.Clear)
            "/hug" -> requireTarget()?.let { CommandResult.Parsed(ChatCommand.Hug(it)) }
                ?: CommandResult.Invalid(CommandFailure.MissingTarget)

            "/j", "/join" -> {
                requireTarget()?.let { CommandResult.Parsed(ChatCommand.Join(it)) }
                    ?: CommandResult.Invalid(CommandFailure.MissingTarget)
            }

            "/leave" -> {
                if (!param.context.isNamedChannel) {
                    CommandResult.Invalid(CommandFailure.RequiresNamedChannel)
                } else {
                    val channel = requireTarget()
                    CommandResult.Parsed(ChatCommand.Leave(channel))
                }
            }

            "/list" -> CommandResult.Parsed(ChatCommand.List)
            "/m", "/msg" -> {
                val message = parts.drop(2).joinToString(" ").ifBlank { null }
                requireTarget()?.let { CommandResult.Parsed(ChatCommand.Message(it, message)) }
                    ?: CommandResult.Invalid(CommandFailure.MissingTarget)
            }

            "/pass" -> {
                if (!param.context.isNamedChannel) {
                    CommandResult.Invalid(CommandFailure.RequiresNamedChannel)
                } else {
                    val currentPassword = parts.getOrNull(1)?.trim()?.ifBlank { null }
                    val newPassword = parts.getOrNull(2)?.trim()?.ifBlank { null }
                    CommandResult.Parsed(ChatCommand.Pass(currentPassword, newPassword))
                }
            }

            "/slap" -> requireTarget()?.let { CommandResult.Parsed(ChatCommand.Slap(it, slapItem())) }
                ?: CommandResult.Invalid(CommandFailure.MissingTarget)

            "/unblock" -> requireTarget()?.let { CommandResult.Parsed(ChatCommand.Unblock(it)) }
                ?: CommandResult.Invalid(CommandFailure.MissingTarget)

            "/w", "/who" -> {
                val channel = requireTarget()
                CommandResult.Parsed(ChatCommand.Who(channel))
            }

            else -> CommandResult.NotACommand
        }
    }
}
