package com.bitchat.repo.di

import com.bitchat.repo.background.BackgroundServiceController
import org.koin.dsl.module

/**
 * Linux-specific DI module for the repo layer.
 */
actual val repoModule = module {
    single { BackgroundServiceController() }
}
