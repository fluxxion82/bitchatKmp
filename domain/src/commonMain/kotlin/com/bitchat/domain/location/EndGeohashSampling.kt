package com.bitchat.domain.location

import com.bitchat.domain.base.Usecase
import com.bitchat.domain.location.repository.LocationRepository

class EndGeohashSampling(
    private val locationRepository: LocationRepository
) : Usecase<Unit, Unit> {
    override suspend fun invoke(param: Unit) {
        locationRepository.endGeohashSampling()
    }
}
