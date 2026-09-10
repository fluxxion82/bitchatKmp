package com.bitchat.domain.location

import com.bitchat.domain.base.Usecase
import com.bitchat.domain.location.model.LocationFixInfo
import com.bitchat.domain.location.repository.LocationRepository

class GetLastFixInfo(
    private val locationRepository: LocationRepository,
) : Usecase<Unit, LocationFixInfo?> {

    override suspend fun invoke(param: Unit): LocationFixInfo? =
        locationRepository.getLastFixInfo()
}
