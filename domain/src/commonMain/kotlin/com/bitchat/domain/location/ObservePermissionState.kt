package com.bitchat.domain.location

import com.bitchat.domain.base.Usecase
import com.bitchat.domain.location.eventbus.LocationEventBus
import com.bitchat.domain.location.model.LocationEvent
import com.bitchat.domain.location.model.PermissionState
import com.bitchat.domain.location.repository.LocationRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.onStart

class ObservePermissionState(
    private val locationRepository: LocationRepository,
    private val locationEventBus: LocationEventBus,
) : Usecase<Unit, Flow<PermissionState>> {

    override suspend fun invoke(param: Unit): Flow<PermissionState> = channelFlow {
        locationEventBus.events()
            .onStart {
                send(locationRepository.getPermissionState())
            }
            .collect { event ->
                when (event) {
                    LocationEvent.PermissionStateChanged -> {
                        send(locationRepository.getPermissionState())
                    }

                    else -> Unit
                }
            }
    }
}
