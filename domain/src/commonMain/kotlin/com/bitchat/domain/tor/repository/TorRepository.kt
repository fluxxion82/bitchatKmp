package com.bitchat.domain.tor.repository

import com.bitchat.domain.tor.model.TorMode
import com.bitchat.domain.tor.model.TorStatus

interface TorRepository {
    suspend fun getTorStatus(): TorStatus
    suspend fun getTorMode(): TorMode
    suspend fun setTorMode(mode: TorMode)
    suspend fun getSocksProxyAddress(): Pair<String, Int>?
    fun isProxyReady(): Boolean
    suspend fun enable()
    suspend fun disable()

    suspend fun clearData()
}