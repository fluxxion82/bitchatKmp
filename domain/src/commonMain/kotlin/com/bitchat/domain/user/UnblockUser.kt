package com.bitchat.domain.user

import com.bitchat.domain.base.Usecase
import com.bitchat.domain.user.model.BlockType
import com.bitchat.domain.user.repository.BlockListRepository

class UnblockUser(
    private val blockListRepository: BlockListRepository,
) : Usecase<UnblockUser.Request, Unit> {

    data class Request(
        val identifier: String,
        val blockType: BlockType
    )

    override suspend fun invoke(param: Request) {
        when (param.blockType) {
            BlockType.MESH -> blockListRepository.unblockMeshUser(param.identifier)
            BlockType.GEOHASH -> blockListRepository.unblockGeohashUser(param.identifier)
        }
    }
}
