package com.bitchat.bluetooth.service

import com.bitchat.bluetooth.manager.CentralLinkPolicy
import com.bitchat.bluetooth.manager.ConnectCall
import com.bitchat.bluetooth.manager.ConnectCallLedger
import com.bitchat.bluetooth.manager.DialBudget
import com.bitchat.bluetooth.manager.HeardAdvertisers
import com.bitchat.bluetooth.manager.OrphanedLinks
import com.bitchat.bluetooth.manager.bitchatLinks
import com.bitchat.bluetooth.manager.connectCallsText
import com.bitchat.bluetooth.manager.dialTimeLeftText
import com.bitchat.bluetooth.protocol.logDebug
import com.bitchat.bluetooth.protocol.logError
import com.bitchat.bluetooth.protocol.logInfo
import com.bitchat.domain.base.CoroutineScopeFacade
import platform.posix.getenv
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.concurrent.AtomicInt
import kotlin.time.TimeSource

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
 *
 * Who is dialled: only an address BlueZ has heard advertise within the last minute, see
 * [HeardAdvertisers]. A device object in BlueZ is not enough, and neither is a link BlueZ reports:
 * both exist for the address a phone connected in from, which nobody advertises, and a dial to it
 * can only time out while this controller neither advertises nor scans.
 *
 * How much is dialled: no more than [DialBudget] allows in all. A dial switches this controller's
 * advertising and scanning off for its length, and no wait imposed on one address bounds what many
 * addresses cost together.
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

        // The link status line is written once in this many reconciliation passes: once a minute.
        private const val STATUS_EVERY_PASSES = 12

        // Not 75: both units refuse to restart on it. _exit, not exit: atexit handlers must not run
        // while gattlib's and GLib's threads do; logError has flushed stdout.
        private const val BUS_REFUSED_EXIT_STATUS = 69
    }

    // Server connections (they connected to us)
    private val serverConnections = mutableSetOf<String>()
    private val connectionMutex = Mutex()
    private val connectCalls = ConnectCallLedger()

    // Permission to open an outbound link, and the deadline gattlib does not enforce on one.
    private val linkPolicy = CentralLinkPolicy(
        immediateDropRetryEnabled = true,
        maxBackoffMs = CentralLinkPolicy.EMBEDDED_MAX_BACKOFF_MS,
        dialBudget = DialBudget()
    )

    // Links BlueZ holds that neither role here uses; see [dropOrphanedLinks].
    private val orphans = OrphanedLinks()

    // The clock for everything timed here: the policy, the dial budget, the age of a link, when an
    // advertiser was last heard. A board has no battery clock and its time of day jumps forward by
    // hours when NTP lands; a jump while a dial is in flight would be charged as an hour of dialling.
    private val started = TimeSource.Monotonic.markNow()

    // After a bus session that failed, no other is tried before this (on the clock above). Only
    // the reconciliation loop reads and writes it.
    private var busRestsUntil = 0L

    private fun monotonicMs(): Long = started.elapsedNow().inWholeMilliseconds

    // The dial candidates: the addresses BlueZ has heard advertise within the last minute. Written
    // on the D-Bus dispatch thread, in the order BlueZ sent its reports, and read by the sweep.
    private val heard = HeardAdvertisers()

    // BlueZ's signal strength reports since the last link status line, for that line. Without them
    // this node dials nobody: they come over the GATT server's match on the shared system bus
    // connection, which the advertising service's dispatcher pumps as well as the server's own, so
    // it takes that rule failing to install, or neither dispatcher running, for none to arrive.
    private val reportsHeard = AtomicInt(0)

    // Addresses of peers this node is already talking to, keyed by peer ID rather than by MAC.
    // Android rotates its advertising address, so the same phone is offered by the scanner under a
    // new MAC every minute or so; the mesh service publishes the mapping here as it learns it.
    private var peerAddressLookup: (String) -> Set<String> = { emptySet() }

    private var onPacketReceivedCallback: OnPacketReceivedCallback? = null
    private var connectionEstablishedCallback: ConnectionEstablishedCallback? = null
    private var connectionReadyCallback: ConnectionReadyCallback? = null

    private var reaperJob: Job? = null
    private var reconciliationJob: Job? = null

    init {
        setupDelegates()
        // Started here rather than in [start], which nothing calls: the mesh service drives
        // scanning, advertising and the GATT server itself and never touches this class's own
        // lifecycle. Without the reaper a connection attempt gattlib goes quiet about would sit
        // pending for ever, and with it the paused scan.
        startAttemptReaper()
        startLinkReconciliation()
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
            linkPolicy.onReleased(deviceAddress, monotonicMs())
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
        reconciliationJob?.cancel()
        reconciliationJob = null

        scanningService.stopScan()
        gattServer.stopAdvertising()
        advertisingService.stopAdvertising()
        clearConnections()
        manager.stopMainLoop()
        manager.closeAdapter()

        logInfo(TAG, "BLE mesh service stopped")
    }

    /**
     * An address is offered for dialling: by gattlib, which names every new device object BlueZ
     * makes, once, or by the sweep.
     *
     * Connecting is not the default answer. First the address must be one BlueZ has heard advertise
     * lately: gattlib also names the object BlueZ makes for a central that connected in, and that
     * address nobody advertises. Then the policy decides: one attempt at a time, a backoff after a
     * failure, no second link to a peer that is already linked.
     */
    suspend fun onDeviceDiscovered(deviceAddress: String, deviceName: String?) {
        // Setting BITCHAT_BLE_NO_DIAL runs this node peripheral-only: it keeps advertising and
        // keeps serving centrals that dial us, but never dials out itself. It is the mirror of
        // BITCHAT_BLE_NO_ADVERTISE in BlueZAdvertisingService, and exists so the desktop's central
        // role can be exercised against a peer that will not race it by dialling first.
        if (dialSuppressed()) {
            logDebug(TAG, "BITCHAT_BLE_NO_DIAL set: not dialling ${deviceAddress.take(8)}")
            return
        }

        if (!heard.isHeard(deviceAddress, monotonicMs())) {
            logDebug(TAG, "Not connecting to ${deviceAddress.take(8)}: BlueZ has not heard it advertise in the last " +
                    "${HeardAdvertisers.HEARD_WINDOW_MS / 1000} s")
            return
        }

        val decision = connectionMutex.withLock {
            linkPolicy.onDiscovered(
                address = deviceAddress,
                now = monotonicMs(),
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
     *
     * UNKNOWN_DEVICE takes the address out of the dial candidates: BlueZ dropped the device, so
     * every further dial would be refused the same way. Its next signal strength report, which
     * says BlueZ has it again, puts it back.
     *
     * A Connect gattlib timed out on is released if nothing here uses its address. Whether the bus
     * refuses this app for good is asked by the reconciliation loop alone, see [exitIfBluezAnswers].
     */
    private suspend fun handleConnectOutcome(
        deviceAddress: String,
        attempt: BlueZGattClientService.ConnectAttempt
    ) {
        connectionMutex.withLock { connectCalls.onDial(attempt.call, monotonicMs()) }
        when (attempt.outcome) {
            BlueZGattClientService.ConnectOutcome.STARTED -> Unit

            BlueZGattClientService.ConnectOutcome.BUSY -> {
                logInfo(TAG, "gattlib still owns the previous attempt to ${deviceAddress.take(8)}; " +
                        "freeing the slot and backing off")
                connectionMutex.withLock { linkPolicy.onNativeBusy(deviceAddress, monotonicMs()) }
            }

            BlueZGattClientService.ConnectOutcome.REFUSED ->
                onClientConnectionFailed(deviceAddress, "gattlib refused the connection")

            BlueZGattClientService.ConnectOutcome.UNKNOWN_DEVICE -> {
                connectionMutex.withLock { linkPolicy.onReleased(deviceAddress, monotonicMs()) }
                heard.onRemoved(deviceAddress)
                logInfo(TAG, "BlueZ no longer has ${deviceAddress.take(8)}; not offering it again " +
                        "until BlueZ hears it advertise again")
            }
        }
        if (attempt.call == ConnectCall.TIMED_OUT) releaseIfUnused(deviceAddress, attempt.dialStarted)
    }

    private suspend fun releaseIfUnused(
        address: String,
        dialStarted: TimeSource.Monotonic.ValueTimeMark
    ) {
        val unused = connectionMutex.withLock {
            !linkPolicy.isPending(address) &&
                !gattClient.holdsConnection(address) &&
                !gattServer.isClientConnected(address)
        }
        if (unused) {
            BlueZDeviceBus.release(address, dialStarted)
        } else {
            logInfo(TAG, "${address.take(8)} is in use and is not released")
        }
    }

    /**
     * End the app, so that systemd restarts it, if bluetoothd answers a fresh connection while the bus
     * refuses this app's own. Called only from the reconciliation loop: a second caller's session would
     * meet the first one's at the session gate and be read as a silent bluetoothd.
     */
    private suspend fun exitIfBluezAnswers() {
        val answered = BlueZDeviceBus.session { it.ping() } == true
        if (answered) {
            // Decided again under the lock: a dial bluetoothd answered during the ping ends the run.
            connectionMutex.withLock {
                if (!connectCalls.exitDue(monotonicMs())) return
                logError(
                    TAG,
                    "The system bus has refused this app's Bluetooth calls for at least " +
                        "${ConnectCallLedger.EXIT_AFTER_MS / 1000} s (${connectCalls.busRefused} refusals since the " +
                        "start) while bluetoothd answers a fresh connection: the 128 pending calls the bus allows this " +
                        "app's connection are calls BlueZ never answered. Exiting so that systemd restarts the app"
                )
                platform.posix._exit(BUS_REFUSED_EXIT_STATUS)
            }
        } else {
            connectionMutex.withLock { connectCalls.onBluezSilent(monotonicMs()) }
            logInfo(
                TAG,
                "The system bus refuses this app's Bluetooth calls, but bluetoothd did not answer a ping on a " +
                    "fresh connection either (or another bus session was running); not restarting, asking again " +
                    "in ${ConnectCallLedger.SILENT_BLUEZ_REST_MS / 1000} s"
            )
        }
    }

    /**
     * Called when client connection is established.
     */
    internal suspend fun onClientConnected(deviceAddress: String) {
        // The ready and the loss of a link are handled by separately launched coroutines, so the
        // loss can be handled first; the entry made here would then stay for ever and count against
        // the link budget (measured: a board that dialled nobody for hours). The client removes its
        // registry entry before it reports a loss, so either the registry still holds this address
        // here, and the loss, reported later, releases it later; or it does not, and the entry goes
        // again in this same locked step.
        val released = connectionMutex.withLock {
            val now = monotonicMs()
            linkPolicy.onConnected(deviceAddress, now)
            linkPolicy.releaseUnheld(gattClient.heldAddresses(), now)
        }
        released.filter { it != deviceAddress }.forEach { address ->
            logInfo(TAG, "Released the link to ${address.take(8)}: the GATT client no longer holds it")
        }
        if (deviceAddress in released) {
            logInfo(TAG, "The link to ${deviceAddress.take(8)} was gone before it was reported ready; not counting it")
            return
        }

        logInfo(TAG, "Client connected: ${deviceAddress.take(8)}")
        connectionEstablishedCallback?.onDeviceConnected(deviceAddress)
        connectionReadyCallback?.onConnectionReady(deviceAddress)
    }

    /**
     * Called when client connection fails.
     */
    internal suspend fun onClientConnectionFailed(deviceAddress: String, reason: String) {
        connectionMutex.withLock { linkPolicy.onReleased(deviceAddress, monotonicMs()) }
        logDebug(TAG, "Client connection failed: ${deviceAddress.take(8)}: $reason")
    }

    /**
     * Drop an outbound link BlueZ has already torn down.
     *
     * Only acts when the central side still holds the address, so this stays a no-op for the
     * device-gone signals that name a peer we never dialled -- which is most of them while
     * scanning. Routing through [BlueZGattClientService.onDisconnected] keeps one teardown path:
     * it clears the registry entry, marks the connection dead so no later gattlib call walks freed
     * memory, and fires onConnectionLost, which releases the policy slot.
     */
    internal fun reapOutboundLink(deviceAddress: String) {
        if (!gattClient.holdsConnection(deviceAddress)) return
        logInfo(TAG, "BlueZ dropped ${deviceAddress.take(8)}; reaping the outbound link")
        gattClient.onDisconnected(deviceAddress, "BlueZ reported the device gone")
    }

    internal suspend fun onClientDisconnected(deviceAddress: String) {
        connectionMutex.withLock { linkPolicy.onReleased(deviceAddress, monotonicMs()) }
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
            // The time is read again: the disconnect above can take seconds inside gattlib, and
            // until this release the attempt is in flight and is charged as such.
            connectionMutex.withLock { linkPolicy.onReleased(address, monotonicMs()) }
        }
    }

    /**
     * Offer every address BlueZ hears advertise that this node has no link to.
     *
     * gattlib names a device once, when its object appears, so a peer whose dial failed gets
     * another try only from here. Every candidate goes through [onDeviceDiscovered], so the policy
     * decides as it does for gattlib's offer: backoff, the attempt in flight and the inbound-link
     * rules all apply.
     */
    internal suspend fun reofferHeardAdvertisers() {
        val held = gattClient.heldAddresses() + connectionMutex.withLock { serverConnections.toSet() }
        heard.recent(monotonicMs()).filter { it !in held }.forEach { address ->
            onDeviceDiscovered(address, null)
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
                reapExpiredAttempts(monotonicMs())
                reofferHeardAdvertisers()
            }
        }
    }

    /**
     * Every sweep interval: let go of policy entries the GATT client no longer holds, then drop
     * links BlueZ holds that nothing here uses.
     *
     * A loop of its own, not part of the reaper's: that one offers the dial candidates, and a dial
     * blocks inside gattlib for up to 25 s, which would stretch these two checks to half a minute.
     */
    private fun startLinkReconciliation() {
        if (reconciliationJob?.isActive == true) return
        reconciliationJob = coroutineScopeFacade.applicationScope.launch {
            var passes = 0
            while (isActive) {
                delay(CentralLinkPolicy.SWEEP_INTERVAL_MS)
                try {
                    if (connectionMutex.withLock { connectCalls.exitDue(monotonicMs()) }) {
                        exitIfBluezAnswers()
                    }
                    val released = connectionMutex.withLock {
                        linkPolicy.releaseUnheld(gattClient.heldAddresses(), monotonicMs())
                    }
                    released.forEach { address ->
                        logInfo(TAG, "Released the link to ${address.take(8)}: the GATT client no longer holds it")
                    }
                    dropOrphanedLinks()
                    if (++passes % STATUS_EVERY_PASSES == 0) logLinkStatus()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logError(TAG, "Link reconciliation failed: ${e.message}")
                }
            }
        }
    }

    /**
     * One line a minute with every record of a link this service keeps, side by side.
     *
     * The faults found on the boards were all two of these records disagreeing for hours (BlueZ
     * holding a link the app did not use, the policy counting one the client had dropped, no
     * advertisement while everything else looked normal), and each took a comparison by hand of
     * `bluetoothctl` against scattered journal lines to see. This is that comparison, kept.
     */
    private suspend fun logLinkStatus() {
        val held = gattClient.heldAddresses()
        val ready = gattClient.getReadyDeviceAddresses().toSet()
        val (policy, calls) = connectionMutex.withLock {
            Triple(
                linkPolicy.pendingCount(),
                linkPolicy.establishedCount(),
                linkPolicy.dialTimeLeftMs(monotonicMs())
            ) to (connectCalls.timedOut to connectCalls.busRefused)
        }
        val (attempts, counted, dialTimeLeft) = policy
        val (timedOut, busRefused) = calls
        val heardCount = heard.recent(monotonicMs()).size
        val reports = reportsHeard.getAndSet(0)
        fun Collection<String>.short() = sorted().joinToString(",") { it.take(8) }.ifEmpty { "-" }
        logInfo(TAG, "Link status: advertising=${if (advertisingService.isAdvertising()) "yes" else "NO"} " +
                "client=[${ready.short()}] client-not-ready=[${(held - ready).short()}] " +
                "server=[${gattServer.getConnectedClients().short()}] bluez-links=[${orphans.tracked().short()}] " +
                "attempts=$attempts policy-links=$counted heard=$heardCount reports=$reports" +
                (dialTimeLeft?.let { " dial-budget=${dialTimeLeftText(it)}" } ?: "") +
                connectCallsText(timedOut, BlueZDeviceBus.releasesWritten, busRefused))
    }

    /**
     * Ask BlueZ to disconnect the links it has held for the whole connect deadline while neither
     * role here uses them: not in the GATT client's registry, and no central that has written to
     * our server.
     *
     * Such a link is what a dial leaves behind when it completes after gattlib's D-Bus timeout
     * (gattlib cannot join it afterwards, and answers BUSY for that address from then on). Nothing
     * else ends it: both controllers are alive, so it never times out. After the disconnect BlueZ
     * reports the link gone, gattlib lets go of the address, and the next dial starts clean; that
     * the outcome arrives that way is why no answer is waited for here.
     *
     * What to disconnect is decided only once the bus is open and BlueZ has listed its devices,
     * because both can take seconds: a link found due before that could have gone and been replaced
     * by a new one meanwhile. And only a link to a device that shows the bitchat service is
     * disconnected. Any other is somebody else's (a device another program connected) and is no
     * longer judged.
     */
    private suspend fun dropOrphanedLinks() {
        val inUse: (String) -> Boolean = { address ->
            gattClient.holdsConnection(address) || gattServer.isClientConnected(address)
        }
        // The clock is read before use is asked about: see OrphanedLinks.due.
        val now = monotonicMs()
        if (now < busRestsUntil || !orphans.anyDue(now, inUse)) return

        val asked = BlueZDeviceBus.session { bus ->
            val devices = bus.devices() ?: return@session false
            val ours = bitchatLinks(devices, BlueZManager.SERVICE_UUID).toSet()
            orphans.due(monotonicMs(), inUse).forEach { orphan ->
                val address = orphan.address
                if (address !in ours) {
                    logInfo(TAG, "BlueZ holds ${address.take(8)}, which nothing here uses and which shows " +
                            "no bitchat service; leaving it alone")
                    orphans.leaveAlone(orphan)
                } else if (orphans.isSameLink(orphan)) {
                    logInfo(TAG, "BlueZ has held a link to ${address.take(8)} for " +
                            "${CentralLinkPolicy.CONNECT_TIMEOUT_MS}ms that nothing here uses; asking it to disconnect")
                    bus.disconnect(address)
                }
            }
            true
        }
        if (asked != true) {
            // Not again on every pass: a bus that does not answer costs each try its full limit.
            busRestsUntil = monotonicMs() + CentralLinkPolicy.CONNECT_TIMEOUT_MS
            logInfo(TAG, "Could not ask BlueZ about the links nothing here uses; trying again in " +
                    "${CentralLinkPolicy.CONNECT_TIMEOUT_MS}ms")
        }
    }

    /**
     * Drop the links bluetoothd kept from a previous process of this app.
     *
     * bluetoothd keeps an LE link when the app exits and tells the next process nothing about it:
     * measured after a restart, `bluetoothctl` showed the other board connected while this app
     * broadcast to no devices and the other board kept "sending" to it. Nothing of such a link is
     * usable, since its GATT application and the subscriptions on it went with the old process.
     *
     * Only safe while nothing can have connected to this process yet, which is why the mesh
     * service waits for this before it advertises. A device that shows no bitchat service is left
     * alone: nothing says it is ours.
     */
    override suspend fun prepareForStart() {
        val listed = BlueZDeviceBus.session { bus ->
            val devices = bus.devices() ?: return@session false
            bitchatLinks(devices, BlueZManager.SERVICE_UUID).forEach { address ->
                logInfo(TAG, "Dropping the link to ${address.take(8)} left over from before this start")
                bus.disconnect(address)
            }
            true
        }
        if (listed != true) {
            logInfo(TAG, "Could not list BlueZ's devices; a link left over from before this start, if there is one, stays")
        }
    }

    /** A received mesh frame proves this link was useful, regardless of its connection age. */
    private fun onMeshFrameExchanged(deviceAddress: String) {
        coroutineScopeFacade.applicationScope.launch {
            connectionMutex.withLock { linkPolicy.onMeshFrameExchanged(deviceAddress) }
        }
    }

    private fun setupDelegates() {
        // gattlib names a new device object. That says nothing about whether the device
        // advertises; BlueZ's own report decides, in [onDeviceDiscovered].
        scanningService.setOnDeviceDiscoveredCallback { address, name ->
            coroutineScopeFacade.applicationScope.launch {
                onDeviceDiscovered(address, name)
            }
        }

        // Setup GATT server delegate
        gattServer.setDelegate(object : GattServerDelegate {
            override fun onDataReceived(data: ByteArray, deviceAddress: String) {
                onMeshFrameExchanged(deviceAddress)
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
        // The orphan tracker and the record of advertisers are updated on the dispatch thread
        // itself, so what BlueZ reports is applied in the order it was sent. A launched coroutine
        // would not keep that order.
        //
        // A link BlueZ reports is recorded and nothing more. It used to be offered for dialling
        // ("BlueZ already holds X; offering it to the mesh"), which is how the address a phone
        // connected in from came to be dialled. A link from before this start is dropped in
        // [prepareForStart], and one nothing here uses in [dropOrphanedLinks].
        gattServer.onDeviceConnected = { address ->
            orphans.onLinkUp(address, monotonicMs())
        }
        gattServer.onDeviceHeard = { address ->
            heard.onHeard(address, monotonicMs())
            reportsHeard.incrementAndGet()
        }
        gattServer.onDeviceRemoved = { address -> heard.onRemoved(address) }
        gattServer.onDeviceLinkGone = { address ->
            orphans.onLinkGone(address)
            coroutineScopeFacade.applicationScope.launch { reapOutboundLink(address) }
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
                onMeshFrameExchanged(deviceAddress)
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

/** True when BITCHAT_BLE_NO_DIAL is set, i.e. this node is running peripheral-only. */
@OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
private fun dialSuppressed(): Boolean = getenv("BITCHAT_BLE_NO_DIAL") != null
