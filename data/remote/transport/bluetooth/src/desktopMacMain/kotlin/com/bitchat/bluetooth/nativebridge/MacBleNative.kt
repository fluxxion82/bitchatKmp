package com.bitchat.bluetooth.nativebridge

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CFunction
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.readBytes
import kotlin.experimental.ExperimentalNativeApi

@OptIn(ExperimentalNativeApi::class)
@CName("bitchat_ble_ping")
fun bitchatBlePing(): Int {
    println("NativeBLE(mac): ping")
    return 1
}

@OptIn(ExperimentalNativeApi::class)
@CName("bitchat_ble_start_advertising")
fun bitchatBleStartAdvertising(serviceUuid: String?, deviceName: String?): Int {
    MacBleController.startAdvertising(serviceUuid, deviceName)
    println("NativeBLE(mac): startAdvertising (uuid=$serviceUuid, name=$deviceName)")
    return 0
}

@OptIn(ExperimentalNativeApi::class)
@CName("bitchat_ble_stop_advertising")
fun bitchatBleStopAdvertising(): Int {
    MacBleController.stopAdvertising()
    println("NativeBLE(mac): stopAdvertising")
    return 0
}

@OptIn(ExperimentalNativeApi::class)
@CName("bitchat_ble_start_scan")
fun bitchatBleStartScan(lowLatency: Boolean): Int {
    MacBleController.startScan(lowLatency)
    println("NativeBLE(mac): startScan (lowLatency=$lowLatency)")
    return 0
}

@OptIn(ExperimentalNativeApi::class)
@CName("bitchat_ble_stop_scan")
fun bitchatBleStopScan(): Int {
    MacBleController.stopScan()
    println("NativeBLE(mac): stopScan")
    return 0
}

@OptIn(ExperimentalNativeApi::class)
@CName("bitchat_ble_connect")
fun bitchatBleConnect(deviceAddress: String?): Int {
    MacBleController.connect(deviceAddress)
    println("NativeBLE(mac): connect (device=$deviceAddress)")
    return 0
}

@OptIn(ExperimentalNativeApi::class)
@CName("bitchat_ble_disconnect")
fun bitchatBleDisconnect(deviceAddress: String?): Int {
    MacBleController.disconnect(deviceAddress)
    println("NativeBLE(mac): disconnect (device=$deviceAddress)")
    return 0
}

@OptIn(ExperimentalNativeApi::class, ExperimentalForeignApi::class)
@CName("bitchat_ble_broadcast")
fun bitchatBleBroadcast(data: CPointer<ByteVar>?, len: Int): Int {
    val bytes = data?.readBytes(len)
    MacBleController.broadcast(bytes)
    println("NativeBLE(mac): broadcast (size=$len)")
    return 0
}

@OptIn(ExperimentalNativeApi::class, ExperimentalForeignApi::class)
@CName("bitchat_ble_write")
fun bitchatBleWrite(deviceAddress: String?, data: CPointer<ByteVar>?, len: Int): Int {
    val bytes = data?.readBytes(len)
    MacBleController.write(deviceAddress, bytes)
    println("NativeBLE(mac): write (device=$deviceAddress, size=$len)")
    return 0
}

@OptIn(ExperimentalNativeApi::class, ExperimentalForeignApi::class)
@CName("bitchat_ble_register_discovery_callback")
fun bitchatBleRegisterDiscoveryCallback(
    cb: CPointer<CFunction<Function3<CPointer<ByteVar>?, CPointer<ByteVar>?, Int, Unit>>>?
): Int {
    MacBleController.discoveryCallback = cb
    println("NativeBLE(mac): registered discovery callback")
    return 0
}

@OptIn(ExperimentalNativeApi::class, ExperimentalForeignApi::class)
@CName("bitchat_ble_register_data_callback")
fun bitchatBleRegisterDataCallback(
    cb: CPointer<CFunction<Function3<CPointer<ByteVar>?, CPointer<ByteVar>?, Int, Unit>>>?
): Int {
    MacBleController.dataCallback = cb
    println("NativeBLE(mac): registered data callback")
    return 0
}

@OptIn(ExperimentalNativeApi::class, ExperimentalForeignApi::class)
@CName("bitchat_ble_notify")
fun bitchatBleNotify(deviceAddress: String?, data: CPointer<ByteVar>?, len: Int): Int {
    val bytes = data?.readBytes(len)
    MacBleController.notify(deviceAddress, bytes)
    println("NativeBLE(mac): notify (device=$deviceAddress, size=$len)")
    return 0
}

@OptIn(ExperimentalNativeApi::class)
@CName("bitchat_ble_subscribe")
fun bitchatBleSubscribe(deviceAddress: String?): Int {
    println("NativeBLE(mac): subscribe stub (device=$deviceAddress)")
    return 0
}

@OptIn(ExperimentalNativeApi::class)
@CName("bitchat_ble_unsubscribe")
fun bitchatBleUnsubscribe(deviceAddress: String?): Int {
    println("NativeBLE(mac): unsubscribe stub (device=$deviceAddress)")
    return 0
}
