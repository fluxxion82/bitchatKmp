package com.bitchat.bluetooth.service

import com.bitchat.bluetooth.manager.CentralLinkPolicy
import com.bitchat.bluetooth.protocol.logDebug
import com.bitchat.bluetooth.protocol.logInfo
import com.bitchat.domain.base.CoroutineScopeFacade
import platform.posix.getenv
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
 * The central role is deliberately unhurried. A BLE controller has one initiator, so two overlapping
 * connection attempts cannot both proceed. This service therefore takes [CentralLinkPolicy]'s
 * permission before every outbound connection and abandons an attempt gattlib has stopped reporting
 * on. See [onDeviceDiscovered] and [reapExpiredAttempts].
 *
 * Do not read `org.bluez.Error.Failed: le-connection-abort-by-local` as evidence of that contention,
 * whatever earlier comments here claimed. BlueZ's `att_connect_cb` turns nearly any ATT connect error
 * into `-ECONNABORTED`, which prints as that string, so it names no cause at all.
 *
 * The scan keeps running across a connection attempt. The experiment once cited here -- that stopping
 * it stopped links coming up -- does not support the conclusion drawn from it, because
 * [BlueZScanningService.stopScan] also calls `stopMainLoop`, so it stopped gattlib's entire event
 * dispatch rather than just the scan. The scan stays up because nothing has shown it should not.
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

    /**
     * Every address the scanner has ever named, so the sweep can offer one again.
     *
     * BlueZ announces a device once, when it first appears. gattlib's discovery callback passes
     * that straight through, so a peer whose connect failed is never offered a second time and the
     * policy's backoff -- which assumes repeated offers -- never gets to expire. Measured on the
     * Pi: two connects failed within ten seconds of start-up and the radio then sat idle with zero
     * links while both peers were still advertising.
     */
    private val knownPeers = mutableSetOf<String>()

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
        handleConnectOutcome(deviceAddress, gattClient.connect(deviceAddress))
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
        // Setting BITCHAT_BLE_NO_DIAL runs this node peripheral-only: it keeps advertising and
        // keeps serving centrals that dial us, but never dials out itself. It is the mirror of
        // BITCHAT_BLE_NO_ADVERTISE in BlueZAdvertisingService, and exists so the desktop's central
        // role can be exercised against a peer that will not race it by dialling first.
        //
        // The address is still remembered, so clearing the variable and restarting picks up
        // everything already discovered rather than waiting for BlueZ to announce it again.
        if (dialSuppressed()) {
            connectionMutex.withLock { knownPeers.add(deviceAddress) }
            logDebug(TAG, "BITCHAT_BLE_NO_DIAL set: not dialling ${deviceAddress.take(8)}")
            return
        }

        val decision = connectionMutex.withLock {
            knownPeers.add(deviceAddress)
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
        handleConnectOutcome(deviceAddress, gattClient.connect(deviceAddress))
    }

    /**
     * Record what gattlib made of a connection request.
     *
     * A refusal means no callback is coming, so releasing the address now frees the initiator in
     * milliseconds rather than holding it for the reaper's full deadline. BUSY is different: gattlib
     * is still holding an attempt this side has already given up on, and asking again before it lets
     * go only collects another refusal. See [CentralLinkPolicy.onNativeBusy].
     */
    private suspend fun handleConnectOutcome(
        deviceAddress: String,
        outcome: BlueZGattClientService.ConnectOutcome
    ) {
        when (outcome) {
            BlueZGattClientService.ConnectOutcome.STARTED -> Unit

            BlueZGattClientService.ConnectOutcome.BUSY -> {
                logInfo(TAG, "gattlib still owns the previous attempt to ${deviceAddress.take(8)}; " +
                        "freeing the slot and backing off")
                connectionMutex.withLock { linkPolicy.onNativeBusy(deviceAddress, currentTimeMillis()) }
            }

            BlueZGattClientService.ConnectOutcome.REFUSED ->
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
    /**
     * Drop an outbound link BlueZ has already torn down.
     *
     * Only acts when the central side still holds the address, so this stays a no-op for the
     * device-gone signals that name a peer we never dialled -- which is most of them while
     * scanning. Routing through [BlueZGattClientService.onDisconnected] keeps one teardown path:
     * it clears the registry entry, marks the connection dead so no later gattlib call walks freed
     * memory, and fires onConnectionLost, which releases the policy slot.
     */
    /**
     * Offer a peer BlueZ has connected but the mesh has never seen.
     *
     * The scanner is not a complete picture: BlueZ announces a device once, when it first appears,
     * so anything it was already connected to is never offered and stays invisible. This routes
     * such a peer through the same policy decision as a fresh sighting, and does nothing when we
     * already hold the link.
     *
     * Peers that are not ours are handled where they already were: service discovery drops a device
     * whose GATT tree carries no [BlueZManager.SERVICE_UUID], so the cost of a wrong guess is one
     * abandoned dial with the policy's backoff behind it, not a retry storm.
     */
    internal suspend fun adoptConnectedPeer(deviceAddress: String) {
        if (gattClient.holdsConnection(deviceAddress)) return
        if (connectionMutex.withLock { serverConnections.contains(deviceAddress) }) return
        logInfo(TAG, "BlueZ already holds ${deviceAddress.take(8)}; offering it to the mesh")
        onDeviceDiscovered(deviceAddress, null)
    }

    internal fun reapOutboundLink(deviceAddress: String) {
        if (!gattClient.holdsConnection(deviceAddress)) return
        logInfo(TAG, "BlueZ dropped ${deviceAddress.take(8)}; reaping the outbound link")
        gattClient.onDisconnected(deviceAddress, "BlueZ reported the device gone")
    }

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
            // This frees the policy's slot, not gattlib's. [BlueZGattClientService.disconnect]
            // returns immediately when there is no registry entry, which is always the case for an
            // attempt that never connected, and gattlib refuses to disconnect a device still in
            // `CONNECTING`. So gattlib keeps the address until it lets go on its own, and the next
            // offer for it comes back BUSY -- handled in [handleConnectOutcome].
            gattClient.disconnect(address)
            connectionMutex.withLock { linkPolicy.onReleased(address, now) }
        }
    }

    /**
     * Offer peers the scanner will not mention again.
     *
     * Every candidate goes back through [onDeviceDiscovered], so the policy makes the same decision
     * it would for a fresh sighting: backoff, in-flight attempts and the inbound-link rules all
     * still apply and a peer that should be left alone is skipped. Addresses we already hold a link
     * to are filtered out first, which keeps the common case to a set difference.
     */
    internal suspend fun reofferKnownPeers() {
        val held = gattClient.heldAddresses() + serverConnections.toSet()
        val candidates = connectionMutex.withLock { knownPeers - held }
        candidates.forEach { address -> onDeviceDiscovered(address, null) }
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
                reofferKnownPeers()
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

        // A BlueZ device-gone signal reaps the outbound link too. gattlib's per-connection
        // disconnect handler is not reliable on its own: BlueZ can drop a device object without it
        // firing, and the link then stayed in the registry forever. The journal showed the effect
        // directly -- BlueZ reporting one connected device while every broadcast claimed two, so
        // half of each one went to a peer that was no longer there and still logged success.
        gattServer.onDeviceConnected = { address ->
            coroutineScopeFacade.applicationScope.launch {
                adoptConnectedPeer(address)
            }
        }
        gattServer.onDeviceLinkGone = { address ->
            coroutineScopeFacade.applicationScope.launch {
                reapOutboundLink(address)
            }
        }

        // Client connect/disconnect. This is the other way the mesh learns a client link came up or
        // went down; the two paths are idempotent, so a peer reaped by both is reported once.
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
/** True when BITCHAT_BLE_NO_DIAL is set, i.e. this node is running peripheral-only. */
@OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
private fun dialSuppressed(): Boolean = getenv("BITCHAT_BLE_NO_DIAL") != null

@OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
private fun currentTimeMillis(): Long {
    // Using POSIX time() returns seconds since epoch, multiply by 1000 for milliseconds
    return platform.posix.time(null) * 1000L
}
