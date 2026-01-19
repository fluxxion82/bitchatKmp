package com.bitchat.bluetooth.service

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.bitchat.domain.base.CoroutineScopeFacade
import com.bitchat.domain.base.CoroutinesContextFacade
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@Suppress("TooGenericExceptionCaught")
class AndroidConnectionService(
    private val context: Context,
    private val coroutineScopeFacade: CoroutineScopeFacade,
    private val coroutineContextFacade: CoroutinesContextFacade,
    private val gattServer: AndroidGattServerService,
    private val gattClient: AndroidGattClientService,
) : BluetoothConnectionService {
    private var bluetoothAdapter: BluetoothAdapter? = null

    private val discoveredDevices = mutableSetOf<String>()  // CLIENT connections
    private val serverConnectedDevices = mutableSetOf<String>()  // SERVER connections
    private val deviceMutex = Mutex()

    private val connectionStates = mutableMapOf<String, ConnectionState>()
    private val lastConnectionAttempt = mutableMapOf<String, Long>()
    private val connectionAttemptCount = mutableMapOf<String, Int>()

    private var onPacketReceivedCallback: ((ByteArray, String) -> Unit)? = null

    private var connectionEstablishedCallback: ConnectionEstablishedCallback? = null

    private var readyCallback: ConnectionReadyCallback? = null

    init {
        val bluetoothManager: BluetoothManager =
            context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter

        setupDelegates()
    }

    private fun setupDelegates() {
        gattServer.setDelegate(object : GattServerDelegate {
            override fun onDataReceived(data: ByteArray, deviceAddress: String) {
                Log.i(TAG, "Received data from server: $deviceAddress, ${data.size} bytes")
                onPacketReceivedCallback?.invoke(data, deviceAddress)
            }

            override fun onClientConnected(deviceAddress: String) {
                Log.i(TAG, "Client connected to server: $deviceAddress")
                coroutineScopeFacade.applicationScope.launch {
                    deviceMutex.withLock {
                        serverConnectedDevices.add(deviceAddress)
                        Log.i(TAG, "Server connection added: $deviceAddress")
                    }
                }
            }

            override fun onClientDisconnected(deviceAddress: String) {
                Log.i(TAG, "Client disconnected from server: $deviceAddress")
                coroutineScopeFacade.applicationScope.launch {
                    deviceMutex.withLock {
                        serverConnectedDevices.remove(deviceAddress)
                    }
                }
            }
        })

        // Wire GATT client callbacks
        gattClient.setDelegate(object : GattClientDelegate {
            override fun onCharacteristicRead(deviceAddress: String, data: ByteArray) {
                Log.i(TAG, "Received notification from client: $deviceAddress, ${data.size} bytes")
                onPacketReceivedCallback?.invoke(data, deviceAddress)
            }

            override fun onWriteSuccess(deviceAddress: String) {
                Log.d(TAG, "Write successful to: $deviceAddress")
            }

            override fun onWriteFailure(deviceAddress: String, error: String) {
                Log.w(TAG, "Write failed to $deviceAddress: $error")
            }
        })
    }

    fun setOnPacketReceivedCallback(callback: (ByteArray, String) -> Unit) {
        this.onPacketReceivedCallback = callback
    }

    override fun setConnectionEstablishedCallback(callback: ConnectionEstablishedCallback) {
        this.connectionEstablishedCallback = callback
    }

    override fun setConnectionReadyCallback(callback: ConnectionReadyCallback) {
        this.readyCallback = callback
        gattClient.setInternalReadyCallback(callback)
    }

    suspend fun onDeviceDiscovered(deviceAddress: String, deviceName: String? = null) {
        deviceMutex.withLock {
            val state = connectionStates[deviceAddress] ?: ConnectionState.DISCONNECTED

            if (state == ConnectionState.CONNECTING || state == ConnectionState.CONNECTED) {
                return
            }

            val now = System.currentTimeMillis()
            val lastAttempt = lastConnectionAttempt[deviceAddress] ?: 0L
            val attemptCount = connectionAttemptCount[deviceAddress] ?: 0

            val backoffMs = minOf(5000L * (1 shl attemptCount), 60000L)

            if (now - lastAttempt < backoffMs) {
                return
            }

            discoveredDevices.add(deviceAddress)
            connectionStates[deviceAddress] = ConnectionState.CONNECTING
            lastConnectionAttempt[deviceAddress] = now
            connectionAttemptCount[deviceAddress] = attemptCount + 1

            val connectedCount = connectionStates.values.count { it == ConnectionState.CONNECTED }
            val connectingCount = connectionStates.values.count { it == ConnectionState.CONNECTING }

            Log.i(
                TAG,
                "📡 Scan: $deviceAddress (${deviceName ?: "Unknown"}) - Attempt #${attemptCount + 1} | Connected: $connectedCount, Connecting: $connectingCount"
            )
        }

        connectToDevice(deviceAddress)
    }

    override suspend fun connectToDevice(deviceAddress: String) {
        Log.i(TAG, "Initiating connection to: $deviceAddress")
        gattClient.connectToDevice(deviceAddress)
    }

    override suspend fun confirmDevice() {
        Log.d(TAG, "confirmDevice() called - no-op in GATT architecture")
    }

    override suspend fun isDeviceConnecting(deviceAddress: String): Boolean {
        return deviceMutex.withLock {
            discoveredDevices.contains(deviceAddress)
        }
    }

    override suspend fun disconnectDeviceByAddress(deviceAddress: String) {
        Log.i(TAG, "Disconnecting from: $deviceAddress")
        gattClient.disconnect(deviceAddress)

        deviceMutex.withLock {
            discoveredDevices.remove(deviceAddress)
        }
    }

    suspend fun onDeviceConnected(deviceAddress: String) {
        deviceMutex.withLock {
            connectionStates[deviceAddress] = ConnectionState.CONNECTED
            connectionAttemptCount[deviceAddress] = 0 // Reset on success
            Log.i(TAG, "✅ Connection established: $deviceAddress")
        }

        Log.d(TAG, "Device connected: $deviceAddress; notifying callback")
        connectionEstablishedCallback?.onDeviceConnected(deviceAddress)
    }

    suspend fun onDeviceConnectionFailed(deviceAddress: String, reason: String) {
        deviceMutex.withLock {
            connectionStates[deviceAddress] = ConnectionState.DISCONNECTED
            Log.w(TAG, "❌ Connection failed: $deviceAddress - $reason")
        }
    }

    override suspend fun clearConnections() {
        Log.i(TAG, "Clearing all connections")
        gattClient.disconnectAll()

        deviceMutex.withLock {
            discoveredDevices.clear()
            serverConnectedDevices.clear()
        }
    }

    override suspend fun broadcastPacket(packetData: ByteArray) {
        val (clientDevices, serverDevices) = deviceMutex.withLock {
            Pair(discoveredDevices.toList(), serverConnectedDevices.toList())
        }

        val totalDevices = clientDevices.size + serverDevices.size
        if (totalDevices == 0) {
            Log.w(TAG, "No connected devices to broadcast to")
            return
        }

        Log.i(TAG, "Broadcasting to ${clientDevices.size} clients, ${serverDevices.size} servers (${packetData.size} bytes)")

        clientDevices.forEach { deviceAddress ->
            coroutineScopeFacade.applicationScope.launch {
                try {
                    val success = gattClient.writeCharacteristic(deviceAddress, packetData)
                    if (!success) {
                        Log.w(TAG, "Failed to write to client $deviceAddress")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error broadcasting to client $deviceAddress: ${e.message}")
                }
            }
        }

        serverDevices.forEach { deviceAddress ->
            coroutineScopeFacade.applicationScope.launch {
                try {
                    val success = gattServer.notifyCharacteristic(deviceAddress, packetData)
                    if (!success) {
                        Log.w(TAG, "Failed to notify server $deviceAddress")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error notifying server $deviceAddress: ${e.message}")
                }
            }
        }
    }

    suspend fun start() {
        Log.i(TAG, "Starting connection service")
        gattServer.startAdvertising()
    }

    suspend fun stop() {
        Log.i(TAG, "Stopping connection service")
        gattServer.stopAdvertising()
        clearConnections()
    }

    override fun hasRequiredPermissions(): Boolean {
        val permissions = mutableListOf<String>()

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            permissions.addAll(
                listOf(
                    Manifest.permission.BLUETOOTH_ADVERTISE,
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.BLUETOOTH_SCAN
                )
            )
        } else {
            permissions.addAll(
                listOf(
                    Manifest.permission.BLUETOOTH,
                    Manifest.permission.BLUETOOTH_ADMIN
                )
            )
        }

        permissions.addAll(
            listOf(
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        )

        val allGranted = permissions.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }

        return allGranted
    }

    enum class ConnectionState {
        DISCONNECTED,
        CONNECTING,
        CONNECTED,
        DISCONNECTING
    }

    companion object {
        private const val TAG = "AndroidConnectionService"
    }
}
