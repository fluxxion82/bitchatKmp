package com.bitchat.domain.location

import com.bitchat.domain.base.Usecase
import com.bitchat.domain.location.model.GeohashChannelLevel
import com.bitchat.domain.location.model.NearbyChannels
import com.bitchat.domain.location.repository.LocationRepository

class GetAvailableChannels(
    private val locationRepository: LocationRepository
) : Usecase<Unit, NearbyChannels> {
    override suspend fun invoke(param: Unit): NearbyChannels {
        val nearby = locationRepository.getAvailableGeohashChannels()
        return nearby.copy(channels = nearby.channels.filter { it.level != GeohashChannelLevel.BUILDING })
    }
}
