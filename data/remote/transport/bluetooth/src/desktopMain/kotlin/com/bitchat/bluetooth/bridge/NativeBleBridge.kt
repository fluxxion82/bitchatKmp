package com.bitchat.bluetooth.bridge

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Platform

/**
 * JNA bridge to the macOS native BLE library. Requires the library to be pre-loaded.
 */
object NativeBleBridge {
    private interface BleNative : Library {
        fun bitchat_ble_ping(): Int
        fun bitchat_ble_start_advertising(serviceUuid: String?, deviceName: String?): Int
        fun bitchat_ble_stop_advertising(): Int
        fun bitchat_ble_start_scan(lowLatency: Int): Int
        fun bitchat_ble_stop_scan(): Int
        fun bitchat_ble_connect(deviceAddress: String?): Int
        fun bitchat_ble_disconnect(deviceAddress: String?): Int
        fun bitchat_ble_broadcast(data: ByteArray?, len: Int): Int
        fun bitchat_ble_write(deviceAddress: String?, data: ByteArray?, len: Int): Int
        fun bitchat_ble_notify(deviceAddress: String?, data: ByteArray?, len: Int): Int
        fun bitchat_ble_subscribe(deviceAddress: String?): Int
        fun bitchat_ble_unsubscribe(deviceAddress: String?): Int
        fun bitchat_ble_register_discovery_callback(cb: DiscoveryCallback?): Int
        fun bitchat_ble_register_data_callback(cb: DataCallback?): Int
    }

    interface DiscoveryCallback : com.sun.jna.Callback {
        fun invoke(deviceId: String?, name: String?, rssi: Int)
    }

    interface DataCallback : com.sun.jna.Callback {
        fun invoke(deviceId: String?, data: com.sun.jna.Pointer?, len: Int)
    }

    @Volatile
    private var native: BleNative? = null

    @Volatile
    private var discoveryCbRef: DiscoveryCallback? = null

    @Volatile
    private var dataCallbackRegistered: Boolean = false
    private val dataListeners = mutableSetOf<(String, ByteArray) -> Unit>()
    private val nativeDataCallback = object : DataCallback {
        override fun invoke(deviceId: String?, data: com.sun.jna.Pointer?, len: Int) {
            val id = deviceId ?: return
            val bytes = if (data != null && len > 0) data.getByteArray(0, len) else ByteArray(0)
            dataListeners.forEach { listener ->
                listener(id, bytes)
            }
        }
    }

    fun init(): Boolean {
        if (!Platform.isMac()) return false
        val libPath = System.getProperty("ble.native.libpath")
        return try {
            native = if (libPath.isNullOrBlank()) {
                Native.load("bitchat_ble", BleNative::class.java)
            } else {
                Native.load(libPath, BleNative::class.java)
            }
            true
        } catch (t: Throwable) {
            println("NativeBleBridge: failed to bind to bitchat_ble (path=$libPath): ${t.message}")
            false
        }
    }

    private fun loadNative(): BleNative? {
        if (native != null) return native
        init()
        return native
    }

    fun ping(): Int? = native?.bitchat_ble_ping()

    fun startAdvertising(serviceUuid: String, deviceName: String): Boolean =
        native?.bitchat_ble_start_advertising(serviceUuid, deviceName) == 0

    fun stopAdvertising(): Boolean =
        native?.bitchat_ble_stop_advertising() == 0

    fun startScan(lowLatency: Boolean): Boolean =
        native?.bitchat_ble_start_scan(if (lowLatency) 1 else 0) == 0

    fun stopScan(): Boolean =
        native?.bitchat_ble_stop_scan() == 0

    fun connect(deviceAddress: String): Boolean =
        native?.bitchat_ble_connect(deviceAddress) == 0

    fun disconnect(deviceAddress: String): Boolean =
        native?.bitchat_ble_disconnect(deviceAddress) == 0

    fun broadcast(data: ByteArray): Boolean =
        native?.bitchat_ble_broadcast(data, data.size) == 0

    fun write(deviceAddress: String, data: ByteArray): Boolean =
        native?.bitchat_ble_write(deviceAddress, data, data.size) == 0

    fun notify(deviceAddress: String, data: ByteArray): Boolean =
        native?.bitchat_ble_notify(deviceAddress, data, data.size) == 0

    fun subscribe(deviceAddress: String): Boolean =
        native?.bitchat_ble_subscribe(deviceAddress) == 0

    fun unsubscribe(deviceAddress: String): Boolean =
        native?.bitchat_ble_unsubscribe(deviceAddress) == 0

    fun registerDiscoveryCallback(callback: DiscoveryCallback) {
        discoveryCbRef = callback
        val bound = native ?: if (Platform.isMac()) {
            try {
                native ?: loadNative()
            } catch (t: Throwable) {
                println("NativeBleBridge: failed to init before discovery registration: ${t.message}")
                null
            }
        } else null
        println("NativeBleBridge: registering discovery callback (nativeLoaded=${bound != null})")
        native?.bitchat_ble_register_discovery_callback(callback)
    }

    fun addDataListener(listener: (String, ByteArray) -> Unit) {
        dataListeners += listener
        if (!dataCallbackRegistered) {
            val bound = native ?: loadNative()
            if (bound != null) {
                native?.bitchat_ble_register_data_callback(nativeDataCallback)
                dataCallbackRegistered = true
            }
        }
    }
}
