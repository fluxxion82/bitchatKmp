package com.bitchat.bluetooth.linux

import com.bitchat.bluetooth.service.GattClientDelegate
import com.bitchat.bluetooth.service.GattClientService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.freedesktop.dbus.DBusPath
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.interfaces.DBusSigHandler
import org.freedesktop.dbus.interfaces.Properties
import org.freedesktop.dbus.matchrules.DBusMatchRuleBuilder
import org.freedesktop.dbus.types.Variant
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.min

/*
 * =============================================================================================
 * The central role, half two: dialling a peer and talking to it.
 * =============================================================================================
 *
 * The peripheral role is a tree of objects we export and BlueZ calls into. This is the mirror
 * image: a tree of objects *BlueZ* exports and we call into, and it does not exist until the link
 * does. Connecting is therefore not one operation but four, and every one of them can fail on its
 * own terms:
 *
 *     Device1.Connect()                    the ACL link, and whatever BlueZ decides that means
 *     ServicesResolved -> true             GATT discovery finished; only now do the objects exist
 *     walk the tree under the device       find *our* service, and its characteristic
 *     GattCharacteristic1.StartNotify()    subscribe, so the peer's writes reach us
 *
 * Four facts shape the code, and each of them cost time to learn:
 *
 *  1. **`Connect()` returning is not the event to wait for, and its failing is not the event to
 *     give up on.** BlueZ holds the call open for the D-Bus reply timeout -- tens of seconds -- and
 *     when it does answer, `org.bluez.Error.Failed: le-connection-abort-by-local` is what BlueZ's
 *     `att_connect_cb` prints for nearly *any* ATT connect error, so it names no cause. Worse, an
 *     error can arrive while BlueZ is still connecting underneath. So the authority on success is
 *     the `ServicesResolved` property, watched as a signal, under a deadline of our own; the
 *     `Connect()` call is raced against it rather than awaited. See [establish].
 *
 *  2. **The scan must keep running across the attempt.** Stopping discovery to free the radio is
 *     the intuitive move and it is measurably wrong on this stack: with the scan stopped, not one
 *     link came up across a dozen attempts, while the same build with an indefinite scan running
 *     connected in 1.5-3.2 s. Nothing in this file touches the discovery lease.
 *
 *  3. **The MTU comes from a property, not from a callback.** In the peripheral role BlueZ hands us
 *     `options["mtu"]` on every `WriteValue`; there is no such thing here. The remote
 *     `GattCharacteristic1` carries an `MTU` property instead, and a peer or a bluetoothd that does
 *     not publish one is not an error -- 500 is what every existing peer already assumes of us.
 *
 *  4. **BlueZ's notifications do not arrive on any flow [BlueZBus] publishes.** Its
 *     `PropertiesChanged` match rule is narrowed to `arg0='org.bluez.Device1'`, and a notification
 *     is a `PropertiesChanged` on `org.bluez.GattCharacteristic1`. So this class installs one match
 *     rule of its own on the shared connection -- it does not open a second one, which would be a
 *     second D-Bus client with its own discovery count and its own advertisement slot. See
 *     [installSignalHandler].
 */

/** The prefix every BlueZ-owned object path carries; the `path_namespace` of our match rule. */
private const val BLUEZ_PATH_NAMESPACE = "/org/bluez"

/**
 * The largest write we will emit, and the value used when no MTU is known.
 *
 * Ceiling and fallback at once, exactly as the peripheral role's constant is: 500 matches
 * `AndroidGattClientService.CHUNK_SIZE`, which is what every peer already sizes its reassembly for,
 * and MTU discovery may only ever lower it.
 */
private const val MAX_CLIENT_CHUNK = 500

/** The mandatory ATT MTU of 23 less the 3-byte ATT header. Below this a peer is talking nonsense. */
private const val MIN_CLIENT_CHUNK = 20

/** Both existing peers pace chunks this far apart; a burst without it overruns the controller. */
private const val CHUNK_DELAY_MS = 25L

/**
 * How long an attempt may run before we abandon it.
 *
 * Bounded by two measurements from the other end of this protocol. Successful connects have been
 * observed at 8.2 s, 13.8 s and 22.0 s, so a deadline under 22 s would cancel connects that were
 * about to work. And [com.bitchat.bluetooth.manager.CentralLinkPolicy]'s reaper abandons an attempt
 * at 30 s, so a deadline at or above that would let the reaper fire on attempts this class is still
 * running -- two things ending the same attempt, with the connect slot freed twice. 25 s sits in
 * the gap: this class is normally what ends an attempt, and the reaper is the backstop for the case
 * where it cannot.
 */
private const val CONNECT_DEADLINE_MS = 25_000L

/**
 * How long to wait for our own `ServicesResolved` watcher to be subscribed before dialling.
 *
 * A `SharedFlow` delivers nothing to a collector that has not subscribed yet, and `Connect()` on a
 * device BlueZ already has a link to can resolve services in single-digit milliseconds. Without
 * this the fast path is precisely the one that loses the event and then waits the full deadline for
 * a signal that already happened. Bounded so that a cancelled scope cannot hang a caller here.
 */
private const val SUBSCRIBE_TIMEOUT_MS = 2_000L

/**
 * How many times, and how far apart, the object tree is walked looking for our characteristic.
 *
 * `ServicesResolved = true` is supposed to mean the objects exist, and in practice it does; the
 * retries cover the adopted-link case, where we joined a device that was already connected and the
 * property was true before we ever looked.
 */
private const val RESOLVE_ATTEMPTS = 3
private const val RESOLVE_RETRY_MS = 300L

/** How many inbound frames may wait for the listener before one is refused. */
private const val INBOUND_QUEUE_CAPACITY = 256

/** `WriteValue(value, {"type": Variant("command")})` -- what every existing peer expects. */
private val WRITE_COMMAND_OPTIONS: Map<String, Variant<*>> =
    mapOf(OPTION_TYPE to Variant("command"))

// ---------------------------------------------------------------------------------------------
// Pure helpers
// ---------------------------------------------------------------------------------------------

/** Where our characteristic lives on a connected peer, and what it says about its MTU. */
internal data class RemoteCharacteristic(
    val path: String,
    val servicePath: String,
    val mtu: Int?
)

/**
 * Finds the bitchat characteristic under [devicePath] in a `GetManagedObjects` snapshot.
 *
 * Two joins, in this order, because neither alone is sufficient:
 *
 *  - the *service* is matched by UUID under the device's path, which is what identifies bitchat
 *    among the handful of services a phone exposes;
 *  - the *characteristic* is matched by UUID **and** by its `Service` property pointing at that
 *    service. Matching the characteristic UUID alone would be enough today and is a trap: a peer
 *    that ever exposed the same UUID under a second service would be talked to through whichever
 *    one BlueZ happened to list first.
 *
 * Every UUID comparison is case-insensitive. BlueZ reports UUIDs lower-cased while the constants
 * here are spelled upper-case to match the Android and iOS peers, so a case-sensitive compare finds
 * nothing at all -- and finds it silently, looking exactly like a peer that does not run bitchat.
 *
 * `Service` is a D-Bus `o`, so its value is a [DBusPath]; a [String] is tolerated only so that a
 * daemon which sent one is a log line rather than a total failure to ever find a characteristic.
 */
internal fun findBitchatCharacteristic(
    objects: Map<String, Map<String, Map<String, Variant<*>>>>,
    devicePath: String,
    serviceUuid: String = BITCHAT_SERVICE_UUID,
    characteristicUuid: String = BITCHAT_CHARACTERISTIC_UUID
): RemoteCharacteristic? {
    val prefix = "$devicePath/"
    val wantedService = serviceUuid.lowercase()
    val wantedCharacteristic = characteristicUuid.lowercase()

    val servicePaths = objects
        .filterKeys { it.startsWith(prefix) }
        .filter { (_, interfaces) ->
            uuidOf(interfaces[IFACE_GATT_SERVICE1]) == wantedService
        }
        .keys
    if (servicePaths.isEmpty()) return null

    objects.forEach { (path, interfaces) ->
        if (!path.startsWith(prefix)) return@forEach
        val properties = interfaces[IFACE_GATT_CHARACTERISTIC1] ?: return@forEach
        if (uuidOf(properties) != wantedCharacteristic) return@forEach
        val owningService = objectPathOf(properties["Service"]) ?: return@forEach
        if (owningService !in servicePaths) return@forEach
        return RemoteCharacteristic(
            path = path,
            servicePath = owningService,
            mtu = (properties["MTU"]?.value as? Number)?.toInt()
        )
    }
    return null
}

private fun uuidOf(properties: Map<String, Variant<*>>?): String? =
    (properties?.get("UUID")?.value as? String)?.lowercase()

private fun objectPathOf(variant: Variant<*>?): String? = when (val value = variant?.value) {
    is DBusPath -> value.path
    is String -> value
    else -> null
}

/**
 * The largest write that is safe on a link whose ATT MTU is [mtu]: `min(500, mtu - 3)`.
 *
 * Three properties, matching the peripheral role's cap exactly so that the two roles cannot end up
 * disagreeing about what a chunk is:
 *
 *  - 500 is both ceiling and fallback. A null or nonsensical MTU -- BlueZ too old to publish the
 *    property, a peer that has not negotiated -- yields 500, which is what every existing peer
 *    already assumes of us, so a node that never reads an MTU still works correctly.
 *  - Discovery may only ever *lower* the cap.
 *  - The floor stops a bogus reading producing a chunk size [BleChunker] cannot make progress on.
 *
 * The cap matters because an oversized write is truncated *silently* by the controller: the symptom
 * is large frames vanishing while small ones work, with nothing logged at either end.
 */
internal fun clientChunkCap(mtu: Int?): Int {
    if (mtu == null || mtu <= 0) return MAX_CLIENT_CHUNK
    return min(MAX_CLIENT_CHUNK, mtu - 3).coerceAtLeast(MIN_CLIENT_CHUNK)
}

/**
 * What a failed `Device1.Connect()` means for the *next* attempt.
 *
 * Taxonomy rather than a boolean because treating every error as retryable is what produces a
 * connection storm: the same address is offered again a second later, BlueZ refuses it again, and
 * the single connection initiator is spent on a peer that was never going to answer. Each value
 * below maps onto a distinct move by the link policy, and they are genuinely different moves.
 *
 * Keyed on the error *name* and text rather than on the exception, because dbus-java maps a known
 * error name onto a generated class and otherwise falls back to a bare `DBusExecutionException`
 * carrying the name in `type` -- so neither the class nor `type` alone identifies every error, and
 * a pure function over the two strings is the part worth testing.
 */
internal enum class ConnectFault {
    /** `org.bluez.Error.InProgress` -- BlueZ still owns an earlier attempt. Coalesce: do not start a second. */
    IN_PROGRESS,

    /** `org.bluez.Error.AlreadyConnected` -- adopt the link and wait for it to become usable. */
    ALREADY_CONNECTED,

    /** The device object is not there. Tolerated: the scanner will offer it again if it returns. */
    GONE,

    /** No reply. Says nothing about whether BlueZ went on to connect, so it is not a failure yet. */
    INDETERMINATE,

    /** Everything else, including `Failed: le-connection-abort-by-local`. Back off. */
    FAILED
}

/** See [ConnectFault]. */
internal fun classifyConnectFault(errorName: String, message: String): ConnectFault = when {
    errorName.endsWith(".InProgress") -> ConnectFault.IN_PROGRESS
    errorName.endsWith(".AlreadyConnected") -> ConnectFault.ALREADY_CONNECTED
    errorName.endsWith(".DoesNotExist") -> ConnectFault.GONE
    errorName.endsWith(".UnknownObject") -> ConnectFault.GONE
    errorName.endsWith(".UnknownMethod") -> ConnectFault.GONE
    errorName.endsWith(".NoReply") -> ConnectFault.INDETERMINATE
    errorName.endsWith(".Timeout") -> ConnectFault.INDETERMINATE
    // `org.bluez.Error.InProgress` is sometimes reported as a plain Failed whose text is the one
    // clue, which is why the message is inspected at all rather than only the name.
    errorName.endsWith(".Failed") && message.contains("in progress", ignoreCase = true) ->
        ConnectFault.IN_PROGRESS

    else -> ConnectFault.FAILED
}

// ---------------------------------------------------------------------------------------------
// The service
// ---------------------------------------------------------------------------------------------

/**
 * The desktop-Linux central-role GATT client.
 *
 * Owns outbound links: dialling, service resolution, subscription, chunked writes and teardown. It
 * decides nothing about *whether* to dial -- that is the connection service's link policy -- and it
 * holds no discovery lease.
 *
 * @param bus the shared connection. Everything here goes through a proxy except the one match rule
 *   in [installSignalHandler], which needs the connection itself.
 */
class LinuxGattClientService(private val bus: BlueZBus) : GattClientService {

    private val log = LoggerFactory.getLogger("bitchat.ble.client")

    /**
     * What the connection service wants to hear about. Separate from [GattClientDelegate], which is
     * the `commonMain` interface and carries no notion of a link going down or a packet arriving.
     *
     * [onDataReceived] is spelled to match `GattServerDelegate`'s so that one override in the
     * connection service serves an inbound frame whichever role it arrived on -- the mesh must not
     * have to know which.
     */
    interface Listener {
        fun onDataReceived(data: ByteArray, deviceAddress: String)
        fun onLinkDown(deviceAddress: String, reason: String)
    }

    /** One outbound link, and everything cached about it. */
    private class Link(val address: String, val devicePath: String) {

        @Volatile
        var characteristicPath: String? = null

        @Volatile
        var servicePath: String? = null

        @Volatile
        var mtu: Int? = null

        @Volatile
        var ready: Boolean = false

        /**
         * Serializes writes to this peer.
         *
         * A frame is a *sequence* of chunks with a marked start and end, so two frames interleaved
         * on one link do not produce two frames at the other end -- they produce one dropped
         * reassembly and one orphan continuation. Per link rather than global, because two peers'
         * writes have no reason to wait for each other.
         */
        val writeGate = Mutex()

        /** Guarded by [writeGate]; rebuilt when the cap moves, which in practice is once. */
        var chunker = BleChunker(MAX_CLIENT_CHUNK)
        var chunkSize = MAX_CLIENT_CHUNK
    }

    /** Guards [scope], [signalHandler] and [handlerConnection]. Never taken on a D-Bus thread. */
    private val lifecycle = Mutex()

    private var scope: CoroutineScope? = null

    private var signalHandler: AutoCloseable? = null

    /** The connection the handler was installed on, so a reconnect of our socket re-installs it. */
    private var handlerConnection: DBusConnection? = null

    /**
     * Read by the signal handler as its liveness check, so it has to be visible without the lock.
     */
    @Volatile
    private var running = false

    private val links = ConcurrentHashMap<String, Link>()

    /**
     * Characteristic object path to device address.
     *
     * The signal handler's whole job is this lookup: a `PropertiesChanged` on
     * `org.bluez.GattCharacteristic1` names a path and nothing else, and every other application's
     * subscriptions arrive on the same thread. A miss here is the fast rejection.
     */
    private val notifyPaths = ConcurrentHashMap<String, String>()

    /** Inbound reassembly, keyed by device address, shared across links. */
    private val chunker = BleChunker()

    /**
     * Reassembled frames on their way to the listener.
     *
     * The hop that keeps the signal thread free. A listener is the mesh's packet processor: it
     * decrypts and it may answer, and answering means an outbound D-Bus call -- which would then be
     * issued from inside the dispatch of the signal that triggered it.
     */
    private val inbound = kotlinx.coroutines.channels.Channel<Frame>(INBOUND_QUEUE_CAPACITY)

    private class Frame(val bytes: ByteArray, val address: String)

    @Volatile
    private var listener: Listener? = null

    @Volatile
    private var delegate: GattClientDelegate? = null

    private val connectsStarted = AtomicLong()
    private val connectsReady = AtomicLong()
    private val notificationsReceived = AtomicLong()
    private val framesDelivered = AtomicLong()
    private val inboundDropped = AtomicLong()
    private val writesSent = AtomicLong()
    private val writesFailed = AtomicLong()

    /** How a connection attempt ended, in the terms the link policy reasons in. */
    enum class ConnectOutcome {
        /** Link up, our characteristic found, notifications subscribed. */
        READY,

        /** BlueZ still owns an earlier attempt to this address. Free the slot and back off hard. */
        IN_PROGRESS,

        /** The attempt failed or ran out of deadline. Ordinary backoff. */
        FAILED,

        /**
         * A link came up and resolved, and carries no bitchat GATT service at all.
         *
         * Distinct from [FAILED] because retrying changes nothing: the same device record produces
         * the same non-link every time. Measured on a dual-mode Android peer -- it advertises our
         * service over LE, but `Device1.Connect()` connected it over **BR/EDR** instead, answered
         * `org.bluez.Error.Failed: br-connection-profile-unavailable`, and then set
         * `ServicesResolved = true` off the *SDP* browse. Everything looks like a resolved link and
         * there is no GATT database under it. Two attempts, 40 s of the single connection
         * initiator, identical both times.
         *
         * BlueZ's D-Bus API has no way to ask `Connect()` for a transport, so the only lever is the
         * device record itself: `Adapter1.RemoveDevice` drops the cached BR/EDR knowledge, and the
         * object the next LE advertisement re-creates has nothing but LE to connect over. Hence a
         * separate outcome -- the caller's response to this one is to clear the record now rather
         * than to back off and try the same thing again.
         */
        NO_SERVICE,

        /** BlueZ has no device object for this address. Nothing to dial; wait to be offered again. */
        GONE,

        /** No bus, no adapter, or no link could be built at all. */
        UNAVAILABLE
    }

    fun setListener(listener: Listener) {
        this.listener = listener
    }

    override fun setDelegate(delegate: GattClientDelegate) {
        this.delegate = delegate
    }

    // -----------------------------------------------------------------------------------------
    // Connecting
    // -----------------------------------------------------------------------------------------

    /**
     * Dials [deviceAddress] and does not return until the link is usable, has failed, or the
     * deadline has passed.
     *
     * Never throws. Every failure is a [ConnectOutcome] so that the caller's link policy can tell
     * them apart -- coalesce, adopt, clear the record, back off -- because they call for different
     * moves. An exception here would collapse all of them into one.
     */
    suspend fun connect(deviceAddress: String): ConnectOutcome {
        ensureRunning()

        links[deviceAddress]?.let { existing ->
            if (existing.ready) {
                log.debug("already linked to {}", deviceAddress)
                return ConnectOutcome.READY
            }
        }

        val devicePath = bus.devicePath(deviceAddress)
        if (devicePath == null) {
            log.warn("cannot dial {}: no adapter, or not an address BlueZ could name", deviceAddress)
            return ConnectOutcome.UNAVAILABLE
        }

        val link = Link(deviceAddress, devicePath)
        links[deviceAddress] = link
        connectsStarted.incrementAndGet()

        val outcome = try {
            establish(link)
        } catch (t: Throwable) {
            log.error("dialling {} threw", deviceAddress, t)
            ConnectOutcome.FAILED
        }

        if (outcome != ConnectOutcome.READY) {
            links.remove(deviceAddress, link)
            forgetLinkState(link)
        }
        return outcome
    }

    /**
     * The attempt itself: watch, dial, race, resolve, subscribe.
     *
     * The watcher is subscribed **before** `Connect()` is issued, and that ordering is the whole
     * reason this is not three sequential calls. `deviceStateChanges` is a `SharedFlow` with no
     * replay, so a `ServicesResolved` that fires between the dial and the subscription is simply
     * gone -- and on a device BlueZ already has a link to, that gap is exactly where the event
     * lands. The attempt would then sit out its entire deadline waiting for something that had
     * already happened.
     *
     * The dial itself runs on the *service* scope rather than as a child of this call. `Connect()`
     * is a blocking D-Bus round trip that BlueZ can hold for tens of seconds and that no
     * cancellation can interrupt; as a child it would keep this function from returning long after
     * its deadline had passed. Launched on the outer scope it outlives the attempt, and completing
     * an already-completed [CompletableDeferred] is a no-op, so a late answer is harmless.
     */
    private suspend fun establish(link: Link): ConnectOutcome {
        val scope = this.scope ?: return ConnectOutcome.UNAVAILABLE
        val device = bus.proxy(link.devicePath, Device1::class.java)
        if (device == null) {
            log.warn("no Device1 proxy at {}", link.devicePath)
            return ConnectOutcome.GONE
        }

        val signal = CompletableDeferred<ConnectOutcome>()
        val subscribed = CompletableDeferred<Unit>()

        val watcher = scope.launch {
            bus.deviceStateChanges
                .onSubscription { subscribed.complete(Unit) }
                .collect { event ->
                    if (event.path != link.devicePath) return@collect
                    when {
                        event.changed["ServicesResolved"]?.value == true ->
                            signal.complete(ConnectOutcome.READY)
                        // A link that comes up and goes straight back down is the common failure
                        // shape when the peer is at its own central-link cap. Ending the attempt
                        // here rather than at the deadline frees the initiator 20 s sooner.
                        event.connected == false -> signal.complete(ConnectOutcome.FAILED)
                    }
                }
        }
        val removals = scope.launch {
            bus.interfacesRemoved.collect { event ->
                if (event.path == link.devicePath && IFACE_DEVICE1 in event.interfaces) {
                    signal.complete(ConnectOutcome.GONE)
                }
            }
        }

        try {
            withTimeoutOrNull(SUBSCRIBE_TIMEOUT_MS) { subscribed.await() }
                ?: log.warn("the ServicesResolved watcher for {} did not subscribe in time", link.address)

            if (readServicesResolved(device)) {
                // Already usable. Common when BlueZ is holding a link it opened for its own reasons,
                // and the case the subscription ordering above exists to make winnable.
                log.info("{} already has services resolved; adopting the link", link.address)
                signal.complete(ConnectOutcome.READY)
            } else {
                scope.launch(Dispatchers.IO) { dial(device, link, signal) }
            }

            val outcome = withTimeoutOrNull(CONNECT_DEADLINE_MS) { signal.await() }
            if (outcome == null) {
                log.warn(
                    "abandoning the attempt to {}: no ServicesResolved within {}ms",
                    link.address,
                    CONNECT_DEADLINE_MS
                )
                return ConnectOutcome.FAILED
            }
            if (outcome != ConnectOutcome.READY) return outcome
        } finally {
            watcher.cancel()
            removals.cancel()
        }

        return finishLink(link)
    }

    /**
     * `Device1.Connect()`, on `Dispatchers.IO`, classifying whatever comes back.
     *
     * Note what does *not* complete the signal: a successful return whose `ServicesResolved` reads
     * false, and an indeterminate error. Both mean "BlueZ has not said no", and BlueZ carries on
     * connecting after either -- so the attempt keeps waiting for the property, and it is the
     * deadline, not the call, that ends it.
     */
    private fun dial(device: Device1, link: Link, signal: CompletableDeferred<ConnectOutcome>) {
        log.info("Connect() -> {} at {}", link.address, link.devicePath)
        try {
            device.Connect()
            // BlueZ documents Connect() as returning once services are resolved, but a return with
            // the property still false has been seen on adopted links; trust the property.
            if (readServicesResolved(device)) {
                signal.complete(ConnectOutcome.READY)
            } else {
                log.debug("Connect() to {} returned with ServicesResolved=false; still waiting", link.address)
            }
        } catch (t: Throwable) {
            val name = dbusErrorName(t)
            val text = dbusErrorText(t)
            when (classifyConnectFault(name, t.message.orEmpty())) {
                ConnectFault.IN_PROGRESS -> {
                    log.info("BlueZ still owns an attempt to {}: {}", link.address, text)
                    signal.complete(ConnectOutcome.IN_PROGRESS)
                }

                ConnectFault.ALREADY_CONNECTED -> {
                    log.info("{} is already connected; adopting and waiting for readiness", link.address)
                    if (readServicesResolved(device)) signal.complete(ConnectOutcome.READY)
                }

                ConnectFault.GONE -> {
                    log.info("BlueZ has no device object for {}: {}", link.address, text)
                    signal.complete(ConnectOutcome.GONE)
                }

                ConnectFault.INDETERMINATE -> log.warn(
                    "Connect() to {} got no reply; BlueZ may still be connecting: {}",
                    link.address,
                    text
                )

                ConnectFault.FAILED -> {
                    log.warn("Connect() to {} failed: {}", link.address, text)
                    signal.complete(ConnectOutcome.FAILED)
                }
            }
        }
    }

    /**
     * `Device1.ServicesResolved`, blocking, never throwing.
     *
     * A `Properties.Get` round trip. False on any error, which is the right default: an error here
     * means the object is not answering, and an attempt that treats that as "resolved" would go on
     * to look for a characteristic that cannot exist.
     */
    private fun readServicesResolved(device: Device1): Boolean = try {
        device.servicesResolved
    } catch (t: Throwable) {
        log.debug("reading ServicesResolved failed: {}", dbusErrorText(t))
        false
    }

    /**
     * The half of the connect that happens *after* the link: find our characteristic, learn the
     * MTU, subscribe.
     *
     * A link with no bitchat characteristic on it is not a usable link, and saying so is what stops
     * the connection service counting it against its central-link budget for the next minute. It is
     * reported as [ConnectOutcome.NO_SERVICE] rather than a plain failure because it is the one
     * failure that retrying cannot fix -- see that value's KDoc for the dual-mode BR/EDR case that
     * produces it.
     */
    private suspend fun finishLink(link: Link): ConnectOutcome {
        val found = resolveCharacteristic(link)
        if (found == null) {
            log.warn(
                "{} resolved services but exposes no bitchat GATT service under {} -- BlueZ has " +
                    "most likely connected it on the wrong transport; dropping the link",
                link.address,
                link.devicePath
            )
            disconnect(link.address)
            return ConnectOutcome.NO_SERVICE
        }

        link.servicePath = found.servicePath
        link.characteristicPath = found.path
        link.mtu = found.mtu
        val cap = clientChunkCap(found.mtu)
        link.chunker = BleChunker(cap)
        link.chunkSize = cap

        if (!startNotify(found.path, link.address)) {
            // Not fatal on its own -- we can still write -- but a link we cannot hear on is half a
            // link, and the mesh's handshake needs both directions. Reported, and kept.
            log.warn("StartNotify on {} failed; {} will be write-only", found.path, link.address)
        }

        /*
         * The link may have gone while we were resolving it -- the peer walked away between
         * `ServicesResolved` and `StartNotify`, and `onLinkLost` has already dropped it and told the
         * listener. Marking it ready now would leave the caller's link policy holding an
         * `established` entry for a link that nothing will ever release, permanently spending one of
         * the two outbound slots. Identity, not presence: a second attempt to the same address would
         * have replaced the entry with a different Link.
         */
        if (links[link.address] !== link) {
            log.info("the link to {} went away while it was being resolved", link.address)
            return ConnectOutcome.FAILED
        }

        notifyPaths[found.path] = link.address
        link.ready = true
        connectsReady.incrementAndGet()
        log.info(
            "link to {} is usable: characteristic {} (MTU {}, chunk cap {}B)",
            link.address,
            found.path,
            found.mtu ?: "unreported",
            cap
        )
        return ConnectOutcome.READY
    }

    /**
     * Walks the managed-objects tree under the device, a few times.
     *
     * `GetManagedObjects` rather than a `Device1`-scoped introspection because BlueZ has no
     * per-device object manager: the whole tree is the only listing there is, and the device path
     * prefix is what scopes it.
     */
    private suspend fun resolveCharacteristic(link: Link): RemoteCharacteristic? {
        repeat(RESOLVE_ATTEMPTS) { attempt ->
            val objects = withContext(Dispatchers.IO) { bus.managedObjects() }
            val found = findBitchatCharacteristic(objects, link.devicePath)
            if (found != null) return found
            if (attempt < RESOLVE_ATTEMPTS - 1) {
                log.debug(
                    "no bitchat characteristic under {} yet (attempt {}/{})",
                    link.devicePath,
                    attempt + 1,
                    RESOLVE_ATTEMPTS
                )
                delay(RESOLVE_RETRY_MS)
            }
        }
        return null
    }

    private suspend fun startNotify(characteristicPath: String, address: String): Boolean =
        withContext(Dispatchers.IO) {
            val characteristic = bus.proxy(characteristicPath, GattCharacteristic1::class.java)
                ?: return@withContext false
            try {
                characteristic.StartNotify()
                log.info("subscribed to notifications from {} at {}", address, characteristicPath)
                true
            } catch (t: Throwable) {
                // BlueZ answers a second subscribe with InProgress, and that is a success: somebody
                // -- us, on a previous attempt to the same peer -- is already notifying.
                if (classifyConnectFault(dbusErrorName(t), t.message.orEmpty()) == ConnectFault.IN_PROGRESS) {
                    log.debug("{} was already notifying", characteristicPath)
                    return@withContext true
                }
                log.warn("StartNotify on {} failed: {}", characteristicPath, dbusErrorText(t))
                false
            }
        }

    // -----------------------------------------------------------------------------------------
    // GattClientService
    // -----------------------------------------------------------------------------------------

    /**
     * Writes [data] to [deviceAddress], chunked to the link's cap, without response.
     *
     * `type=command` is not a preference. It is a write-without-response, which is what the
     * characteristic's `write-without-response` flag permits and what every existing peer uses; a
     * `request` write would be rejected by a peer whose flags do not allow it, and would cost a
     * round trip per chunk on one that does.
     */
    override suspend fun writeCharacteristic(deviceAddress: String, data: ByteArray): Boolean {
        val link = links[deviceAddress]
        if (link == null || !link.ready) {
            log.warn("write of {}B to {} refused: no ready link", data.size, deviceAddress)
            delegate?.onWriteFailure(deviceAddress, "no ready link")
            return false
        }
        val characteristicPath = link.characteristicPath
        if (characteristicPath == null) {
            log.warn("write of {}B to {} refused: the characteristic path was invalidated", data.size, deviceAddress)
            delegate?.onWriteFailure(deviceAddress, "no characteristic")
            return false
        }
        val characteristic = bus.proxy(characteristicPath, GattCharacteristic1::class.java)
        if (characteristic == null) {
            delegate?.onWriteFailure(deviceAddress, "no proxy")
            return false
        }

        return link.writeGate.withLock {
            val chunks = link.chunker.chunk(data)
            if (chunks.size > 1) {
                log.debug("writing {}B to {} in {} chunks", data.size, deviceAddress, chunks.size)
            }
            chunks.forEachIndexed { index, chunk ->
                val failure = withContext(Dispatchers.IO) {
                    runCatching { characteristic.WriteValue(chunk, WRITE_COMMAND_OPTIONS) }.exceptionOrNull()
                }
                if (failure != null) {
                    writesFailed.incrementAndGet()
                    log.warn(
                        "write to {} failed at chunk {}/{}: {}",
                        deviceAddress,
                        index + 1,
                        chunks.size,
                        dbusErrorText(failure)
                    )
                    delegate?.onWriteFailure(deviceAddress, dbusErrorText(failure))
                    // A write failing because the object is gone means the link is gone; anything
                    // else may be transient and leaves the link alone for the next frame to try.
                    if (classifyConnectFault(dbusErrorName(failure), failure.message.orEmpty()) ==
                        ConnectFault.GONE
                    ) {
                        onLinkLost(deviceAddress, "write to a device object that is gone")
                    }
                    return@withLock false
                }
                writesSent.incrementAndGet()
                // Pacing, not politeness: both peers space chunks this far apart, and a burst
                // without it overruns the controller's transmit queue and loses the tail.
                if (index < chunks.lastIndex) delay(CHUNK_DELAY_MS)
            }
            delegate?.onWriteSuccess(deviceAddress)
            true
        }
    }

    /**
     * Drops the link to [deviceAddress].
     *
     * Unlike the peripheral role, this one genuinely can hang up: the link is ours, we opened it,
     * and `Device1.Disconnect` ends it. `StopNotify` first so BlueZ is not left holding a
     * subscription against a device it is about to disconnect; both are best-effort, because the
     * common reason to call this is that the link is already gone.
     */
    override suspend fun disconnect(deviceAddress: String) {
        val link = links.remove(deviceAddress) ?: run {
            log.debug("disconnect({}): no such link", deviceAddress)
            return
        }
        withContext(Dispatchers.IO) {
            link.characteristicPath?.let { path ->
                runCatching { bus.proxy(path, GattCharacteristic1::class.java)?.StopNotify() }
                    .onFailure { log.debug("StopNotify on {}: {}", path, dbusErrorText(it)) }
            }
            runCatching { bus.proxy(link.devicePath, Device1::class.java)?.Disconnect() }
                .onSuccess { log.info("disconnected from {}", deviceAddress) }
                .onFailure { log.debug("Disconnect on {}: {}", link.devicePath, dbusErrorText(it)) }
        }
        forgetLinkState(link)
    }

    override suspend fun disconnectAll() {
        val addresses = links.keys.toList()
        if (addresses.isEmpty()) return
        log.info("dropping {} outbound link(s)", addresses.size)
        addresses.forEach { disconnect(it) }
    }

    // -----------------------------------------------------------------------------------------
    // Central-role extras used by the connection service
    // -----------------------------------------------------------------------------------------

    /** The outbound links a broadcast can actually be written to. */
    fun readyAddresses(): Set<String> =
        links.entries.filter { it.value.ready }.map { it.key }.toSet()

    fun isReady(deviceAddress: String): Boolean = links[deviceAddress]?.ready == true

    /**
     * `Adapter1.RemoveDevice` -- BlueZ's only way to clear a wedged device record.
     *
     * **Never call this indiscriminately.** `RemoveDevice` discards the device object *and its
     * pairing data*, so aimed at the wrong address it unpairs the user's headphones, their mouse or
     * their car. The caller's contract is that the address was discovered through our own
     * service-UUID discovery filter -- i.e. it is a bitchat advertiser and nothing else -- and that
     * repeated attempts to it have failed. What it buys in that case is real: a `Device1` BlueZ has
     * cached in a bad state is re-created clean on the next advertisement, which is often the
     * difference between a peer that never connects again and one that connects immediately.
     */
    suspend fun forgetDevice(deviceAddress: String): Boolean {
        val devicePath = bus.devicePath(deviceAddress) ?: return false
        val adapterPath = (bus.status.value as? BlueZStatus.Available)?.adapterPath ?: return false
        val adapter = bus.proxy(adapterPath, Adapter1::class.java) ?: return false
        return withContext(Dispatchers.IO) {
            try {
                adapter.RemoveDevice(DBusPath(devicePath))
                log.info("asked BlueZ to forget {} so it is re-created on the next advertisement", deviceAddress)
                true
            } catch (t: Throwable) {
                // DoesNotExist is the ordinary answer when BlueZ dropped it first, which is the
                // outcome we wanted anyway.
                if (isDoesNotExist(t)) {
                    log.debug("RemoveDevice({}): BlueZ had already dropped it", devicePath)
                    true
                } else {
                    log.warn("RemoveDevice({}) failed: {}", devicePath, dbusErrorText(t))
                    false
                }
            }
        }
    }

    /** One line of counters for the transport's health log. */
    fun statusLine(): String {
        val stats = chunker.stats
        return "client: links=${links.size} ready=${readyAddresses().size}" +
            " dialled=${connectsStarted.get()} established=${connectsReady.get()}" +
            " notifications=${notificationsReceived.get()}" +
            " frames reassembled=${stats.framesReassembled} dropped=${stats.framesDropped}" +
            " delivered=${framesDelivered.get()} queue-dropped=${inboundDropped.get()}" +
            " writes=${writesSent.get()} failed=${writesFailed.get()}"
    }

    // -----------------------------------------------------------------------------------------
    // Lifecycle, signals and inbound
    // -----------------------------------------------------------------------------------------

    /**
     * Starts the collectors and makes sure our one match rule is installed on the *current*
     * connection.
     *
     * The connection identity is checked every time rather than once, because [BlueZBus.stop]
     * followed by a restart produces a new `DBusConnection` on which our old handler is not
     * installed -- and a client that stops receiving notifications keeps writing happily, so
     * nothing else would ever notice.
     */
    private suspend fun ensureRunning() = lifecycle.withLock {
        if (scope == null) {
            val clientScope = CoroutineScope(
                SupervisorJob() + Dispatchers.IO + CoroutineName("bitchat-ble-client")
            )
            scope = clientScope
            running = true
            clientScope.launch { drainInbound() }
            clientScope.launch { watchLinkState() }
            clientScope.launch { watchRemovals() }
        }

        val connection = bus.connection()
        if (connection != null && connection !== handlerConnection) {
            runCatching { signalHandler?.close() }
            signalHandler = installSignalHandler(connection)
            handlerConnection = connection
        }
    }

    /**
     * The one match rule this class owns: `PropertiesChanged` for `org.bluez.GattCharacteristic1`.
     *
     * [BlueZBus] cannot publish these. Its own rule narrows on `arg0='org.bluez.Device1'`, which is
     * right for what it does and excludes exactly the signal a notification is. Widening it there
     * would push the busiest signal on the bus -- one per notification per subscribed
     * characteristic, ours and every other application's -- through a shared flow that four other
     * collectors are on.
     *
     * No sender is named, for the reason stated at the top of [BlueZBus]: dbus-java refuses a
     * well-known bus name as a signal source, and pinning bluetoothd's unique name would stop
     * matching the moment it restarts. `path_namespace` and `arg0` narrow it instead, and the
     * handler re-checks the path against [notifyPaths] anyway.
     */
    private fun installSignalHandler(connection: DBusConnection): AutoCloseable? {
        val rule = DBusMatchRuleBuilder.create()
            .withType(Properties.PropertiesChanged::class.java)
            .withPathNamespace(BLUEZ_PATH_NAMESPACE)
            .withArg0123(0, IFACE_GATT_CHARACTERISTIC1)
            .build()
        return try {
            val handle = connection.addSigHandler(
                rule,
                DBusSigHandler<Properties.PropertiesChanged> { signal -> onNotification(signal) }
            )
            log.debug("watching {}", rule)
            handle
        } catch (t: Throwable) {
            log.error("could not watch characteristic notifications: {}", dbusErrorText(t))
            null
        }
    }

    /**
     * A notification, on dbus-java's single signal thread.
     *
     * Everything here is bounded and non-blocking: two map lookups, a `memcpy` under [BleChunker]'s
     * lock, and at most one `trySend`. In particular there is no D-Bus call and no `runBlocking` --
     * this thread also carries disconnect detection and the peripheral role's link events, and
     * anything it waits on stalls the entire transport.
     */
    private fun onNotification(signal: Properties.PropertiesChanged) {
        if (!running) return
        if (signal.interfaceName != IFACE_GATT_CHARACTERISTIC1) return
        val path = signal.path ?: return
        // The fast rejection: every other application's subscriptions land here too.
        val address = notifyPaths[path] ?: return
        val value = signal.propertiesChanged["Value"] ?: return
        val bytes = asBytes(value.value)
        if (bytes == null) {
            log.warn("notification on {} carried a {} rather than bytes", path, value.sig)
            return
        }
        notificationsReceived.incrementAndGet()
        val frame = chunker.receive(address, bytes) ?: return
        if (inbound.trySend(Frame(frame, address)).isSuccess) return
        inboundDropped.incrementAndGet()
        log.warn("inbound queue is full ({}); dropped a {}B frame from {}", INBOUND_QUEUE_CAPACITY, frame.size, address)
    }

    /**
     * A D-Bus `ay` is a `byte[]`, and dbus-java delivers it as one.
     *
     * The `List` branch is tolerance, not expectation: a marshaller that handed back a list of
     * boxed bytes would otherwise be a silent total failure of the inbound path rather than one
     * line in the log.
     */
    private fun asBytes(value: Any?): ByteArray? = when (value) {
        is ByteArray -> value
        is List<*> -> {
            val out = ByteArray(value.size)
            var ok = true
            for (index in value.indices) {
                val element = value[index] as? Number
                if (element == null) {
                    ok = false
                    break
                }
                out[index] = element.toByte()
            }
            if (ok) out else null
        }

        else -> null
    }

    /** The single ordered path from the radio to the listener. */
    private suspend fun drainInbound() {
        for (frame in inbound) {
            val target = listener
            if (target == null) {
                log.debug("dropping a {}B frame from {}: no listener", frame.bytes.size, frame.address)
                continue
            }
            framesDelivered.incrementAndGet()
            runCatching {
                target.onDataReceived(frame.bytes, frame.address)
                delegate?.onCharacteristicRead(frame.address, frame.bytes)
            }.onFailure { log.error("the listener threw on a frame from {}", frame.address, it) }
        }
    }

    /** `Connected = false` on a link we hold -- one of the two ways an outbound link ends. */
    private suspend fun watchLinkState() {
        bus.deviceStateChanges.collect { event ->
            if (event.connected != false) return@collect
            val link = links[event.address] ?: return@collect
            if (link.devicePath != event.path) return@collect
            onLinkLost(event.address, "Connected=false")
        }
    }

    /**
     * The other way, and the reason cached object paths have to be re-validated rather than
     * trusted: BlueZ drops the device object entirely.
     *
     * That is what an Android peer rotating its resolvable private address looks like, and it
     * arrives with no `Connected = false` beforehand. Every path this class caches -- device,
     * service, characteristic -- lives under the removed one, so all of them are stale at that
     * moment; a write against the characteristic path afterwards is a call into an object that no
     * longer exists. The service and characteristic can also be removed *without* the device, which
     * is what a peer restarting its GATT database looks like; that leaves the link up and the
     * cached paths wrong, so it is handled separately.
     */
    private suspend fun watchRemovals() {
        bus.interfacesRemoved.collect { event ->
            if (IFACE_DEVICE1 in event.interfaces) {
                val address = event.deviceAddress ?: return@collect
                val link = links[address] ?: return@collect
                if (link.devicePath == event.path) onLinkLost(address, "InterfacesRemoved")
                return@collect
            }
            if (IFACE_GATT_CHARACTERISTIC1 !in event.interfaces &&
                IFACE_GATT_SERVICE1 !in event.interfaces
            ) {
                return@collect
            }
            val address = notifyPaths[event.path]
                ?: links.values.firstOrNull { it.servicePath == event.path }?.address
                ?: return@collect
            val link = links[address] ?: return@collect
            log.warn(
                "BlueZ removed {} from under {}; the link's cached paths are stale",
                event.path,
                address
            )
            link.ready = false
            link.characteristicPath?.let { notifyPaths.remove(it) }
            link.characteristicPath = null
            link.servicePath = null
            onLinkLost(address, "the GATT objects were removed")
        }
    }

    /** A link ended for a reason we did not choose. Tell the listener exactly once. */
    private fun onLinkLost(address: String, reason: String) {
        val link = links.remove(address) ?: return
        forgetLinkState(link)
        log.info("outbound link to {} is gone ({}); {} left", address, reason, links.size)
        listener?.onLinkDown(address, reason)
    }

    private fun forgetLinkState(link: Link) {
        link.ready = false
        link.characteristicPath?.let { notifyPaths.remove(it) }
        chunker.forget(link.address)
    }
}
