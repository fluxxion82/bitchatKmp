package com.bitchat.tor

import com.bitchat.domain.tor.model.TorStatus
import kotlinx.coroutines.flow.StateFlow

/** The routing surface exposed by Tor without giving clients ownership of its lifecycle. */
interface TorRouteSource {
    val statusFlow: StateFlow<TorStatus>
    val isAvailable: Boolean

    fun getSocksProxyAddress(): Pair<String, Int>?
    fun isProxyReady(): Boolean
}

/** Preserves [TorManager]'s route generation and port semantics for existing platforms. */
class TorManagerRouteSource(
    private val torManager: TorManager,
) : TorRouteSource {
    override val statusFlow: StateFlow<TorStatus> get() = torManager.statusFlow
    override val isAvailable: Boolean get() = torManager.isAvailable

    override fun getSocksProxyAddress(): Pair<String, Int>? = torManager.getSocksProxyAddress()

    override fun isProxyReady(): Boolean = torManager.isProxyReady()
}
