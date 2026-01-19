package com.bitchat.bluetooth.di

import com.bitchat.bluetooth.service.MacosConnectionEventBus
import com.bitchat.domain.connectivity.eventbus.ConnectionEventBus
import org.koin.core.module.Module
import org.koin.dsl.module

actual val connectionModule: Module = module {
    single<ConnectionEventBus> {
        MacosConnectionEventBus(
            coroutineScopeFacade = get(),
        )
    }
}
