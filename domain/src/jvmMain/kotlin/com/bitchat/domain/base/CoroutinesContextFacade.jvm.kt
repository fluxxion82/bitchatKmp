package com.bitchat.domain.base

import kotlinx.coroutines.Dispatchers
import kotlin.coroutines.CoroutineContext

class DefaultContextFacade : CoroutinesContextFacade {
    override val io = Dispatchers.IO as CoroutineContext
    override val main = Dispatchers.Main as CoroutineContext
    override val default = Dispatchers.Default as CoroutineContext
    override val unconfined = Dispatchers.Unconfined as CoroutineContext
//    override val errorHandler = CoroutineExceptionHandler { _, error ->
//        when (error.cause) {
//            else -> throw error
//        }
//    }
}

actual val defaultContextFacade: CoroutinesContextFacade = DefaultContextFacade()
