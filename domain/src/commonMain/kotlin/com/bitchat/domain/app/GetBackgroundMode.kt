package com.bitchat.domain.app

import com.bitchat.domain.app.eventbus.AppEventBus
import com.bitchat.domain.app.model.AppEvent
import com.bitchat.domain.app.model.BackgroundMode
import com.bitchat.domain.app.repository.AppRepository
import com.bitchat.domain.base.Usecase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.onStart

class GetBackgroundMode(
    private val repository: AppRepository,
    private val eventBus: AppEventBus,
) : Usecase<Unit, Flow<BackgroundMode>> {
    override suspend fun invoke(param: Unit): Flow<BackgroundMode> = channelFlow {
        eventBus.getAppEvent()
            .onStart { send(repository.getBackgroundMode()) }
            .collect { event ->
                if (event is AppEvent.BackgroundModeChanged) {
                    send(repository.getBackgroundMode())
                }
            }
    }
}
