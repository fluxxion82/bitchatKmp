package com.bitchat.android.di

import com.bitchat.android.BuildConfig
import com.bitchat.domain.initialization.models.AppInformation
import com.bitchat.domain.initialization.models.Version
import org.koin.dsl.module

val buildConfigModule = module {
    single {
        AppInformation(
            // From build.gradle.kts, so Settings shows the version the APK really has. An APK
            // carries no git identity of its own, so there is no additionalInfo to show.
            version = Version(BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE.toString(), ""),
            versionCode = BuildConfig.VERSION_CODE,
            id = "com.bitchat.android",
            debug = true,
        )
    }
}
