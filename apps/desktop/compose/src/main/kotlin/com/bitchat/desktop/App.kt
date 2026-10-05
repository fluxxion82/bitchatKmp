package com.bitchat.desktop

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.bitchat.design.BitchatTheme
import com.bitchat.desktop.ble.NativeBleLoader
import com.bitchat.desktop.di.desktopDataModules
import com.bitchat.desktop.di.LoRaProtocolSelector
import com.bitchat.desktop.location.NativeLocationLoader
import com.bitchat.domain.app.model.AppTheme
import com.bitchat.domain.base.LogPolicy
import com.bitchat.domain.base.invoke
import com.bitchat.domain.initialization.InitializeApplication
import com.bitchat.screens.BitchatGraph
import com.bitchat.viewmodel.main.MainViewModel
import kotlinx.coroutines.InternalCoroutinesApi
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import org.koin.core.context.startKoin

@OptIn(ExperimentalFoundationApi::class, InternalCoroutinesApi::class)
fun main() {
    SingleInstanceLock.acquireOrExit()
    // Message bodies stay out of the logs unless explicitly asked for.
    LogPolicy.configure(System.getenv(LogPolicy.ENV_VAR))
    application {
        val app = remember { App() }
        kotlinx.coroutines.runBlocking {
            app.initializeApplication()
        }

        Window(
            onCloseRequest = ::exitApplication,
            title = "Bitchat",
        ) {
            val mainViewModel = app.mainViewModel
            val appTheme by mainViewModel.appTheme.collectAsState()
            println("app theme: $appTheme")
            val isDarkTheme = when (appTheme) {
                AppTheme.SYSTEM -> isSystemInDarkTheme()
                AppTheme.LIGHT -> false
                AppTheme.DARK -> true
            }

            BitchatTheme(darkTheme = isDarkTheme) {
                BitchatGraph(app.mainViewModel)
            }
        }
    }
}

class App : KoinComponent {
    val initializeApplication: InitializeApplication by inject()
    val mainViewModel: MainViewModel by inject()

    init {
        NativeBleLoader.loadIfEnabled()
        NativeLocationLoader.loadIfEnabled()

        // Get preferred protocol for initial selection
        val initialProtocol = LoRaProtocolSelector.getPreferredProtocol()

        startKoin {
            modules(desktopDataModules("com.bitchat.desktop", initialProtocol))
        }
    }
}
