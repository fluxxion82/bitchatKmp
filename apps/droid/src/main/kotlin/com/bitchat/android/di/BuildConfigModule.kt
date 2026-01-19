package com.bitchat.android.di

import com.bitchat.domain.initialization.models.AppInformation
import com.bitchat.domain.initialization.models.Version
import org.koin.dsl.module

val buildConfigModule = module {
    single {
        AppInformation(
            version = Version("1", "0", "0"),
            versionCode = 1,
            id = "com.bitchat.android",
            debug = true,
        )
    }
}
