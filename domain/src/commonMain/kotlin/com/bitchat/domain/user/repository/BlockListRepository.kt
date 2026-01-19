package com.bitchat.domain.user.repository

import com.bitchat.domain.user.model.BlockedUser
import kotlinx.coroutines.flow.Flow

interface BlockListRepository {
    suspend fun getMeshBlockedUsers(): Set<BlockedUser>
    suspend fun isMeshUserBlocked(fingerprint: String): Boolean
    suspend fun blockMeshUser(fingerprint: String, nickname: String?)
    suspend fun unblockMeshUser(fingerprint: String)

    suspend fun getGeohashBlockedUsers(): Set<BlockedUser>
    suspend fun isGeohashUserBlocked(pubkeyHex: String): Boolean
    suspend fun blockGeohashUser(pubkeyHex: String, nickname: String?)
    suspend fun unblockGeohashUser(pubkeyHex: String)

    suspend fun getAllBlockedUsers(): List<BlockedUser>
    fun observeBlockList(): Flow<List<BlockedUser>>

    suspend fun clearData()
}
