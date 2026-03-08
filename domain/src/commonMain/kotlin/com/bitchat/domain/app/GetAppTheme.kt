package com.bitchat.domain.app

import com.bitchat.domain.app.eventbus.AppEventBus
import com.bitchat.domain.app.model.AppTheme
import com.bitchat.domain.app.repository.AppRepository
import com.bitchat.domain.base.Usecase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.onStart

class GetAppTheme(
    private val repository: AppRepository,
    private val eventBus: AppEventBus,
) : Usecase<Unit, Flow<AppTheme>> {
    override suspend fun invoke(param: Unit): Flow<AppTheme> = channelFlow {
        eventBus.getAppEvent()
            .onStart { send(repository.getAppTheme()) }
            .collect {
                send(repository.getAppTheme())
            }
    }
}
