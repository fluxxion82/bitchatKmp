package com.bitchat.bluetooth.service

import com.bitchat.bluetooth.protocol.logDebug
import com.bitchat.bluetooth.protocol.logInfo
import com.bitchat.domain.base.CoroutineScopeFacade
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * BlueZ Connection Service - orchestrates BLE mesh networking on Linux.
 *
 * Combines Central role (scanning + GATT client) and Peripheral role
 * (advertising + GATT server) to enable full mesh communication.
 */
class BlueZConnectionService(
    private val coroutineScopeFacade: CoroutineScopeFacade,
    private val manager: BlueZManager,
    private val scanningService: BlueZScanningService,
    private val gattClient: BlueZGattClientService,
    private val gattServer: BlueZGattServerService,
    private val advertisingService: BlueZAdvertisingService
) : BluetoothConnectionService {

    companion object {
        private const val TAG = "BLUEZ_CONN"
    }

    enum class ConnectionState {
        DISCONNECTED,
        CONNECTING,
        CONNECTED,
        DISCONNECTING
    }

    // Client connections (we connected to them)
    private val clientConnections = mutableSetOf<String>()
    // Server connections (they connected to us)
    private val serverConnections = mutableSetOf<String>()
    private val connectionMutex = Mutex()
    private val connectionStates = mutableMapOf<String, ConnectionState>()
    private val lastConnectionAttempt = mutableMapOf<String, Long>()
    private val connectionAttemptCount = mutableMapOf<String, Int>()

    private var onPacketReceivedCallback: OnPacketReceivedCallback? = null
    private var connectionEstablishedCallback: ConnectionEstablishedCallback? = null
    private var connectionReadyCallback: ConnectionReadyCallback? = null

    init {
        setupDelegates()
    }

    override suspend fun connectToDevice(deviceAddress: String) {
        logInfo(TAG, "Connect request: $deviceAddress")
        gattClient.connect(deviceAddress)
    }

    override suspend fun confirmDevice() {
        // No-op for BLE mesh (no pairing confirmation needed)
    }

    override suspend fun isDeviceConnecting(deviceAddress: String): Boolean {
        return connectionMutex.withLock {
            connectionStates[deviceAddress] == ConnectionState.CONNECTING
        }
    }

    override suspend fun disconnectDeviceByAddress(deviceAddress: String) {
        logInfo(TAG, "Disconnect request: $deviceAddress")
        gattClient.disconnect(deviceAddress)
        connectionMutex.withLock {
            clientConnections.remove(deviceAddress)
            connectionStates.remove(deviceAddress)
        }
    }

    override suspend fun clearConnections() {
        logInfo(TAG, "Clearing all connections")
        gattClient.disconnectAll()
        connectionMutex.withLock {
            clientConnections.clear()
            serverConnections.clear()
            connectionStates.clear()
        }
    }

    override suspend fun broadcastPacket(packetData: ByteArray) {
        val readyClients = gattClient.getReadyDeviceAddresses()
        val (clients, servers) = connectionMutex.withLock {
            Pair(clientConnections.toList(), serverConnections.toList())
        }

        val totalDevices = readyClients.size + servers.size
        logInfo(TAG, "Broadcasting ${packetData.size}B to $totalDevices devices " +
                "(clients:${readyClients.size}, servers:${servers.size})")

        if (totalDevices == 0) {
            logDebug(TAG, "No devices to broadcast to")
            return
        }

        // Write to client connections (we are Central)
        readyClients.forEach { deviceAddress ->
            coroutineScopeFacade.applicationScope.launch {
                val success = gattClient.writeCharacteristic(deviceAddress, packetData)
                if (!success) {
                    logDebug(TAG, "Client write failed: ${deviceAddress.take(8)}")
                }
            }
        }

        // Notify server connections (we are Peripheral)
        servers.forEach { deviceAddress ->
            coroutineScopeFacade.applicationScope.launch {
                val success = gattServer.notifyCharacteristic(deviceAddress, packetData)
                if (!success) {
                    logDebug(TAG, "Server notify failed: ${deviceAddress.take(8)}")
                }
            }
        }
    }

    override fun hasRequiredPermissions(): Boolean {
        // Linux doesn't have runtime permissions like Android/iOS
        // Bluetooth access requires either root or bluetooth group membership
        return true
    }

    override fun setConnectionEstablishedCallback(callback: ConnectionEstablishedCallback) {
        this.connectionEstablishedCallback = callback
    }

    override fun setConnectionReadyCallback(callback: ConnectionReadyCallback) {
        this.connectionReadyCallback = callback
    }

    /**
     * Set callback for when packets are received from any connected device.
     */
    override fun setOnPacketReceivedCallback(callback: OnPacketReceivedCallback) {
        this.onPacketReceivedCallback = callback
    }

    /**
     * Start the BLE mesh service (scanning + advertising).
     */
    suspend fun start() {
        logInfo(TAG, "Starting BLE mesh service...")

        // Open adapter
        if (!manager.openAdapter()) {
            logDebug(TAG, "Failed to open adapter")
            return
        }

        // Start GLib main loop thread - REQUIRED for GattLib callbacks to fire
        manager.startMainLoop()

        // Start advertising (Peripheral role)
        advertisingService.startAdvertising(BlueZManager.SERVICE_UUID, "bitchat")

        // Start GATT server
        gattServer.startAdvertising()

        // Start scanning for other devices (Central role)
        scanningService.startScan(lowLatency = true)

        logInfo(TAG, "BLE mesh service started")
    }

    /**
     * Stop the BLE mesh service.
     */
    suspend fun stop() {
        logInfo(TAG, "Stopping BLE mesh service...")

        scanningService.stopScan()
        gattServer.stopAdvertising()
        advertisingService.stopAdvertising()
        clearConnections()
        manager.stopMainLoop()
        manager.closeAdapter()

        logInfo(TAG, "BLE mesh service stopped")
    }

    /**
     * Called when scanning discovers a device.
     */
    suspend fun onDeviceDiscovered(deviceAddress: String, deviceName: String?) {
        connectionMutex.withLock {
            // Skip if already connected as a client
            val state = connectionStates[deviceAddress] ?: ConnectionState.DISCONNECTED
            if (state == ConnectionState.CONNECTING || state == ConnectionState.CONNECTED) {
                return
            }

            // Skip if device is already connected to us as a GATT server client
            // (BLE typically only supports one connection between two devices)
            if (serverConnections.contains(deviceAddress)) {
                logDebug(TAG, "Skipping client connect to ${deviceAddress.take(8)} - already connected as server client")
                return
            }

            // Exponential backoff for connection attempts
            val now = currentTimeMillis()
            val lastAttempt = lastConnectionAttempt[deviceAddress] ?: 0L
            val attemptCount = connectionAttemptCount[deviceAddress] ?: 0
            val backoffMs = minOf(5000L * (1 shl attemptCount), 60000L)

            if (now - lastAttempt < backoffMs) {
                return // Too soon for retry
            }

            clientConnections.add(deviceAddress)
            connectionStates[deviceAddress] = ConnectionState.CONNECTING
            lastConnectionAttempt[deviceAddress] = now
            connectionAttemptCount[deviceAddress] = attemptCount + 1

            logInfo(TAG, "Connecting to discovered device: ${deviceAddress.take(8)} " +
                    "(attempt ${attemptCount + 1}, total: ${clientConnections.size})")
        }

        connectToDevice(deviceAddress)
    }

    /**
     * Called when client connection is established.
     */
    internal suspend fun onClientConnected(deviceAddress: String) {
        connectionMutex.withLock {
            connectionStates[deviceAddress] = ConnectionState.CONNECTED
            connectionAttemptCount[deviceAddress] = 0
        }

        logInfo(TAG, "Client connected: ${deviceAddress.take(8)}")
        connectionEstablishedCallback?.onDeviceConnected(deviceAddress)
        connectionReadyCallback?.onConnectionReady(deviceAddress)
    }

    /**
     * Called when client connection fails.
     */
    internal suspend fun onClientConnectionFailed(deviceAddress: String, reason: String) {
        connectionMutex.withLock {
            connectionStates[deviceAddress] = ConnectionState.DISCONNECTED
        }
        logDebug(TAG, "Client connection failed: ${deviceAddress.take(8)}: $reason")
    }

    /**
     * Called when client disconnects.
     */
    internal suspend fun onClientDisconnected(deviceAddress: String) {
        connectionMutex.withLock {
            clientConnections.remove(deviceAddress)
            connectionStates.remove(deviceAddress)
        }
        logInfo(TAG, "Client disconnected: ${deviceAddress.take(8)}")
    }

    /**
     * Called when a server client connects (Peripheral role).
     */
    internal suspend fun onServerClientConnected(deviceAddress: String) {
        connectionMutex.withLock {
            serverConnections.add(deviceAddress)
        }
        logInfo(TAG, "Server client connected: ${deviceAddress.take(8)}")
        connectionEstablishedCallback?.onDeviceConnected(deviceAddress)
    }

    /**
     * Called when a server client disconnects.
     */
    internal suspend fun onServerClientDisconnected(deviceAddress: String) {
        connectionMutex.withLock {
            serverConnections.remove(deviceAddress)
        }
        logInfo(TAG, "Server client disconnected: ${deviceAddress.take(8)}")
    }

    /**
     * Get total number of connected devices (clients + servers).
     */
    suspend fun getConnectedDeviceCount(): Int {
        return connectionMutex.withLock {
            gattClient.getReadyDeviceAddresses().size + serverConnections.size
        }
    }

    private fun setupDelegates() {
        // Setup scanning callback
        scanningService.setOnDeviceDiscoveredCallback { address, name ->
            coroutineScopeFacade.applicationScope.launch {
                onDeviceDiscovered(address, name)
            }
        }

        // Setup GATT server delegate
        gattServer.setDelegate(object : GattServerDelegate {
            override fun onDataReceived(data: ByteArray, deviceAddress: String) {
                onPacketReceivedCallback?.onPacketReceived(data, deviceAddress)
            }

            override fun onClientConnected(deviceAddress: String) {
                coroutineScopeFacade.applicationScope.launch {
                    onServerClientConnected(deviceAddress)
                }
            }

            override fun onClientDisconnected(deviceAddress: String) {
                coroutineScopeFacade.applicationScope.launch {
                    onServerClientDisconnected(deviceAddress)
                }
            }
        })

        // Setup GATT client delegate
        gattClient.setDelegate(object : GattClientDelegate {
            override fun onCharacteristicRead(deviceAddress: String, data: ByteArray) {
                onPacketReceivedCallback?.onPacketReceived(data, deviceAddress)
            }

            override fun onWriteSuccess(deviceAddress: String) {
                // No-op - writes are fire-and-forget in mesh mode
            }

            override fun onWriteFailure(deviceAddress: String, error: String) {
                logDebug(TAG, "Write failure to ${deviceAddress.take(8)}: $error")
            }
        })
    }
}

/**
 * Get current time in milliseconds.
 */
@OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
private fun currentTimeMillis(): Long {
    // Using POSIX time() returns seconds since epoch, multiply by 1000 for milliseconds
    return platform.posix.time(null) * 1000L
}
