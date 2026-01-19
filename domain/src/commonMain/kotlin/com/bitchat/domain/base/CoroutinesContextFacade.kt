package com.bitchat.domain.base

import kotlin.coroutines.CoroutineContext

interface CoroutinesContextFacade {
    val io: CoroutineContext
    val main: CoroutineContext
    val default: CoroutineContext
    val unconfined: CoroutineContext
    val compatScoreDispatcher: CoroutineContext
    // val errorHandler: CoroutineContext
}

expect val defaultContextFacade: CoroutinesContextFacade
