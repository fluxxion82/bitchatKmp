package com.bitchat.domain.tor

import com.bitchat.domain.base.Usecase
import com.bitchat.domain.tor.model.TorMode
import kotlinx.coroutines.flow.Flow

/**
 * What the user has asked for, for the settings switch to render.
 *
 * Distinct from [GetTorMode], which reports the *effective* mode and so answers OFF whenever
 * protection is not real. Driving the switch from that meant a failed start left it unchecked and
 * — being disabled at the same time — unable to express OFF at all.
 */
class ObserveRequestedTorMode(
    private val requestedIntent: RequestedTorIntent,
) : Usecase<Unit, Flow<TorMode>> {

    override suspend fun invoke(param: Unit): Flow<TorMode> = requestedIntent.updates
}
