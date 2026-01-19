package com.bitchat.domain.location

import com.bitchat.domain.base.Usecase
import com.bitchat.domain.location.model.GeohashChannelLevel
import com.bitchat.domain.location.repository.LocationRepository

class ResolveLocationName(
    private val locationRepository: LocationRepository
) : Usecase<ResolveLocationName.Params, String?> {

    data class Params(
        val geohash: String,
        val level: GeohashChannelLevel
    )

    override suspend fun invoke(param: Params): String? {
        return locationRepository.resolveLocationName(param.geohash, param.level)
    }
}
