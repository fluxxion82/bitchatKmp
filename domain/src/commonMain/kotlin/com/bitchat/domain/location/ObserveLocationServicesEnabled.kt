package com.bitchat.domain.location

import com.bitchat.domain.base.Usecase
import com.bitchat.domain.location.eventbus.LocationEventBus
import com.bitchat.domain.location.model.LocationEvent
import com.bitchat.domain.location.repository.LocationRepository
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch

class ObserveLocationServicesEnabled(
    private val locationRepository: LocationRepository,
    private val locationEventBus: LocationEventBus,
) : Usecase<Unit, Flow<Boolean>> {

    override suspend fun invoke(param: Unit): Flow<Boolean> = channelFlow {
        // Subscribed before the first read: an event published during it must not be lost to a later one.
        val events = Channel<LocationEvent>(Channel.BUFFERED)
        launch(start = CoroutineStart.UNDISPATCHED) { locationEventBus.events().collect(events::send) }

        send(locationRepository.isLocationServicesEnabled())
        for (event in events) {
            when (event) {
                LocationEvent.LocationServicesChanged -> {
                    send(locationRepository.isLocationServicesEnabled())
                }

                else -> Unit
            }
        }
    }
}
