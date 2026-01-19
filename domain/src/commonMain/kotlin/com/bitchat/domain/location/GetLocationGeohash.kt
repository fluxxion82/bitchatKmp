package com.bitchat.domain.location

import com.bitchat.domain.base.Usecase
import com.bitchat.domain.location.model.GeohashChannelLevel
import com.bitchat.domain.location.repository.LocationRepository

class GetLocationGeohash(
    private val repository: LocationRepository
) : Usecase<GeohashChannelLevel, String> {

    override suspend fun invoke(param: GeohashChannelLevel): String {
        return repository.getLocationGeohash(param)
    }
}
