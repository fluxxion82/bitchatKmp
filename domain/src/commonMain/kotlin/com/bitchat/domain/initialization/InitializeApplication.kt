package com.bitchat.domain.initialization

import com.bitchat.domain.base.CoroutinesContextFacade
import com.bitchat.domain.base.Usecase
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch

class InitializeApplication(
    private val initializers: Set<AppInitializer>,
    private val contextFacade: CoroutinesContextFacade,
) : Usecase<Unit, Unit> {

    override suspend fun invoke(param: Unit) = coroutineScope {
        initializers.map {
            launch(contextFacade.default) { it.initialize() }
        }.joinAll()
    }
}
