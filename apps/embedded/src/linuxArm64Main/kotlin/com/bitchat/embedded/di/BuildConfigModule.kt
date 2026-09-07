package com.bitchat.embedded.di

import com.bitchat.domain.initialization.AppInitializer
import com.bitchat.domain.initialization.models.AppInformation
import com.bitchat.domain.initialization.models.Version
import com.bitchat.embedded.BuildIdentity
import com.bitchat.embedded.EmbeddedBuildInfo
import org.koin.dsl.bind
import org.koin.dsl.module

val buildConfigModule = module {
    single {
        AppInformation(
            version = Version(
                name = EmbeddedBuildInfo.VERSION,
                build = BuildIdentity.shortSha,
                additionalInfo = BuildIdentity.line,
            ),
            versionCode = 1,
            id = "com.bitchat.embedded",
            debug = BuildIdentity.isDebug,
        )
    }

    // Auto-activate user state for embedded (no onboarding UI)
    single {
        EmbeddedUserStateInitializer(
            userRepository = get(),
            userEventBus = get(),
        )
    } bind AppInitializer::class
}
