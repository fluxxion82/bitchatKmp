package com.bitchat.iosdi.di

import com.bitchat.bluetooth.di.bluetoothModule
import com.bitchat.client.di.clientModule
import com.bitchat.domain.di.domainModule
import com.bitchat.local.di.commonLocal
import com.bitchat.local.di.localModule
import com.bitchat.nostr.di.nostrModule
import com.bitchat.repo.di.commonRepoModule
import com.bitchat.tor.di.torModule
import com.bitchat.viewmodel.di.viewModelModule
import org.koin.core.KoinApplication
import org.koin.core.context.startKoin
import org.koin.core.module.Module

fun initKoin(appModule: Module, mock: Boolean): KoinApplication {
    val koinApplication = startKoin {
        modules(
            appModule,
            commonLocal,
            commonRepoModule,
            localModule,
            viewModelModule,
            domainModule,
            bluetoothModule,
            commonLocal,
            localModule,
            clientModule,
            nostrModule,
            torModule,
        )
    }

    return koinApplication
}
