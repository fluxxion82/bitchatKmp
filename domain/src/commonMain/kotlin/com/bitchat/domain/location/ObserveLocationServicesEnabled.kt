package com.bitchat.domain.location

import com.bitchat.domain.base.Usecase
import com.bitchat.domain.location.eventbus.LocationEventBus
import com.bitchat.domain.location.model.LocationEvent
import com.bitchat.domain.location.repository.LocationRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.onStart

class ObserveLocationServicesEnabled(
    private val locationRepository: LocationRepository,
    private val locationEventBus: LocationEventBus,
) : Usecase<Unit, Flow<Boolean>> {

    override suspend fun invoke(param: Unit): Flow<Boolean> = channelFlow {
        locationEventBus.events()
            .onStart {
                send(locationRepository.isLocationServicesEnabled())
            }
            .collect { event ->
                when (event) {
                    LocationEvent.LocationServicesChanged -> {
                        send(locationRepository.isLocationServicesEnabled())
                    }

                    else -> Unit
                }
            }
    }
}
