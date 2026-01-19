package com.bitchat.screens.bluetooth

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.cinterop.ExperimentalForeignApi
import platform.CoreBluetooth.CBCentralManager
import platform.CoreBluetooth.CBCentralManagerDelegateProtocol
import platform.CoreBluetooth.CBManagerStatePoweredOff
import platform.CoreBluetooth.CBManagerStatePoweredOn
import platform.CoreBluetooth.CBManagerStateUnauthorized
import platform.CoreBluetooth.CBManagerStateUnknown
import platform.Foundation.NSURL
import platform.UIKit.UIApplication
import platform.darwin.NSObject

@OptIn(ExperimentalForeignApi::class)
@Composable
actual fun rememberBluetoothEnabler(onGranted: () -> Unit, onDenied: () -> Unit): () -> Unit {
    var stateCheckTrigger by remember { mutableStateOf(0) }

    LaunchedEffect(stateCheckTrigger) {
        if (stateCheckTrigger > 0) {
            var manager: CBCentralManager? = null

            val delegate = object : NSObject(), CBCentralManagerDelegateProtocol {
                override fun centralManagerDidUpdateState(central: CBCentralManager) {
                    println("centralManagerDidUpdateState: state = ${stateToString(central.state)}")

                    when (central.state) {
                        CBManagerStatePoweredOn -> {
                            println("bluetooth is powered on, calling onGranted()")
                            onGranted()
                        }

                        CBManagerStatePoweredOff -> {
                            println("bluetooth is powered off, opening Settings")
                            openBluetoothSettings()
                            onDenied()
                        }

                        CBManagerStateUnauthorized -> {
                            println("bluetooth is unauthorized, opening Settings")
                            openBluetoothSettings()
                            onDenied()
                        }

                        else -> {
                            println("bluetooth state: ${stateToString(central.state)}, calling onDenied()")
                            onDenied()
                        }
                    }
                }
            }

            manager = CBCentralManager(delegate = delegate, queue = null)
            if (manager.state != CBManagerStateUnknown) {
                println("state is already determined, manually triggering callback")
                delegate.centralManagerDidUpdateState(manager)
            }
        }
    }

    return remember {
        {
            println("enable Bluetooth button clicked")
            stateCheckTrigger++
        }
    }
}

private fun stateToString(state: Long): String {
    return when (state) {
        CBManagerStateUnknown -> "Unknown"
        0L -> "Resetting"
        1L -> "Unsupported"
        CBManagerStateUnauthorized -> "Unauthorized"
        CBManagerStatePoweredOff -> "Powered Off"
        CBManagerStatePoweredOn -> "Powered On"
        else -> "Other ($state)"
    }
}

private fun openBluetoothSettings() {
    val settingsUrl = NSURL.URLWithString("App-Prefs:root=Bluetooth")
    if (settingsUrl != null && UIApplication.sharedApplication.canOpenURL(settingsUrl)) {
        UIApplication.sharedApplication.openURL(settingsUrl)
    } else {
        val generalSettingsUrl = NSURL.URLWithString("app-settings:")
        if (generalSettingsUrl != null && UIApplication.sharedApplication.canOpenURL(generalSettingsUrl)) {
            UIApplication.sharedApplication.openURL(generalSettingsUrl)
        }
    }
}
