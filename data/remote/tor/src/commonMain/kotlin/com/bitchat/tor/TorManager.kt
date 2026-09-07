package com.bitchat.tor

import com.bitchat.domain.tor.model.TorStatus
import kotlinx.coroutines.flow.StateFlow

expect class TorManager(dataDir: String) {
    val statusFlow: StateFlow<TorStatus>

    /**
     * False when Tor can never run on this host, whatever the user switches on - today that means
     * the native Arti library could not be loaded on JVM desktop or Android. Distinct from
     * [isProxyReady], which is about a Tor that *can* run but is not ready yet.
     */
    val isAvailable: Boolean

    fun getSocksProxyAddress(): Pair<String, Int>?
    fun isProxyReady(): Boolean
    suspend fun start()
    suspend fun stop()
    fun destroy()
}
