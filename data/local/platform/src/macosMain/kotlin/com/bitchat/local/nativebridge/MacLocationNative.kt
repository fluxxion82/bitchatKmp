package com.bitchat.local.nativebridge

import kotlinx.cinterop.CPointer
import kotlinx.cinterop.DoubleVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.set
import kotlin.experimental.ExperimentalNativeApi

/**
 * Exported C functions for the macOS location shared library.
 * These functions are called from JVM via JNA.
 */

@OptIn(ExperimentalNativeApi::class)
@CName("bitchat_location_ping")
fun bitchatLocationPing(): Int {
    println("[MacLocationNative] ping")
    return 1
}

@OptIn(ExperimentalNativeApi::class, ExperimentalForeignApi::class)
@CName("bitchat_location_get_current")
fun bitchatLocationGetCurrent(latPtr: CPointer<DoubleVar>?, lonPtr: CPointer<DoubleVar>?): Int {
    println("[MacLocationNative] get_current called")

    val location = MacLocationController.getCurrentLocation()
    if (location == null) {
        println("[MacLocationNative] get_current failed - no location")
        return -1
    }

    val (lat, lon) = location
    latPtr?.let { it[0] = lat }
    lonPtr?.let { it[0] = lon }

    println("[MacLocationNative] get_current success: ($lat, $lon)")
    return 0
}

@OptIn(ExperimentalNativeApi::class)
@CName("bitchat_location_has_permission")
fun bitchatLocationHasPermission(): Int {
    val hasPermission = MacLocationController.hasPermission()
    println("[MacLocationNative] has_permission: $hasPermission")
    return if (hasPermission) 1 else 0
}

@OptIn(ExperimentalNativeApi::class)
@CName("bitchat_location_request_permission")
fun bitchatLocationRequestPermission(): Int {
    println("[MacLocationNative] request_permission")
    MacLocationController.requestPermission()
    return 0
}
