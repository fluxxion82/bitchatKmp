package com.bitchat.domain.tor

import com.bitchat.domain.tor.model.TorMode
import kotlinx.coroutines.flow.StateFlow

/**
 * What the user has asked for, as distinct from what Tor is currently managing to do.
 *
 * Three different things were previously collapsed into one another: this intent, the effective
 * mode (`TorRepo.getTorMode`, which reports OFF when a start failed or the engine cannot proxy),
 * and readiness. Routing cannot be driven by any of the others, because each of them reads OFF
 * during exactly the windows enforcement has to cover -- a missing library, a failed bootstrap, a
 * build that cannot proxy. Only this says whether the user asked to be protected.
 *
 * [current] is readable synchronously so a routing decision never has to await a coroutine, and
 * the value is loaded from preferences at construction: application initializers run concurrently,
 * so anything restored asynchronously would be read before it arrived.
 */
interface RequestedTorIntent {
    /** The intent right now. Cheap and synchronous. */
    val current: TorMode

    /** For observers. Conflates, so never use it to drive a privacy-critical transition. */
    val updates: StateFlow<TorMode>
}

/**
 * The write side, deliberately separate.
 *
 * Only a user action -- or an explicit reset such as wiping all data -- may change intent. A
 * native start or stop completing must never write it: a slow enable that finished after the user
 * switched off used to overwrite their choice, and a slow disable did the reverse.
 */
interface MutableRequestedTorIntent : RequestedTorIntent {
    fun set(mode: TorMode)
}
