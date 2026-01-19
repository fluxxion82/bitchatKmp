package com.bitchat.domain.app

import com.bitchat.domain.app.eventbus.AppEventBus
import com.bitchat.domain.app.model.AppTheme
import com.bitchat.domain.app.repository.AppRepository
import com.bitchat.domain.base.Usecase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.receiveAsFlow

class GetAppTheme(
    private val repository: AppRepository,
    private val eventBus: AppEventBus,
) : Usecase<Unit, Flow<AppTheme>> {
    override suspend fun invoke(param: Unit): Flow<AppTheme> = channelFlow {
        eventBus.getAppEvent()
            .receiveAsFlow()
            .onStart { send(repository.getAppTheme()) }
            .map { repository.getAppTheme() }
            .collect {
                send(it)
            }
    }
}
