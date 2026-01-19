package com.bitchat.nostr.di

import com.bitchat.nostr.AndroidResourceReader
import com.bitchat.nostr.ResourceReader
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

actual val nostrPlatformModule = module {
    single<ResourceReader> { AndroidResourceReader(context = androidContext()) }
}
