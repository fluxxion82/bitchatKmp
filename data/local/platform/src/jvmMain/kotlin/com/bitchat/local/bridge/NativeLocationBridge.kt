package com.bitchat.local.bridge

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Platform
import com.sun.jna.ptr.DoubleByReference

object NativeLocationBridge {
    private interface LocationNative : Library {
        fun bitchat_location_ping(): Int
        fun bitchat_location_get_current(lat: DoubleByReference, lon: DoubleByReference): Int
        fun bitchat_location_has_permission(): Int
        fun bitchat_location_request_permission(): Int
    }

    @Volatile
    private var native: LocationNative? = null

    fun init(): Boolean {
        if (!Platform.isMac()) {
            println("NativeLocationBridge: not macOS, skipping native init")
            return false
        }

        val libPath = System.getProperty("location.native.libpath")
        return try {
            native = if (libPath.isNullOrBlank()) {
                Native.load("bitchat_location", LocationNative::class.java)
            } else {
                Native.load(libPath, LocationNative::class.java)
            }
            val pingResult = native?.bitchat_location_ping()
            println("NativeLocationBridge: init success (ping=$pingResult)")
            pingResult == 1
        } catch (t: Throwable) {
            println("NativeLocationBridge: failed to load bitchat_location (path=$libPath): ${t.message}")
            false
        }
    }

    fun isAvailable(): Boolean = native != null

    fun getCurrentLocation(): Pair<Double, Double>? {
        val n = native ?: return null
        val lat = DoubleByReference()
        val lon = DoubleByReference()
        val result = n.bitchat_location_get_current(lat, lon)
        return if (result == 0) {
            lat.value to lon.value
        } else {
            println("NativeLocationBridge: getCurrentLocation failed (result=$result)")
            null
        }
    }

    fun hasPermission(): Boolean {
        val n = native ?: return false
        return n.bitchat_location_has_permission() == 1
    }

    fun requestPermission() {
        native?.bitchat_location_request_permission()
    }
}
