package com.bitchat.domain.user.model

import kotlinx.serialization.Serializable

@Serializable
data class BlockedUser(
    val identifier: String,
    val nickname: String?,
    val blockedAt: Long,
    val blockType: BlockType
)

@Serializable
enum class BlockType {
    MESH,
    GEOHASH,
}
