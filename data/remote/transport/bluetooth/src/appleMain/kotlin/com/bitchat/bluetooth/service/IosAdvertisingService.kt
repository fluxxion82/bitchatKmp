package com.bitchat.bluetooth.service

import platform.CoreBluetooth.CBManagerStatePoweredOn
import platform.CoreBluetooth.CBPeripheralManager
import platform.CoreBluetooth.CBPeripheralManagerDelegateProtocol
import platform.CoreBluetooth.CBPeripheralManagerOptionRestoreIdentifierKey
import platform.CoreBluetooth.CBPeripheralManagerRestoredStateAdvertisementDataKey
import platform.CoreBluetooth.CBUUID
import platform.darwin.NSObject
import platform.darwin.dispatch_queue_create
import platform.darwin.dispatch_queue_t

/**
 * iOS stub for Advertising Service
 */
class IosAdvertisingService : AdvertisingService {
    companion object {
        private const val ADVERTISING_RESTORE_ID = "com.bitchat.bluetooth.advertising"
        private const val DEFAULT_SERVICE_UUID = "F47B5E2D-4A9E-4C5A-9B3F-8E1D2C3A4B5C"
    }

    private val queue: dispatch_queue_t = dispatch_queue_create("com.bitchat.bluetooth.advertising", null)
    private val delegateProxy = AdvertisingDelegate()
    private val peripheralManager = CBPeripheralManager(
        delegate = delegateProxy,
        queue = queue,
        options = mapOf(CBPeripheralManagerOptionRestoreIdentifierKey to ADVERTISING_RESTORE_ID)
    )

    private var isCurrentlyAdvertising = false
    private var pendingAdvertisement: Map<Any?, Any?>? = null

    override suspend fun startAdvertising(serviceUuid: String, deviceName: String) {
        if (isCurrentlyAdvertising) {
            return
        }

        val uuid = safeUuidFromString(serviceUuid)
        val advertisement = mutableMapOf<Any?, Any?>(
            platform.CoreBluetooth.CBAdvertisementDataServiceUUIDsKey to listOf(uuid),
            platform.CoreBluetooth.CBAdvertisementDataLocalNameKey to deviceName
        )
        pendingAdvertisement = advertisement

        if (peripheralManager.state == CBManagerStatePoweredOn) {
            peripheralManager.startAdvertising(advertisement)
            isCurrentlyAdvertising = true
        }
    }

    override suspend fun stopAdvertising() {
        if (!isCurrentlyAdvertising) {
            return
        }
        peripheralManager.stopAdvertising()
        isCurrentlyAdvertising = false
    }

    override fun isAdvertising(): Boolean = isCurrentlyAdvertising

    private inner class AdvertisingDelegate : NSObject(), CBPeripheralManagerDelegateProtocol {
        override fun peripheralManagerDidUpdateState(peripheral: CBPeripheralManager) {
            if (peripheral.state != CBManagerStatePoweredOn) {
                return
            }
            val advertisement = pendingAdvertisement ?: return
            peripheral.startAdvertising(advertisement)
            isCurrentlyAdvertising = true
        }

        override fun peripheralManager(
            peripheral: CBPeripheralManager,
            willRestoreState: Map<Any?, *>
        ) {
            val restored = willRestoreState[CBPeripheralManagerRestoredStateAdvertisementDataKey] as? Map<Any?, Any?>
            if (restored != null) {
                pendingAdvertisement = restored
            }
        }
    }

    private fun safeUuidFromString(input: String): CBUUID {
        val isUuid = input.matches(
            Regex(
                "^[0-9a-fA-F]{4}$|^[0-9a-fA-F]{8}$|^[0-9a-fA-F]{8}-" +
                        "[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$"
            )
        )
        val value = if (isUuid) input else DEFAULT_SERVICE_UUID
        return CBUUID.UUIDWithString(value)
    }
}
