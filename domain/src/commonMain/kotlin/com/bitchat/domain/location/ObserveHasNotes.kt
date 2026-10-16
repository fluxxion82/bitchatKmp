package com.bitchat.domain.location

import com.bitchat.domain.app.model.ActiveState
import com.bitchat.domain.app.model.UserState
import com.bitchat.domain.base.Usecase
import com.bitchat.domain.location.eventbus.LocationEventBus
import com.bitchat.domain.location.model.Channel
import com.bitchat.domain.location.model.LocationEvent
import com.bitchat.domain.location.repository.LocationRepository
import com.bitchat.domain.user.repository.UserRepository
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.channels.Channel as EventChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch

class ObserveHasNotes(
    private val locationRepository: LocationRepository,
    private val locationEventBus: LocationEventBus,
    private val userRepository: UserRepository,
) : Usecase<Unit, Flow<Boolean>> {

    override suspend fun invoke(param: Unit): Flow<Boolean> = channelFlow {
        // Subscribed before the first read: an event published during it must not be lost to a later one.
        val events = EventChannel<LocationEvent>(EventChannel.BUFFERED)
        launch(start = CoroutineStart.UNDISPATCHED) { locationEventBus.events().collect(events::send) }

        send(getHasNotes())
        for (event in events) {
            when (event) {
                LocationEvent.ChannelChanged,
                LocationEvent.NotesChanged -> {
                    send(getHasNotes())
                }

                else -> Unit
            }
        }
    }

    private suspend fun getHasNotes(): Boolean {
        val selectedChannel = userRepository.getUserState()
            ?.let { it as? UserState.Active }
            ?.activeState?.let { it as? ActiveState.Chat }
            ?.channel

        return when (selectedChannel) {
            is Channel.Location -> locationRepository.hasNotes(selectedChannel.geohash)
            is Channel.Mesh -> {
                /*
                 * Deliberately does not take a location fix.
                 *
                 * This asked for the nearest geohash channel and then called hasNotes on it, but
                 * hasNotes is hardcoded false until notes storage exists, so the answer was always
                 * false. The collector is installed by MainViewModel, so every cold launch took a
                 * location fix purely to discard the result. Restore the fix when hasNotes does
                 * something.
                 */
                false
            }

            is Channel.MeshDM,
            is Channel.NostrDM,
            is Channel.NamedChannel,
            is Channel.Meshtastic,
            null -> false
        }
    }
}
