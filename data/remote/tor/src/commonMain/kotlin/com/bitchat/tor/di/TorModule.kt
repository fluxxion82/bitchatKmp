package com.bitchat.tor.di

import com.bitchat.tor.TorManager
import com.bitchat.tor.TorManagerRouteSource
import com.bitchat.tor.TorRouteSource
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.dsl.module

val torModule = module {
    includes(torPlatformModule)
    single { TorManager(dataDir = get(named("torDataDir"))) }
    single<TorRouteSource> { TorManagerRouteSource(get()) }
}

expect val torPlatformModule: Module
