package com.bitchat.repo.di

import com.bitchat.domain.base.CoroutineScopeFacade
import com.bitchat.repo.tor.TorLifecycleControl
import com.bitchat.repo.tor.TorLifecycleCoordinator
import com.bitchat.repo.tor.TorLifecycleManager
import com.bitchat.repo.tor.TorManagerLifecycleManager
import org.koin.dsl.bind
import org.koin.dsl.module

/** Apple graph bindings for the sole Tor lifecycle authority. */
val appleTorLifecycleModule = module {
    single { TorManagerLifecycleManager(get()) } bind TorLifecycleManager::class
    single {
        TorLifecycleCoordinator(
            manager = get(),
            requestedIntent = get(),
            foregroundState = get(),
            scope = get<CoroutineScopeFacade>().applicationScope,
        )
    } bind TorLifecycleControl::class
}
