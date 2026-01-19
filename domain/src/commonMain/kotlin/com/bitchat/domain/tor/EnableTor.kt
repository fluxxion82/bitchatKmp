package com.bitchat.domain.tor

import com.bitchat.domain.base.CoroutineScopeFacade
import com.bitchat.domain.base.Usecase
import com.bitchat.domain.tor.eventbus.TorEventBus
import com.bitchat.domain.tor.model.TorEvent
import com.bitchat.domain.tor.model.TorMode
import com.bitchat.domain.tor.repository.TorRepository
import kotlinx.coroutines.launch

class EnableTor(
    private val torRepository: TorRepository,
    private val torEventBus: TorEventBus,
    private val coroutineScopeFacade: CoroutineScopeFacade,
) : Usecase<Unit, Unit> {

    override suspend fun invoke(param: Unit) {
        coroutineScopeFacade.applicationScope.launch {
            torRepository.setTorMode(TorMode.ON)
            torEventBus.update(TorEvent.ModeChanged)
            torRepository.enable()
        }
    }
}
