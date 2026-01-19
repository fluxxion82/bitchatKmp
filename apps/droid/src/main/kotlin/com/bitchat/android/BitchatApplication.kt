package com.bitchat.android

import android.app.Application
import com.bitchat.android.di.appModule
import com.bitchat.android.di.buildConfigModule
import com.bitchat.android.di.buildTypeModule
import com.bitchat.bluetooth.di.bluetoothModule
import com.bitchat.client.di.clientModule
import com.bitchat.domain.base.invoke
import com.bitchat.domain.di.domainModule
import com.bitchat.domain.initialization.InitializeApplication
import com.bitchat.local.di.commonLocal
import com.bitchat.local.di.localModule
import com.bitchat.mediautils.initMediaFileUtils
import com.bitchat.nostr.di.nostrModule
import com.bitchat.repo.di.commonRepoModule
import com.bitchat.tor.di.torModule
import com.bitchat.viewmodel.di.viewModelModule
import kotlinx.coroutines.runBlocking
import org.koin.android.ext.koin.androidContext
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import org.koin.core.context.GlobalContext.startKoin

class BitchatApplication : Application(), KoinComponent {
    private val initializeApp: InitializeApplication by inject()

    override fun onCreate() {
        super.onCreate()

        initMediaFileUtils(this)

        registerKoin()

        runBlocking {
            initializeApp()
        }
    }

    private fun registerKoin() {
        startKoin {
            androidContext(this@BitchatApplication)

            modules(
                listOf(
                    appModule,
                    buildTypeModule,
                    buildConfigModule,
                    commonRepoModule,
                    domainModule,
                    commonLocal,
                    localModule,
                    clientModule,
                    viewModelModule,
                    bluetoothModule,
                    nostrModule,
                    torModule,
                )
            )
        }
    }
}
