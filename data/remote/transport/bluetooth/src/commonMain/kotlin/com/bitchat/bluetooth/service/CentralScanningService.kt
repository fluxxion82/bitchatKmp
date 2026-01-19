package com.bitchat.bluetooth.service

interface CentralScanningService {
    suspend fun startScan(lowLatency: Boolean)
    suspend fun stopScan()
}
