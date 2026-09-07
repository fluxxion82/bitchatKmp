package com.bitchat.domain.tor.repository

import com.bitchat.domain.tor.model.TorAvailability
import com.bitchat.domain.tor.model.TorMode
import com.bitchat.domain.tor.model.TorStatus

interface TorRepository {
    /**
     * Whether Tor can protect traffic in this build on this host, and if not, why: the native
     * library may be missing, or the platform's HTTP engine may be unable to use a SOCKS proxy at
     * all. The settings switch is disabled with that explanation instead of silently doing
     * nothing - or worse, bootstrapping a Tor no request will ever go through.
     */
    fun torAvailability(): TorAvailability

    suspend fun getTorStatus(): TorStatus

    /**
     * The *effective* mode: what Tor is actually doing for the user in this process right now.
     * Reported OFF whenever protection is not real - a build whose HTTP engine cannot use a SOCKS
     * proxy, or a start that failed - so the settings switch never shows an anonymity that is not
     * there. This is the one the UI reads.
     */
    suspend fun getTorMode(): TorMode

    /**
     * The *stored intent*: what the user last switched on, unfiltered by whether Tor happens to
     * work today. Startup reads this, so repairing a broken install - or simply restarting after a
     * transient failure - brings Tor back instead of leaving it silently disabled forever.
     */
    suspend fun getStoredTorMode(): TorMode

    suspend fun setTorMode(mode: TorMode)
    suspend fun getSocksProxyAddress(): Pair<String, Int>?
    fun isProxyReady(): Boolean
    suspend fun enable()
    suspend fun disable()

    suspend fun clearData()
}