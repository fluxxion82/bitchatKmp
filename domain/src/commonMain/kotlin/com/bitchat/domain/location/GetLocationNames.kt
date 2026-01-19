package com.bitchat.domain.location

import com.bitchat.domain.base.Usecase
import com.bitchat.domain.location.model.GeohashChannelLevel
import com.bitchat.domain.location.repository.LocationRepository

class GetLocationNames(
    private val locationRepository: LocationRepository
) : Usecase<Unit, Map<GeohashChannelLevel, String>> {
    override suspend fun invoke(param: Unit): Map<GeohashChannelLevel, String> {
        return locationRepository.getLocationNames()
    }
}
