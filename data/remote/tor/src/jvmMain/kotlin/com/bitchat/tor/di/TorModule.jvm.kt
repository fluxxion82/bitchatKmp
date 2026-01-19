package com.bitchat.tor.di

import org.koin.core.qualifier.named
import org.koin.dsl.module
import java.io.File

actual val torPlatformModule = module {
    single(named("torDataDir")) {
        val userHome = System.getProperty("user.home")
        val torDir = File(userHome, ".bitchat/tor")
        torDir.mkdirs()
        torDir.absolutePath
    }
}
