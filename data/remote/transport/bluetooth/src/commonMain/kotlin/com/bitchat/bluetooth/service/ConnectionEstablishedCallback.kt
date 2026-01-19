package com.bitchat.bluetooth.service

interface ConnectionEstablishedCallback {
    fun onDeviceConnected(deviceAddress: String)
}
