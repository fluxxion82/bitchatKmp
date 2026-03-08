package com.bitchat.tor.di

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import org.koin.core.qualifier.named
import org.koin.dsl.module
import platform.posix.getenv
import platform.posix.mkdir

@OptIn(ExperimentalForeignApi::class)
actual val torPlatformModule = module {
    single(named("torDataDir")) {
        val userHome = getenv("HOME")?.toKString() ?: "/tmp"
        val torDir = "$userHome/.bitchat/tor"
        // Create directory with 0700 permissions
        mkdir(torDir, 0x1C0u) // 0700 in octal
        torDir
    }
}
