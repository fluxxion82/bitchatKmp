package com.bitchat.domain.location

import com.bitchat.domain.base.Usecase
import com.bitchat.domain.location.repository.LocationRepository

class BeginGeohashSampling(
    private val locationRepository: LocationRepository
) : Usecase<List<String>, Unit> {
    override suspend fun invoke(param: List<String>) {
        locationRepository.beginGeohashSampling(param)
    }
}
