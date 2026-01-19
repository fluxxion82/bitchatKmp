package com.bitchat.domain.tor

import com.bitchat.domain.base.Usecase
import com.bitchat.domain.tor.eventbus.TorEventBus
import com.bitchat.domain.tor.model.TorEvent
import com.bitchat.domain.tor.model.TorMode
import com.bitchat.domain.tor.repository.TorRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.onStart

class GetTorMode(
    private val torRepository: TorRepository,
    private val torEventBus: TorEventBus,
) : Usecase<Unit, Flow<TorMode>> {

    override suspend fun invoke(param: Unit): Flow<TorMode> = channelFlow {
        torEventBus.events()
            .onStart {
                send(torRepository.getTorMode())
            }
            .collect { event ->
                when (event) {
                    TorEvent.ModeChanged -> {
                        send(torRepository.getTorMode())
                    }

                    else -> Unit
                }
            }
    }
}
