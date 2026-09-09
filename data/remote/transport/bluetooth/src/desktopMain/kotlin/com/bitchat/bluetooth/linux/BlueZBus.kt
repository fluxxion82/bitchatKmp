package com.bitchat.bluetooth.linux

import com.bitchat.bluetooth.manager.BlueZObjectPath
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.freedesktop.dbus.connections.IDisconnectCallback
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.interfaces.DBus
import org.freedesktop.dbus.interfaces.DBusInterface
import org.freedesktop.dbus.interfaces.DBusSigHandler
import org.freedesktop.dbus.interfaces.ObjectManager
import org.freedesktop.dbus.interfaces.Properties
import org.freedesktop.dbus.matchrules.DBusMatchRule
import org.freedesktop.dbus.matchrules.DBusMatchRuleBuilder
import org.freedesktop.dbus.types.Variant
import org.slf4j.LoggerFactory
import java.io.IOException
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/*
 * =============================================================================================
 * BlueZBus -- the single owner of the D-Bus connection for the desktop-Linux BLE transport.
 * =============================================================================================
 *
 * One process should hold exactly one connection to the system bus, because almost everything
 * BlueZ tracks is scoped to the *D-Bus client*, not to the adapter: the discovery reference
 * count, the discovery filter, which advertisement slot is ours, and which GATT application is
 * registered. Two connections in one process are two clients, and they fight each other in ways
 * that look like BlueZ misbehaving.
 *
 * So this class owns the connection and the facts derived from it -- which adapter, whether
 * bluetoothd is on the bus, what the object tree currently holds -- and publishes them. It calls
 * into no service of ours. Advertising, GATT and scanning subscribe to it and ask it for proxies.
 * The arrow points one way on purpose: those services need the bus to exist before they can do
 * anything, and the bus needs to survive all three of them restarting, so any call in the other
 * direction is a dependency cycle waiting to be discovered at Koin graph-construction time.
 *
 * Three things about dbus-java 5.2.0 shape most of what follows, and all three were verified
 * against the jar and against live BlueZ rather than assumed:
 *
 *  1. The METHODCALL receiving pool defaults to four threads (ReceivingServiceConfig's field
 *     initialisers: signal=1, error=1, methodCall=4, methodReturn=1). Four threads means BlueZ's
 *     `Properties.GetAll` and `ObjectManager.GetManagedObjects` -- which it issues *while*
 *     RegisterAdvertisement/RegisterApplication are still in flight -- can be answered out of the
 *     order it asked them in. There is documented precedent of that breaking registration, and
 *     nothing we export needs concurrency, so the pool is pinned to one thread in [start].
 *
 *  2. The SIGNAL pool is a single thread (`DBus-Signal-Receiver-1`, observed live) and it carries
 *     everything we have matched, which for `org.bluez` includes an RSSI update per advertisement
 *     per nearby device. Anything a signal handler blocks on stalls disconnect detection and
 *     inbound data for the entire transport. Every handler below therefore does nothing but a
 *     non-blocking emit -- see the note on overflow policy above [deviceStateChanges].
 *
 *  3. `addSigHandler(Class, source, handler)` rejects a well-known bus name as the signal source:
 *     `InvalidBusNameException: Cannot watch for signals based on well known bus name as source.
 *     Only unique names supported`. So a rule cannot say `sender='org.bluez'`, and pinning
 *     bluetoothd's *unique* name instead would silently stop matching the moment bluetoothd
 *     restarts -- which is precisely the event this class exists to survive. The rules below carry
 *     no sender at all and narrow on path/interface/arg0 instead, which is stable across a
 *     restart, and the handlers re-check the object path.
 */

/** The bus name everything in this file talks to. */
private const val BLUEZ_BUS = "org.bluez"

/** BlueZ's root ObjectManager, and the path it emits InterfacesAdded/Removed from. */
private const val BLUEZ_ROOT = "/"

/**
 * The prefix every BlueZ-owned object path carries, used to reject the other services' ObjectManager
 * signals. Spelled without a trailing slash because it doubles as the `path_namespace` of the
 * `Device1` match rule, which the daemon wants that way.
 */
private const val BLUEZ_ROOT_PREFIX = "/org/bluez"

/**
 * `-Dble.adapter=hci0` (or `-Dble.adapter=/org/bluez/hci0`) pins the adapter.
 *
 * Machines with a built-in radio plus a USB dongle are common enough, and the interesting adapter
 * is rarely the first one BlueZ happens to list. Nothing here hardcodes `hci0`: absent the
 * property the adapter is whichever `org.bluez.Adapter1` sorts first, which at least makes the
 * choice reproducible between runs.
 */
private const val ADAPTER_PROPERTY = "ble.adapter"

/**
 * The `Device1` properties that describe the state of a *link* rather than of an advertisement.
 *
 * The split matters because it decides which of the two device flows an event lands on, and those
 * two flows have deliberately different tolerances for loss. See [BlueZBus.deviceStateChanges].
 */
private val DEVICE_STATE_KEYS = setOf("Connected", "ServicesResolved", "Paired", "Bonded")

/**
 * What the transport is allowed to assume about BlueZ right now.
 *
 * [Available.owner] is bluetoothd's unique bus name. It is part of the state, and not merely
 * diagnostic, because it is how a subscriber tells "BlueZ has been up all along" from "bluetoothd
 * restarted under us". The distinction is invisible from anything else: our exported objects
 * survive a bluetoothd restart untouched, while every registration we made against BlueZ --
 * advertisement, GATT application, discovery -- is gone. A service that re-registers whenever it
 * sees an [Available] with an owner it has not seen before recovers correctly; one that waits for
 * an error waits forever, because BlueZ simply never calls it again.
 */
sealed interface BlueZStatus {

    /** [BlueZBus.start] has not been called, or [BlueZBus.stop] has. */
    data object Stopped : BlueZStatus

    /** The system bus or bluetoothd is not reachable. Never thrown -- always reported. */
    data class Unavailable(val reason: String) : BlueZStatus

    /** bluetoothd is on the bus at [owner] and [adapterPath] is usable. */
    data class Available(
        val owner: String,
        val adapterPath: String,
        val adapterAddress: String
    ) : BlueZStatus
}

/** The adapter this transport is bound to, as read out of the managed-objects tree. */
data class BlueZAdapter(val path: String, val address: String)

/**
 * One entry of `GetManagedObjects`: an object path and, per D-Bus interface name, its properties.
 *
 * Kept as raw [Variant]s rather than mapped into a typed device model, because the bus has no
 * business deciding which of BlueZ's forty-odd `Device1` properties matter to a scanner.
 */
data class BlueZObject(
    val path: String,
    val interfaces: Map<String, Map<String, Variant<*>>>
) {
    /** The properties of [interfaceName], or an empty map when the object does not carry it. */
    fun properties(interfaceName: String): Map<String, Variant<*>> =
        interfaces[interfaceName].orEmpty()
}

/** An `org.freedesktop.DBus.ObjectManager.InterfacesAdded` naming an object under `/org/bluez`. */
data class BlueZInterfacesAdded(
    val path: String,
    val interfaces: Map<String, Map<String, Variant<*>>>
) {
    /** Non-null when the added object is a device, which is the only case a scanner cares about. */
    val deviceAddress: String? get() = BlueZObjectPath.deviceAddress(path)
}

/**
 * An `InterfacesRemoved`. For a `Device1` this is one of the two ways a link ends -- it is what an
 * Android peer rotating its resolvable private address looks like, and it arrives with no
 * `Connected = false` beforehand.
 */
data class BlueZInterfacesRemoved(
    val path: String,
    val interfaces: List<String>
) {
    val deviceAddress: String? get() = BlueZObjectPath.deviceAddress(path)
}

/**
 * A `PropertiesChanged` for `org.bluez.Device1`.
 *
 * [address] is resolved from the object path rather than from a `Device1.Address` read: the signal
 * does not carry the address, and a `Properties.Get` from the signal thread would be a blocking
 * round trip on the one thread that must never block. It comes from
 * [BlueZObjectPath.deviceAddress] so that every consumer spells a peer the same way the peripheral
 * role in `linuxMain` does.
 */
data class BlueZDeviceProperties(
    val path: String,
    val address: String,
    val changed: Map<String, Variant<*>>,
    val invalidated: List<String>
) {
    /**
     * `false` when this event reports the link going down, `true` when it reports it coming up,
     * null when it says nothing about the link at all.
     */
    val connected: Boolean? get() = changed["Connected"]?.value as? Boolean
}

/**
 * Owns the D-Bus connection, the adapter selection, the signal subscriptions and the discovery
 * lease. Construct freely; nothing touches the bus until [start].
 */
class BlueZBus {

    private val log = LoggerFactory.getLogger(BlueZBus::class.java)

    /**
     * Guards [connection], [adapter], [handlers], [scope] and [discoveryLeases] against each other.
     *
     * Deliberately *not* taken by any signal handler. Handlers run on dbus-java's single signal
     * thread while [acquireDiscovery] may be holding this lock across a blocking `StartDiscovery`
     * round trip; a handler that waited on the lock would park the one thread that has to keep
     * draining the bus, and the round trip it is waiting behind is answered on a different pool, so
     * the stall would last as long as BlueZ takes. The handlers only touch `@Volatile` fields and
     * do a non-blocking emit, so they need no lock at all.
     */
    private val lock = ReentrantLock()

    @Volatile
    private var connection: DBusConnection? = null

    /**
     * Read by the signal handlers as their one liveness check, so it has to be visible without the
     * lock. Cleared *before* anything is torn down, which is what makes a signal that is already in
     * flight when [stop] runs turn into a no-op rather than a race against a closed connection.
     */
    @Volatile
    private var running: Boolean = false

    @Volatile
    private var adapter: BlueZAdapter? = null

    private val handlers = mutableListOf<AutoCloseable>()

    private var scope: CoroutineScope? = null

    /**
     * Wakes the repair coroutine when bluetoothd's ownership of `org.bluez` changes; the payload is
     * the new owner, empty when the name has gone away.
     *
     * [Channel.CONFLATED] because only the *latest* owner is actionable. A bluetoothd restart
     * produces a name-lost followed immediately by a name-acquired, and re-resolving the adapter
     * against the intermediate state would just fail and be redone a millisecond later. Conflation
     * also makes `trySend` from the signal thread total: it can neither block nor be refused.
     */
    private var ownerChanges: Channel<String>? = null

    /**
     * How many callers currently want discovery, and whether BlueZ believes we asked for it.
     *
     * BlueZ reference-counts `StartDiscovery` per D-Bus *client*, and this process is one client,
     * so a scanner that paired its own Start/Stop calls would stop discovery out from under
     * whatever else was scanning. The count lives here for the same reason the connection does.
     */
    private var discoveryLeases: Int = 0
    private var discoveryAsserted: Boolean = false

    private val statusFlow = MutableStateFlow<BlueZStatus>(BlueZStatus.Stopped)

    /** The current view of the bus and of BlueZ. Never throws; a dead bus shows up here. */
    val status: StateFlow<BlueZStatus> = statusFlow.asStateFlow()

    /** Convenience for callers that only need up/down and not why. */
    val available: Flow<Boolean> =
        statusFlow.map { it is BlueZStatus.Available }.distinctUntilChanged()

    /*
     * -----------------------------------------------------------------------------------------
     * Signal fan-out
     * -----------------------------------------------------------------------------------------
     *
     * Four separate flows, not one, because the four kinds of event have four different costs of
     * being dropped and one shared buffer would force the cheapest to set the policy for the most
     * expensive.
     *
     * These are `MutableSharedFlow`s rather than `Channel`s for one reason: a `Channel` is
     * single-consumer, and at least three services subscribe here -- the scanner wants
     * InterfacesAdded, the GATT server wants disconnects, the connection service wants both. A
     * `Channel.receiveAsFlow()` would let whichever of them collected first steal the others'
     * events. `tryEmit` on a shared flow with `extraBufferCapacity > 0` and a drop policy is the
     * same non-blocking, bounded operation as `trySend` -- it returns immediately and never
     * suspends -- so the constraint that matters on the signal thread is met either way.
     */

    private val interfacesAddedFlow = MutableSharedFlow<BlueZInterfacesAdded>(
        replay = 0,
        extraBufferCapacity = 128,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    private val interfacesRemovedFlow = MutableSharedFlow<BlueZInterfacesRemoved>(
        replay = 0,
        extraBufferCapacity = 128,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    private val deviceStateFlow = MutableSharedFlow<BlueZDeviceProperties>(
        replay = 0,
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    private val deviceAdvertisementFlow = MutableSharedFlow<BlueZDeviceProperties>(
        replay = 0,
        extraBufferCapacity = 32,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    /**
     * A `Device1` object appearing under our adapter.
     *
     * Buffered at 128 and dropping the oldest on overflow. A missed addition is recoverable --
     * [knownDevices] will still show the device, and BlueZ will keep emitting `PropertiesChanged`
     * for it -- so the cheap policy is the right one, and 128 is well past the number of distinct
     * peers a scan realistically turns up before a consumer gets a turn.
     */
    val interfacesAdded: SharedFlow<BlueZInterfacesAdded> = interfacesAddedFlow.asSharedFlow()

    /**
     * A `Device1` object disappearing. Same buffer, but for the opposite reason: removals are rare
     * -- BlueZ prunes a device, or a peer rotated its address -- so 128 outstanding removals means
     * the consumer has been stalled for minutes and has bigger problems than a lost event.
     */
    val interfacesRemoved: SharedFlow<BlueZInterfacesRemoved> = interfacesRemovedFlow.asSharedFlow()

    /**
     * `Device1` events that change [DEVICE_STATE_KEYS] -- the ones that mean a link came up or went
     * down.
     *
     * These must not be lost: a dropped `Connected = false` leaves the GATT server holding a client
     * that no longer exists, and nothing else will ever tell it otherwise. Hence the largest buffer
     * of the four and the split from [deviceAdvertisements], which is what makes the size mean
     * something -- a device-state event only arrives when a link actually changes, so 256 of them
     * outstanding cannot happen in normal operation. `DROP_OLDEST` is still the overflow policy
     * because the alternative on a single, un-blockable signal thread is to drop the *newest*, and
     * between two bad options losing the stalest state is the less wrong one.
     */
    val deviceStateChanges: SharedFlow<BlueZDeviceProperties> = deviceStateFlow.asSharedFlow()

    /**
     * Every other `Device1` change: `RSSI`, `ManufacturerData`, `ServiceData`, `TxPower`, `Name`.
     *
     * This is the firehose -- one event per advertisement per device in range, several per second
     * each. Buffered at 32 and dropping the oldest, because these events are pure snapshots: a
     * stale RSSI has already been superseded by the next one, and keeping it would only push a
     * fresher reading out. Discarding here is what keeps the shared signal thread from being made
     * to care about a slow scanner.
     */
    val deviceAdvertisements: SharedFlow<BlueZDeviceProperties> =
        deviceAdvertisementFlow.asSharedFlow()

    // ---------------------------------------------------------------------------------------
    // Lifecycle
    // ---------------------------------------------------------------------------------------

    /**
     * Connects to the system bus, selects an adapter and installs the signal subscriptions.
     *
     * Returns whether the *bus* connection stands, which is not the same question as whether BlueZ
     * is usable -- an adapter that is missing or unpowered still leaves a working connection and is
     * reported through [status]. Nothing here throws: this object is built inside a Koin
     * `single { }`, and an exception escaping a provider aborts graph construction for the whole
     * application, so an absent bluetoothd would take the app down with it.
     *
     * Blocking, and safe to call more than once. Call it from `Dispatchers.IO`: the SASL handshake
     * with the daemon is synchronous, and the native-unixsocket transport ignores
     * `TransportConfigBuilder.withTimeout` (it never reads the value), so there is no configuration
     * that would bound how long a wedged bus can hold this call.
     */
    fun start(): Boolean = lock.withLock {
        if (connection != null) return@withLock true

        val conn = try {
            DBusConnectionBuilder.forSystemBus()
                /*
                 * The whole reason this connection is built by hand rather than with the spike's
                 * bare `forSystemBus().build()`. dbus-java's default of four METHODCALL threads
                 * lets BlueZ's registration-time callbacks be answered out of order; one thread
                 * makes the order it observes the order it asked in. It cannot deadlock the way a
                 * single *overall* thread would, because the reply to our outbound
                 * `RegisterApplication` arrives on the METHODRETURN pool, not this one.
                 */
                .receivingThreadConfig()
                .withMethodCallThreadCount(1)
                .connectionConfig()
                /*
                 * Watchdog one of two. This fires when our own socket dies -- bus restarted, peer
                 * closed, I/O error -- which the NameOwnerChanged watch below structurally cannot
                 * report, because it is delivered over the very connection that just went away.
                 */
                .withDisconnectCallback(disconnectCallback)
                .build()
        } catch (t: Throwable) {
            // Includes DBusException for an unreachable socket and the RuntimeExceptions the
            // transport ServiceLoader can raise. Reported, never rethrown -- see the KDoc.
            val reason = "system bus unreachable: ${t.javaClass.simpleName}: ${t.message}"
            log.warn("BlueZ bus start failed -- {}", reason)
            statusFlow.value = BlueZStatus.Unavailable(reason)
            return@withLock false
        }

        connection = conn
        running = true
        log.info("connected to the system bus as {}", conn.uniqueName)

        val channel = Channel<String>(Channel.CONFLATED)
        ownerChanges = channel
        val busScope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineName("bluez-bus"))
        scope = busScope
        busScope.launch { pumpOwnerChanges(channel) }

        installHandlers(conn)
        refreshAdapterLocked()
        true
    }

    /**
     * Releases the discovery lease, drops the signal subscriptions and closes the connection.
     *
     * Idempotent. [running] is cleared first so that a signal already being dispatched on
     * dbus-java's thread discards its payload instead of racing the teardown, and `disconnect()` is
     * called *outside* [lock] because it joins dbus-java's reader thread -- holding our lock across
     * that would block any concurrent [acquireDiscovery] for the duration of a shutdown it can no
     * longer affect.
     */
    fun stop() {
        var conn: DBusConnection? = null
        lock.withLock {
            conn = connection ?: return
            running = false
            connection = null

            // The lease count goes with the connection: whatever BlueZ was counting for us dies
            // with the client, and a restarted bus must not inherit a count nobody is holding.
            stopDiscoveryLocked(conn!!)
            discoveryLeases = 0

            handlers.forEach { handler ->
                runCatching { handler.close() }
                    .onFailure { log.debug("removing a signal handler failed: {}", it.message) }
            }
            handlers.clear()

            ownerChanges?.close()
            ownerChanges = null
            scope?.cancel()
            scope = null

            adapter = null
            statusFlow.value = BlueZStatus.Stopped
        }
        runCatching { conn?.disconnect() }
            .onFailure { log.debug("disconnect failed: {}", it.message) }
        log.info("BlueZ bus stopped")
    }

    /**
     * Watchdog one of two: our own connection dying.
     *
     * Runs on a dbus-java thread that is already unwinding, so it does nothing but publish -- no
     * lock (a concurrent [stop] may be holding it, and this callback is invoked from inside
     * `disconnect()`), no D-Bus calls, no blocking. [stop] still has to be called to clean up; this
     * only makes sure nobody keeps issuing calls into a socket that is gone.
     */
    private val disconnectCallback = object : IDisconnectCallback {
        override fun clientDisconnect() = onConnectionLost("connection closed")
        override fun disconnectOnError(_ex: IOException) =
            onConnectionLost("transport error: ${_ex.message}")
    }

    private fun onConnectionLost(reason: String) {
        if (!running) return
        running = false
        adapter = null
        log.warn("D-Bus connection lost -- {}", reason)
        statusFlow.value = BlueZStatus.Unavailable(reason)
    }

    // ---------------------------------------------------------------------------------------
    // Adapter
    // ---------------------------------------------------------------------------------------

    /** The selected adapter, or null while the bus is down or no adapter is present. */
    fun adapter(): BlueZAdapter? = adapter

    /** A proxy for the selected adapter, for callers that need `Adapter1` directly. */
    fun adapterProxy(): Adapter1? =
        adapter?.let { proxy(it.path, Adapter1::class.java) }

    /**
     * `Adapter1.Powered`. False here is the reason nearly every other BlueZ call fails, so it is
     * worth checking before concluding anything else is broken. Blocking; call it off the UI
     * thread.
     */
    fun isPowered(): Boolean = try {
        adapterProxy()?.powered ?: false
    } catch (e: Exception) {
        log.debug("reading Powered failed: {}", e.message)
        false
    }

    /**
     * Writes `Adapter1.Powered`, through `org.freedesktop.DBus.Properties.Set`.
     *
     * `Adapter1` declares `powered` read-only because nothing else in this transport writes it, so
     * the write goes the generic way rather than through the bound property -- exactly as the spike
     * does. BlueZ answers before the controller has actually come up, so a caller that needs the
     * radio ready must wait for `Powered` to read back true rather than trusting this return.
     */
    fun setPowered(powered: Boolean): Boolean {
        val path = adapter?.path ?: return false
        val props = proxy(path, Properties::class.java) ?: return false
        return try {
            props.Set(IFACE_ADAPTER1, "Powered", Variant(powered))
            true
        } catch (e: Exception) {
            log.warn("setting Powered={} on {} failed: {}", powered, path, e.message)
            false
        }
    }

    /**
     * Re-reads the object tree, picks the adapter and republishes [status]. Caller holds [lock].
     */
    private fun refreshAdapterLocked(): Boolean {
        val conn = connection ?: return false

        val owner = runCatching { conn.getDBusOwnerName(BLUEZ_BUS) }.getOrNull()
        if (owner.isNullOrEmpty()) {
            adapter = null
            statusFlow.value = BlueZStatus.Unavailable("bluetoothd does not own $BLUEZ_BUS")
            log.warn("$BLUEZ_BUS has no owner -- is bluetoothd running?")
            return false
        }

        val adapters = managedObjects().filterValues { IFACE_ADAPTER1 in it }
        if (adapters.isEmpty()) {
            adapter = null
            statusFlow.value = BlueZStatus.Unavailable("no $IFACE_ADAPTER1 on the bus")
            log.warn("bluetoothd is up but exports no adapter")
            return false
        }

        val requested = System.getProperty(ADAPTER_PROPERTY)?.trim()?.takeIf { it.isNotEmpty() }
        val path = when {
            // Both spellings are accepted because both are natural to type, and getting it wrong
            // would otherwise silently fall through to "some other adapter".
            requested == null -> adapters.keys.minOrNull()
            requested.startsWith("/") -> adapters.keys.firstOrNull { it == requested }
            else -> adapters.keys.firstOrNull { it.substringAfterLast('/') == requested }
        }
        if (path == null) {
            adapter = null
            statusFlow.value =
                BlueZStatus.Unavailable("-D$ADAPTER_PROPERTY=$requested matches no adapter")
            log.warn("-D{}={} matches none of {}", ADAPTER_PROPERTY, requested, adapters.keys)
            return false
        }

        val address = adapters[path]?.get(IFACE_ADAPTER1)?.get("Address")?.value as? String ?: ""
        adapter = BlueZAdapter(path, address)
        statusFlow.value = BlueZStatus.Available(owner, path, address)
        log.info("adapter {} ({}) via bluetoothd at {}", path, address, owner)
        return true
    }

    // ---------------------------------------------------------------------------------------
    // Proxies and the managed-objects snapshot
    // ---------------------------------------------------------------------------------------

    /**
     * A remote object on `org.bluez`, or null when the bus is down or the path is not there.
     *
     * This is how the services reach BlueZ without holding a connection of their own; that the
     * proxy goes stale when [stop] runs is the point, because a stale proxy fails loudly at its
     * next call rather than quietly talking to a second connection nobody is watching.
     */
    fun <T : DBusInterface> proxy(path: String, type: Class<T>): T? {
        val conn = connection ?: return null
        return try {
            conn.getRemoteObject(BLUEZ_BUS, path, type)
        } catch (e: Exception) {
            log.warn("no proxy for {} as {}: {}", path, type.simpleName, e.message)
            null
        }
    }

    /** The live connection, for the one thing a proxy cannot do: exporting our own objects. */
    fun connection(): DBusConnection? = connection

    /**
     * `ObjectManager.GetManagedObjects` on `org.bluez` at `/`, keyed by object path.
     *
     * Not a convenience. BlueZ caches `Device1` objects across discovery sessions, so a peer it has
     * already seen fires *no* `InterfacesAdded` on the next scan -- it is simply there, silently,
     * from the moment the scan starts. A scanner that only listens for signals never sees those
     * devices at all. This snapshot is how it finds them.
     *
     * Blocking round trip; call it off the UI thread and off the signal thread.
     */
    fun managedObjects(): Map<String, Map<String, Map<String, Variant<*>>>> {
        val conn = connection ?: return emptyMap()
        return try {
            val objectManager = conn.getRemoteObject(BLUEZ_BUS, BLUEZ_ROOT, ObjectManager::class.java)
            objectManager.GetManagedObjects().entries.associate { (path, interfaces) ->
                path.path to interfaces.mapValues { (_, properties) -> properties.toMap() }
            }
        } catch (e: Exception) {
            log.warn("GetManagedObjects failed: {}", e.message)
            emptyMap()
        }
    }

    /**
     * The `Device1` objects BlueZ currently holds for our adapter -- the already-known peers the
     * signal stream will not announce. Devices on another adapter are filtered out because acting
     * on one would mean issuing calls against a controller we are not driving.
     */
    fun knownDevices(): List<BlueZObject> {
        val adapterPath = adapter?.path ?: return emptyList()
        return managedObjects()
            .filter { (path, interfaces) ->
                IFACE_DEVICE1 in interfaces && path.startsWith("$adapterPath/")
            }
            .map { (path, interfaces) -> BlueZObject(path, interfaces) }
    }

    // ---------------------------------------------------------------------------------------
    // Discovery lease
    // ---------------------------------------------------------------------------------------

    /**
     * Registers interest in discovery, starting it if nobody else had.
     *
     * BlueZ reference-counts `StartDiscovery`/`StopDiscovery` per D-Bus client, and this process is
     * a single client no matter how many of our objects want to scan. Left to pair the calls
     * themselves, two callers produce one count and the first `StopDiscovery` ends scanning for
     * both. So the count lives here and callers take and drop a lease instead.
     *
     * Returns whether discovery is actually running. The contract is deliberately all-or-nothing:
     * `true` means a lease is held and [releaseDiscovery] must eventually be called, `false` means
     * nothing was taken and must not be released. The alternative -- handing back a lease that BlueZ
     * refused -- leaks a count that only an owner change would ever clear.
     *
     * Blocking; the `StartDiscovery` round trip happens under [lock]. Call from `Dispatchers.IO`.
     */
    fun acquireDiscovery(): Boolean = lock.withLock {
        if (connection == null) return@withLock false
        if (!discoveryAsserted && !startDiscoveryLocked()) return@withLock false
        discoveryLeases++
        true
    }

    /** Drops one lease, stopping discovery when the last one goes. Idempotent below zero. */
    fun releaseDiscovery() = lock.withLock {
        if (discoveryLeases == 0) return@withLock
        discoveryLeases--
        if (discoveryLeases == 0) {
            connection?.let { stopDiscoveryLocked(it) }
        }
    }

    /** True while at least one lease is outstanding and BlueZ has accepted the request. */
    fun isDiscovering(): Boolean = lock.withLock { discoveryAsserted }

    private fun startDiscoveryLocked(): Boolean {
        val path = adapter?.path ?: return false
        val adapterProxy = proxy(path, Adapter1::class.java) ?: return false
        try {
            adapterProxy.StartDiscovery()
            discoveryAsserted = true
            log.info("discovery started on {}", path)
        } catch (e: Exception) {
            // `org.bluez.Error.InProgress` means this client already has a count, which is the
            // state we wanted, so it is a success and not a failure.
            val alreadyScanning = e.message?.contains("progress", ignoreCase = true) == true
            discoveryAsserted = alreadyScanning
            if (alreadyScanning) {
                log.debug("discovery already in progress for this client on {}", path)
            } else {
                log.warn("StartDiscovery on {} failed: {}", path, e.message)
            }
        }
        return discoveryAsserted
    }

    private fun stopDiscoveryLocked(conn: DBusConnection) {
        if (!discoveryAsserted) return
        discoveryAsserted = false
        val path = adapter?.path ?: return
        runCatching {
            conn.getRemoteObject(BLUEZ_BUS, path, Adapter1::class.java).StopDiscovery()
        }.onSuccess {
            log.info("discovery stopped on {}", path)
        }.onFailure {
            // Expected when BlueZ has already dropped our count -- an adapter power cycle, or the
            // bluetoothd restart that brought us here. Not a failure worth raising.
            log.debug("StopDiscovery on {}: {}", path, it.message)
        }
    }

    // ---------------------------------------------------------------------------------------
    // Path helpers
    // ---------------------------------------------------------------------------------------

    /**
     * Path to address, delegated to `commonMain` so that the desktop transport and the `linuxMain`
     * peripheral cannot end up spelling the same peer two different ways.
     */
    fun deviceAddress(path: String): String? = BlueZObjectPath.deviceAddress(path)

    /** Address to path, against the currently selected adapter. */
    fun devicePath(address: String): String? =
        adapter?.path?.let { devicePath(it, address) }

    companion object {

        /**
         * Address to path -- the direction [BlueZObjectPath] does not provide.
         *
         * BlueZ spells a device object `<adapter>/dev_AA_BB_CC_DD_EE_FF`: upper case, underscores
         * for colons. Rather than validate the address here and risk the two directions disagreeing
         * about what counts as an address, the constructed path is handed back to
         * [BlueZObjectPath.deviceAddress]; if it does not parse, it was not a valid address and
         * there is no path to return. That keeps a single definition of "an address" in the module.
         */
        fun devicePath(adapterPath: String, address: String): String? {
            val segment = "dev_" + address.trim().uppercase().replace(':', '_')
            if (BlueZObjectPath.deviceAddress("/$segment") == null) return null
            return "$adapterPath/$segment"
        }
    }

    // ---------------------------------------------------------------------------------------
    // Signal subscriptions
    // ---------------------------------------------------------------------------------------

    /**
     * Installs the four match rules. Caller holds [lock].
     *
     * None of them names a sender, for the reason set out at the top of the file: dbus-java refuses
     * a well-known name as a signal source, and bluetoothd's unique name is exactly what a restart
     * changes. What narrows them instead is `path_namespace` and `arg0`, both of which the daemon
     * accepts and dbus-java also applies locally (verified live: a rule of
     * `path_namespace='/org/bluez',arg0='org.bluez.Adapter1'` matched the adapter's own
     * `Discovering` change and nothing else). The handlers re-check the path anyway, because the
     * two ObjectManager rules cannot be narrowed that way -- see below.
     */
    private fun installHandlers(conn: DBusConnection) {
        /*
         * BlueZ emits InterfacesAdded/Removed from `/`, its ObjectManager root -- confirmed on the
         * wire, `path=/ member=InterfacesAdded sender=:1.4`. So `path_namespace='/org/bluez'` would
         * match none of them, and the only usable narrowing is the interface and member. Every
         * other ObjectManager on the system bus (NetworkManager is a busy one) therefore also
         * reaches the signal thread, which is why both handlers reject a foreign path before doing
         * anything else. That check costs a `startsWith`; a match rule cannot do better here.
         */
        subscribe(
            conn,
            rule(ObjectManager.InterfacesAdded::class.java),
            DBusSigHandler<ObjectManager.InterfacesAdded> { signal ->
                if (!running) return@DBusSigHandler
                // `signalSource` is the object the signal is *about*; `objectPath` is the path it
                // was emitted from, which for BlueZ is always `/`. The names read backwards.
                val path = signal.signalSource?.path ?: return@DBusSigHandler
                if (!path.startsWith("$BLUEZ_ROOT_PREFIX/")) return@DBusSigHandler
                interfacesAddedFlow.tryEmit(
                    BlueZInterfacesAdded(path, signal.interfaces.mapValues { it.value.toMap() })
                )
            }
        )

        subscribe(
            conn,
            rule(ObjectManager.InterfacesRemoved::class.java),
            DBusSigHandler<ObjectManager.InterfacesRemoved> { signal ->
                if (!running) return@DBusSigHandler
                val path = signal.signalSource?.path ?: return@DBusSigHandler
                if (!path.startsWith("$BLUEZ_ROOT_PREFIX/")) return@DBusSigHandler
                interfacesRemovedFlow.tryEmit(
                    BlueZInterfacesRemoved(path, signal.interfaces.toList())
                )
            }
        )

        /*
         * The firehose. `arg0='org.bluez.Device1'` keeps the adapter's own property changes and
         * every other service's PropertiesChanged off this handler, and `path_namespace` keeps
         * anything outside BlueZ's tree off it as well; what is left is genuinely per-device, and
         * it is still mostly RSSI.
         */
        subscribe(
            conn,
            DBusMatchRuleBuilder.create()
                .withType(Properties.PropertiesChanged::class.java)
                .withPathNamespace(BLUEZ_ROOT_PREFIX)
                .withArg0123(0, IFACE_DEVICE1)
                .build(),
            DBusSigHandler<Properties.PropertiesChanged> { signal ->
                if (!running) return@DBusSigHandler
                if (signal.interfaceName != IFACE_DEVICE1) return@DBusSigHandler
                val path = signal.path ?: return@DBusSigHandler
                val address = BlueZObjectPath.deviceAddress(path) ?: return@DBusSigHandler
                val changed = signal.propertiesChanged.toMap()
                val invalidated = signal.propertiesRemoved.toList()
                val event = BlueZDeviceProperties(path, address, changed, invalidated)
                // The one decision made on the signal thread, and it is two set lookups: which of
                // the two device flows this belongs on. Everything downstream of here is somebody
                // else's thread.
                val statefulEvent = changed.keys.any { it in DEVICE_STATE_KEYS } ||
                    invalidated.any { it in DEVICE_STATE_KEYS }
                if (statefulEvent) {
                    deviceStateFlow.tryEmit(event)
                } else {
                    deviceAdvertisementFlow.tryEmit(event)
                }
            }
        )

        /*
         * Watchdog two of two. bluetoothd restarting is invisible from the transport's point of
         * view -- our exported objects are still exported, our proxies still resolve, and BlueZ
         * simply never calls us again, because every registration we made died with the old
         * process. `arg0='org.bluez'` narrows this to the one name we care about, so the handler
         * does not have to inspect the signal at all: anything that arrives here is about
         * bluetoothd.
         *
         * Deliberately not reading `signal.name`: `DBus.NameOwnerChanged` has a public `name` field
         * and inherits `Message.getName()`, which returns the D-Bus *member* name, and Kotlin
         * property syntax across those two resolves to something that looks right and is not. The
         * match rule already guarantees what `name` would tell us.
         */
        subscribe(
            conn,
            DBusMatchRuleBuilder.create()
                .withType(DBus.NameOwnerChanged::class.java)
                .withArg0123(0, BLUEZ_BUS)
                .build(),
            DBusSigHandler<DBus.NameOwnerChanged> { signal ->
                if (!running) return@DBusSigHandler
                // Hand off and return. Reacting means GetManagedObjects and possibly
                // StartDiscovery, both blocking round trips, and this is the thread that must never
                // block. The channel is conflated, so this cannot fail or wait.
                ownerChanges?.trySend(signal.newOwner.orEmpty())
            }
        )
    }

    private fun rule(type: Class<out org.freedesktop.dbus.messages.DBusSignal>): DBusMatchRule =
        DBusMatchRuleBuilder.create().withType(type).build()

    private fun <T : org.freedesktop.dbus.messages.DBusSignal> subscribe(
        conn: DBusConnection,
        matchRule: DBusMatchRule,
        handler: DBusSigHandler<T>
    ) {
        try {
            handlers += conn.addSigHandler(matchRule, handler)
            log.debug("watching {}", matchRule)
        } catch (e: Exception) {
            // A rule the daemon rejects is a defect in this file, not a runtime condition, but it
            // must not take the connection down with it -- the other three rules are still useful.
            log.error("could not install match rule {}: {}", matchRule, e.message)
        }
    }

    /**
     * Reacts to bluetoothd coming and going, off the signal thread.
     *
     * A restart is republished as an [BlueZStatus.Available] carrying the *new* owner, which is the
     * signal subscribers use to re-register; nothing else distinguishes the new bluetoothd from the
     * old one. The discovery lease is re-asserted here too, because the reference count that backed
     * it belonged to a process that no longer exists, while the callers holding leases have no idea
     * anything happened.
     */
    private suspend fun pumpOwnerChanges(channel: Channel<String>) {
        for (owner in channel) {
            if (!running) return
            if (owner.isEmpty()) {
                lock.withLock {
                    adapter = null
                    // Not a StopDiscovery: there is nothing left to tell. The count died with the
                    // daemon, but the leases are still held by callers who have not asked to stop,
                    // so `discoveryLeases` is left alone and re-asserted when BlueZ returns.
                    discoveryAsserted = false
                }
                log.warn("bluetoothd left the bus")
                statusFlow.value = BlueZStatus.Unavailable("bluetoothd left the bus")
                continue
            }
            log.info("bluetoothd is back at {} -- re-resolving", owner)
            lock.withLock {
                discoveryAsserted = false
                if (refreshAdapterLocked() && discoveryLeases > 0) startDiscoveryLocked()
            }
        }
    }
}
