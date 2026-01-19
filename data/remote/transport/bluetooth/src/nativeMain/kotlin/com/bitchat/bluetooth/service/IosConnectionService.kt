package com.bitchat.bluetooth.service

import com.bitchat.bluetooth.protocol.logDebug
import com.bitchat.bluetooth.protocol.logInfo
import com.bitchat.domain.base.CoroutineScopeFacade
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import platform.CoreBluetooth.CBPeripheral
import kotlin.time.Clock

class IosConnectionService(
    private val coroutineScopeFacade: CoroutineScopeFacade,
    private val gattServer: IosGattServerService,
    private val gattClient: IosGattClientService,
) : BluetoothConnectionService {

    enum class ConnectionState {
        DISCONNECTED,
        CONNECTING,
        CONNECTED,
        DISCONNECTING
    }

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
        setupDelegates()
    }

    override suspend fun connectToDevice(deviceAddress: String) {
        gattClient.connectToDevice(deviceAddress)
    }

    override suspend fun confirmDevice() {
        // No-op for BLE mesh
    }

    override suspend fun isDeviceConnecting(deviceAddress: String): Boolean {
        return deviceMutex.withLock {
            discoveredDevices.contains(deviceAddress)
        }
    }

    override suspend fun disconnectDeviceByAddress(deviceAddress: String) {
        gattClient.disconnect(deviceAddress)
        deviceMutex.withLock {
            discoveredDevices.remove(deviceAddress)
        }
    }

    override suspend fun clearConnections() {
        gattClient.disconnectAll()
        deviceMutex.withLock {
            discoveredDevices.clear()
            serverConnectedDevices.clear()
        }
    }

    override suspend fun broadcastPacket(packetData: ByteArray) {
        val readyClients = gattClient.getReadyDeviceAddresses()
        val (clientDevices, serverDevices) = deviceMutex.withLock {
            Pair(discoveredDevices.toList(), serverConnectedDevices.toList())
        }
        val pendingClients = clientDevices.filterNot { readyClients.contains(it) }

        val totalDevices = readyClients.size + serverDevices.size
        logInfo(
            "BROADCAST_WRITE",
            "Broadcasting ${packetData.size}B to $totalDevices devices (ready clients:${readyClients.size}, servers:${serverDevices.size}, pending clients:${pendingClients.size})"
        )

        if (totalDevices == 0) {
            logDebug("BROADCAST_WRITE", "No devices to write to")
            return
        }

        if (pendingClients.isNotEmpty()) {
            logDebug(
                "BROADCAST_WRITE",
                "Skipping ${pendingClients.size} pending clients (not ready): ${
                    pendingClients.joinToString { it.take(8) }
                }"
            )
        }

        readyClients.forEachIndexed { index, deviceAddress ->
            coroutineScopeFacade.applicationScope.launch {
                val success = gattClient.writeCharacteristic(deviceAddress, packetData)
                if (!success) {
                    logDebug("BROADCAST_WRITE", "Client write failed: ${deviceAddress.take(8)}")
                }
            }
        }

        serverDevices.forEachIndexed { index, deviceAddress ->
            coroutineScopeFacade.applicationScope.launch {
                val success = gattServer.notifyCharacteristic(deviceAddress, packetData)
                if (!success) {
                    logDebug("BROADCAST_WRITE", "Server notify failed: ${deviceAddress.take(8)}")
                }
            }
        }
    }

    override fun hasRequiredPermissions(): Boolean {
        return true
    }

    fun setOnPacketReceivedCallback(callback: (ByteArray, String) -> Unit) {
        this.onPacketReceivedCallback = callback
    }

    override fun setConnectionEstablishedCallback(callback: ConnectionEstablishedCallback) {
        this.connectionEstablishedCallback = callback
    }

    override fun setConnectionReadyCallback(callback: ConnectionReadyCallback) {
        this.readyCallback = callback
        gattClient.setReadyCallback(callback)
    }

    suspend fun onDeviceDiscovered(peripheral: CBPeripheral, deviceName: String? = null, rssi: Int = -50) {
        val deviceAddress = peripheral.identifier.UUIDString
        deviceMutex.withLock {
            val state = connectionStates[deviceAddress] ?: ConnectionState.DISCONNECTED
            if (state == ConnectionState.CONNECTING || state == ConnectionState.CONNECTED) {
                return
            }

            val now = Clock.System.now().toEpochMilliseconds()
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
            logInfo("CONNECTION", "Connecting to ${deviceAddress.take(8)} (total: ${discoveredDevices.size})")
        }

        gattClient.registerDiscoveredPeripheral(peripheral)
        connectToDevice(deviceAddress)
    }

    suspend fun onDeviceConnected(deviceAddress: String) {
        deviceMutex.withLock {
            connectionStates[deviceAddress] = ConnectionState.CONNECTED
            connectionAttemptCount[deviceAddress] = 0
        }

        logInfo("CONNECTION", "Connected: ${deviceAddress.take(8)}")
        connectionEstablishedCallback?.onDeviceConnected(deviceAddress)
    }

    suspend fun onDeviceConnectionFailed(deviceAddress: String, reason: String) {
        deviceMutex.withLock {
            connectionStates[deviceAddress] = ConnectionState.DISCONNECTED
        }
    }

    suspend fun start() {
        gattServer.startAdvertising()
    }

    suspend fun stop() {
        gattServer.stopAdvertising()
        clearConnections()
    }

    private fun setupDelegates() {
        gattServer.setDelegate(object : GattServerDelegate {
            override fun onDataReceived(data: ByteArray, deviceAddress: String) {
                onPacketReceivedCallback?.invoke(data, deviceAddress)
            }

            override fun onClientConnected(deviceAddress: String) {
                coroutineScopeFacade.applicationScope.launch {
                    deviceMutex.withLock {
                        serverConnectedDevices.add(deviceAddress)
                        logDebug("CONNECTION", "Server client connected: ${deviceAddress.take(8)}")
                    }
                }
            }

            override fun onClientDisconnected(deviceAddress: String) {
                coroutineScopeFacade.applicationScope.launch {
                    deviceMutex.withLock {
                        serverConnectedDevices.remove(deviceAddress)
                    }
                }
            }
        })

        gattClient.setDelegate(object : GattClientDelegate {
            override fun onCharacteristicRead(deviceAddress: String, data: ByteArray) {
                onPacketReceivedCallback?.invoke(data, deviceAddress)
            }

            override fun onWriteSuccess(deviceAddress: String) {
                // No-op
            }

            override fun onWriteFailure(deviceAddress: String, error: String) {
                // No-op
            }
        })

        gattClient.setConnectionDelegate(object : IosGattClientConnectionDelegate {
            override fun onConnectionSuccess(deviceAddress: String) {
                coroutineScopeFacade.applicationScope.launch {
                    onDeviceConnected(deviceAddress)
                }
            }

            override fun onConnectionFailure(deviceAddress: String, reason: String) {
                coroutineScopeFacade.applicationScope.launch {
                    onDeviceConnectionFailed(deviceAddress, reason)
                }
            }
        })
    }
}
