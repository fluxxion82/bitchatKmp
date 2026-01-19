package com.bitchat.tor

import com.bitchat.domain.tor.model.TorStatus
import kotlinx.coroutines.flow.StateFlow

expect class TorManager(dataDir: String) {
    val statusFlow: StateFlow<TorStatus>

    fun getSocksProxyAddress(): Pair<String, Int>?
    fun isProxyReady(): Boolean
    suspend fun start()
    suspend fun stop()
    fun destroy()
}
