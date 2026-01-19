package com.bitchat.repo.repositories

import com.bitchat.domain.base.CoroutinesContextFacade
import com.bitchat.domain.user.model.BlockType
import com.bitchat.domain.user.model.BlockedUser
import com.bitchat.domain.user.repository.BlockListRepository
import com.bitchat.local.prefs.BlockListPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlin.time.Clock

class BlockListRepo(
    private val coroutinesContextFacade: CoroutinesContextFacade,
    private val blockListPreferences: BlockListPreferences,
) : BlockListRepository {
    private val blockListFlow = MutableStateFlow<List<BlockedUser>>(emptyList())

    init {
        blockListFlow.value = blockListPreferences.getAllBlockedUsers()
    }

    override suspend fun getMeshBlockedUsers(): Set<BlockedUser> = withContext(coroutinesContextFacade.io) {
        blockListPreferences.getMeshBlockedUsers().values.toSet()
    }

    override suspend fun isMeshUserBlocked(fingerprint: String): Boolean = withContext(coroutinesContextFacade.io) {
        blockListPreferences.isMeshUserBlocked(fingerprint)
    }

    override suspend fun blockMeshUser(fingerprint: String, nickname: String?) = withContext(coroutinesContextFacade.io) {
        val blockedUser = BlockedUser(
            identifier = fingerprint.lowercase(),
            nickname = nickname,
            blockedAt = Clock.System.now().toEpochMilliseconds(),
            blockType = BlockType.MESH
        )
        blockListPreferences.addMeshBlockedUser(blockedUser)
        updateFlow()
    }

    override suspend fun unblockMeshUser(fingerprint: String) = withContext(coroutinesContextFacade.io) {
        blockListPreferences.removeMeshBlockedUser(fingerprint)
        updateFlow()
    }

    override suspend fun getGeohashBlockedUsers(): Set<BlockedUser> = withContext(coroutinesContextFacade.io) {
        blockListPreferences.getGeohashBlockedUsers().values.toSet()
    }

    override suspend fun isGeohashUserBlocked(pubkeyHex: String): Boolean = withContext(coroutinesContextFacade.io) {
        blockListPreferences.isGeohashUserBlocked(pubkeyHex)
    }

    override suspend fun blockGeohashUser(pubkeyHex: String, nickname: String?) = withContext(coroutinesContextFacade.io) {
        val blockedUser = BlockedUser(
            identifier = pubkeyHex.lowercase(),
            nickname = nickname,
            blockedAt = Clock.System.now().toEpochMilliseconds(),
            blockType = BlockType.GEOHASH
        )
        blockListPreferences.addGeohashBlockedUser(blockedUser)
        updateFlow()
    }

    override suspend fun unblockGeohashUser(pubkeyHex: String) = withContext(coroutinesContextFacade.io) {
        blockListPreferences.removeGeohashBlockedUser(pubkeyHex)
        updateFlow()
    }

    override suspend fun getAllBlockedUsers(): List<BlockedUser> = withContext(coroutinesContextFacade.io) {
        blockListPreferences.getAllBlockedUsers()
    }

    override fun observeBlockList(): Flow<List<BlockedUser>> = blockListFlow.asStateFlow()

    private fun updateFlow() {
        blockListFlow.value = blockListPreferences.getAllBlockedUsers()
    }

    override suspend fun clearData() = withContext(coroutinesContextFacade.io) {
        blockListPreferences.clearAllBlocks()
        updateFlow()
    }
}
