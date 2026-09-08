package com.bitchat.bluetooth.service

import com.bitchat.bluetooth.manager.CentralLinkPolicy
import com.bitchat.bluetooth.protocol.logDebug
import com.bitchat.bluetooth.protocol.logInfo
import com.bitchat.domain.base.CoroutineScopeFacade
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * BlueZ Connection Service - orchestrates BLE mesh networking on Linux.
 *
 * Combines Central role (scanning + GATT client) and Peripheral role
 * (advertising + GATT server) to enable full mesh communication.
 *
 * The central role is deliberately unhurried. A BLE controller has one initiator, so two
 * overlapping connection attempts cannot both proceed: the host cancels one and BlueZ reports
 * `org.bluez.Error.Failed: le-connection-abort-by-local`. This service therefore takes
 * [CentralLinkPolicy]'s permission before every outbound connection and abandons an attempt gattlib
 * has stopped reporting on. See [onDeviceDiscovered] and [reapExpiredAttempts].
 *
 * The scan deliberately keeps running across a connection attempt. Stopping it for the attempt's
 * duration looks right on paper -- the radio would have the attempt to itself -- and it was tried
 * on the device: across twenty-five minutes and a dozen attempts not one link came up, every
 * attempt ending either in `le-connection-abort-by-local` after three seconds or in the full
 * twenty-five second D-Bus timeout, where the same build with the scan left running had been
 * connecting in 1.5 to 3.2 seconds. BlueZ appears to need its discovery session to keep a
 * scan-discovered device connectable, so the scan stays up.
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

    // Server connections (they connected to us)
    private val serverConnections = mutableSetOf<String>()
    private val connectionMutex = Mutex()

    // Permission to open an outbound link, and the deadline gattlib does not enforce on one.
    private val linkPolicy = CentralLinkPolicy()

    // Addresses of peers this node is already talking to, keyed by peer ID rather than by MAC.
    // Android rotates its advertising address, so the same phone is offered by the scanner under a
    // new MAC every minute or so; the mesh service publishes the mapping here as it learns it.
    private var peerAddressLookup: (String) -> Set<String> = { emptySet() }

    private var onPacketReceivedCallback: OnPacketReceivedCallback? = null
    private var connectionEstablishedCallback: ConnectionEstablishedCallback? = null
    private var connectionReadyCallback: ConnectionReadyCallback? = null

    private var reaperJob: Job? = null

    init {
        setupDelegates()
        // Started here rather than in [start], which nothing calls: the mesh service drives
        // scanning, advertising and the GATT server itself and never touches this class's own
        // lifecycle. Without the reaper a connection attempt gattlib goes quiet about would sit
        // pending for ever, and with it the paused scan.
        startAttemptReaper()
    }

    override fun setPeerAddressLookup(lookup: (String) -> Set<String>) {
        peerAddressLookup = lookup
    }

    override suspend fun connectToDevice(deviceAddress: String) {
        logInfo(TAG, "Connect request: $deviceAddress")
        gattClient.connect(deviceAddress)
    }

    override suspend fun confirmDevice() {
        // No-op for BLE mesh (no pairing confirmation needed)
    }

    override suspend fun isDeviceConnecting(deviceAddress: String): Boolean {
        return connectionMutex.withLock { linkPolicy.isPending(deviceAddress) }
    }

    override suspend fun disconnectDeviceByAddress(deviceAddress: String) {
        logInfo(TAG, "Disconnect request: $deviceAddress")
        gattClient.disconnect(deviceAddress)
        gattServer.onClientDisconnected(deviceAddress)
        connectionMutex.withLock {
            linkPolicy.onReleased(deviceAddress, currentTimeMillis())
            serverConnections.remove(deviceAddress)
        }
    }

    override suspend fun clearConnections() {
        logInfo(TAG, "Clearing all connections")
        gattClient.disconnectAll()
        connectionMutex.withLock {
            linkPolicy.clear()
            serverConnections.clear()
        }
    }

    override suspend fun broadcastPacket(packetData: ByteArray): Boolean {
        val readyClients = gattClient.getReadyDeviceAddresses()
        val servers = connectionMutex.withLock { serverConnections.toList() }

        // The GATT server's own registry is the authority on which server entries are live, so an
        // entry it no longer holds is dropped here rather than counted as a delivery.
        val targets = gattServer.partitionBroadcastTargets(servers)

        val totalDevices = readyClients.size + targets.live.size
        logInfo(TAG, "Broadcasting ${packetData.size}B to $totalDevices devices " +
                "(clients:${readyClients.size}, servers:${targets.live.size})")

        if (targets.stale.isNotEmpty()) {
            logInfo(TAG, "Dropping ${targets.stale.size} server entry/entries with no live " +
                    "link: ${targets.stale.joinToString { it.take(8) }}")
            targets.stale.forEach { onServerClientDisconnected(it) }
        }

        if (totalDevices == 0) {
            logDebug(TAG, "No devices to broadcast to")
            return false
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

        // Notify server connections (we are Peripheral). The GATT server's notification is a
        // PropertiesChanged signal on the characteristic's object path, which names no device, so
        // one emission reaches every subscribed central: emitting per entry sent N copies to
        // everyone.
        if (targets.live.isNotEmpty()) {
            coroutineScopeFacade.applicationScope.launch {
                if (!gattServer.notifySubscribers(packetData)) {
                    logInfo(TAG, "Server notify failed for ${targets.live.size} client(s)")
                }
            }
        }

        return true
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

        startAttemptReaper()

        logInfo(TAG, "BLE mesh service started")
    }

    /**
     * Stop the BLE mesh service.
     */
    suspend fun stop() {
        logInfo(TAG, "Stopping BLE mesh service...")

        reaperJob?.cancel()
        reaperJob = null

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
     *
     * Connecting is not the default answer. The scanner offers every bitchat advertiser it sees,
     * several times a minute, and because Android rotates its resolvable private address most of
     * those offers are the same phone under a new MAC. Answering all of them is what produced the
     * churn: overlapping attempts cancelling each other, and links that lasted seconds.
     */
    suspend fun onDeviceDiscovered(deviceAddress: String, deviceName: String?) {
        val decision = connectionMutex.withLock {
            linkPolicy.onDiscovered(
                address = deviceAddress,
                now = currentTimeMillis(),
                inboundAddresses = serverConnections.toSet(),
                peerAddresses = peerAddressLookup(deviceAddress)
            )
        }

        when (decision) {
            is CentralLinkPolicy.Decision.Skip -> {
                logDebug(TAG, "Not connecting to ${deviceAddress.take(8)}: ${decision.reason}")
                return
            }

            CentralLinkPolicy.Decision.Connect -> Unit
        }

        logInfo(TAG, "Connecting to discovered device: ${deviceAddress.take(8)} " +
                "(links: ${connectionMutex.withLock { linkPolicy.establishedCount() }})")

        logInfo(TAG, "Connect request: $deviceAddress")
        if (!gattClient.connect(deviceAddress)) {
            // gattlib refused outright, so no callback is coming for this one either. Releasing it
            // now rather than at the deadline frees the initiator in milliseconds instead of
            // holding it for the full timeout.
            onClientConnectionFailed(deviceAddress, "gattlib refused the connection")
        }
    }

    /**
     * Called when client connection is established.
     */
    internal suspend fun onClientConnected(deviceAddress: String) {
        connectionMutex.withLock { linkPolicy.onConnected(deviceAddress) }

        logInfo(TAG, "Client connected: ${deviceAddress.take(8)}")
        connectionEstablishedCallback?.onDeviceConnected(deviceAddress)
        connectionReadyCallback?.onConnectionReady(deviceAddress)
    }

    /**
     * Called when client connection fails.
     */
    internal suspend fun onClientConnectionFailed(deviceAddress: String, reason: String) {
        connectionMutex.withLock { linkPolicy.onReleased(deviceAddress, currentTimeMillis()) }
        logDebug(TAG, "Client connection failed: ${deviceAddress.take(8)}: $reason")
    }

    /**
     * Called when client disconnects.
     */
    internal suspend fun onClientDisconnected(deviceAddress: String) {
        connectionMutex.withLock { linkPolicy.onReleased(deviceAddress, currentTimeMillis()) }
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
        connectionReadyCallback?.onConnectionReady(deviceAddress)
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

    /**
     * Abandon connection attempts that have gone quiet.
     *
     * gattlib never ends one of these itself. `gattlib_connect` waits for BlueZ to report
     * `ServicesResolved` turning true and arms `_stop_connect_func` (`dbus/gattlib.c`) as a
     * timeout, but that handler only clears its own timer id: it does not fail the attempt, does
     * not call the connection callback and does not release the link. So an attempt BlueZ accepts
     * but never resolves produces no callback at all, and before this the registry simply grew --
     * the journal shows it reaching seven pending attempts while no link was up and every
     * broadcast went to zero devices.
     */
    internal suspend fun reapExpiredAttempts(now: Long) {
        val expired = connectionMutex.withLock { linkPolicy.expiredAttempts(now) }
        if (expired.isEmpty()) return

        expired.forEach { address ->
            logInfo(TAG, "Abandoning connection attempt to ${address.take(8)}: no result from " +
                    "gattlib within ${CentralLinkPolicy.CONNECT_TIMEOUT_MS}ms")
            gattClient.disconnect(address)
            connectionMutex.withLock { linkPolicy.onReleased(address, now) }
        }
    }

    private fun startAttemptReaper() {
        if (reaperJob?.isActive == true) return
        reaperJob = coroutineScopeFacade.applicationScope.launch {
            logInfo(TAG, "Central link reaper started (deadline " +
                    "${CentralLinkPolicy.CONNECT_TIMEOUT_MS}ms, sweep " +
                    "${CentralLinkPolicy.SWEEP_INTERVAL_MS}ms)")
            while (isActive) {
                delay(CentralLinkPolicy.SWEEP_INTERVAL_MS)
                reapExpiredAttempts(currentTimeMillis())
            }
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

        // Client connect/disconnect. gattlib only reports a peer going away through the
        // per-connection disconnect handler BlueZGattClientService registers, so this is the single
        // place the mesh learns that a client link came up or went down.
        gattClient.onConnectionReady = { address ->
            coroutineScopeFacade.applicationScope.launch {
                onClientConnected(address)
            }
        }
        gattClient.onConnectionLost = { address ->
            coroutineScopeFacade.applicationScope.launch {
                onClientDisconnected(address)
            }
        }

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
