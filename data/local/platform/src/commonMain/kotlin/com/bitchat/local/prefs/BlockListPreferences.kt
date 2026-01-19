package com.bitchat.local.prefs

import com.bitchat.domain.user.model.BlockedUser

interface BlockListPreferences {
    fun getMeshBlockedUsers(): Map<String, BlockedUser>
    fun isMeshUserBlocked(fingerprint: String): Boolean
    fun addMeshBlockedUser(blockedUser: BlockedUser)
    fun removeMeshBlockedUser(fingerprint: String)

    fun getGeohashBlockedUsers(): Map<String, BlockedUser>
    fun isGeohashUserBlocked(pubkeyHex: String): Boolean
    fun addGeohashBlockedUser(blockedUser: BlockedUser)
    fun removeGeohashBlockedUser(pubkeyHex: String)

    fun getAllBlockedUsers(): List<BlockedUser>
    fun clearAllBlocks()
}
