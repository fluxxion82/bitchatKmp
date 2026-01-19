package com.bitchat.nostr.di

import com.bitchat.nostr.JvmResourceReader
import com.bitchat.nostr.ResourceReader
import org.koin.dsl.module

actual val nostrPlatformModule = module {
    single<ResourceReader> { JvmResourceReader() }
}
