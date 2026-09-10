package com.bitchat.domain.tor

import com.bitchat.domain.base.CoroutineScopeFacade
import com.bitchat.domain.base.Usecase
import com.bitchat.domain.tor.eventbus.TorEventBus
import com.bitchat.domain.tor.model.TorMode
import com.bitchat.domain.tor.model.TorEvent
import com.bitchat.domain.tor.repository.TorRepository
import kotlinx.coroutines.launch

class EnableTor(
    private val torRepository: TorRepository,
    private val requestedIntent: MutableRequestedTorIntent,
    private val torEventBus: TorEventBus,
    private val coroutineScopeFacade: CoroutineScopeFacade,
) : Usecase<Unit, Unit> {

    override suspend fun invoke(param: Unit) {
        /*
         * Published before the native work is launched, and never by that work completing.
         *
         * enable() used to persist ON once Tor was actually up. That made the intent a result
         * rather than a request: a start that blocked for the length of a bootstrap would write ON
         * after the user had already switched off, and routing had nothing to read during the
         * whole window in between - which is exactly the window enforcement has to cover.
         */
        requestedIntent.set(TorMode.ON)
        torEventBus.update(TorEvent.ModeChanged)

        coroutineScopeFacade.applicationScope.launch {
            torRepository.enable()
            torEventBus.update(TorEvent.ModeChanged)
        }
    }
}
