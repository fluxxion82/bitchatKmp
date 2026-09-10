package com.bitchat.domain.tor

import com.bitchat.domain.base.Usecase
import com.bitchat.domain.tor.eventbus.TorEventBus
import com.bitchat.domain.tor.model.TorEvent
import com.bitchat.domain.tor.model.TorMode
import com.bitchat.domain.tor.repository.TorRepository

class DisableTor(
    private val torRepository: TorRepository,
    private val requestedIntent: MutableRequestedTorIntent,
    private val torEventBus: TorEventBus,
) : Usecase<Unit, Unit> {

    override suspend fun invoke(param: Unit) {
        /*
         * Intent first, then the native stop. Turning Tor off must take effect the moment the user
         * asks, not when a stop that may block finally returns -- and it must work even when Tor
         * never started, which on a platform with no library is every time.
         */
        requestedIntent.set(TorMode.OFF)
        torEventBus.update(TorEvent.ModeChanged)
        torRepository.disable()
    }
}
