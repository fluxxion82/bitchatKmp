package com.bitchat.tor.di

import com.bitchat.tor.TorManager
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.dsl.module

val torModule = module {
    includes(torPlatformModule)
    single { TorManager(dataDir = get(named("torDataDir"))) }
}

expect val torPlatformModule: Module
