package com.bitchat.domain.location

import com.bitchat.domain.base.Usecase
import com.bitchat.domain.chat.repository.ChatRepository
import com.bitchat.domain.location.model.ParticipantCounts
import com.bitchat.domain.location.repository.LocationRepository

class GetParticipantCounts(
    private val locationRepository: LocationRepository,
    private val chatRepository: ChatRepository
) : Usecase<Unit, ParticipantCounts> {
    override suspend fun invoke(param: Unit): ParticipantCounts {
        val geohashCounts = locationRepository.getParticipantCounts()
        val meshPeers = chatRepository.getMeshPeers()

        return ParticipantCounts(
            geohashCounts = geohashCounts,
            meshCount = meshPeers.size
        )
    }
}
