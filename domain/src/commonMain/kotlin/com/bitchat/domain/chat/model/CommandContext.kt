package com.bitchat.domain.chat.model

data class CommandContext(
    val isLocationChannel: Boolean,
    val isMeshChannel: Boolean,
    val isNamedChannel: Boolean,
    val currentChannel: String?
)
