package com.bitchat.repo.di

import com.bitchat.repo.background.BackgroundServiceController
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

actual val repoModule = module {
    single { BackgroundServiceController(androidContext()) }
}
