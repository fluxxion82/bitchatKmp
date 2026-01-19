package com.bitchat.tor.di

import kotlinx.cinterop.ExperimentalForeignApi
import org.koin.core.qualifier.named
import org.koin.dsl.module
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask

@OptIn(ExperimentalForeignApi::class)
actual val torPlatformModule = module {
    single(named("torDataDir")) {
        val paths = NSSearchPathForDirectoriesInDomains(
            NSApplicationSupportDirectory,
            NSUserDomainMask,
            true
        )
        val appSupportDir = paths.firstOrNull() as? String
            ?: throw IllegalStateException("Could not find Application Support directory")

        "$appSupportDir/tor"
    }
}
