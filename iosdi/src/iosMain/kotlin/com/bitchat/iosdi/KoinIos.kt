package com.bitchat.iosdi

import com.bitchat.domain.initialization.AppInitializer
import com.bitchat.domain.initialization.InitializeApplication
import com.bitchat.domain.initialization.models.AppInformation
import com.bitchat.domain.initialization.models.Version
import com.bitchat.iosdi.di.initKoin
import com.bitchat.repo.di.SecureStoreStart
import com.bitchat.repo.di.openSecureStores
import org.koin.core.KoinApplication
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import org.koin.dsl.module

fun initKoinIos(
    initializers: MutableSet<AppInitializer>,
    mock: Boolean,
): KoinApplication = initKoin(
    module {
        single {
            AppInformation(
                // MARKETING_VERSION and CURRENT_PROJECT_VERSION in apps/iosApp/Configuration/Config.xcconfig.
                // An app has no git identity of its own, so additionalInfo stays empty.
                version = Version("1.0", "1", ""),
                versionCode = 1,
                id = "com.bitchat.Bitchat",
                debug = true,
            )
        }

        initializers.forEach { initializer ->
            single { initializer }
        }
    },
    mock,
)

private var startedApplication: KoinApplication? = null

/**
 * Starts the app: its graph, once, and then what a start reads from the Keychain.
 *
 * iOS launches the app in the background on a locked phone (Bluetooth state restoration). While
 * the Keychain is locked - before the first unlock after a restart, or with items an older build
 * saved as readable only when unlocked - the identity cannot be loaded, and a start that went on
 * regardless ended in an exception nobody takes.
 *
 * Called from Swift, on the main thread.
 *
 * @return null once the secure stores are open and the identity is loaded; calling it again after
 *   that changes nothing. Otherwise why the Keychain did not answer: nothing is to be used yet,
 *   and this is to be called again once the device may have been unlocked.
 */
fun startApplication(
    initializers: MutableSet<AppInitializer>,
    mock: Boolean,
): String? {
    val application = startedApplication ?: initKoinIos(initializers, mock).also { startedApplication = it }
    return when (val start = application.koin.openSecureStores()) {
        SecureStoreStart.Ready -> null
        is SecureStoreStart.Unavailable -> start.reason
    }
}

// Called from Swift
object KotlinDependencies : KoinComponent {
    val initializeApplication: InitializeApplication by inject()
}
