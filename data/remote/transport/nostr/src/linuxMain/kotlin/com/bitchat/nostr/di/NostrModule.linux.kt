package com.bitchat.nostr.di

import com.bitchat.nostr.LinuxResourceReader
import com.bitchat.nostr.ResourceReader
import org.koin.dsl.module

actual val nostrPlatformModule = module {
    single<ResourceReader> { LinuxResourceReader() }
}
