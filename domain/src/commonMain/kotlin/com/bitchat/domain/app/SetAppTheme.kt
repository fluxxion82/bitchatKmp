package com.bitchat.domain.app

import com.bitchat.domain.app.eventbus.AppEventBus
import com.bitchat.domain.app.model.AppEvent
import com.bitchat.domain.app.model.AppTheme
import com.bitchat.domain.app.repository.AppRepository
import com.bitchat.domain.base.Usecase

class SetAppTheme(
    private val repository: AppRepository,
    private val eventBus: AppEventBus,
) : Usecase<AppTheme, Unit> {
    override suspend fun invoke(param: AppTheme) {
        repository.setAppTheme(param)
        eventBus.update(AppEvent.ThemeUpdated)
    }
}
