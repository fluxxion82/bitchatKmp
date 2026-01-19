package com.bitchat.nostr.di

import com.bitchat.nostr.NativeResourceReader
import com.bitchat.nostr.ResourceReader
import org.koin.dsl.module

actual val nostrPlatformModule = module {
    single<ResourceReader> { NativeResourceReader() }
}
