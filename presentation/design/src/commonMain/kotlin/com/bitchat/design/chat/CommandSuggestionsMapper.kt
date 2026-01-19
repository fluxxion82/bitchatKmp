package com.bitchat.design.chat

import com.bitchat.domain.location.model.Channel
import com.bitchat.viewvo.chat.CommandSuggestion

private val baseCommandSuggestions = listOf(
    CommandSuggestion("/block", emptyList(), "[nickname]", "block or list blocked peers"),
    CommandSuggestion("/channels", emptyList(), null, "show all discovered channels"),
    CommandSuggestion("/clear", emptyList(), null, "clear chat messages"),
    CommandSuggestion("/hug", emptyList(), "<nickname>", "send someone a warm hug"),
    CommandSuggestion("/j", listOf("/join"), "<channel>", "join or create a channel"),
    CommandSuggestion("/m", listOf("/msg"), "<nickname> [message]", "send private message"),
    CommandSuggestion("/slap", emptyList(), "<nickname> [object]", "slap someone with a chosen object"),
    CommandSuggestion("/unblock", emptyList(), "<nickname>", "unblock a peer"),
    CommandSuggestion("/w", listOf("/who"), null, "see who's online"),
)

private val meshOnlySuggestions = listOf<CommandSuggestion>(

)

private val channelOnlySuggestions = listOf(
    CommandSuggestion("/pass", emptyList(), "[password]", "change channel password"),
    CommandSuggestion("/leave", emptyList(), "", "leave the channel"),
)

private val locationOnlySuggestions = listOf<CommandSuggestion>(

)

fun Channel.channelCommandSuggestions(currentChannel: String?): List<CommandSuggestion> {
    val meshSuggestions = if (this is Channel.Mesh) meshOnlySuggestions else emptyList()
    val channelSuggestions = if (this is Channel.NamedChannel) channelOnlySuggestions else emptyList()
    val locationSuggestions = if (this is Channel.Location) locationOnlySuggestions else emptyList()
    return baseCommandSuggestions + meshSuggestions + channelSuggestions + locationSuggestions
}
