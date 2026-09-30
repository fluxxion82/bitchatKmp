package com.bitchat.embedded.di

import com.bitchat.domain.initialization.models.AppInformation
import com.bitchat.domain.initialization.models.Version
import com.bitchat.embedded.BuildIdentity
import com.bitchat.repo.initialization.headlessUserStateModule
import org.koin.core.module.Module
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

    includes(headlessUserStateModule)
}
