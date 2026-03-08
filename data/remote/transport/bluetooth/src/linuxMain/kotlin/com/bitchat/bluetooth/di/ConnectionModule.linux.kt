package com.bitchat.bluetooth.di

import com.bitchat.bluetooth.service.LinuxConnectionEventBus
import com.bitchat.domain.connectivity.eventbus.ConnectionEventBus
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Linux connection module providing stub ConnectionEventBus.
 */
actual val connectionModule: Module = module {
    single<ConnectionEventBus> { LinuxConnectionEventBus() }
}
