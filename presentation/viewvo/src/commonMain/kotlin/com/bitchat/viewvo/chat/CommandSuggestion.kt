package com.bitchat.viewvo.chat

data class CommandSuggestion(
    val command: String,
    val aliases: List<String> = emptyList(),
    val syntax: String? = null,
    val description: String
)
