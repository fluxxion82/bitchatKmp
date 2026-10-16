package com.bitchat.domain.app

import com.bitchat.domain.app.eventbus.AppEventBus
import com.bitchat.domain.app.model.AppEvent
import com.bitchat.domain.app.model.BackgroundMode
import com.bitchat.domain.app.repository.AppRepository
import com.bitchat.domain.base.Usecase
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch

class ObserveBackgroundMode(
    private val repository: AppRepository,
    private val eventBus: AppEventBus,
) : Usecase<Unit, Flow<BackgroundMode>> {
    override suspend fun invoke(param: Unit): Flow<BackgroundMode> = channelFlow {
        // Subscribed before the first read: an event published during it must not be lost to a later one.
        val events = Channel<AppEvent>(Channel.BUFFERED)
        launch(start = CoroutineStart.UNDISPATCHED) { eventBus.getAppEvent().collect(events::send) }

        send(repository.getBackgroundMode())
        for (event in events) {
            if (event is AppEvent.BackgroundModeChanged) {
                send(repository.getBackgroundMode())
            }
        }
    }
}
