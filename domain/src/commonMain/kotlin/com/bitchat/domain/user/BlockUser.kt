package com.bitchat.domain.user

import com.bitchat.domain.base.Usecase
import com.bitchat.domain.user.model.BlockType
import com.bitchat.domain.user.repository.BlockListRepository

class BlockUser(
    private val blockListRepository: BlockListRepository,
) : Usecase<BlockUser.Request, Unit> {

    data class Request(
        val identifier: String,
        val nickname: String?,
        val blockType: BlockType
    )

    override suspend fun invoke(param: Request) {
        when (param.blockType) {
            BlockType.MESH -> blockListRepository.blockMeshUser(param.identifier, param.nickname)
            BlockType.GEOHASH -> blockListRepository.blockGeohashUser(param.identifier, param.nickname)
        }
    }
}
