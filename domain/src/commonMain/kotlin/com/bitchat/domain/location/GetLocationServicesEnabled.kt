package com.bitchat.domain.location

import com.bitchat.domain.base.Usecase
import com.bitchat.domain.location.repository.LocationRepository

class GetLocationServicesEnabled(
    private val locationRepository: LocationRepository
) : Usecase<Unit, Boolean> {
    override suspend fun invoke(param: Unit): Boolean {
        return locationRepository.isLocationServicesEnabled()
    }
}
