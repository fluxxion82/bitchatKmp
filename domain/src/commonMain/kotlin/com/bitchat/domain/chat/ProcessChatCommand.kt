package com.bitchat.domain.chat

import com.bitchat.domain.base.Usecase
import com.bitchat.domain.chat.model.ChatCommand
import com.bitchat.domain.chat.model.CommandContext
import com.bitchat.domain.chat.model.CommandResult
import com.bitchat.domain.chat.model.failure.CommandFailure

/**
 * Reads a typed line as a chat command.
 *
 * Which commands take a nickname as their first argument is stated once, as the `syntax` of each
 * entry in `presentation:viewvo`'s `CommandSuggestions.kt`; the input's completion list derives
 * from it (`takesNicknameFirst`). Changing a command's first argument here means changing that
 * syntax with it, or the list will offer people to a command that wants a channel.
 */
class ProcessChatCommand : Usecase<ProcessChatCommand.ChatCommandRequest, CommandResult> {
    data class ChatCommandRequest(
        val input: String,
        val context: CommandContext
    )

    override suspend fun invoke(param: ChatCommandRequest): CommandResult {
        val (parts, command, target) = commandParts(param.input) ?: return CommandResult.NotACommand
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

/** A command line split as [ProcessChatCommand] reads it: its words, the lowercased command, the target. */
internal data class CommandParts(val parts: List<String>, val command: String, val target: String)

internal fun commandParts(input: String): CommandParts? {
    val trimmed = input.trim()
    if (trimmed.isEmpty() || !trimmed.startsWith("/")) return null
    val parts = trimmed.split(Regex("\\s+"))
    if (parts.isEmpty()) return null
    return CommandParts(parts, parts.first().lowercase(), parts.getOrNull(1)?.removePrefix("@")?.trim().orEmpty())
}

/**
 * The nickname a `/m` or `/msg` line is addressed to, read exactly as [ProcessChatCommand] reads
 * it (so `@bob` is `bob`); null for any other line or a missing target.
 */
fun messageCommandTarget(input: String): String? {
    val parts = commandParts(input) ?: return null
    if (parts.command != "/m" && parts.command != "/msg") return null
    return parts.target.ifBlank { null }
}
