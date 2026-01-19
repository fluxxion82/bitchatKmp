package com.bitchat.repo.repositories

import com.bitchat.domain.base.CoroutineScopeFacade
import com.bitchat.domain.base.CoroutinesContextFacade
import com.bitchat.domain.tor.eventbus.TorEventBus
import com.bitchat.domain.tor.model.TorEvent
import com.bitchat.domain.tor.model.TorMode
import com.bitchat.domain.tor.model.TorStatus
import com.bitchat.domain.tor.repository.TorRepository
import com.bitchat.local.prefs.TorPreferences
import com.bitchat.tor.TorManager
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.concurrent.Volatile

class TorRepo(
    private val torManager: TorManager,
    private val torPreferences: TorPreferences,
    private val coroutinesContextFacade: CoroutinesContextFacade,
    private val coroutineScopeFacade: CoroutineScopeFacade,
    private val torEventBus: TorEventBus,
) : TorRepository {
    @Volatile
    private var externalLogLine: String? = null

    init {
        coroutineScopeFacade.applicationScope.launch {
            torManager.statusFlow
                .collect {
                    torEventBus.update(TorEvent.StatusChanged)
                }
        }
    }

    override suspend fun getSocksProxyAddress(): Pair<String, Int>? = withContext(coroutinesContextFacade.io) {
        torManager.getSocksProxyAddress()
    }

    override fun isProxyReady(): Boolean {
        return torManager.isProxyReady()
    }

    override suspend fun enable() = withContext(coroutinesContextFacade.io) {
        torManager.start()
        torPreferences.setTorMode(TorMode.ON)
    }

    override suspend fun disable() = withContext(coroutinesContextFacade.io) {
        torManager.stop()
        torPreferences.setTorMode(TorMode.OFF)
    }

    override suspend fun getTorStatus(): TorStatus = withContext(coroutinesContextFacade.io) {
        val status = torManager.statusFlow.value
        val logLine = externalLogLine ?: status.lastLogLine
        status.copy(lastLogLine = logLine)
    }

    override suspend fun getTorMode(): TorMode = withContext(coroutinesContextFacade.io) {
        torPreferences.getTorMode()
    }

    override suspend fun setTorMode(mode: TorMode) = withContext(coroutinesContextFacade.io) {
        torPreferences.setTorMode(mode)
    }

    fun recordExternalLogLine(line: String) {
        externalLogLine = line
        coroutineScopeFacade.applicationScope.launch {
            torEventBus.update(TorEvent.StatusChanged)
        }
    }

    override suspend fun clearData() = withContext(coroutinesContextFacade.io) {
        disable()
        torPreferences.setTorMode(TorMode.OFF)
    }
}
