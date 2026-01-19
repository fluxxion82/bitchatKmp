package com.bitchat.domain.location

import com.bitchat.domain.base.Usecase
import com.bitchat.domain.location.model.PermissionState
import com.bitchat.domain.location.repository.LocationRepository

class GetPermissionState(
    private val locationRepository: LocationRepository,
) : Usecase<Unit, PermissionState> {

    override suspend fun invoke(param: Unit): PermissionState {
        return locationRepository.getPermissionState()
    }
}
