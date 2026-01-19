package com.bitchat.desktop

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.runtime.remember
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.bitchat.bluetooth.di.bluetoothModule
import com.bitchat.client.di.clientModule
import com.bitchat.desktop.ble.NativeBleLoader
import com.bitchat.desktop.di.buildConfigModule
import com.bitchat.desktop.location.NativeLocationLoader
import com.bitchat.domain.base.invoke
import com.bitchat.domain.di.domainModule
import com.bitchat.domain.initialization.InitializeApplication
import com.bitchat.local.di.commonLocal
import com.bitchat.local.di.localModule
import com.bitchat.nostr.di.nostrModule
import com.bitchat.repo.di.commonRepoModule
import com.bitchat.repo.di.repoModule
import com.bitchat.screens.BitchatGraph
import com.bitchat.tor.di.torModule
import com.bitchat.viewmodel.di.viewModelModule
import com.bitchat.viewmodel.main.MainViewModel
import kotlinx.coroutines.InternalCoroutinesApi
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import org.koin.core.context.startKoin

@OptIn(ExperimentalFoundationApi::class, InternalCoroutinesApi::class)
fun main() {
    application {
        val app = remember { App() }
        kotlinx.coroutines.runBlocking {
            app.initializeApplication()
        }

        Window(
            onCloseRequest = ::exitApplication,
            title = "Bitchat",
        ) {
            BitchatGraph(app.mainViewModel)
        }
    }
}

class App : KoinComponent {
    val initializeApplication: InitializeApplication by inject()
    val mainViewModel: MainViewModel by inject()

    init {
        NativeBleLoader.loadIfEnabled()
        NativeLocationLoader.loadIfEnabled()
        startKoin {
            modules(
                buildConfigModule,
                domainModule,
                commonLocal,
                localModule,
                clientModule,
                commonRepoModule,
                repoModule,
                viewModelModule,
                nostrModule,
                bluetoothModule,
                torModule,
            )
        }
    }
}
