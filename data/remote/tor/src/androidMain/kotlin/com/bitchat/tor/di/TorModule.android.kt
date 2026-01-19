package com.bitchat.tor.di

import android.content.Context
import org.koin.core.qualifier.named
import org.koin.dsl.module

actual val torPlatformModule = module {
    single(named("torDataDir")) {
        val context: Context = get()
        context.getDir("tor", Context.MODE_PRIVATE).absolutePath
    }
}
