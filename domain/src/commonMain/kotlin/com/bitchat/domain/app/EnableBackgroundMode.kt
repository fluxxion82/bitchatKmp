package com.bitchat.domain.app

import com.bitchat.domain.app.eventbus.AppEventBus
import com.bitchat.domain.app.model.AppEvent
import com.bitchat.domain.app.repository.AppRepository
import com.bitchat.domain.base.Usecase

class EnableBackgroundMode(
    private val repository: AppRepository,
    private val eventBus: AppEventBus,
) : Usecase<Unit, Unit> {
    override suspend fun invoke(param: Unit) {
        repository.enableBackgroundMode()
        eventBus.update(AppEvent.BackgroundModeChanged)
    }
}
