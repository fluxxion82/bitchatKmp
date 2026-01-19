package com.bitchat.bluetooth.service

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCSignatureOverride
import platform.CoreBluetooth.CBCentralManager
import platform.CoreBluetooth.CBCentralManagerDelegateProtocol
import platform.CoreBluetooth.CBCentralManagerOptionRestoreIdentifierKey
import platform.CoreBluetooth.CBCentralManagerRestoredStatePeripheralsKey
import platform.CoreBluetooth.CBCharacteristic
import platform.CoreBluetooth.CBDescriptor
import platform.CoreBluetooth.CBL2CAPChannel
import platform.CoreBluetooth.CBManagerState
import platform.CoreBluetooth.CBManagerStatePoweredOn
import platform.CoreBluetooth.CBPeripheral
import platform.CoreBluetooth.CBPeripheralDelegateProtocol
import platform.CoreBluetooth.CBService
import platform.CoreBluetooth.CBUUID
import platform.Foundation.NSNumber
import platform.darwin.NSObject
import platform.darwin.dispatch_queue_create
import platform.darwin.dispatch_queue_t

@OptIn(ExperimentalForeignApi::class)
class IosSharedCentralManager {
    companion object {
        private const val RESTORE_ID = "com.bitchat.bluetooth.shared"
    }

    interface ScanDelegate {
        fun onDeviceDiscovered(peripheral: CBPeripheral, advertisementData: Map<Any?, *>, rssi: NSNumber)
        fun onStateChange(state: CBManagerState)
    }

    interface GattDelegate {
        fun onConnected(peripheral: CBPeripheral)
        fun onDisconnected(peripheral: CBPeripheral, error: String?)
        fun onServicesDiscovered(peripheral: CBPeripheral, error: String?)
        fun onCharacteristicsDiscovered(peripheral: CBPeripheral, service: CBService, error: String?)
        fun onCharacteristicValueUpdated(peripheral: CBPeripheral, characteristic: CBCharacteristic, error: String?)
        fun onMtuChanged(peripheral: CBPeripheral, mtu: Int, error: String?)
    }

    private val queue: dispatch_queue_t = dispatch_queue_create("com.bitchat.bluetooth.shared", null)
    private val sharedDelegate = SharedCentralDelegate()
    private val centralManager: CBCentralManager

    private var scanDelegate: ScanDelegate? = null
    private var gattDelegate: GattDelegate? = null

    init {
        centralManager = CBCentralManager(
            delegate = sharedDelegate,
            queue = queue,
            options = mapOf(CBCentralManagerOptionRestoreIdentifierKey to RESTORE_ID)
        )
        println("Created shared CBCentralManager instance")
    }

    fun registerScanDelegate(delegate: ScanDelegate) {
        this.scanDelegate = delegate
    }

    fun registerGattDelegate(delegate: GattDelegate) {
        this.gattDelegate = delegate
    }

    fun getState(): CBManagerState = centralManager.state

    fun startScan(serviceUUIDs: List<CBUUID>, options: Map<Any?, Any?>) {
        if (centralManager.state == CBManagerStatePoweredOn) {
            centralManager.scanForPeripheralsWithServices(serviceUUIDs, options)
            println("Started scanning")
        } else {
            println("Cannot scan - BT state: ${centralManager.state}")
        }
    }

    fun stopScan() {
        centralManager.stopScan()
        println("[SHARED_CENTRAL] Stopped scanning")
    }

    fun connectPeripheral(peripheral: CBPeripheral, options: Map<Any?, *>? = null) {
        println("[SHARED_CENTRAL] Connecting to peripheral: ${peripheral.identifier.UUIDString}")
        centralManager.connectPeripheral(peripheral, options)
    }

    fun disconnectPeripheral(peripheral: CBPeripheral) {
        println("[SHARED_CENTRAL] Disconnecting from peripheral: ${peripheral.identifier.UUIDString}")
        centralManager.cancelPeripheralConnection(peripheral)
    }

    fun retrievePeripherals(identifiers: List<*>): List<*> {
        return centralManager.retrievePeripheralsWithIdentifiers(identifiers)
    }

    private inner class SharedCentralDelegate : NSObject(), CBCentralManagerDelegateProtocol, CBPeripheralDelegateProtocol {

        override fun centralManagerDidUpdateState(central: CBCentralManager) {
            println("[SHARED_CENTRAL] State changed: ${central.state}")
            scanDelegate?.onStateChange(central.state)
        }

        override fun centralManager(
            central: CBCentralManager,
            didDiscoverPeripheral: CBPeripheral,
            advertisementData: Map<Any?, *>,
            RSSI: NSNumber
        ) {
            scanDelegate?.onDeviceDiscovered(didDiscoverPeripheral, advertisementData, RSSI)
        }

        override fun centralManager(
            central: CBCentralManager,
            didConnectPeripheral: CBPeripheral
        ) {
            println("[SHARED_CENTRAL] 🎯 didConnectPeripheral: ${didConnectPeripheral.identifier.UUIDString}")
            // Set peripheral delegate to this shared delegate
            didConnectPeripheral.delegate = this
            gattDelegate?.onConnected(didConnectPeripheral)
        }

        @ObjCSignatureOverride
        override fun centralManager(
            central: CBCentralManager,
            didDisconnectPeripheral: CBPeripheral,
            error: platform.Foundation.NSError?
        ) {
            println("[SHARED_CENTRAL] didDisconnectPeripheral: ${didDisconnectPeripheral.identifier.UUIDString}")
            gattDelegate?.onDisconnected(didDisconnectPeripheral, error?.localizedDescription)
        }

        @ObjCSignatureOverride
        override fun centralManager(
            central: CBCentralManager,
            didFailToConnectPeripheral: CBPeripheral,
            error: platform.Foundation.NSError?
        ) {
            println("[SHARED_CENTRAL] ❌ Failed to connect: ${didFailToConnectPeripheral.identifier.UUIDString}, error: ${error?.localizedDescription}")
            gattDelegate?.onDisconnected(didFailToConnectPeripheral, error?.localizedDescription ?: "Connection failed")
        }

        override fun centralManager(
            central: CBCentralManager,
            willRestoreState: Map<Any?, *>
        ) {
            val peripherals = willRestoreState[CBCentralManagerRestoredStatePeripheralsKey] as? List<*>
            println("[SHARED_CENTRAL] Restoring state with ${peripherals?.size ?: 0} peripherals")
            peripherals?.forEach { restored ->
                val peripheral = restored as? CBPeripheral ?: return@forEach
                peripheral.delegate = this
            }
        }

        override fun peripheral(
            peripheral: CBPeripheral,
            didDiscoverServices: platform.Foundation.NSError?
        ) {
            gattDelegate?.onServicesDiscovered(peripheral, didDiscoverServices?.localizedDescription)
        }

        override fun peripheral(
            peripheral: CBPeripheral,
            didDiscoverCharacteristicsForService: CBService,
            error: platform.Foundation.NSError?
        ) {
            gattDelegate?.onCharacteristicsDiscovered(peripheral, didDiscoverCharacteristicsForService, error?.localizedDescription)
        }

        @ObjCSignatureOverride
        override fun peripheral(
            peripheral: CBPeripheral,
            didUpdateValueForCharacteristic: CBCharacteristic,
            error: platform.Foundation.NSError?
        ) {
            gattDelegate?.onCharacteristicValueUpdated(peripheral, didUpdateValueForCharacteristic, error?.localizedDescription)
        }

        override fun peripheralDidUpdateName(peripheral: CBPeripheral) {
            // Not used for now
        }

        override fun peripheral(peripheral: CBPeripheral, didModifyServices: List<*>) {
            // Not used for now
        }

        override fun peripheral(peripheral: CBPeripheral, didReadRSSI: NSNumber, error: platform.Foundation.NSError?) {
            // Not used for now
        }

        @ObjCSignatureOverride
        override fun peripheral(
            peripheral: CBPeripheral,
            didUpdateNotificationStateForCharacteristic: CBCharacteristic,
            error: platform.Foundation.NSError?
        ) {
            // Notifications enabled/disabled
            println("[SHARED_CENTRAL] Notification state updated for characteristic: ${didUpdateNotificationStateForCharacteristic.UUID}")
        }

        @ObjCSignatureOverride
        override fun peripheral(
            peripheral: CBPeripheral,
            didWriteValueForCharacteristic: CBCharacteristic,
            error: platform.Foundation.NSError?
        ) {
            // Write confirmation (for WRITE_WITH_RESPONSE)
            if (error != null) {
                println("[SHARED_CENTRAL] ❌ Write error: ${error.localizedDescription}")
            }
        }

        @ObjCSignatureOverride
        override fun peripheral(
            peripheral: CBPeripheral,
            didWriteValueForDescriptor: CBDescriptor,
            error: platform.Foundation.NSError?
        ) {
            // Descriptor write confirmation
            if (error != null) {
                println("[SHARED_CENTRAL] ❌ Descriptor write error: ${error.localizedDescription}")
            }
        }

        @ObjCSignatureOverride
        override fun peripheral(
            peripheral: CBPeripheral,
            didUpdateValueForDescriptor: CBDescriptor,
            error: platform.Foundation.NSError?
        ) {
            // Descriptor value updated
        }

        @ObjCSignatureOverride
        override fun peripheral(
            peripheral: CBPeripheral,
            didOpenL2CAPChannel: CBL2CAPChannel?,
            error: platform.Foundation.NSError?
        ) {
            // L2CAP channel opened (not used)
        }

        @ObjCSignatureOverride
        override fun peripheralIsReadyToSendWriteWithoutResponse(peripheral: CBPeripheral) {
            // Ready for more writes (not used)
        }
    }
}
