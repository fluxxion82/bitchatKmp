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
        val mode = torRepository.getTorMode()
        println("TorAppInitializer: Mode is $mode")
        if (mode == TorMode.ON) {
            coroutineScopeFacade.applicationScope.launch {
                println("TorAppInitializer: Starting Tor...")
                torRepository.enable()
            }
        }
    }
}
