package com.bitchat.domain.location

import com.bitchat.domain.base.Usecase
import com.bitchat.domain.location.eventbus.LocationEventBus
import com.bitchat.domain.location.model.LocationEvent
import com.bitchat.domain.location.model.Note
import com.bitchat.domain.location.repository.LocationRepository
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch

class ObserveNotes(
    private val repository: LocationRepository,
    private val locationEventBus: LocationEventBus,
) : Usecase<String, Flow<List<Note>>> {

    override suspend fun invoke(param: String): Flow<List<Note>> = channelFlow {
        // Subscribed before the first read: an event published during it must not be lost to a later one.
        val events = Channel<LocationEvent>(Channel.BUFFERED)
        launch(start = CoroutineStart.UNDISPATCHED) { locationEventBus.events().collect(events::send) }

        send(repository.getNotes(param))
        for (event in events) {
            when (event) {
                LocationEvent.NotesChanged -> {
                    send(repository.getNotes(param))
                }

                else -> Unit
            }
        }
    }
}
