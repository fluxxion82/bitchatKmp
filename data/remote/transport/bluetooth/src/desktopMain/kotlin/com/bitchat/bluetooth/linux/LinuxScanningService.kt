package com.bitchat.bluetooth.linux

import com.bitchat.bluetooth.service.CentralScanningService
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.freedesktop.dbus.types.Variant
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/*
 * =============================================================================================
 * The central role, half one: finding peers worth dialling.
 * =============================================================================================
 *
 * BlueZ has no "scan callback". What it has is a discovery session -- reference-counted per D-Bus
 * client, which is why the lease lives on [BlueZBus] and not here -- and an object tree that grows
 * a `Device1` per peer the controller hears. Discovery is therefore not an event stream at all; it
 * is a tree, and the events are hints that the tree changed.
 *
 * That distinction is the entire design of this file, because BlueZ **caches `Device1` objects
 * across discovery sessions**. A peer bluetoothd has seen before is simply *there* the moment a
 * scan starts: no `InterfacesAdded`, no announcement of any kind, only `PropertiesChanged` carrying
 * a fresh RSSI. A scanner keyed on `InterfacesAdded` alone therefore finds every peer exactly once
 * -- on the first run after a `bluetoothctl remove` -- and never again. So candidates come from
 * three sources, and all three are load-bearing:
 *
 *   1. `GetManagedObjects`, sampled on a timer. The only source that sees a cached device, and the
 *      only one that carries `UUIDs` for a device whose advertisement we did not witness.
 *   2. `InterfacesAdded`, for a peer BlueZ has genuinely never seen. Fastest of the three.
 *   3. `Device1` `PropertiesChanged`, which is what a *known* peer's advertisement looks like. This
 *      is the one that makes re-offers prompt enough for [com.bitchat.bluetooth.manager.CentralLinkPolicy]'s
 *      backoff to mean something: a peer whose backoff has just expired is re-offered within a
 *      second rather than at the next snapshot.
 *
 * Everything below the sources is one funnel: confirm the device really advertises the bitchat
 * service UUID, throttle repeats, and hand the address to a channel. The channel exists because the
 * three sources run on dbus-java's *single* SIGNAL thread. Anything a signal handler blocks on
 * stalls the whole transport -- including inbound data on the peripheral role -- so the sources do
 * nothing but a bounded, non-blocking hand-off, and the consumer's callback is invoked from a
 * coroutine of ours. See [drainOffers].
 */

/**
 * Below which RSSI a device is not worth reporting, in dBm.
 *
 * BlueZ applies this itself: with an `RSSI` entry in the discovery filter it drops reports weaker
 * than the threshold rather than turning them into `PropertiesChanged` traffic we would then have
 * to filter. -95 dBm is deliberately permissive -- a phone in the next room reads around -85 on the
 * reference adapter -- because the cost of a marginal peer is one connection attempt that the link
 * policy will back off, while the cost of an over-tight threshold is a peer that is never dialled
 * and no log line saying why.
 */
private const val RSSI_THRESHOLD_DBM: Short = -95

/**
 * `DiscoveryFilter.DuplicateData`, and the reasoning for the value.
 *
 * `true` asks BlueZ to forward every advertising report the controller hears, including ones whose
 * payload is byte-identical to the last. That is the right choice for a scanner that is *measuring*
 * -- a beacon logger, an RSSI heat map -- and the wrong one here, for two reasons. It multiplies
 * traffic on the single signal thread by the advertising rate of every peer in range, several
 * events per second each, all of which this file would discard. And it buys nothing: the two events
 * this scanner actually needs are a peer appearing (a new `Device1`, which is `InterfacesAdded`
 * whatever this flag says) and a peer still being here (any RSSI change at all, which BlueZ still
 * reports with duplicates filtered, because a changed RSSI is by definition not a duplicate).
 *
 * `false` is also what makes the periodic snapshot cheap rather than mandatory: a peer that is
 * genuinely quiet on the air produces no events either way, and the snapshot is what notices it.
 */
private const val DUPLICATE_DATA: Boolean = false

/**
 * How often the managed-objects tree is re-sampled.
 *
 * The first sample happens immediately -- it is the one that finds every cached peer -- and this is
 * the interval for the ones after it. `GetManagedObjects` is a single round trip returning a few
 * kilobytes, so 10 s costs nothing measurable, and it is the ceiling on how long a peer whose
 * `UUIDs` we could not confirm from a signal stays unconfirmed.
 */
private const val SNAPSHOT_INTERVAL_MS: Long = 10_000L

/**
 * How often the same address may be offered.
 *
 * An advertising peer produces several `PropertiesChanged` per second; offering on each of them
 * would hand the connection service hundreds of decisions a minute, every one of which it would
 * refuse. Five seconds is chosen to match [com.bitchat.bluetooth.manager.CentralLinkPolicy]'s base
 * backoff: the policy is the thing that decides whether an offer becomes a dial, and re-offering
 * faster than its shortest backoff cannot produce an extra connection -- only extra log lines.
 */
private const val OFFER_REPEAT_INTERVAL_MS: Long = 5_000L

/**
 * When an address is forgotten from the throttle map.
 *
 * Purely to bound memory: Android rotates its resolvable private address every ten minutes or so,
 * and each rotation is a new key here that will never be seen again. Five minutes of silence is far
 * longer than any backoff and far shorter than a session.
 */
private const val OFFER_ENTRY_TTL_MS: Long = 5 * 60_000L

/**
 * How many offers may be waiting for the consumer before one is refused.
 *
 * Refusing, rather than dropping the oldest, so that the loss is *countable*: a `DROP_OLDEST`
 * channel makes `trySend` always succeed and the drop invisible. 64 outstanding offers means the
 * consumer has been stalled for minutes, which is a defect and should be visible as one.
 */
private const val OFFER_QUEUE_CAPACITY: Int = 64

/**
 * The bitchat service UUID, as BlueZ spells it.
 *
 * BlueZ normalises every UUID it reports to lower case, in `UUIDs`, in `GetManagedObjects` and in
 * the discovery filter it echoes back. Comparisons therefore have to be case-insensitive; this
 * constant exists so the lowering happens once rather than in each of the three sources.
 */
private val SERVICE_UUID_LOWER: String = BITCHAT_SERVICE_UUID.lowercase()

/**
 * Which addresses may be offered right now, and when each was last offered.
 *
 * Pure bookkeeping with no clock of its own -- callers pass `now` -- so the throttle can be tested
 * without sleeping. Split out of the service for exactly that reason: it is the one piece of this
 * file whose behaviour is not "whatever BlueZ did".
 *
 * Thread-safe because the three sources call [shouldOffer] from dbus-java's signal thread while the
 * snapshot loop calls it from a coroutine. `putIfAbsent`/`replace` rather than a lock, so a signal
 * handler is never parked here.
 */
internal class DiscoveryOfferGate(
    private val repeatIntervalMs: Long = OFFER_REPEAT_INTERVAL_MS
) {

    private val lastOffered = ConcurrentHashMap<String, Long>()

    /**
     * Whether [address] may be offered at [now], recording the offer when it may.
     *
     * Returns true at most once per [repeatIntervalMs] per address. Two callers racing on the same
     * address at the same instant cannot both win: the winner is decided by `replace`, which is
     * atomic against the value the loser read.
     */
    fun shouldOffer(address: String, now: Long): Boolean {
        while (true) {
            val previous = lastOffered.putIfAbsent(address, now) ?: return true
            if (now - previous < repeatIntervalMs) return false
            if (lastOffered.replace(address, previous, now)) return true
            // Lost the race to another source offering the same address; re-read and re-decide,
            // which on the next pass sees the winner's timestamp and refuses.
        }
    }

    /** Drop [address], so the next sighting is offered immediately. Called when BlueZ forgets it. */
    fun forget(address: String) {
        lastOffered.remove(address)
    }

    /** Drops entries untouched for [ttlMs]; returns how many. Bounds the map, nothing more. */
    fun prune(now: Long, ttlMs: Long = OFFER_ENTRY_TTL_MS): Int {
        val stale = lastOffered.entries.filter { now - it.value >= ttlMs }.map { it.key }
        stale.forEach { lastOffered.remove(it) }
        return stale.size
    }

    fun size(): Int = lastOffered.size
}

/**
 * True when a `Device1` property map says the peer advertises the bitchat service.
 *
 * The comparison is case-insensitive because BlueZ lower-cases every UUID it reports while the
 * constant is spelled upper-case to match the Android and iOS peers. Getting that wrong is silent:
 * the filter still narrows what BlueZ *reports*, so the scanner appears to work and simply never
 * confirms a candidate.
 *
 * A device with no `UUIDs` at all is not a candidate. BlueZ omits the property until it has parsed
 * an advertisement carrying a service list, which for our purposes is the definition of "we have
 * not seen it advertise bitchat".
 */
internal fun advertisesBitchat(deviceProperties: Map<String, Variant<*>>): Boolean {
    val uuids = deviceProperties["UUIDs"]?.value as? List<*> ?: return false
    return uuids.any { (it as? String)?.lowercase() == SERVICE_UUID_LOWER }
}

/**
 * `Device1.RSSI` out of a property map, or null when BlueZ is not currently reporting one.
 *
 * The absence is meaningful and is not an error: BlueZ *removes* `RSSI` from a device it is no
 * longer hearing. A cached `Device1` with no RSSI is a peer that was here once and is not here now,
 * which is the difference between offering a peer and offering a ghost. The value is a D-Bus `n`,
 * so it arrives as a [Short]; read as a [Number] anyway, because nothing downstream cares and a
 * daemon that sent an `i` would otherwise be a silent total failure of this check.
 */
internal fun rssiOf(deviceProperties: Map<String, Variant<*>>): Int? =
    (deviceProperties["RSSI"]?.value as? Number)?.toInt()

/**
 * Discovery for the desktop-Linux central role.
 *
 * Owns the discovery filter, a lease on [BlueZBus]'s reference-counted discovery session, the three
 * candidate sources and the throttle between them and the consumer. It opens no connection, dials
 * nothing and knows nothing about links: everything it produces is an address on a channel.
 *
 * @param bus the shared connection. Discovery is per D-Bus client and this process is one client,
 *   so the Start/Stop pair is the bus's to own; this class only takes and drops a lease.
 */
class LinuxScanningService(private val bus: BlueZBus) : CentralScanningService {

    private val log = LoggerFactory.getLogger("bitchat.ble.scan")

    /**
     * Guards [scope], [leaseHeld] and [filterOwner] against each other. A coroutine [Mutex] and not
     * a lock, because the only things that take it are `startScan`, `stopScan` and the status
     * watcher -- all coroutines. No signal handler ever touches it; see the class KDoc.
     */
    private val gate = Mutex()

    private var scope: CoroutineScope? = null

    /** Whether [BlueZBus.releaseDiscovery] is owed. Never inferred -- see the lease's contract. */
    private var leaseHeld = false

    /**
     * bluetoothd's unique bus name at the moment the discovery filter was last accepted.
     *
     * The filter is per D-Bus client and therefore dies with the daemon that held it, while
     * [BlueZBus] re-asserts the *lease* across a restart on its own. Without this, a restarted
     * bluetoothd would scan for us with no filter at all -- every device in range reported, none of
     * them narrowed to bitchat -- and nothing would ever say so.
     */
    private var filterOwner: String? = null

    /** What the caller asked for, as opposed to what BlueZ currently believes. */
    @Volatile
    private var desired = false

    /**
     * Addresses confirmed to advertise the bitchat service UUID.
     *
     * Confirmation is separate from offering because the sources differ in what they carry: an
     * `InterfacesAdded` and a snapshot entry both carry `UUIDs`, while an RSSI `PropertiesChanged`
     * carries nothing but the RSSI. Without this set, the third source -- the only prompt one --
     * could not tell a bitchat peer from a fitness tracker, and the discovery filter cannot be
     * relied on to do it either: BlueZ *unions* the filters of every client on the adapter and
     * broadcasts the resulting signals to all of them.
     */
    private val candidates = ConcurrentHashMap.newKeySet<String>()

    /**
     * Offers on their way to the consumer.
     *
     * [BufferOverflow.SUSPEND] with a bounded capacity, driven exclusively through `trySend`, which
     * makes a full queue a refusal rather than a wait -- the producers are signal handlers and must
     * never block. See [OFFER_QUEUE_CAPACITY] for why the loss is counted rather than hidden.
     */
    private val offers = Channel<String>(OFFER_QUEUE_CAPACITY, BufferOverflow.SUSPEND)

    private val gateway = DiscoveryOfferGate()

    /**
     * Where an offer becomes a connection decision.
     *
     * Volatile and settable after construction because the consumer -- the connection service --
     * is built *from* the graph this scanner is already in, so it cannot be a constructor argument
     * without a cycle. The contract on it is the one thing that matters here and it is the DI
     * layer's to honour: it is invoked from [drainOffers], which is a coroutine of ours, and it
     * must not block and must not `runBlocking`. A callback that dialled inline would hold this
     * scanner's drain loop for the length of a connection attempt.
     */
    @Volatile
    private var onDeviceDiscovered: ((String) -> Unit)? = null

    private val offersMade = AtomicLong()
    private val offersRefused = AtomicLong()
    private val snapshots = AtomicLong()

    /**
     * Installs the discovery callback. Called once, from the DI layer, before [startScan].
     *
     * @param callback must return promptly and must not block; see [onDeviceDiscovered].
     */
    fun setOnDeviceDiscoveredCallback(callback: (String) -> Unit) {
        onDeviceDiscovered = callback
    }

    // -----------------------------------------------------------------------------------------
    // CentralScanningService
    // -----------------------------------------------------------------------------------------

    /**
     * Brings the bus up if it is not, applies the discovery filter and takes a discovery lease.
     *
     * [lowLatency] has no BlueZ equivalent and is ignored. The D-Bus API exposes no scan window or
     * interval -- `SetDiscoveryFilter` takes `Transport`, `UUIDs`, `RSSI`/`Pathloss` and
     * `DuplicateData`, and nothing else -- so a caller asking for low latency gets whatever the
     * controller's default scan parameters are. Saying so here is better than a knob that silently
     * does nothing.
     *
     * Never throws, and never fails permanently: if BlueZ is not up yet the collectors still start,
     * and [watchStatus] applies the filter and takes the lease the moment bluetoothd resolves. That
     * is the same path that recovers from a daemon restart, and it is why an app launched before
     * bluetoothd does not need restarting.
     */
    override suspend fun startScan(lowLatency: Boolean) {
        // Not inside the gate: it is a bounded wait on a socket, and holding the gate across it
        // would make `stopScan` wait for a bus that may never answer.
        val up = bus.ensureStarted()

        gate.withLock {
            desired = true
            startScopeLocked()
            if (!up) {
                log.warn(
                    "scan requested but BlueZ is {} -- the filter and the lease will be taken when " +
                        "bluetoothd resolves",
                    bus.status.value
                )
                return@withLock
            }
            acquireLocked("startScan")
        }
    }

    /**
     * Drops the lease and stops the collectors.
     *
     * Deliberately does not stop the *bus*: the peripheral role is still using it, and
     * `stopServices()` calls this alongside the GATT server's own teardown. Deliberately does not
     * call `StopDiscovery` either -- the lease is reference-counted per client, so ending the
     * session is the bus's decision to make once every lease is back.
     */
    override suspend fun stopScan() {
        gate.withLock {
            if (!desired && scope == null && !leaseHeld) return@withLock
            desired = false
            releaseLocked()
            filterOwner = null
            candidates.clear()
            scope?.cancel()
            scope = null
            log.info("scan stopped; {}", statusLine())
        }
    }

    /** One line of counters for the transport's health log. See [LinuxGattServerService.statusLine]. */
    fun statusLine(): String =
        "scan: discovering=${bus.isDiscovering()} candidates=${candidates.size}" +
            " offers=${offersMade.get()} refused=${offersRefused.get()}" +
            " snapshots=${snapshots.get()} tracked=${gateway.size()}"

    // -----------------------------------------------------------------------------------------
    // Lease and filter
    // -----------------------------------------------------------------------------------------

    /**
     * Applies the filter and takes a lease, in that order. Caller holds [gate].
     *
     * The order is the whole point: `SetDiscoveryFilter` is what BlueZ consults when it *starts* a
     * session, so a filter applied after `StartDiscovery` narrows nothing until the next session.
     * Both are blocking round trips and both are moved off the caller's thread together.
     */
    private suspend fun acquireLocked(reason: String) = withContext(Dispatchers.IO) {
        val status = bus.status.value
        if (status !is BlueZStatus.Available) {
            log.warn("cannot start discovery ({}): BlueZ is {}", reason, status)
            return@withContext
        }

        if (applyFilter(status.adapterPath)) filterOwner = status.owner

        if (leaseHeld) {
            log.debug("discovery lease already held ({})", reason)
            return@withContext
        }
        if (bus.acquireDiscovery()) {
            leaseHeld = true
            log.info(
                "discovery running on {} filtered to {} (transport=le, RSSI>={}dBm, DuplicateData={})",
                status.adapterPath,
                BITCHAT_SERVICE_UUID,
                RSSI_THRESHOLD_DBM,
                DUPLICATE_DATA
            )
        } else {
            // All-or-nothing by the lease's contract: nothing was taken, so nothing is owed.
            log.warn("BlueZ refused a discovery lease ({}); will retry when its status changes", reason)
        }
    }

    /** Caller holds [gate]. Idempotent below zero by the lease's own contract. */
    private fun releaseLocked() {
        if (!leaseHeld) return
        leaseHeld = false
        bus.releaseDiscovery()
    }

    /**
     * `Adapter1.SetDiscoveryFilter`, the one call that decides what discovery costs.
     *
     * Unfiltered, this adapter reports every advertiser in range -- and on the reference host that
     * is dozens of devices at several reports a second each, all of them arriving on the single
     * signal thread the peripheral role also depends on. The filter moves that rejection into
     * bluetoothd.
     *
     * Every value's *contained type* decides its type on the wire, and two of them are traps.
     * `UUIDs` is a D-Bus `as`, which the one-argument `Variant` constructor cannot produce -- a
     * `java.util.List` has its element type erased, so it is rejected with "Can't wrap ... in an
     * unqualified Variant"; [variantOfStrings] is the two-argument form that works. `RSSI` is an
     * `n`, a *signed 16-bit* integer, so the threshold must be a [Short]: a Kotlin `Int` there is
     * marshalled as `i` and BlueZ rejects the whole filter, which fails open into an unfiltered
     * scan rather than into an error anybody would notice.
     */
    private fun applyFilter(adapterPath: String): Boolean {
        val adapter = bus.proxy(adapterPath, Adapter1::class.java)
        if (adapter == null) {
            log.warn("no Adapter1 proxy on {}; discovery will not be filtered", adapterPath)
            return false
        }
        val filter = mapOf<String, Variant<*>>(
            "UUIDs" to variantOfStrings(listOf(BITCHAT_SERVICE_UUID)),
            "Transport" to Variant("le"),
            "RSSI" to Variant(RSSI_THRESHOLD_DBM),
            "DuplicateData" to Variant(DUPLICATE_DATA)
        )
        return try {
            adapter.SetDiscoveryFilter(filter)
            log.debug("discovery filter applied to {}", adapterPath)
            true
        } catch (t: Throwable) {
            log.error("SetDiscoveryFilter on {} failed: {}", adapterPath, dbusErrorText(t))
            false
        }
    }

    // -----------------------------------------------------------------------------------------
    // Collectors
    // -----------------------------------------------------------------------------------------

    /** Caller holds [gate]. */
    private fun startScopeLocked() {
        if (scope != null) return
        val scanScope =
            CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineName("bitchat-ble-scan"))
        scope = scanScope
        scanScope.launch { watchStatus() }
        scanScope.launch { watchInterfacesAdded() }
        scanScope.launch { watchAdvertisements() }
        scanScope.launch { watchRemovals() }
        scanScope.launch { pollSnapshot() }
        scanScope.launch { drainOffers() }
    }

    /**
     * Re-applies the filter, and takes the lease, when bluetoothd changes underneath us.
     *
     * Two distinct recoveries share this one collector. A daemon *restart* leaves [BlueZBus] to
     * re-assert `StartDiscovery` for the leases it still holds -- but not the filter, which was
     * per-client state in a process that no longer exists. A bus that was simply *down* when
     * [startScan] ran has no lease at all yet. Comparing the owner distinguishes them: a new owner
     * means re-filter, and [acquireLocked] takes a lease only if one is not already held.
     */
    private suspend fun watchStatus() {
        bus.status.collect { status ->
            when (status) {
                is BlueZStatus.Available -> {
                    if (!desired) return@collect
                    if (status.owner == filterOwner && leaseHeld) return@collect
                    gate.withLock {
                        if (!desired) return@withLock
                        if (status.owner == filterOwner && leaseHeld) return@withLock
                        if (filterOwner != null && status.owner != filterOwner) {
                            log.warn(
                                "bluetoothd is now {} (was {}) -- re-applying the discovery filter",
                                status.owner,
                                filterOwner
                            )
                            // The lease count died with the old daemon. BlueZ re-asserts the
                            // session for us, so the lease is still ours and must not be re-taken.
                            candidates.clear()
                        }
                        acquireLocked("bluetoothd ${status.owner}")
                    }
                }

                else -> gate.withLock {
                    if (filterOwner != null) {
                        log.warn("BlueZ is {} -- discovery is void until it returns", status)
                        filterOwner = null
                    }
                }
            }
        }
    }

    /**
     * Source two: a `Device1` BlueZ has never held before.
     *
     * The payload carries the device's whole property set, so this is the one source that can
     * confirm a candidate and offer it in the same event -- no round trip, no wait for a snapshot.
     */
    private suspend fun watchInterfacesAdded() {
        bus.interfacesAdded.collect { event ->
            val properties = event.interfaces[IFACE_DEVICE1] ?: return@collect
            val address = event.deviceAddress ?: return@collect
            if (!isOnOurAdapter(event.path)) return@collect
            if (!advertisesBitchat(properties)) return@collect
            candidates.add(address)
            // RSSI is not required here, unlike in the snapshot: an object BlueZ has only just
            // created was created *because* the controller heard it, so it is in range by
            // construction. A threshold check still applies if the value happens to be present.
            val rssi = rssiOf(properties)
            if (rssi != null && rssi < RSSI_THRESHOLD_DBM) return@collect
            offer(address, "InterfacesAdded", rssi)
        }
    }

    /**
     * Source three: a peer BlueZ already knows, still advertising.
     *
     * This is the prompt source and it is nearly all RSSI. It offers only addresses that are
     * already candidates, because the event says nothing about services -- except in the one case
     * where BlueZ has just parsed a service list it did not have before, which arrives here as a
     * `UUIDs` change and is the cheapest confirmation there is.
     */
    private suspend fun watchAdvertisements() {
        bus.deviceAdvertisements.collect { event ->
            if (!isOnOurAdapter(event.path)) return@collect
            if (advertisesBitchat(event.changed)) candidates.add(event.address)
            if (event.address !in candidates) return@collect
            val rssi = rssiOf(event.changed)
            if (rssi != null && rssi < RSSI_THRESHOLD_DBM) return@collect
            offer(event.address, "PropertiesChanged", rssi)
        }
    }

    /**
     * BlueZ dropping a device object -- which for an Android peer is what an address rotation looks
     * like, arriving with no warning of any kind.
     *
     * Both the candidacy and the throttle entry go, so that if the same address ever comes back it
     * is treated as new rather than as something we offered thirty seconds ago.
     */
    private suspend fun watchRemovals() {
        bus.interfacesRemoved.collect { event ->
            if (IFACE_DEVICE1 !in event.interfaces) return@collect
            val address = event.deviceAddress ?: return@collect
            if (candidates.remove(address)) {
                log.debug("BlueZ dropped {}; no longer a candidate", address)
            }
            gateway.forget(address)
        }
    }

    /**
     * Source one: the tree itself, sampled.
     *
     * Runs immediately and then on [SNAPSHOT_INTERVAL_MS], because the first sample is a different
     * job from the ones after it -- it is the only thing that ever sees a peer BlueZ cached on a
     * previous run, which will announce itself in no other way.
     *
     * Unlike the signal sources this one **requires an RSSI**. A cached `Device1` keeps its `UUIDs`
     * forever, so without that check every bitchat peer this host has ever met would be offered on
     * every pass, and the single connection initiator would be spent dialling phones that left the
     * building days ago. BlueZ removes `RSSI` from a device it is no longer hearing, which makes
     * its presence the closest thing the API has to "in range right now".
     */
    private suspend fun pollSnapshot() {
        while (true) {
            val known = withContext(Dispatchers.IO) { bus.knownDevices() }
            snapshots.incrementAndGet()

            var offered = 0
            known.forEach { device ->
                val properties = device.properties(IFACE_DEVICE1)
                val address = bus.deviceAddress(device.path) ?: return@forEach
                if (!advertisesBitchat(properties)) return@forEach
                candidates.add(address)
                val rssi = rssiOf(properties) ?: return@forEach
                if (rssi < RSSI_THRESHOLD_DBM) return@forEach
                if (offer(address, "snapshot", rssi)) offered++
            }

            val pruned = gateway.prune(System.currentTimeMillis())
            log.debug(
                "snapshot: {} device(s) on this adapter, {} bitchat candidate(s), {} offered, {} pruned",
                known.size,
                candidates.size,
                offered,
                pruned
            )
            delay(SNAPSHOT_INTERVAL_MS)
        }
    }

    /**
     * The one place the consumer's callback is invoked, and it is a coroutine on `Dispatchers.IO`
     * rather than dbus-java's signal thread. That hop is the reason the channel exists.
     *
     * A callback that has not been installed yet is not an error worth shouting about: offers
     * repeat every [OFFER_REPEAT_INTERVAL_MS], so an address dropped here comes back within
     * seconds. A callback that *throws* is caught, because the alternative is that one bad address
     * ends discovery for the process.
     */
    private suspend fun drainOffers() {
        for (address in offers) {
            val callback = onDeviceDiscovered
            if (callback == null) {
                log.debug("discarding an offer of {}: no discovery callback is installed yet", address)
                continue
            }
            runCatching { callback(address) }
                .onFailure { log.error("the discovery callback threw on {}", address, it) }
        }
    }

    // -----------------------------------------------------------------------------------------
    // The funnel
    // -----------------------------------------------------------------------------------------

    /**
     * Throttles and enqueues one candidate. Safe on the signal thread: nothing here blocks.
     *
     * @return whether the address was actually enqueued.
     */
    private fun offer(address: String, source: String, rssi: Int?): Boolean {
        if (!gateway.shouldOffer(address, System.currentTimeMillis())) return false
        if (!offers.trySend(address).isSuccess) {
            offersRefused.incrementAndGet()
            log.warn(
                "offer queue is full ({} waiting); refused {} from {}",
                OFFER_QUEUE_CAPACITY,
                address,
                source
            )
            return false
        }
        offersMade.incrementAndGet()
        log.info("bitchat peer {} via {} (RSSI {})", address, source, rssi ?: "unknown")
        return true
    }

    /**
     * Rejects a device on another adapter.
     *
     * Not paranoia: BlueZ broadcasts these signals to every client on the system bus, and a host
     * with a built-in radio plus a dongle exports two adapters. Acting on a device under the other
     * one would mean issuing `Connect` against a controller this transport is not driving.
     */
    private fun isOnOurAdapter(path: String): Boolean {
        val adapterPath = bus.adapter()?.path ?: return false
        return path.startsWith("$adapterPath/")
    }
}
