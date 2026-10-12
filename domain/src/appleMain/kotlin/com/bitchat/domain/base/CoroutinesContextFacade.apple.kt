package com.bitchat.domain.base

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlin.coroutines.CoroutineContext

class DefaultContextFacade : CoroutinesContextFacade {
    // One worker: the repositories hold collections that only running one at a time keeps safe.
    override val io: CoroutineContext = Dispatchers.IO.limitedParallelism(1)
    override val main: CoroutineContext = Dispatchers.Main

    // Initializers and the Bluetooth services' application-scope jobs need the main queue.
    override val default: CoroutineContext = Dispatchers.Main
    override val unconfined: CoroutineContext = Dispatchers.Unconfined
}

actual val defaultContextFacade: CoroutinesContextFacade = DefaultContextFacade()
