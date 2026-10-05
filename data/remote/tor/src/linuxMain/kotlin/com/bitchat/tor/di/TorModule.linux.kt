package com.bitchat.tor.di

import com.bitchat.local.statedir.StateDirectory
import org.koin.core.qualifier.named
import org.koin.dsl.module

actual val torPlatformModule = module {
    single(named("torDataDir")) {
        // Arti's state is only ever kept in a directory this user owns; a failure stops startup.
        StateDirectory.own("tor")
    }
}
