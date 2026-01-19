package com.bitchat.domain.chat.model

data class ChannelMember(
    val peerID: String,
    val nickname: String,
    val npub: String?,
    val joinedAt: Long,
    val transport: ChannelTransport
)

enum class ChannelTransport {
    MESH,
    NOSTR,
    BOTH
}
