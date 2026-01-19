package com.bitchat.bluetooth.service

interface AdvertisingService {
    suspend fun startAdvertising(serviceUuid: String, deviceName: String)
    suspend fun stopAdvertising()
    fun isAdvertising(): Boolean
}
