package com.bitchat.domain.base

import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlin.coroutines.CoroutineContext

interface CoroutineScopeFacade {
    val applicationScope: CoroutineScope
    val connectivityEventScope: CoroutineScope
    val bluetoothScope: CoroutineScope
    val nostrScope: CoroutineScope
}

class DefaultScopeFacade(
    private val contextFacade: CoroutinesContextFacade,
) : CoroutineScopeFacade {
    private val appJob = SupervisorJob()
    private val handler = CoroutineExceptionHandler { ctx, t ->
        println("Coroutine uncaught [${ctx[CoroutineName]}]")
        t.printStackTrace()
    }

    override val applicationScope: CoroutineScope =
        CoroutineScope(appJob + contextFacade.default + handler + CoroutineName("app"))

    override val connectivityEventScope: CoroutineScope = childScope("connectivity", contextFacade.io)
    override val bluetoothScope: CoroutineScope = childScope("bluetooth", contextFacade.io)
    override val nostrScope: CoroutineScope = childScope("nostr", contextFacade.io)

    private fun childScope(
        name: String,
        context: CoroutineContext
    ): CoroutineScope {
        val parent = applicationScope.coroutineContext[Job]
        return CoroutineScope(
            SupervisorJob(parent) + context + handler + CoroutineName(name)
        )
    }
}
