package com.bitchat.bluetooth.nativebridge

import com.bitchat.bluetooth.util.CoreBluetoothRunLoop
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CFunction
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCSignatureOverride
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.cstr
import kotlinx.cinterop.invoke
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.usePinned
import platform.CoreBluetooth.CBATTErrorSuccess
import platform.CoreBluetooth.CBATTRequest
import platform.CoreBluetooth.CBAdvertisementDataLocalNameKey
import platform.CoreBluetooth.CBAdvertisementDataServiceUUIDsKey
import platform.CoreBluetooth.CBAttributePermissionsReadable
import platform.CoreBluetooth.CBAttributePermissionsWriteable
import platform.CoreBluetooth.CBCentral
import platform.CoreBluetooth.CBCentralManager
import platform.CoreBluetooth.CBCentralManagerDelegateProtocol
import platform.CoreBluetooth.CBCentralManagerScanOptionAllowDuplicatesKey
import platform.CoreBluetooth.CBCharacteristic
import platform.CoreBluetooth.CBCharacteristicPropertyNotify
import platform.CoreBluetooth.CBCharacteristicPropertyRead
import platform.CoreBluetooth.CBCharacteristicPropertyWrite
import platform.CoreBluetooth.CBCharacteristicPropertyWriteWithoutResponse
import platform.CoreBluetooth.CBCharacteristicWriteWithResponse
import platform.CoreBluetooth.CBCharacteristicWriteWithoutResponse
import platform.CoreBluetooth.CBManagerStatePoweredOn
import platform.CoreBluetooth.CBMutableCharacteristic
import platform.CoreBluetooth.CBMutableService
import platform.CoreBluetooth.CBPeripheral
import platform.CoreBluetooth.CBPeripheralDelegateProtocol
import platform.CoreBluetooth.CBPeripheralManager
import platform.CoreBluetooth.CBPeripheralManagerDelegateProtocol
import platform.CoreBluetooth.CBService
import platform.CoreBluetooth.CBUUID
import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.Foundation.NSNumber
import platform.Foundation.NSUUID
import platform.Foundation.create
import platform.darwin.NSObject
import platform.darwin.dispatch_get_main_queue
import platform.posix.memcpy

@OptIn(ExperimentalForeignApi::class)
internal object MacBleController {
    private const val SERVICE_UUID = "F47B5E2D-4A9E-4C5A-9B3F-8E1D2C3A4B5C"
    private const val CHARACTERISTIC_UUID = "A1B2C3D4-E5F6-4A5B-8C9D-0E1F2A3B4C5D"

    private val centralDelegate = CentralDelegate()
    private val peripheralDelegate = PeripheralDelegate()
    private val peripheralConnectionDelegate = PeripheralConnectionDelegate()

    private var pendingAdvertise: Pair<String?, String?>? = null
    private var pendingScan: Boolean = false
    private var serviceAdded = false
    private var service: CBMutableService? = null
    private var characteristic: CBMutableCharacteristic? = null
    private val subscribedCentrals = mutableSetOf<CBCentral>()
    private val peripherals = mutableMapOf<String, CBPeripheral>()
    private val peripheralCharacteristics = mutableMapOf<String, CBCharacteristic>()

    private val centralManager: CBCentralManager by lazy {
        CoreBluetoothRunLoop.ensure()
        CBCentralManager(centralDelegate, dispatch_get_main_queue())
    }
    private val peripheralManager: CBPeripheralManager by lazy {
        CoreBluetoothRunLoop.ensure()
        CBPeripheralManager(peripheralDelegate, dispatch_get_main_queue())
    }

    fun startAdvertising(serviceUuid: String?, deviceName: String?) {
        if (peripheralManager.state != CBManagerStatePoweredOn) {
            pendingAdvertise = serviceUuid to deviceName
            println("NativeBLE(mac): peripheral not powered on, deferring advertise")
            return
        }
        println("NativeBLE(mac): starting advertise (state=${peripheralManager.state}, name=$deviceName)")
        val uuid = parseServiceUuid(serviceUuid)
        ensureService(uuid)
        val payload: Map<Any?, Any?> = buildMap {
            put(CBAdvertisementDataLocalNameKey, deviceName ?: "Bitchat")
            if (uuid != null) {
                put(CBAdvertisementDataServiceUUIDsKey, listOf(uuid))
            }
        }
        peripheralManager.startAdvertising(payload)
    }

    fun stopAdvertising() {
        peripheralManager.stopAdvertising()
        pendingAdvertise = null
    }

    fun startScan(lowLatency: Boolean) {
        if (centralManager.state != CBManagerStatePoweredOn) {
            pendingScan = true
            println("NativeBLE(mac): central not powered on, deferring scan")
            return
        }
        println("NativeBLE(mac): starting scan (lowLatency=$lowLatency, state=${centralManager.state})")
        val service = parseServiceUuid(SERVICE_UUID)
        val services = service?.let { listOf(it) }
        val options: Map<Any?, Any?> = mapOf(CBCentralManagerScanOptionAllowDuplicatesKey to true)
        centralManager.scanForPeripheralsWithServices(services, options)
        pendingScan = false
    }

    fun stopScan() {
        centralManager.stopScan()
    }

    fun connect(deviceId: String?) {
        if (deviceId.isNullOrEmpty()) return
        val known = peripherals[deviceId]
        val peripheral = known ?: run {
            val uuid = NSUUID(deviceId)
            val retrieved = centralManager.retrievePeripheralsWithIdentifiers(listOf(uuid)).firstOrNull() as? CBPeripheral
            if (retrieved != null) {
                peripherals[deviceId] = retrieved
            }
            retrieved
        }
        if (peripheral == null) {
            println("NativeBLE(mac): connect failed, no peripheral for $deviceId")
            return
        }
        peripheral.setDelegate(peripheralConnectionDelegate)
        println("NativeBLE(mac): connecting to $deviceId")
        centralManager.connectPeripheral(peripheral, null)
    }

    fun disconnect(deviceId: String?) {
        if (deviceId.isNullOrEmpty()) return
        peripherals[deviceId]?.let { peripheral ->
            centralManager.cancelPeripheralConnection(peripheral)
        }
    }

    fun broadcast(data: ByteArray?) {
        if (data == null || data.isEmpty()) return
        val ch = characteristic ?: return
        if (subscribedCentrals.isEmpty()) return
        val payload = data.toNSData()
        peripheralManager.updateValue(payload, ch, subscribedCentrals.toList())
    }

    fun notify(deviceId: String?, data: ByteArray?) {
        if (deviceId.isNullOrEmpty() || data == null || data.isEmpty()) return
        val ch = characteristic ?: return
        val target = subscribedCentrals.firstOrNull { it.identifier.UUIDString == deviceId } ?: return
        val payload = data.toNSData()
        peripheralManager.updateValue(payload, ch, listOf(target))
    }

    fun write(deviceId: String?, data: ByteArray?) {
        if (deviceId.isNullOrEmpty() || data == null || data.isEmpty()) return
        val peripheral = peripherals[deviceId] ?: return
        val ch = peripheralCharacteristics[deviceId] ?: run {
            println("NativeBLE(mac): no characteristic for $deviceId")
            return
        }
        val writeType = if ((ch.properties and CBCharacteristicPropertyWriteWithoutResponse) != 0uL) {
            CBCharacteristicWriteWithoutResponse
        } else {
            CBCharacteristicWriteWithResponse
        }
        peripheral.writeValue(data.toNSData(), ch, writeType)
    }

    var discoveryCallback: CPointer<CFunction<Function3<CPointer<ByteVar>?, CPointer<ByteVar>?, Int, Unit>>>? = null
    var dataCallback: CPointer<CFunction<Function3<CPointer<ByteVar>?, CPointer<ByteVar>?, Int, Unit>>>? = null

    private fun parseServiceUuid(raw: String?): CBUUID? {
        val candidate = raw?.trim().orEmpty()
        if (candidate.isEmpty()) return null
        val hex = candidate.replace("-", "")
        val isHex = hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
        val validLength = hex.length == 4 || hex.length == 8 || hex.length == 32
        if (!isHex || !validLength) {
            println("NativeBLE(mac): invalid service UUID '$raw', advertising without service filter")
            return null
        }
        return CBUUID.UUIDWithString(candidate)
    }

    private class CentralDelegate : NSObject(), CBCentralManagerDelegateProtocol {
        override fun centralManagerDidUpdateState(central: CBCentralManager) {
            println("NativeBLE(mac): central state updated=${central.state}")
            if (central.state == CBManagerStatePoweredOn && MacBleController.pendingScan) {
                startScan(lowLatency = true)
            }
        }

        override fun centralManager(
            central: CBCentralManager,
            didDiscoverPeripheral: CBPeripheral,
            advertisementData: Map<Any?, *>,
            RSSI: NSNumber
        ) {
            if (!hasTargetService(advertisementData)) {
                return
            }
            peripherals[didDiscoverPeripheral.identifier.UUIDString] = didDiscoverPeripheral
            val id = didDiscoverPeripheral.identifier.UUIDString
            val name = didDiscoverPeripheral.name
            val rssiInt = RSSI.intValue
            val callback = discoveryCallback
            if (callback != null) {
                memScoped {
                    val idPtr = id.cstr.getPointer(this)
                    val namePtr = name?.cstr?.getPointer(this)
                    callback(idPtr, namePtr, rssiInt)
                }
            }
        }

        @ObjCSignatureOverride
        override fun centralManager(central: CBCentralManager, didConnectPeripheral: CBPeripheral) {
            val id = didConnectPeripheral.identifier.UUIDString
            println("NativeBLE(mac): connected peripheral $id; discovering services")
            didConnectPeripheral.setDelegate(peripheralConnectionDelegate)
            val service = parseServiceUuid(SERVICE_UUID)
            didConnectPeripheral.discoverServices(service?.let { listOf(it) })
        }

        @ObjCSignatureOverride
        override fun centralManager(
            central: CBCentralManager,
            didFailToConnectPeripheral: CBPeripheral,
            error: NSError?
        ) {
            val id = didFailToConnectPeripheral.identifier.UUIDString
            println("NativeBLE(mac): failed to connect $id error=${error?.localizedDescription}")
        }

        @ObjCSignatureOverride
        override fun centralManager(
            central: CBCentralManager,
            didDisconnectPeripheral: CBPeripheral,
            error: NSError?
        ) {
            val id = didDisconnectPeripheral.identifier.UUIDString
            println("NativeBLE(mac): disconnected $id error=${error?.localizedDescription}")
            peripheralCharacteristics.remove(id)
        }
    }

    private class PeripheralDelegate : NSObject(), CBPeripheralManagerDelegateProtocol {
        override fun peripheralManagerDidUpdateState(peripheral: CBPeripheralManager) {
            println("NativeBLE(mac): peripheral state updated=${peripheral.state}")
            if (peripheral.state == CBManagerStatePoweredOn) {
                ensureService(parseServiceUuid(SERVICE_UUID))
                pendingAdvertise?.let { (uuid, name) ->
                    startAdvertising(uuid, name)
                }
            }
        }

        override fun peripheralManager(
            peripheral: CBPeripheralManager,
            didAddService: CBService,
            error: NSError?
        ) {
            println("NativeBLE(mac): didAddService uuid=${didAddService.UUID.UUIDString} error=${error?.localizedDescription}")
            if (error != null) {
                serviceAdded = false
            }
        }

        override fun peripheralManagerDidStartAdvertising(peripheral: CBPeripheralManager, error: NSError?) {
            println("NativeBLE(mac): didStartAdvertising error=${error?.localizedDescription}")
        }

        @ObjCSignatureOverride
        override fun peripheralManager(
            peripheral: CBPeripheralManager,
            central: CBCentral,
            didSubscribeToCharacteristic: CBCharacteristic
        ) {
            subscribedCentrals.add(central)
            println("NativeBLE(mac): central subscribed ${central.identifier.UUIDString}")
        }

        @ObjCSignatureOverride
        override fun peripheralManager(
            peripheral: CBPeripheralManager,
            central: CBCentral,
            didUnsubscribeFromCharacteristic: CBCharacteristic
        ) {
            subscribedCentrals.remove(central)
            println("NativeBLE(mac): central unsubscribed ${central.identifier.UUIDString}")
        }

        override fun peripheralManager(
            peripheral: CBPeripheralManager,
            didReceiveWriteRequests: List<*>
        ) {
            println("NativeBLE(mac): didReceiveWriteRequests count=${didReceiveWriteRequests.size}")
            didReceiveWriteRequests.forEach { anyReq ->
                val req = anyReq as? CBATTRequest ?: return@forEach
                val targetChar = characteristic ?: run {
                    println("NativeBLE(mac): write request ignored - no characteristic")
                    return@forEach
                }
                if (req.characteristic != targetChar) {
                    println("NativeBLE(mac): write request ignored - wrong characteristic")
                    return@forEach
                }
                val data = req.value?.toByteArray() ?: run {
                    println("NativeBLE(mac): write request ignored - no data")
                    return@forEach
                }
                val deviceId = req.central.identifier.UUIDString
                println("NativeBLE(mac): write request from $deviceId size=${data.size} hasCallback=${dataCallback != null}")
                dataCallback?.let { cb ->
                    memScoped {
                        val idPtr = deviceId.cstr.getPointer(this)
                        data.usePinned { pinned ->
                            cb(idPtr, pinned.addressOf(0), data.size)
                        }
                    }
                }
                peripheral.respondToRequest(req, CBATTErrorSuccess)
            }
        }
    }

    private class PeripheralConnectionDelegate : NSObject(), CBPeripheralDelegateProtocol {
        @ObjCSignatureOverride
        override fun peripheral(peripheral: CBPeripheral, didDiscoverServices: NSError?) {
            val id = peripheral.identifier.UUIDString
            if (didDiscoverServices != null) {
                println("NativeBLE(mac): service discovery failed for $id error=${didDiscoverServices.localizedDescription}")
                return
            }
            val target = parseServiceUuid(SERVICE_UUID)
            val services = peripheral.services ?: run {
                println("NativeBLE(mac): no services found for $id")
                return
            }
            println("NativeBLE(mac): discovered ${services.size} services for $id")
            services.forEach { anyService ->
                val svc = anyService as? CBService ?: return@forEach
                println("NativeBLE(mac): service ${svc.UUID.UUIDString} (target=${target?.UUIDString})")
                if (svc.UUID == target) {
                    val characteristicUuid = parseServiceUuid(CHARACTERISTIC_UUID)
                    println("NativeBLE(mac): discovering characteristics for service")
                    peripheral.discoverCharacteristics(characteristicUuid?.let { listOf(it) }, svc)
                }
            }
        }

        @ObjCSignatureOverride
        override fun peripheral(
            peripheral: CBPeripheral,
            didDiscoverCharacteristicsForService: CBService,
            error: NSError?
        ) {
            val id = peripheral.identifier.UUIDString
            if (error != null) {
                println("NativeBLE(mac): characteristic discovery failed for $id error=${error.localizedDescription}")
                return
            }
            val characteristicUuid = parseServiceUuid(CHARACTERISTIC_UUID) ?: return
            val chars = didDiscoverCharacteristicsForService.characteristics ?: run {
                println("NativeBLE(mac): no characteristics found for $id")
                return
            }
            println("NativeBLE(mac): discovered ${chars.size} characteristics for $id")
            chars.forEach { anyChar ->
                val ch = anyChar as? CBCharacteristic ?: return@forEach
                println("NativeBLE(mac): characteristic ${ch.UUID.UUIDString} (target=${characteristicUuid.UUIDString})")
                if (ch.UUID == characteristicUuid) {
                    peripheralCharacteristics[id] = ch
                    println("NativeBLE(mac): subscribing to notifications for $id")
                    peripheral.setNotifyValue(true, ch)
                }
            }
        }

        @ObjCSignatureOverride
        override fun peripheral(
            peripheral: CBPeripheral,
            didUpdateValueForCharacteristic: CBCharacteristic,
            error: NSError?
        ) {
            val deviceId = peripheral.identifier.UUIDString
            if (error != null) {
                println("NativeBLE(mac): notification error for $deviceId: ${error.localizedDescription}")
                return
            }
            val data = didUpdateValueForCharacteristic.value?.toByteArray() ?: run {
                println("NativeBLE(mac): notification from $deviceId has no data")
                return
            }
            println("NativeBLE(mac): notification from $deviceId size=${data.size} hasCallback=${dataCallback != null}")
            dataCallback?.let { cb ->
                memScoped {
                    val idPtr = deviceId.cstr.getPointer(this)
                    data.usePinned { pinned ->
                        cb(idPtr, pinned.addressOf(0), data.size)
                    }
                }
            }
        }
    }

    private fun hasTargetService(advertisementData: Map<Any?, *>): Boolean {
        val serviceList = advertisementData[CBAdvertisementDataServiceUUIDsKey] as? List<*>
        val target = parseServiceUuid(SERVICE_UUID) ?: return false
        return serviceList?.any { (it as? CBUUID)?.UUIDString == target.UUIDString } == true
    }

    private fun ensureService(uuid: CBUUID?) {
        if (serviceAdded) return
        val serviceUuid = uuid ?: parseServiceUuid(SERVICE_UUID) ?: return
        val characteristicUuid = parseServiceUuid(CHARACTERISTIC_UUID) ?: return
        val props =
            CBCharacteristicPropertyRead or CBCharacteristicPropertyNotify or CBCharacteristicPropertyWrite or CBCharacteristicPropertyWriteWithoutResponse
        val perms = CBAttributePermissionsReadable or CBAttributePermissionsWriteable
        val char = CBMutableCharacteristic(
            type = characteristicUuid,
            properties = props,
            value = null,
            permissions = perms
        )

        val svc = CBMutableService(type = serviceUuid, primary = true).apply {
            setCharacteristics(listOf(char))
        }
        println("NativeBLE(mac): adding service uuid=${svc.UUID.UUIDString} char=${char.UUID.UUIDString}")
        characteristic = char
        service = svc
        peripheralManager.addService(svc)
        serviceAdded = true
        serviceAdded = true
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun ByteArray.toNSData(): NSData {
        return usePinned { pinned ->
            NSData.create(bytes = pinned.addressOf(0), length = size.toULong())
        }
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun NSData.toByteArray(): ByteArray {
        return ByteArray(length.toInt()).apply {
            usePinned { pinned ->
                memcpy(pinned.addressOf(0), bytes, length)
            }
        }
    }

}
