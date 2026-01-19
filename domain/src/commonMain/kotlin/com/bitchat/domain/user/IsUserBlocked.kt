package com.bitchat.domain.user

import com.bitchat.domain.base.Usecase
import com.bitchat.domain.user.model.BlockType
import com.bitchat.domain.user.repository.BlockListRepository

class IsUserBlocked(
    private val blockListRepository: BlockListRepository,
) : Usecase<IsUserBlocked.Request, Boolean> {

    data class Request(
        val identifier: String,
        val blockType: BlockType
    )

    override suspend fun invoke(param: Request): Boolean {
        return when (param.blockType) {
            BlockType.MESH -> blockListRepository.isMeshUserBlocked(param.identifier)
            BlockType.GEOHASH -> blockListRepository.isGeohashUserBlocked(param.identifier)
        }
    }
}
