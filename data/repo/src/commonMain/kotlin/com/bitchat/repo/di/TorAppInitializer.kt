package com.bitchat.repo.di

import com.bitchat.domain.base.CoroutineScopeFacade
import com.bitchat.domain.initialization.AppInitializer
import com.bitchat.domain.tor.model.TorMode
import com.bitchat.domain.tor.repository.TorRepository
import kotlinx.coroutines.launch

class TorAppInitializer(
    private val torRepository: TorRepository,
    private val coroutineScopeFacade: CoroutineScopeFacade,
) : AppInitializer {
    override suspend fun initialize() {
        println("TorAppInitializer: Checking Tor mode...")
        // Stored intent, not the effective mode: a start that failed last run reports OFF while
        // the user's ON is still on disk, and that ON is exactly what should be retried here.
        val mode = torRepository.getStoredTorMode()
        println("TorAppInitializer: Stored Tor mode is $mode")
        if (mode == TorMode.ON) {
            coroutineScopeFacade.applicationScope.launch {
                println("TorAppInitializer: Starting Tor...")
                torRepository.enable()
            }
        }
    }
}
