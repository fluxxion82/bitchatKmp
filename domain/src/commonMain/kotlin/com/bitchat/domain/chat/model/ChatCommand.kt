package com.bitchat.domain.chat.model

sealed interface ChatCommand {
    data class Block(val target: String?) : ChatCommand
    object Channels : ChatCommand
    object Clear : ChatCommand

    data class Hug(val target: String) : ChatCommand
    data class Join(val channel: String) : ChatCommand
    data class Leave(val channel: String?) : ChatCommand
    object List : ChatCommand
    data class Message(val target: String, val message: String?) : ChatCommand
    data class Pass(
        val currentPassword: String?,
        val newPassword: String?
    ) : ChatCommand

    object Save : ChatCommand
    data class Slap(val target: String, val item: String) : ChatCommand
    data class Transfer(val target: String) : ChatCommand
    data class Unblock(val target: String) : ChatCommand

    data class Who(val channel: String?) : ChatCommand
}
