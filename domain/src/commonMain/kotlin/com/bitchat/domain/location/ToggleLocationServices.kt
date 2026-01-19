package com.bitchat.domain.location

import com.bitchat.domain.base.Usecase
import com.bitchat.domain.location.eventbus.LocationEventBus
import com.bitchat.domain.location.model.LocationEvent
import com.bitchat.domain.location.repository.LocationRepository

class ToggleLocationServices(
    private val locationRepository: LocationRepository,
    private val locationEventBus: LocationEventBus,
) : Usecase<Unit, Unit> {
    override suspend fun invoke(param: Unit) {
        locationRepository.toggleLocationServices()
        locationEventBus.update(LocationEvent.LocationServicesChanged)
    }
}
