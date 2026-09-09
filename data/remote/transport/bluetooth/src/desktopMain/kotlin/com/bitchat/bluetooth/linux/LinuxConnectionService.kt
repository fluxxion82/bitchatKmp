package com.bitchat.bluetooth.linux

import com.bitchat.bluetooth.manager.CentralLinkPolicy
import com.bitchat.bluetooth.manager.GattClientRegistry
import com.bitchat.bluetooth.service.BluetoothConnectionService
import com.bitchat.bluetooth.service.ConnectionEstablishedCallback
import com.bitchat.bluetooth.service.ConnectionReadyCallback
import com.bitchat.bluetooth.service.GattServerDelegate
import com.bitchat.bluetooth.service.OnPacketReceivedCallback
import com.bitchat.local.bridge.NativeLocationBridge
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/*
 * =============================================================================================
 * The link layer the mesh talks to on desktop Linux -- both roles.
 * =============================================================================================
 *
 * `BluetoothMeshService` knows nothing about BlueZ, GATT or D-Bus. It knows one object that
 * accepts packets, hands packets back, and says when a link exists. That object was
 * `DesktopConnectionService`, every method of which is a `println` and a `return false` -- and
 * critically `setOnPacketReceivedCallback` was a no-op, so on Linux there was no path at all from
 * a peer's `WriteValue` to `BluetoothMeshService.onPacketReceived`. A node could advertise, accept
 * a connection, reassemble a frame, and then drop it on the floor.
 *
 * This class is that path, in both directions. Inbound, it is a `GattServerDelegate` -- the far end
 * of the GATT server's `drainInbound` loop -- and a [LinuxGattClientService.Listener], the far end
 * of the GATT client's. Both feed the one callback the mesh installed:
 *
 *     peripheral role                              central role
 *     BlueZ -> BitchatGattCharacteristic.WriteValue    peer -> Properties.PropertiesChanged
 *           -> LinuxGattServerService.onWriteValue           -> LinuxGattClientService.onNotification
 *           -> inbound Channel                               -> inbound Channel
 *           -> LinuxGattServerService.drainInbound           -> LinuxGattClientService.drainInbound
 *                        \                                          /
 *                         -> LinuxConnectionService.onDataReceived <-
 *                         -> OnPacketReceivedCallback   (installed by BluetoothMeshService.init)
 *                         -> BluetoothMeshService.onPacketReceived
 *
 * That the two roles converge on one override is deliberate: the mesh must not have to know which
 * role a packet arrived on, and a peer we dialled and a peer that dialled us are the same peer.
 *
 * Nothing on either chain blocks and nothing on it runs `runBlocking`. The hops that matter are the
 * two channels, which get the work off dbus-java's METHODCALL and SIGNAL threads, and the mesh's
 * own `serviceScope.launch`, which gets decryption off the drain loops.
 *
 * ## The central half
 *
 * Dialling is not the default answer to a discovery offer, and that is the whole of the policy this
 * class adds. The scanner offers every bitchat advertiser it hears, repeatedly, and because Android
 * rotates its resolvable private address most of those offers are the same phone under a new MAC.
 * A controller has exactly one connection initiator; answering every offer means overlapping
 * attempts that cancel each other, which BlueZ reports as
 * `org.bluez.Error.Failed: le-connection-abort-by-local` and which the mesh sees as links that last
 * seconds. So every offer goes through [CentralLinkPolicy] -- one attempt in flight, two outbound
 * links, backoff measured from when an attempt *stopped* rather than when it started -- and the
 * reaper in [startAttemptReaper] is what ends an attempt nothing else ever will.
 */
class LinuxConnectionService(
    private val gattServer: LinuxGattServerService,
    private val gattClient: LinuxGattClientService
) : BluetoothConnectionService, GattServerDelegate, LinuxGattClientService.Listener {

    private val log = LoggerFactory.getLogger("bitchat.ble.connection")

    /**
     * Which centrals hold a link to us, as seen from *this* layer.
     *
     * A second registry alongside the GATT server's own is deliberate, not duplication. They
     * answer different questions: the server's decides whether a notification may be emitted at
     * all, and this one decides whether the mesh has already been told about a peer. Deriving the
     * second from the first would mean asking the server on every event and losing the "is this
     * new?" answer that [GattClientRegistry.onConnected] returns, which is the only thing stopping
     * a duplicate announce every time an Android peer rotates its address onto a link we already
     * hold.
     *
     * It holds **inbound** links only. Outbound ones live in [LinuxGattClientService], which is the
     * only thing that can say whether one is usable -- a link whose characteristic has not been
     * resolved is not a link a broadcast can be written to -- and duplicating that answer here would
     * be a second thing to keep in step with reality.
     */
    private val clients = GattClientRegistry()

    /**
     * Volatile, not synchronised: every one of these is written once during Koin graph
     * construction and read afterwards from the D-Bus drain loop and from the mesh's dispatcher.
     * A publish that a reader might miss is the entire failure mode, and volatile is exactly the
     * guarantee that rules it out.
     */
    @Volatile
    private var establishedCallback: ConnectionEstablishedCallback? = null

    @Volatile
    private var readyCallback: ConnectionReadyCallback? = null

    @Volatile
    private var packetCallback: OnPacketReceivedCallback? = null

    /**
     * Which other addresses stand for the peer behind an address.
     *
     * Consumed in [dial], which is the only place it can be: only the mesh sees peer IDs, so only
     * it can say that two MACs are one phone, and only this class decides whether to dial one. An
     * Android peer rotates its resolvable private address every few minutes, so without this the
     * scanner offers a phone we are already linked to under a MAC nothing else can relate to the
     * first, and the second link displaces the one that was working.
     *
     * The default returns nothing, which is the honest answer for "we know of no aliases", and
     * the contract forbids blocking here: the mesh's implementation reads a lock-free snapshot
     * because this is called while a connection decision is in flight.
     */
    @Volatile
    private var peerAddressLookup: (String) -> Set<String> = { emptySet() }

    /**
     * Where the deferred `onConnectionReady` runs. Its own scope rather than the GATT server's, so
     * that a slow mesh callback cannot stall the drain loop that is meanwhile the only path for
     * the next inbound packet.
     */
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineName("bitchat-linux-connection")
    )

    /**
     * Permission to open an outbound link, and the deadline nothing else enforces on one.
     *
     * Reused from `commonMain` rather than reimplemented: the rules it encodes -- one attempt in
     * flight, two central links, 30 s deadline, backoff from the end of the last attempt -- were
     * arrived at against a real radio and a real journal on the embedded build, and a second copy
     * of them here would be a second thing to get wrong. It is deliberately clock-free, so every
     * call passes `now`.
     */
    private val linkPolicy = CentralLinkPolicy()

    /**
     * Guards [linkPolicy], which is plain mutable bookkeeping with no synchronisation of its own.
     *
     * A coroutine [Mutex] rather than a lock because every caller is a coroutine -- a discovery
     * offer, the reaper, a disconnect -- and because the critical sections are map operations that
     * never touch the bus. No D-Bus call is ever made while it is held: an attempt runs for tens of
     * seconds, and holding the policy across one would serialise the very decisions it exists to
     * make in parallel.
     */
    private val policyLock = Mutex()

    /**
     * Addresses this node discovered through its own service-UUID discovery filter.
     *
     * The gate on [LinuxGattClientService.forgetDevice]. `Adapter1.RemoveDevice` discards a device
     * object *and its pairing data*, so it must never be aimed at anything but a device we know to
     * be a bitchat advertiser -- otherwise a run of failed connections unpairs the user's headphones
     * as a side effect of trying to fix a mesh link.
     */
    private val discoveredByUs = ConcurrentHashMap.newKeySet<String>()

    /**
     * Consecutive failed attempts per address, for that same gate.
     *
     * Cleared on success. Distinct from [CentralLinkPolicy]'s own attempt count, which is private to
     * it and drives backoff; this one only decides when a device record is worth clearing.
     */
    private val consecutiveFailures = ConcurrentHashMap<String, Int>()

    private val packetsDelivered = AtomicLong()
    private val packetsUndeliverable = AtomicLong()
    private val broadcastsDelivered = AtomicLong()
    private val broadcastsDropped = AtomicLong()
    private val offersAccepted = AtomicLong()
    private val offersSkipped = AtomicLong()
    private val attemptsReaped = AtomicLong()

    init {
        /*
         * Started here rather than from a `start()` nobody calls: the mesh drives scanning,
         * advertising and the GATT server itself and never touches this class's lifecycle. Without
         * the reaper an attempt that produced no outcome would hold the single connection initiator
         * for ever, and with it every other peer in range.
         */
        startAttemptReaper()
    }

    // -----------------------------------------------------------------------------------------
    // GattServerDelegate -- the inbound half
    // -----------------------------------------------------------------------------------------

    /**
     * A reassembled frame from [deviceAddress], on the GATT server's drain loop.
     *
     * This is the wire the whole transport turns on. It is one indirection deliberately: the mesh
     * installs its callback on the *connection* service and not on the GATT server, because it is
     * this layer, not the server, that also carries the central role's packets, and the mesh must
     * not have to know which role a packet arrived on. The same override serves
     * [LinuxGattClientService.Listener], which is why its signature is spelled identically.
     *
     * Called from `drainInbound`, which already wraps the delegate in `runCatching`, so a throwing
     * mesh cannot take the loop down. Nothing is caught here that would only be re-thrown.
     */
    override fun onDataReceived(data: ByteArray, deviceAddress: String) {
        val callback = packetCallback
        if (callback == null) {
            // Reaching here means the mesh has not been constructed yet while a peer is already
            // writing to us -- a wiring failure, not a runtime condition, and the counter is what
            // separates "no packets arrived" from "packets arrived and went nowhere".
            packetsUndeliverable.incrementAndGet()
            log.error(
                "dropping a {}B frame from {}: no packet callback is installed",
                data.size,
                deviceAddress
            )
            return
        }
        val count = packetsDelivered.incrementAndGet()
        log.debug("frame {} of {}B from {} -> mesh", count, data.size, deviceAddress)
        callback.onPacketReceived(data, deviceAddress)
    }

    /**
     * A central has been registered by the GATT server, which happens on its first write.
     *
     * Both mesh callbacks are raised from here because in the peripheral role there is no second
     * event to wait for: a peer that has written to us has already found the characteristic, and a
     * notification back to it will reach it. There is no service discovery and no MTU exchange of
     * our own to gate "ready" on the way the central role does.
     */
    override fun onClientConnected(deviceAddress: String) {
        if (!clients.onConnected(deviceAddress)) return
        log.info("central {} connected ({} link(s) held)", deviceAddress, clients.size())
        raiseLinkUp(deviceAddress)
    }

    /**
     * Tell the mesh a link exists, then -- a moment later -- that it is usable.
     *
     * Shared by both roles, because the race it works around is in the mesh and not in either role.
     */
    private fun raiseLinkUp(deviceAddress: String) {
        establishedCallback?.onDeviceConnected(deviceAddress)

        scope.launch {
            /*
             * Why the gap.
             *
             * The mesh answers `onDeviceConnected` by *queuing* the address for an announce and
             * `onConnectionReady` by consuming that queue entry, and it does both from
             * `serviceScope.launch` on `Dispatchers.Default` -- a multi-threaded dispatcher. Two
             * coroutines launched back to back on it are queued in order and then run wherever
             * there is a worker, so "ready" can genuinely overtake "connected", find nothing
             * queued, and drop the announce. The peer then never learns our nickname or keys and
             * the link sits there useless.
             *
             * Every other platform gets this gap for free and spends it on real work -- Android on
             * service discovery and MTU negotiation, hundreds of milliseconds of it. The
             * peripheral role has no such step, so the gap is made explicit rather than left to
             * luck. It costs one announce arriving a fraction of a second later.
             */
            delay(CONNECTION_READY_DELAY_MS)
            log.info("link to {} is usable; announcing", deviceAddress)
            readyCallback?.onConnectionReady(deviceAddress)
        }
    }

    /**
     * The link to [deviceAddress] is gone -- `Connected=false`, or BlueZ dropped the device object
     * because an Android peer rotated its address.
     *
     * The mesh is not told directly: `commonMain` has no disconnect callback on this interface, and
     * it reaps peers on its own timer. What matters here is that the registry shrinks, so that the
     * next [broadcastPacket] tells the truth about whether anything can carry a packet.
     */
    override fun onClientDisconnected(deviceAddress: String) {
        if (!clients.onDisconnected(deviceAddress)) return
        log.info("central {} disconnected ({} link(s) held)", deviceAddress, clients.size())
    }

    // -----------------------------------------------------------------------------------------
    // BluetoothConnectionService -- the outbound half
    // -----------------------------------------------------------------------------------------

    /**
     * Send to every peer this node holds a link to, on **both** roles, and say whether anything
     * carried it.
     *
     * The two roles do not share a mechanism and must not share a code path:
     *
     * **Inbound links, one emission.** A BlueZ notification is a `PropertiesChanged` signal on the
     * characteristic's object path, which names no device; it reaches every subscriber. Looping
     * over the registry and emitting per entry sends N copies of every packet to everyone, which is
     * what put duplicated `Packet received` lines in the device journal. Hence
     * [LinuxGattServerService.notifySubscribers], once, whatever the client count.
     *
     * **Outbound links, one write each.** A central-role write is addressed by construction --
     * `WriteValue` on that peer's characteristic -- so here the fan-out is the point. They run
     * concurrently rather than in sequence because each is paced at 25 ms per chunk, and a
     * multi-chunk frame to a slow peer would otherwise delay the next peer by the whole frame.
     *
     * **False when nothing could have received it.** Because the peripheral emission is
     * device-agnostic it cannot fail for lack of listeners -- the signal goes out and D-Bus is
     * happy. The only thing that knows better is the registry, and
     * `BluetoothMeshService.initiateNoiseHandshake` depends on being told: it counts an attempt only
     * when a link carried the packet, and against a service that always returned true it burned all
     * five retries into an empty room while the peer had no way to answer. True here means at least
     * one path delivered, not that every path did.
     */
    override suspend fun broadcastPacket(packetData: ByteArray): Boolean {
        val outbound = gattClient.readyAddresses()
        val inbound = clients.size()

        if (inbound == 0 && outbound.isEmpty()) {
            broadcastsDropped.incrementAndGet()
            log.warn("broadcast of {}B dropped: this node holds no link to anybody", packetData.size)
            return false
        }

        log.debug(
            "broadcasting {}B to {} inbound link(s) and {} outbound link(s)",
            packetData.size,
            inbound,
            outbound.size
        )

        val notified = inbound > 0 && gattServer.notifySubscribers(packetData)
        if (inbound > 0 && !notified) {
            log.warn(
                "the peripheral-role notification of {}B failed although {} link(s) are registered here",
                packetData.size,
                inbound
            )
        }

        val written = if (outbound.isEmpty()) {
            emptyList()
        } else {
            coroutineScope {
                outbound.map { address ->
                    async { address to gattClient.writeCharacteristic(address, packetData) }
                }.awaitAll()
            }
        }
        written.filterNot { it.second }.forEach { (address, _) ->
            log.warn("write of {}B to {} failed", packetData.size, address)
        }

        val delivered = notified || written.any { it.second }
        if (delivered) broadcastsDelivered.incrementAndGet() else broadcastsDropped.incrementAndGet()
        return delivered
    }

    /**
     * Forget every link, and drop the ones that are ours to drop.
     *
     * The asymmetry is real, not an oversight. A peripheral cannot hang up on a central -- the link
     * belongs to the central, and `Device1.Disconnect` on a device we did not dial is BlueZ's
     * business, not ours -- so the inbound half is registry-only. The outbound half is the opposite:
     * we opened those links and they stay open until we close them, so leaving them would hold the
     * controller's connection budget across a `stopServices()`.
     *
     * Tearing the GATT registration down is `stopAdvertising`, which `stopServices()` calls in its
     * own right; doing it from here as well would unexport the application twice.
     */
    override suspend fun clearConnections() {
        val held = clients.size()
        clients.clear()
        gattClient.disconnectAll()
        policyLock.withLock { linkPolicy.clear() }
        consecutiveFailures.clear()
        log.info("cleared {} inbound link(s) and every outbound link; {}", held, statusLine())
    }

    /**
     * Nothing to confirm in the peripheral role.
     *
     * On Android this acknowledges a pairing dialog. BlueZ never raises one for the connections
     * this transport makes: the bitchat characteristic requires no authentication, so a central
     * reads and writes it without pairing at all.
     */
    override suspend fun confirmDevice() {
        log.debug("confirmDevice: nothing to confirm -- the bitchat characteristic is unauthenticated")
    }

    /**
     * Whatever the platform needs before it may touch the radio.
     *
     * BlueZ needs nothing: the app talks to `org.bluez` over the system bus, and access is
     * D-Bus policy, which either allows the call or refuses it at the point of the call -- there
     * is nothing to ask for in advance and nothing a user can grant from inside the app.
     *
     * The `location.native` check is nonetheless kept exactly as `DesktopConnectionService` had
     * it, and the reason is that this is *not* a Bluetooth permission. It is the shared desktop
     * gate for the CoreLocation bridge, consulted through `AppRepository.hasRequiredPermissions()`
     * by the same initialiser that starts the mesh. Dropping it here because BlueZ does not care
     * would silently re-enable the mesh on a Mac whose location permission was refused.
     */
    override fun hasRequiredPermissions(): Boolean {
        val useNativeLocation = System.getProperty("location.native")?.lowercase() == "macos"
        return if (useNativeLocation && NativeLocationBridge.isAvailable()) {
            NativeLocationBridge.hasPermission()
        } else {
            true // IP-based fallback doesn't require permission
        }
    }

    // -----------------------------------------------------------------------------------------
    // Central role
    // -----------------------------------------------------------------------------------------

    /**
     * The scanner has seen a bitchat advertiser. Decide, off this thread, whether to dial it.
     *
     * Not a `suspend` function and not a blocking one, because of who calls it: the scanner's own
     * drain loop, one address at a time. A decision that suspended there would stop discovery being
     * reported for the length of a connection attempt -- up to 25 seconds -- and the offers that
     * piled up behind it would name peers that had since walked away. So the answer is always
     * "immediately, on another coroutine", and the ordering that matters is enforced by the link
     * policy rather than by this queue.
     *
     * The same address arrives here every few seconds for as long as the peer is in range. That is
     * the design, not a defect: it is what lets a peer be retried the moment its backoff expires,
     * and [CentralLinkPolicy] refuses the ones in between at the cost of two map lookups.
     */
    fun onDeviceDiscovered(deviceAddress: String) {
        discoveredByUs.add(deviceAddress)
        scope.launch {
            /*
             * The slot is released here, not left to the reaper.
             *
             * [dial] takes the policy's one connect slot before it does anything else, so a
             * coroutine that dies between taking it and reporting an outcome holds the whole node's
             * ability to reach anybody until the 30 s deadline expires. The reaper does recover it
             * -- observed doing exactly that against a `NoClassDefFoundError` raised by a jar
             * replaced under a running JVM -- but half a minute of a mesh that cannot dial is worth
             * one `try`.
             */
            try {
                dial(deviceAddress, "discovery")
            } catch (t: Throwable) {
                log.error("the dial of {} threw; releasing the connect slot", deviceAddress, t)
                policyLock.withLock { linkPolicy.onReleased(deviceAddress, System.currentTimeMillis()) }
            }
        }
    }

    /**
     * Dial [deviceAddress], if the policy allows it.
     *
     * Both entry points -- a discovery offer and an explicit [connectToDevice] -- come through here
     * rather than only the first. An explicit connect that bypassed the policy would be a second
     * attempt in flight, and the controller's single initiator cancels the first one when that
     * happens; there is no such thing as an outbound link this class is allowed to open outside the
     * budget.
     */
    private suspend fun dial(deviceAddress: String, reason: String) {
        val now = System.currentTimeMillis()
        val decision = policyLock.withLock {
            linkPolicy.onDiscovered(
                address = deviceAddress,
                now = now,
                inboundAddresses = clients.addresses(),
                // The stored lookup, finally consumed: it is what stops us dialling a phone we are
                // already talking to under the resolvable private address it used a minute ago.
                // Documented not to block, and called here with the policy lock held.
                peerAddresses = peerAddressLookup(deviceAddress)
            )
        }

        if (decision is CentralLinkPolicy.Decision.Skip) {
            offersSkipped.incrementAndGet()
            log.debug("not dialling {} ({}): {}", deviceAddress, reason, decision.reason)
            return
        }

        offersAccepted.incrementAndGet()
        log.info(
            "dialling {} ({}); {} attempt(s) in flight, {} outbound link(s) held",
            deviceAddress,
            reason,
            policyLock.withLock { linkPolicy.pendingCount() },
            policyLock.withLock { linkPolicy.establishedCount() }
        )

        val outcome = gattClient.connect(deviceAddress)
        handleConnectOutcome(deviceAddress, outcome)
    }

    /**
     * Record what BlueZ made of the attempt.
     *
     * The taxonomy is the point. Treating every failure alike is what produces a connection storm:
     * the scanner re-offers the address a few seconds later, the policy has no reason to refuse it,
     * and the single initiator is spent on a peer that was never going to answer.
     *
     *  - `IN_PROGRESS` is not a failure of ours at all. BlueZ still owns an earlier attempt, and
     *    asking again before it lets go only collects another refusal, so the connect slot is freed
     *    immediately and the address goes to the longest backoff. That is exactly what
     *    [CentralLinkPolicy.onNativeBusy] is for; the state it describes on the embedded build --
     *    the native layer holding an attempt this side has given up on -- is the same state.
     *  - `GONE` is tolerated rather than punished: the device object simply is not there, which the
     *    scanner will fix by offering it again when BlueZ re-creates it.
     *  - `FAILED` and `UNAVAILABLE` take the ordinary escalating backoff.
     */
    private suspend fun handleConnectOutcome(
        deviceAddress: String,
        outcome: LinuxGattClientService.ConnectOutcome
    ) {
        val now = System.currentTimeMillis()
        when (outcome) {
            LinuxGattClientService.ConnectOutcome.READY -> {
                policyLock.withLock { linkPolicy.onConnected(deviceAddress) }
                consecutiveFailures.remove(deviceAddress)
                log.info(
                    "outbound link to {} established ({} held)",
                    deviceAddress,
                    policyLock.withLock { linkPolicy.establishedCount() }
                )
                raiseLinkUp(deviceAddress)
            }

            LinuxGattClientService.ConnectOutcome.IN_PROGRESS -> {
                log.info("BlueZ still owns the previous attempt to {}; backing off hard", deviceAddress)
                policyLock.withLock { linkPolicy.onNativeBusy(deviceAddress, now) }
            }

            LinuxGattClientService.ConnectOutcome.GONE -> {
                log.debug("{} has no device object; waiting to be offered it again", deviceAddress)
                policyLock.withLock { linkPolicy.onReleased(deviceAddress, now) }
            }

            /*
             * The one failure that backing off cannot fix. The link came up, BlueZ said services
             * were resolved, and there is no bitchat GATT database under the device -- measured on
             * a dual-mode Android peer that `Connect()` reached over BR/EDR. The same device record
             * produces the same non-link every time, so the record is cleared now instead of after
             * [FORGET_AFTER_FAILURES] identical attempts; the next LE advertisement re-creates it
             * with no BR/EDR knowledge to prefer.
             */
            LinuxGattClientService.ConnectOutcome.NO_SERVICE -> {
                policyLock.withLock { linkPolicy.onReleased(deviceAddress, now) }
                consecutiveFailures.merge(deviceAddress, 1, Int::plus)
                clearDeviceRecord(deviceAddress, "it resolved with no bitchat GATT service")
            }

            LinuxGattClientService.ConnectOutcome.FAILED,
            LinuxGattClientService.ConnectOutcome.UNAVAILABLE -> {
                policyLock.withLock { linkPolicy.onReleased(deviceAddress, now) }
                onAttemptFailed(deviceAddress, outcome.name)
            }
        }
    }

    /**
     * Count a failure, and clear the device record once there have been enough of them.
     *
     * `Adapter1.RemoveDevice` is the only reliable way to clear a `Device1` BlueZ has cached in a
     * state it will not connect from, and it is genuinely destructive -- it discards pairing data
     * too. Two conditions therefore gate it, and both are necessary: the address must be one *we*
     * discovered through our own service-UUID filter, so it is a bitchat advertiser rather than the
     * user's headphones, and it must have failed [FORGET_AFTER_FAILURES] times running, so a peer
     * that is merely busy is not forgotten on its first refusal.
     */
    private suspend fun onAttemptFailed(deviceAddress: String, reason: String) {
        val failures = consecutiveFailures.merge(deviceAddress, 1, Int::plus) ?: 1
        log.info("attempt to {} failed ({}); {} in a row", deviceAddress, reason, failures)

        if (failures < FORGET_AFTER_FAILURES) return
        clearDeviceRecord(deviceAddress, "$failures attempts in a row failed")
    }

    /**
     * Ask BlueZ to forget a device record, if and only if we are entitled to.
     *
     * The entitlement is the whole of the safety here. `Adapter1.RemoveDevice` discards pairing data
     * along with the record, so it may only ever be aimed at an address *this node* discovered
     * through its own service-UUID discovery filter -- which makes it a bitchat advertiser and not
     * the user's headphones, mouse or car. An address that arrived any other way is left alone,
     * however badly it is behaving.
     */
    private suspend fun clearDeviceRecord(deviceAddress: String, reason: String) {
        if (deviceAddress !in discoveredByUs) {
            // Nothing here discovered it, so nothing here knows what it is. Never remove it.
            log.debug("not clearing the device record for {}: this node did not discover it", deviceAddress)
            return
        }
        log.info("clearing BlueZ's device record for {}: {}", deviceAddress, reason)
        if (gattClient.forgetDevice(deviceAddress)) {
            consecutiveFailures.remove(deviceAddress)
        }
    }

    /**
     * Abandon attempts that produced no outcome.
     *
     * [LinuxGattClientService] enforces a 25 s deadline of its own, so in normal operation this
     * finds nothing -- and that is the point of having it. The failure it exists for is an attempt
     * whose coroutine is wedged rather than slow: a `Device1.Connect()` that BlueZ never answers
     * blocks an IO thread that no cancellation can interrupt, and the policy slot it holds would
     * otherwise be held for the life of the process. On the embedded build, before the equivalent
     * reaper existed, the journal shows seven attempts pending at once while no link was up and
     * every broadcast went to zero devices.
     */
    private fun startAttemptReaper() {
        scope.launch {
            log.info(
                "central link reaper started (deadline {}ms, sweep {}ms)",
                CentralLinkPolicy.CONNECT_TIMEOUT_MS,
                CentralLinkPolicy.SWEEP_INTERVAL_MS
            )
            while (isActive) {
                delay(CentralLinkPolicy.SWEEP_INTERVAL_MS)
                runCatching { reapExpiredAttempts(System.currentTimeMillis()) }
                    .onFailure { log.error("the attempt reaper threw", it) }
            }
        }
    }

    internal suspend fun reapExpiredAttempts(now: Long) {
        val expired = policyLock.withLock { linkPolicy.expiredAttempts(now) }
        if (expired.isEmpty()) return

        expired.forEach { address ->
            attemptsReaped.incrementAndGet()
            log.warn(
                "abandoning the attempt to {}: no outcome within {}ms",
                address,
                CentralLinkPolicy.CONNECT_TIMEOUT_MS
            )
            gattClient.disconnect(address)
            policyLock.withLock { linkPolicy.onReleased(address, now) }
        }
    }

    /**
     * An outbound link went down on its own -- the peer walked away, or rotated its address.
     *
     * The mesh is not told directly, because `commonMain` has no disconnect callback on this
     * interface and it reaps peers on its own timer. What matters is that the policy learns the
     * link is gone, both so the address can be dialled again after its backoff and so the outbound
     * budget is not permanently spent on a peer that has left.
     */
    override fun onLinkDown(deviceAddress: String, reason: String) {
        scope.launch {
            policyLock.withLock { linkPolicy.onReleased(deviceAddress, System.currentTimeMillis()) }
            log.info("outbound link to {} released ({})", deviceAddress, reason)
        }
    }

    /**
     * An explicit request to open a link, from outside the discovery path.
     *
     * Routed through the same policy as a discovery offer; see [dial].
     */
    override suspend fun connectToDevice(deviceAddress: String) {
        dial(deviceAddress, "explicit request")
    }

    /** True while an attempt to [deviceAddress] is in flight and has neither succeeded nor been reaped. */
    override suspend fun isDeviceConnecting(deviceAddress: String): Boolean =
        policyLock.withLock { linkPolicy.isPending(deviceAddress) }

    /**
     * Drop the link to [deviceAddress], whichever role holds it.
     *
     * The mesh calls this when a peer turns up under a new address and the old one is superseded,
     * so both halves have to be handled: the outbound link is really disconnected, because it is
     * ours to close, while the inbound registry entry is only forgotten, because a peripheral
     * cannot hang up on a central.
     */
    override suspend fun disconnectDeviceByAddress(deviceAddress: String) {
        val wasInbound = clients.onDisconnected(deviceAddress)
        val wasOutbound = gattClient.isReady(deviceAddress)
        gattClient.disconnect(deviceAddress)
        policyLock.withLock { linkPolicy.onReleased(deviceAddress, System.currentTimeMillis()) }

        when {
            wasOutbound -> log.info("dropped the outbound link to {}", deviceAddress)
            wasInbound -> log.info("forgot the inbound link to {}", deviceAddress)
            else -> log.debug("disconnectDeviceByAddress({}): no such link", deviceAddress)
        }
    }

    // -----------------------------------------------------------------------------------------
    // Wiring
    // -----------------------------------------------------------------------------------------

    override fun setPeerAddressLookup(lookup: (String) -> Set<String>) {
        peerAddressLookup = lookup
    }

    override fun setConnectionEstablishedCallback(callback: ConnectionEstablishedCallback) {
        establishedCallback = callback
    }

    override fun setConnectionReadyCallback(callback: ConnectionReadyCallback) {
        readyCallback = callback
    }

    override fun setOnPacketReceivedCallback(callback: OnPacketReceivedCallback) {
        packetCallback = callback
    }

    /**
     * One line of counters, for the same reason the GATT server has one: the useful signal is
     * where the count stops. Frames the server reassembled but this layer could not deliver is a
     * wiring failure; broadcasts dropped with links held is a notification failure.
     */
    fun statusLine(): String =
        "connection: inbound=${clients.size()} outbound=${gattClient.readyAddresses().size}" +
            " delivered=${packetsDelivered.get()}" +
            " undeliverable=${packetsUndeliverable.get()}" +
            " broadcasts=${broadcastsDelivered.get()} dropped=${broadcastsDropped.get()}" +
            " dialled=${offersAccepted.get()} skipped=${offersSkipped.get()}" +
            " reaped=${attemptsReaped.get()}"

    private companion object {
        /** See [raiseLinkUp]. Long enough to lose a dispatcher race, short enough to ignore. */
        const val CONNECTION_READY_DELAY_MS: Long = 250L

        /**
         * How many failures in a row before a device record is cleared. See [onAttemptFailed].
         *
         * Three, because the ordinary reasons an attempt fails -- the peer is at its own central
         * cap, the peer is mid-handshake with somebody else, the controller lost the race -- all
         * clear themselves within a couple of backoffs, and at the third the escalating backoff has
         * already reached twenty seconds. Anything that has failed that consistently is more likely
         * to be a stale device record than a busy peer.
         */
        const val FORGET_AFTER_FAILURES: Int = 3
    }
}
