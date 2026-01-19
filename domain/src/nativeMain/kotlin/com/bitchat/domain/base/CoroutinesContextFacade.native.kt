package com.bitchat.domain.base

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Runnable
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import platform.darwin.dispatch_queue_t
import kotlin.coroutines.CoroutineContext

class DefaultContextFacade : CoroutinesContextFacade {
    override val io = NsQueueDispatcher(dispatch_get_main_queue()) as CoroutineContext
    override val main = NsQueueDispatcher(dispatch_get_main_queue()) as CoroutineContext
    override val default = NsQueueDispatcher(dispatch_get_main_queue()) as CoroutineContext
    override val unconfined = NsQueueDispatcher(dispatch_get_main_queue()) as CoroutineContext
    override val compatScoreDispatcher: CoroutineContext = Dispatchers.Default.limitedParallelism(32)
//    override val errorHandler = CoroutineExceptionHandler { _, error ->
//        when (error.cause) {
//            else -> throw error
//        }
//    }
}

class NsQueueDispatcher(
    private val dispatchQueue: dispatch_queue_t,
) : CoroutineDispatcher() {
    override fun dispatch(context: CoroutineContext, block: Runnable) {
        dispatch_async(dispatchQueue) {
            block.run()
        }
    }
}

actual val defaultContextFacade: CoroutinesContextFacade = DefaultContextFacade()
