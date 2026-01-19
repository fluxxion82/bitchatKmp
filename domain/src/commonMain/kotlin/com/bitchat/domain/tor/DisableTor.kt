package com.bitchat.domain.tor

import com.bitchat.domain.base.Usecase
import com.bitchat.domain.tor.eventbus.TorEventBus
import com.bitchat.domain.tor.model.TorEvent
import com.bitchat.domain.tor.model.TorMode
import com.bitchat.domain.tor.repository.TorRepository

class DisableTor(
    private val torRepository: TorRepository,
    private val torEventBus: TorEventBus,
) : Usecase<Unit, Unit> {

    override suspend fun invoke(param: Unit) {
        torRepository.setTorMode(TorMode.OFF)
        torEventBus.update(TorEvent.ModeChanged)
        torRepository.disable()
    }
}
