package com.bitchat.domain.location

import com.bitchat.domain.base.Usecase
import com.bitchat.domain.location.model.GeohashChannel
import com.bitchat.domain.location.model.GeohashChannelLevel
import com.bitchat.domain.location.repository.LocationRepository

class GetAvailableChannels(
    private val locationRepository: LocationRepository
) : Usecase<Unit, List<GeohashChannel>> {
    override suspend fun invoke(param: Unit): List<GeohashChannel> {
        return locationRepository.getAvailableGeohashChannels()
            .filter { it.level != GeohashChannelLevel.BUILDING }
    }
}
