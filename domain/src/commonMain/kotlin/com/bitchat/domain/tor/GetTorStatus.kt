package com.bitchat.domain.tor

import com.bitchat.domain.base.Usecase
import com.bitchat.domain.tor.eventbus.TorEventBus
import com.bitchat.domain.tor.model.TorEvent
import com.bitchat.domain.tor.model.TorStatus
import com.bitchat.domain.tor.repository.TorRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.onStart

class GetTorStatus(
    private val torRepository: TorRepository,
    private val torEventBus: TorEventBus,
) : Usecase<Unit, Flow<TorStatus>> {

    override suspend fun invoke(param: Unit): Flow<TorStatus> = channelFlow {
        suspend fun currentStatus(): TorStatus {
            val mode = torRepository.getTorMode()
            return torRepository.getTorStatus().copy(mode = mode)
        }

        torEventBus.events()
            .onStart {
                send(currentStatus())
            }
            .collect { event ->
                when (event) {
                    TorEvent.StatusChanged,
                    TorEvent.ModeChanged -> {
                        send(currentStatus())
                    }
                }
            }
    }
}
