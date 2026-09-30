package com.bitchat.embedded.di

import com.bitchat.domain.initialization.AppInitializer
import com.bitchat.domain.initialization.models.AppInformation
import com.bitchat.domain.initialization.models.Version
import com.bitchat.embedded.BuildIdentity
import org.koin.core.module.Module
import org.koin.dsl.bind
import org.koin.dsl.module

/** App information for the binary [identity] describes, with application id [appId]. */
fun buildConfigModule(identity: BuildIdentity, appId: String): Module = module {
    single {
        AppInformation(
            version = Version(
                name = identity.version,
                build = identity.shortSha,
                additionalInfo = identity.line,
            ),
            versionCode = 1,
            id = appId,
            debug = identity.isDebug,
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
