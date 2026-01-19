package com.bitchat.domain.chat.model

data class ChannelInfo(
    val name: String,
    val isProtected: Boolean,
    val memberCount: Int,
    val creatorNpub: String?,
    val keyCommitment: String?,
    val isOwner: Boolean,
    val nostrEventId: String?
)
