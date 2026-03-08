package com.bitchat.embedded.di

import com.bitchat.domain.initialization.AppInitializer
import com.bitchat.domain.initialization.models.AppInformation
import com.bitchat.domain.initialization.models.Version
import org.koin.dsl.bind
import org.koin.dsl.module

val buildConfigModule = module {
    single {
        AppInformation(
            version = "1.0.0".toVersion(),
            versionCode = 1,
            id = "com.bitchat.embedded",
            debug = true,
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

internal fun String.toVersion(): Version {
    val splitVersion = if (isEmpty()) {
        listOf("0.0.0", "0")
    } else if (!contains("_")) {
        listOf(this, "0")
    } else {
        this.split("_").map { it }
    }

    return Version(
        name = splitVersion[0],
        build = splitVersion[1].substringBefore("-", splitVersion[1].substringBefore(".", splitVersion[1])),
        additionalInfo = "",
    )
}
