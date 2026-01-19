package com.bitchat.bluetooth.service

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.util.Log
import com.bitchat.domain.base.CoroutineScopeFacade
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.*

@SuppressLint("MissingPermission")
class AndroidGattClientService(
    private val context: Context,
    private val coroutineScopeFacade: CoroutineScopeFacade,
) : GattClientService {
    private val bluetoothManager: BluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter

    private val connections = mutableMapOf<String, DeviceConnection>()
    private val connectionMutex = Mutex()

    private var delegate: GattClientDelegate? = null
    private var connectionDelegate: GattClientConnectionDelegate? = null

    private var readyCallback: ConnectionReadyCallback? = null

    // Chunk type markers - use high byte values to avoid conflict with packet types
    // (existing protocol uses low byte values like 0x01=ANNOUNCE, 0x02=MESSAGE, etc.)
    private val CHUNK_START: Byte = 0xFC.toByte()     // 252
    private val CHUNK_CONTINUE: Byte = 0xFD.toByte() // 253
    private val CHUNK_END: Byte = 0xFE.toByte()       // 254

    private val reassemblyBuffers = mutableMapOf<String, ReassemblyBuffer>()

    override fun setDelegate(delegate: GattClientDelegate) {
        this.delegate = delegate
    }

    fun setConnectionDelegate(delegate: GattClientConnectionDelegate) {
        this.connectionDelegate = delegate
    }

    internal fun setInternalReadyCallback(callback: ConnectionReadyCallback) {
        this.readyCallback = callback
    }

    suspend fun connectToDevice(deviceAddress: String, rssi: Int = -50) {
        val existingState = connectionMutex.withLock {
            connections[deviceAddress]?.state
        }

        when (existingState) {
            ConnectionState.CONNECTING -> {
                Log.d(TAG, "Already connecting to $deviceAddress, skipping duplicate connection attempt")
                return
            }

            ConnectionState.CONNECTED -> {
                Log.d(TAG, "Already connected to $deviceAddress")
                return
            }

            ConnectionState.FAILED -> {
                Log.d(TAG, "Retrying connection to $deviceAddress after previous failure")
                connectionMutex.withLock {
                    connections[deviceAddress]?.let { conn ->
                        try {
                            conn.gatt.close()
                        } catch (e: Exception) {
                            Log.w(TAG, "Error closing old GATT: ${e.message}")
                        }
                        connections.remove(deviceAddress)
                    }
                }
            }

            ConnectionState.DISCONNECTED, null -> {
                // OK to connect
            }
        }

        val device = bluetoothAdapter?.getRemoteDevice(deviceAddress)
        if (device == null) {
            Log.e(TAG, "Cannot get remote device for $deviceAddress")
            return
        }

        Log.i(TAG, "Connecting to device: $deviceAddress")
        connectToDeviceInternal(device, rssi)
    }

    override suspend fun writeCharacteristic(deviceAddress: String, data: ByteArray): Boolean {
        val connection = connectionMutex.withLock {
            connections[deviceAddress]
        }

        if (connection == null) {
            Log.w(TAG, "No connection to $deviceAddress, write failed")
            return false
        }

        if (!connection.isReady || connection.characteristic == null) {
            val timestamp = System.currentTimeMillis()
            Log.w(TAG, "[$timestamp] Connection to $deviceAddress not ready yet (isReady=${connection.isReady}, state=${connection.state})")
            return false
        }

        return try {
            val characteristic = connection.characteristic!!
            characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE

            if (data.size <= CHUNK_SIZE) {
                characteristic.value = data
                val success = connection.gatt.writeCharacteristic(characteristic)
                if (success) {
                    Log.d(TAG, "Write initiated to $deviceAddress, ${data.size} bytes")
                    delegate?.onWriteSuccess(deviceAddress)
                } else {
                    Log.w(TAG, "Failed to initiate write to $deviceAddress")
                    delegate?.onWriteFailure(deviceAddress, "Write initiation failed")
                }
                success
            } else {
                writeChunked(connection, deviceAddress, data)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error writing to $deviceAddress: ${e.message}")
            delegate?.onWriteFailure(deviceAddress, e.message ?: "Unknown error")
            false
        }
    }

    @Suppress("DEPRECATION")
    private suspend fun writeChunked(
        connection: DeviceConnection,
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
                // First chunk: [type][4-byte size][payload]
                ByteArray(1 + 4 + payloadSize).apply {
                    this[0] = chunkType
                    this[1] = ((totalSize shr 24) and 0xFF).toByte()
                    this[2] = ((totalSize shr 16) and 0xFF).toByte()
                    this[3] = ((totalSize shr 8) and 0xFF).toByte()
                    this[4] = (totalSize and 0xFF).toByte()
                    System.arraycopy(data, offset, this, 5, payloadSize)
                }
            } else {
                // Subsequent chunks: [type][payload]
                ByteArray(1 + payloadSize).apply {
                    this[0] = chunkType
                    System.arraycopy(data, offset, this, 1, payloadSize)
                }
            }

            chunks.add(chunk)
            offset += payloadSize
            chunkIndex++
        }

        Log.i(TAG, "Chunking ${data.size} bytes into ${chunks.size} chunks for $deviceAddress")

        val characteristic = connection.characteristic!!
        var allSuccess = true

        for ((index, chunk) in chunks.withIndex()) {
            characteristic.value = chunk
            val success = connection.gatt.writeCharacteristic(characteristic)

            if (!success) {
                Log.e(TAG, "Failed to write chunk ${index + 1}/${chunks.size} to $deviceAddress")
                allSuccess = false
                break
            }

            Log.d(TAG, "Wrote chunk ${index + 1}/${chunks.size} (${chunk.size} bytes) to $deviceAddress")

            if (index < chunks.size - 1) {
                delay(CHUNK_DELAY_MS)
            }
        }

        if (allSuccess) {
            Log.i(TAG, "Successfully wrote all ${chunks.size} chunks (${data.size} bytes) to $deviceAddress")
            delegate?.onWriteSuccess(deviceAddress)
        } else {
            delegate?.onWriteFailure(deviceAddress, "Chunked write failed")
        }

        return allSuccess
    }

    private fun handleIncomingNotification(deviceAddress: String, value: ByteArray) {
        if (value.isEmpty()) {
            Log.w(TAG, "Client: Received empty notification from $deviceAddress")
            return
        }

        val chunkType = value[0]

        when (chunkType) {
            CHUNK_START -> {
                // Start of chunked transfer
                if (value.size < 5) {
                    Log.e(TAG, "Client: Invalid START chunk from $deviceAddress - too short")
                    return
                }
                val totalSize = ((value[1].toInt() and 0xFF) shl 24) or
                        ((value[2].toInt() and 0xFF) shl 16) or
                        ((value[3].toInt() and 0xFF) shl 8) or
                        (value[4].toInt() and 0xFF)

                val buffer = ReassemblyBuffer(expectedSize = totalSize)
                buffer.data.write(value, 5, value.size - 5)
                reassemblyBuffers[deviceAddress] = buffer

                Log.i(TAG, "Client: Started receiving chunked notification from $deviceAddress, expecting $totalSize bytes")
            }

            CHUNK_CONTINUE -> {
                // Continuation chunk
                val buffer = reassemblyBuffers[deviceAddress]
                if (buffer == null) {
                    Log.w(TAG, "Client: Received CONTINUE chunk without START from $deviceAddress")
                    return
                }
                buffer.data.write(value, 1, value.size - 1)
                Log.d(TAG, "Client: Received CONTINUE chunk from $deviceAddress, total so far: ${buffer.data.size()}")
            }

            CHUNK_END -> {
                // End chunk
                val buffer = reassemblyBuffers[deviceAddress]
                if (buffer == null) {
                    Log.w(TAG, "Client: Received END chunk without START from $deviceAddress")
                    return
                }
                buffer.data.write(value, 1, value.size - 1)
                val completeData = buffer.data.toByteArray()
                reassemblyBuffers.remove(deviceAddress)

                Log.i(TAG, "Client: Completed chunked notification from $deviceAddress: ${completeData.size} bytes")
                delegate?.onCharacteristicRead(deviceAddress, completeData)
            }

            else -> {
                // Non-chunked data (regular small packet)
                Log.i(TAG, "Client: Received notification from $deviceAddress, ${value.size} bytes")
                delegate?.onCharacteristicRead(deviceAddress, value)
            }
        }
    }

    override suspend fun disconnect(deviceAddress: String) {
        connectionMutex.withLock {
            connections[deviceAddress]?.let { connection ->
                try {
                    connection.gatt.disconnect()
                    Log.i(TAG, "Disconnecting from $deviceAddress")
                } catch (e: Exception) {
                    Log.w(TAG, "Error disconnecting from $deviceAddress: ${e.message}")
                }
            }
        }
    }

    override suspend fun disconnectAll() {
        connectionMutex.withLock {
            connections.values.forEach { connection ->
                try {
                    connection.gatt.disconnect()
                } catch (e: Exception) {
                    Log.w(TAG, "Error disconnecting: ${e.message}")
                }
            }
            Log.i(TAG, "Disconnected from all devices")
        }
    }

    @Suppress("DEPRECATION")
    private fun connectToDeviceInternal(device: BluetoothDevice, rssi: Int) {
        val deviceAddress = device.address

        val gattCallback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                when {
                    newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS -> {
                        Log.i(TAG, "✅ Client: Connected to $deviceAddress, requesting MTU")

                        coroutineScopeFacade.applicationScope.launch {
                            connectionMutex.withLock {
                                connections[deviceAddress]?.state = ConnectionState.CONNECTED
                            }
                            connectionDelegate?.onConnectionSuccess(deviceAddress)
                            delay(200) // Small delay for reliability
                            gatt.requestMtu(517)
                        }
                    }

                    newState == BluetoothProfile.STATE_DISCONNECTED -> {
                        if (status != BluetoothGatt.GATT_SUCCESS) {
                            Log.w(TAG, "❌ Client: Disconnected from $deviceAddress with error status $status")
                            coroutineScopeFacade.applicationScope.launch {
                                connectionMutex.withLock {
                                    connections[deviceAddress]?.state = ConnectionState.FAILED
                                }
                                connectionDelegate?.onConnectionFailure(deviceAddress, "Status: $status")
                            }
                        } else {
                            Log.d(TAG, "Client: Cleanly disconnected from $deviceAddress")
                        }

                        coroutineScopeFacade.applicationScope.launch {
                            delay(500)
                            connectionMutex.withLock {
                                connections[deviceAddress]?.let { conn ->
                                    conn.state = ConnectionState.DISCONNECTED
                                }
                                connections.remove(deviceAddress)
                            }
                            try {
                                gatt.close()
                            } catch (e: Exception) {
                                Log.w(TAG, "Error closing GATT: ${e.message}")
                            }
                        }
                    }
                }
            }

            override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
                Log.i(TAG, "Client: MTU changed to $mtu for $deviceAddress, status: $status")

                if (status == BluetoothGatt.GATT_SUCCESS) {
                    Log.i(TAG, "MTU negotiated, discovering services for $deviceAddress")
                    gatt.discoverServices()
                } else {
                    Log.w(TAG, "MTU negotiation failed for $deviceAddress, disconnecting")
                    coroutineScopeFacade.applicationScope.launch {
                        connectionMutex.withLock {
                            connections[deviceAddress]?.state = ConnectionState.FAILED
                        }
                    }
                    gatt.disconnect()
                }
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    val service = gatt.getService(SERVICE_UUID)
                    if (service != null) {
                        val characteristic = service.getCharacteristic(CHARACTERISTIC_UUID)
                        if (characteristic != null) {
                            Log.d(TAG, "Client: Found characteristic for $deviceAddress")

                            coroutineScopeFacade.applicationScope.launch {
                                connectionMutex.withLock {
                                    connections[deviceAddress]?.let { conn ->
                                        conn.characteristic = characteristic
                                    }
                                }
                            }

                            gatt.setCharacteristicNotification(characteristic, true)
                            val descriptor = characteristic.getDescriptor(DESCRIPTOR_UUID)
                            if (descriptor != null) {
                                descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                                gatt.writeDescriptor(descriptor)

                                coroutineScopeFacade.applicationScope.launch {
                                    delay(200)
                                    val timestamp = System.currentTimeMillis()
                                    connectionMutex.withLock {
                                        connections[deviceAddress]?.isReady = true
                                    }
                                    Log.i(TAG, "[$timestamp] Client: Connection setup complete for $deviceAddress")

                                    // Notify that connection is ready for announces
                                    readyCallback?.onConnectionReady(deviceAddress)
                                }
                            } else {
                                Log.e(TAG, "Client: CCCD descriptor not found for $deviceAddress")
                                gatt.disconnect()
                            }
                        } else {
                            Log.e(TAG, "Client: Characteristic not found for $deviceAddress")
                            gatt.disconnect()
                        }
                    } else {
                        Log.e(TAG, "Client: Service not found for $deviceAddress")
                        gatt.disconnect()
                    }
                } else {
                    Log.e(TAG, "Client: Service discovery failed for $deviceAddress, status: $status")
                    gatt.disconnect()
                }
            }

            override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
                val value = characteristic.value
                handleIncomingNotification(deviceAddress, value)
            }

            override fun onCharacteristicWrite(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                status: Int
            ) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    Log.d(TAG, "Client: Write successful to $deviceAddress")
                    delegate?.onWriteSuccess(deviceAddress)
                } else {
                    Log.w(TAG, "Client: Write failed to $deviceAddress, status: $status")
                    delegate?.onWriteFailure(deviceAddress, "Write failed with status $status")
                }
            }
        }

        try {
            Log.d(TAG, "Client: Attempting GATT connection to $deviceAddress")
            val gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)

            if (gatt == null) {
                Log.e(TAG, "connectGatt returned null for $deviceAddress")
            } else {
                coroutineScopeFacade.applicationScope.launch {
                    connectionMutex.withLock {
                        connections[deviceAddress] = DeviceConnection(
                            device = device,
                            gatt = gatt,
                            state = ConnectionState.CONNECTING,
                            isReady = false
                        )
                    }
                }
                Log.d(TAG, "GATT connection initiated for $deviceAddress")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error connecting to $deviceAddress: ${e.message}")
        }
    }

    private data class ReassemblyBuffer(
        var expectedSize: Int = 0,
        val data: java.io.ByteArrayOutputStream = java.io.ByteArrayOutputStream()
    )

    private enum class ConnectionState {
        DISCONNECTED,
        CONNECTING,
        CONNECTED,
        FAILED
    }

    private data class DeviceConnection(
        val device: BluetoothDevice,
        val gatt: BluetoothGatt,
        var characteristic: BluetoothGattCharacteristic? = null,
        var isReady: Boolean = false,
        var state: ConnectionState = ConnectionState.CONNECTING
    )

    companion object {
        private const val TAG = "AndroidGattClientService"

        // Bitchat mesh service UUIDs (must match AndroidGattServerService)
        val SERVICE_UUID: UUID = UUID.fromString("F47B5E2D-4A9E-4C5A-9B3F-8E1D2C3A4B5C")
        val CHARACTERISTIC_UUID: UUID = UUID.fromString("A1B2C3D4-E5F6-4A5B-8C9D-0E1F2A3B4C5D")
        val DESCRIPTOR_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb") // CCCD

        // Chunking constants for large data transfers
        // BLE MTU is typically 512-517, leave room for headers
        private const val CHUNK_SIZE = 500
        private const val CHUNK_DELAY_MS = 25L  // Delay between chunks to prevent buffer overflow
    }
}

interface GattClientConnectionDelegate {
    suspend fun onConnectionSuccess(deviceAddress: String)
    suspend fun onConnectionFailure(deviceAddress: String, reason: String)
}
