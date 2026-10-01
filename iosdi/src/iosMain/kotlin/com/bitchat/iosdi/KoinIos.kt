package com.bitchat.iosdi

import com.bitchat.domain.initialization.AppInitializer
import com.bitchat.domain.initialization.InitializeApplication
import com.bitchat.domain.initialization.models.AppInformation
import com.bitchat.domain.initialization.models.Version
import com.bitchat.iosdi.di.initKoin
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

// Called from Swift
object KotlinDependencies : KoinComponent {
    val initializeApplication: InitializeApplication by inject()
}
