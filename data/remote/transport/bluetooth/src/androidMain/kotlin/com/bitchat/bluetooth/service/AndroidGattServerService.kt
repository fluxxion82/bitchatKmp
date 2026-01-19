package com.bitchat.bluetooth.service

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.os.ParcelUuid
import android.util.Log
import com.bitchat.domain.base.CoroutineScopeFacade
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.util.*

@SuppressLint("MissingPermission")
class AndroidGattServerService(
    private val context: Context,
    private val coroutineScopeFacade: CoroutineScopeFacade,
) : GattServerService {
    private val bluetoothManager: BluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter
    private val bleAdvertiser: BluetoothLeAdvertiser? = bluetoothAdapter?.bluetoothLeAdvertiser

    private var gattServer: BluetoothGattServer? = null
    private var characteristic: BluetoothGattCharacteristic? = null
    private var advertiseCallback: AdvertiseCallback? = null

    private var delegate: GattServerDelegate? = null

    private var isActive = false
    private val subscribedDevices = mutableMapOf<String, BluetoothDevice>()

    private data class ReassemblyBuffer(
        var expectedSize: Int = 0,
        val data: ByteArrayOutputStream = ByteArrayOutputStream()
    )
    private val reassemblyBuffers = mutableMapOf<String, ReassemblyBuffer>()

    override fun setDelegate(delegate: GattServerDelegate) {
        this.delegate = delegate
    }

    override suspend fun startAdvertising() {
        if (isActive) {
            Log.d(TAG, "GATT server already active")
            return
        }

        if (bluetoothAdapter?.isEnabled != true) {
            Log.e(TAG, "Bluetooth is not enabled")
            return
        }

        if (bleAdvertiser == null) {
            Log.e(TAG, "BLE advertiser not available")
            return
        }

        isActive = true

        coroutineScopeFacade.applicationScope.launch {
            setupGattServer()
            delay(300)
            startBleAdvertising()
        }

        Log.i(TAG, "GATT server started")
    }

    override suspend fun stopAdvertising() {
        if (!isActive) {
            stopBleAdvertising()
            gattServer?.close()
            gattServer = null
            Log.i(TAG, "GATT server stopped (already inactive)")
            return
        }

        isActive = false

        coroutineScopeFacade.applicationScope.launch {
            stopBleAdvertising()

            try {
                subscribedDevices.values.forEach { device ->
                    try {
                        gattServer?.cancelConnection(device)
                    } catch (_: Exception) {
                    }
                }
                subscribedDevices.clear()
            } catch (_: Exception) {
            }

            gattServer?.close()
            gattServer = null

            Log.i(TAG, "GATT server stopped")
        }
    }

    override suspend fun onCharacteristicWriteRequest(data: ByteArray, deviceAddress: String) {
        // This is handled internally by the GATT server callback
        // Kept for interface compliance
    }

    override suspend fun notifyCharacteristic(deviceAddress: String, data: ByteArray): Boolean {
        try {
            val device = subscribedDevices[deviceAddress]
            if (device == null) {
                Log.w(TAG, "No subscribed device found for $deviceAddress")
                return false
            }

            val char = characteristic
            if (char == null) {
                Log.w(TAG, "Characteristic not found for notification")
                return false
            }

            val server = gattServer
            if (server == null) {
                Log.w(TAG, "GATT server not available for notification")
                return false
            }

            return if (data.size <= CHUNK_SIZE) {
                char.value = data
                val success = server.notifyCharacteristicChanged(device, char, false)
                if (success) {
                    Log.d(TAG, "Server: Notify succeeded to $deviceAddress (${data.size} bytes)")
                } else {
                    Log.w(TAG, "Server: Notify failed to $deviceAddress")
                }
                success
            } else {
                notifyChunked(device, char, server, deviceAddress, data)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error notifying characteristic: ${e.message}")
            return false
        }
    }

    @Suppress("DEPRECATION")
    private suspend fun notifyChunked(
        device: BluetoothDevice,
        char: BluetoothGattCharacteristic,
        server: BluetoothGattServer,
        deviceAddress: String,
        data: ByteArray
    ): Boolean {
        val totalSize = data.size
        val chunks = mutableListOf<ByteArray>()

        var offset = 0
        var chunkIndex = 0

        while (offset < totalSize) {
            val remaining = totalSize - offset
            val isFirst = chunkIndex == 0
            val headerSize = if (isFirst) 5 else 1
            val payloadSize = minOf(remaining, CHUNK_SIZE - headerSize)
            val isLast = offset + payloadSize >= totalSize

            val chunkType: Byte = when {
                isFirst -> CHUNK_START
                isLast -> CHUNK_END
                else -> CHUNK_CONTINUE
            }

            val chunk = if (isFirst) {
                ByteArray(1 + 4 + payloadSize).apply {
                    this[0] = chunkType
                    this[1] = ((totalSize shr 24) and 0xFF).toByte()
                    this[2] = ((totalSize shr 16) and 0xFF).toByte()
                    this[3] = ((totalSize shr 8) and 0xFF).toByte()
                    this[4] = (totalSize and 0xFF).toByte()
                    System.arraycopy(data, offset, this, 5, payloadSize)
                }
            } else {
                ByteArray(1 + payloadSize).apply {
                    this[0] = chunkType
                    System.arraycopy(data, offset, this, 1, payloadSize)
                }
            }

            chunks.add(chunk)
            offset += payloadSize
            chunkIndex++
        }

        Log.i(TAG, "Chunking notification ${data.size} bytes into ${chunks.size} chunks for $deviceAddress")

        var allSuccess = true
        for ((index, chunk) in chunks.withIndex()) {
            char.value = chunk
            val success = server.notifyCharacteristicChanged(device, char, false)

            if (!success) {
                Log.e(TAG, "Failed to notify chunk ${index + 1}/${chunks.size} to $deviceAddress")
                allSuccess = false
                break
            }

            Log.d(TAG, "Notified chunk ${index + 1}/${chunks.size} (${chunk.size} bytes) to $deviceAddress")

            if (index < chunks.size - 1) {
                delay(CHUNK_DELAY_MS)
            }
        }

        if (allSuccess) {
            Log.i(TAG, "Successfully notified all ${chunks.size} chunks (${data.size} bytes) to $deviceAddress")
        }

        return allSuccess
    }

    private fun handleIncomingData(deviceAddress: String, value: ByteArray) {
        if (value.isEmpty()) {
            Log.w(TAG, "Received empty data from $deviceAddress")
            return
        }

        val chunkType = value[0]

        when (chunkType) {
            CHUNK_START -> {
                // Start of chunked transfer
                if (value.size < 5) {
                    Log.e(TAG, "Invalid START chunk from $deviceAddress - too short")
                    return
                }
                val totalSize = ((value[1].toInt() and 0xFF) shl 24) or
                        ((value[2].toInt() and 0xFF) shl 16) or
                        ((value[3].toInt() and 0xFF) shl 8) or
                        (value[4].toInt() and 0xFF)

                val buffer = ReassemblyBuffer(expectedSize = totalSize)
                buffer.data.write(value, 5, value.size - 5)
                reassemblyBuffers[deviceAddress] = buffer

                Log.i(TAG, "Server: Started receiving chunked data from $deviceAddress, expecting $totalSize bytes")
            }

            CHUNK_CONTINUE -> {
                val buffer = reassemblyBuffers[deviceAddress]
                if (buffer == null) {
                    Log.w(TAG, "Received CONTINUE chunk without START from $deviceAddress")
                    return
                }
                buffer.data.write(value, 1, value.size - 1)
                Log.d(TAG, "Server: Received CONTINUE chunk from $deviceAddress, total so far: ${buffer.data.size()}")
            }

            CHUNK_END -> {
                val buffer = reassemblyBuffers[deviceAddress]
                if (buffer == null) {
                    Log.w(TAG, "Received END chunk without START from $deviceAddress")
                    return
                }
                buffer.data.write(value, 1, value.size - 1)
                val completeData = buffer.data.toByteArray()
                reassemblyBuffers.remove(deviceAddress)

                Log.i(TAG, "Server: Completed chunked transfer from $deviceAddress: ${completeData.size} bytes")
                delegate?.onDataReceived(completeData, deviceAddress)
            }

            else -> {
                Log.i(TAG, "Server: Received packet from $deviceAddress, size: ${value.size} bytes")
                delegate?.onDataReceived(value, deviceAddress)
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun setupGattServer() {
        val serverCallback = object : BluetoothGattServerCallback() {
            override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
                if (!isActive) {
                    Log.d(TAG, "Ignoring connection state change after shutdown")
                    return
                }

                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        Log.i(TAG, "Server: Device connected ${device.address}")
                        subscribedDevices[device.address] = device

                        coroutineScopeFacade.applicationScope.launch {
                            delay(100)
                            if (isActive) {
                                delegate?.onClientConnected(device.address)
                            }
                        }
                    }

                    BluetoothProfile.STATE_DISCONNECTED -> {
                        Log.i(TAG, "Server: Device disconnected ${device.address}")
                        subscribedDevices.remove(device.address)
                        delegate?.onClientDisconnected(device.address)
                    }
                }
            }

            override fun onServiceAdded(status: Int, service: BluetoothGattService) {
                if (!isActive) {
                    Log.d(TAG, "Ignoring service added callback after shutdown")
                    return
                }

                if (status == BluetoothGatt.GATT_SUCCESS) {
                    Log.d(TAG, "Service added successfully: ${service.uuid}")
                } else {
                    Log.e(TAG, "Failed to add service: ${service.uuid}, status: $status")
                }
            }

            override fun onCharacteristicWriteRequest(
                device: BluetoothDevice,
                requestId: Int,
                characteristic: BluetoothGattCharacteristic,
                preparedWrite: Boolean,
                responseNeeded: Boolean,
                offset: Int,
                value: ByteArray
            ) {
                if (!isActive) {
                    Log.d(TAG, "Ignoring characteristic write after shutdown")
                    return
                }

                if (characteristic.uuid == CHARACTERISTIC_UUID) {
                    // Ensure device is in subscribedDevices so we can notify it back
                    // This handles cases where the connection event was missed or address changed
                    if (!subscribedDevices.containsKey(device.address)) {
                        Log.i(TAG, "Server: Adding write-sender to subscribers: ${device.address}")
                        subscribedDevices[device.address] = device
                        coroutineScopeFacade.applicationScope.launch {
                            delay(100)
                            if (isActive) {
                                delegate?.onClientConnected(device.address)
                            }
                        }
                    }

                    handleIncomingData(device.address, value)

                    if (responseNeeded) {
                        gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
                    }
                }
            }

            override fun onDescriptorWriteRequest(
                device: BluetoothDevice,
                requestId: Int,
                descriptor: BluetoothGattDescriptor,
                preparedWrite: Boolean,
                responseNeeded: Boolean,
                offset: Int,
                value: ByteArray
            ) {
                if (!isActive) {
                    Log.d(TAG, "Ignoring descriptor write after shutdown")
                    return
                }

                if (BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE.contentEquals(value)) {
                    subscribedDevices[device.address] = device
                    Log.d(TAG, "Server: Connection setup complete for ${device.address}")

                    coroutineScopeFacade.applicationScope.launch {
                        delay(100)
                        if (isActive) {
                            delegate?.onClientConnected(device.address)
                        }
                    }
                }

                if (responseNeeded) {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
                }
            }
        }

        gattServer?.let { server ->
            Log.d(TAG, "Cleaning up existing GATT server")
            try {
                server.close()
            } catch (e: Exception) {
                Log.w(TAG, "Error closing existing GATT server: ${e.message}")
            }
        }

        // Small delay to ensure cleanup is complete
        Thread.sleep(100)

        if (!isActive) {
            Log.d(TAG, "Service inactive, skipping GATT server creation")
            return
        }

        gattServer = bluetoothManager.openGattServer(context, serverCallback)

        characteristic = BluetoothGattCharacteristic(
            CHARACTERISTIC_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ or
                    BluetoothGattCharacteristic.PROPERTY_WRITE or
                    BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE or
                    BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ or
                    BluetoothGattCharacteristic.PERMISSION_WRITE
        )

        val descriptor = BluetoothGattDescriptor(
            DESCRIPTOR_UUID,
            BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
        )
        characteristic?.addDescriptor(descriptor)

        val service = BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        service.addCharacteristic(characteristic)

        gattServer?.addService(service)

        Log.i(TAG, "GATT server setup complete")
    }

    @Suppress("DEPRECATION")
    private fun startBleAdvertising() {
        if (bleAdvertiser == null) {
            Log.e(TAG, "BLE advertiser not available")
            return
        }

        if (advertiseCallback != null) {
            Log.d(TAG, "Already advertising")
            return
        }

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setConnectable(true)
            .setTimeout(0)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .build()

        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .addServiceUuid(ParcelUuid(SERVICE_UUID))
            .build()

        val callback = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
                Log.i(TAG, "Advertising started successfully")
            }

            override fun onStartFailure(errorCode: Int) {
                Log.e(TAG, "Advertising failed to start, error code: $errorCode")
            }
        }

        advertiseCallback = callback

        try {
            bleAdvertiser.startAdvertising(settings, data, callback)
            Log.i(TAG, "BLE advertising initiated")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start advertising: ${e.message}")
            advertiseCallback = null
        }
    }

    @Suppress("DEPRECATION")
    private fun stopBleAdvertising() {
        advertiseCallback?.let { callback ->
            try {
                bleAdvertiser?.stopAdvertising(callback)
                Log.i(TAG, "Advertising stopped")
            } catch (e: Exception) {
                Log.w(TAG, "Error stopping advertising: ${e.message}")
            }
            advertiseCallback = null
        }
    }

    companion object {
        private const val TAG = "AndroidGattServerService"

        val SERVICE_UUID: UUID = UUID.fromString("F47B5E2D-4A9E-4C5A-9B3F-8E1D2C3A4B5C")
        val CHARACTERISTIC_UUID: UUID = UUID.fromString("A1B2C3D4-E5F6-4A5B-8C9D-0E1F2A3B4C5D")
        val DESCRIPTOR_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb") // CCCD

        private const val CHUNK_SIZE = 500
        private const val CHUNK_DELAY_MS = 25L

        private val CHUNK_START: Byte = 0xFC.toByte()     // 252
        private val CHUNK_CONTINUE: Byte = 0xFD.toByte() // 253
        private val CHUNK_END: Byte = 0xFE.toByte()       // 254
    }
}
