package com.bitchat.domain.tor

import com.bitchat.domain.base.Usecase
import com.bitchat.domain.tor.model.TorAvailability
import com.bitchat.domain.tor.repository.TorRepository

/**
 * Whether Tor can protect traffic in this build on this host, and if not, why. Constant for the
 * process lifetime: it reflects whether the HTTP engine can proxy at all and whether the native
 * Tor library loaded, not whether Tor is currently on or bootstrapped.
 */
class GetTorAvailability(
    private val torRepository: TorRepository,
) : Usecase<Unit, TorAvailability> {

    override suspend fun invoke(param: Unit): TorAvailability = torRepository.torAvailability()
}
