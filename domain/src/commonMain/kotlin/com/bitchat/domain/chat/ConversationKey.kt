package com.bitchat.domain.chat

import com.bitchat.domain.location.model.Channel

/** A channel name as it is stored and compared: lower case, with one leading `#`. */
fun normalizeChannelName(name: String): String {
    val trimmed = name.trim()
    val withHash = if (trimmed.startsWith("#")) trimmed else "#$trimmed"
    return withHash.lowercase()
}

/**
 * What identifies the conversation this channel is: its peer, geohash, node or normalized name,
 * without the data that describes it (a display name, a DM's source geohash, a geohash's level).
 * Two channels with the same key are the same conversation, so this is what decides whether the
 * user is already in a chat, which chat a system line belongs to, and what `/clear` clears.
 */
fun Channel.conversationKey(): String = when (this) {
    Channel.Mesh -> "mesh"
    is Channel.Location -> "geohash:${geohash.lowercase()}"
    is Channel.NamedChannel -> "channel:${normalizeChannelName(channelName)}"
    is Channel.MeshDM -> "dm:$peerID"
    is Channel.NostrDM -> "dm:$peerID"
    is Channel.Meshtastic -> if (nodeNum == null) "meshtastic" else "meshtastic:$nodeNum"
}

/** Whether this is the same conversation as [other] (see [conversationKey]). */
fun Channel.isSameConversation(other: Channel): Boolean = conversationKey() == other.conversationKey()
