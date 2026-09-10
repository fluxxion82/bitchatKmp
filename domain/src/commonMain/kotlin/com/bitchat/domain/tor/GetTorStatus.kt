package com.bitchat.domain.tor

import com.bitchat.domain.base.Usecase
import com.bitchat.domain.tor.eventbus.TorEventBus
import com.bitchat.domain.tor.model.TorAvailability
import com.bitchat.domain.tor.model.TorEvent
import com.bitchat.domain.tor.model.TorMode
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
            /*
             * The *requested* mode, not the effective one.
             *
             * The header shows its Tor indicator only when this is ON. Reporting the effective
             * mode meant it read OFF exactly when Tor had been asked for and failed -- so the
             * indicator disappeared at the one moment the user needed to see it, and a Tor that
             * was not protecting them looked identical to a Tor they had never turned on.
             *
             * Qualified by availability for the same reason the settings switch is: where the
             * HTTP engine cannot use a SOCKS proxy at all, a stored ON never protected anything,
             * and showing an indicator for it would claim otherwise.
             */
            val mode = if (torRepository.torAvailability() == TorAvailability.NO_PROXY_SUPPORT) {
                TorMode.OFF
            } else {
                torRepository.getStoredTorMode()
            }
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
