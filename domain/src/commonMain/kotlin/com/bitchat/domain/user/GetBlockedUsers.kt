package com.bitchat.domain.user

import com.bitchat.domain.base.Usecase
import com.bitchat.domain.user.model.BlockedUser
import com.bitchat.domain.user.repository.BlockListRepository

class GetBlockedUsers(
    private val blockListRepository: BlockListRepository,
) : Usecase<Unit, List<BlockedUser>> {

    override suspend fun invoke(param: Unit): List<BlockedUser> {
        return blockListRepository.getAllBlockedUsers()
    }
}
