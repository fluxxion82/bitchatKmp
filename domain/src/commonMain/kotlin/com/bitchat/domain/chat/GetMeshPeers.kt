package com.bitchat.domain.chat

import com.bitchat.domain.base.Usecase
import com.bitchat.domain.chat.repository.ChatRepository
import com.bitchat.domain.location.model.GeoPerson

class GetMeshPeers(
    private val chatRepository: ChatRepository,
) : Usecase<Unit, List<GeoPerson>> {
    override suspend fun invoke(param: Unit): List<GeoPerson> {
        return chatRepository.getMeshPeers()
    }
}
