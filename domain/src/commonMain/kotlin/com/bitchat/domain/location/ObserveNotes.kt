package com.bitchat.domain.location

import com.bitchat.domain.base.Usecase
import com.bitchat.domain.location.eventbus.LocationEventBus
import com.bitchat.domain.location.model.LocationEvent
import com.bitchat.domain.location.model.Note
import com.bitchat.domain.location.repository.LocationRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.onStart

class ObserveNotes(
    private val repository: LocationRepository,
    private val locationEventBus: LocationEventBus,
) : Usecase<String, Flow<List<Note>>> {

    override suspend fun invoke(param: String): Flow<List<Note>> = channelFlow {
        locationEventBus.events()
            .onStart {
                send(repository.getNotes(param))
            }
            .collect { event ->
                when (event) {
                    LocationEvent.NotesChanged -> {
                        send(repository.getNotes(param))
                    }

                    else -> Unit
                }
            }
    }
}
