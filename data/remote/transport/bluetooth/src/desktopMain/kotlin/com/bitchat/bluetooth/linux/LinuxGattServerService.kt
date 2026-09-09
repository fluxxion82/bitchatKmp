@file:Suppress("FunctionName")

package com.bitchat.bluetooth.linux

import com.bitchat.bluetooth.manager.BlueZObjectPath
import com.bitchat.bluetooth.manager.BroadcastTargets
import com.bitchat.bluetooth.manager.GattClientRegistry
import com.bitchat.bluetooth.service.GattServerDelegate
import com.bitchat.bluetooth.service.GattServerService
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.freedesktop.dbus.DBusPath
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.exceptions.DBusExecutionException
import org.freedesktop.dbus.interfaces.ObjectManager
import org.freedesktop.dbus.interfaces.Properties
import org.freedesktop.dbus.types.UInt16
import org.freedesktop.dbus.types.Variant
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.min

/*
 * =============================================================================================
 * The peripheral role: the GATT application we export, and everything that happens on it.
 * =============================================================================================
 *
 * BlueZ does not let a process "be a peripheral". It lets a process export a small tree of D-Bus
 * objects that *describe* a GATT database, hand the root of that tree to `GattManager1`, and then
 * answer method calls BlueZ makes on those objects on behalf of whatever central connected. So
 * almost nothing here is a call we make; almost everything is a call we answer.
 *
 *     /org/bitchat/gatt                      ObjectManager + Properties   (the application root)
 *       └── service0                         org.bluez.GattService1
 *             └── char0                      org.bluez.GattCharacteristic1
 *
 * Three consequences shape the whole file:
 *
 *  1. **Every inbound handler runs on dbus-java's METHODCALL pool**, which `BlueZBus` deliberately
 *     pins to a single thread so that BlueZ's registration-time callbacks are answered in the order
 *     it asked them. A handler that blocks stops the transport; a handler that makes an outbound
 *     D-Bus call from inside an inbound one puts the two on the same thread. So the handlers below
 *     validate, snapshot and hand off, and nothing else. There is no `runBlocking` in this file.
 *
 *  2. **A notification is not a reply.** It is a `PropertiesChanged` signal we emit on the
 *     characteristic's own object path -- which names no device -- so one emission reaches every
 *     subscribed central. That is why `WriteValue` does not answer inline (see [onWriteValue]) and
 *     why [emit] is the single serialized outbound path.
 *
 *  3. **BlueZ never tells us a central went away.** The only inbound traffic on our own tree is
 *     `WriteValue`/`ReadValue`/`Start|StopNotify`. Link teardown is learned from the two `Device1`
 *     signals that [BlueZBus] fans out; see [watchDisconnects].
 */

// ---------------------------------------------------------------------------------------------
// The local object tree and the wire constants
// ---------------------------------------------------------------------------------------------

/*
 * The UUIDs are wire constants shared with the Android and iOS peers and must never change. The
 * paths are the opposite: a private conversation between this process and its own bluetoothd that
 * no peer ever sees. They are spelled the same as `linuxMain` only so that a `busctl` transcript
 * from either build reads the same way.
 *
 * BlueZ normalises UUIDs to lower case on the way back out, so every comparison against these is
 * case-insensitive. Nothing in this file compares them; the scanner does, and this note is here
 * because this is where the constants live.
 */

/** `org.bluez.GattService1.UUID` for the bitchat mesh service. */
const val BITCHAT_SERVICE_UUID: String = "F47B5E2D-4A9E-4C5A-9B3F-8E1D2C3A4B5C"

/** `org.bluez.GattCharacteristic1.UUID` -- the single characteristic every packet crosses. */
const val BITCHAT_CHARACTERISTIC_UUID: String = "A1B2C3D4-E5F6-4A5B-8C9D-0E1F2A3B4C5D"

const val GATT_APP_PATH: String = "/org/bitchat/gatt"
const val GATT_SERVICE_PATH: String = "/org/bitchat/gatt/service0"
const val GATT_CHARACTERISTIC_PATH: String = "/org/bitchat/gatt/service0/char0"

/**
 * `write-without-response` is load-bearing, not decoration: the existing peers write with
 * `type=command` (that is what gattlib does), and BlueZ rejects a write whose type the flags do not
 * permit. `notify` is what makes `StartNotify` legal and our `PropertiesChanged` deliverable.
 */
private val CHARACTERISTIC_FLAGS = listOf("read", "write", "write-without-response", "notify")

/**
 * Candidate base handles for the service, tried in order.
 *
 * BlueZ 5.74 made `Handle` mandatory in practice. `gatt-database.c` passes `handle - 1` to gatt-db
 * as the start of the requested range; with `Handle` absent it defaults to 0, the subtraction
 * underflows to `UINT16_MAX`, and gatt-db rejects the range with
 * `org.bluez.Error.Failed: Failed to create entry in database`. So a handle must be picked, and the
 * safe floor is a property of whatever else is registered on this adapter right now rather than a
 * constant: too low collides with bluetoothd's own local services (GAP/GATT sit in the low handles,
 * and 0x0010 is measured to fail), too high is fine but pointless. 0x0040 is the measured floor on
 * the reference host; the rest of the ladder exists for a host with more local services than that.
 */
private val HANDLE_BASES = listOf(0x0040, 0x0080, 0x0100, 0x0200)

/**
 * How far above its service the characteristic's handle must sit.
 *
 * `gatt-db.c` places the characteristic *declaration* at `handle - 1`, and
 * `service_insert_characteristic` rejects a declaration at or below the service's own handle, so
 * the characteristic needs at least `serviceHandle + 2`. 0x0040/0x0042 is the measured-good pair.
 */
private const val CHARACTERISTIC_HANDLE_OFFSET = 2

/**
 * The largest notification chunk we will ever emit, and the value used when no MTU is known.
 *
 * It is a ceiling and a fallback at once, which is the whole MTU policy in one constant: 500 is
 * what `AndroidGattClientService` already assumes of us, MTU discovery may only ever *lower* it,
 * and a peripheral that has never learned an MTU must still work. See [notificationChunkCap].
 */
private const val MAX_NOTIFICATION_CHUNK = 500

/**
 * The floor the cap may not go below: the mandatory ATT MTU of 23, less the 3-byte notification
 * header. A peer reporting anything smaller is reporting nonsense, and [BleChunker] cannot make
 * forward progress on a chunk size below 6 anyway.
 */
private const val MIN_NOTIFICATION_CHUNK = 20

/** Both existing peers pace chunks 25 ms apart; a burst without it overruns the controller. */
private const val CHUNK_DELAY_MS = 25L

/** How often in-flight reassembly buffers are aged out. See [BleChunker.sweep]. */
private const val REASSEMBLY_SWEEP_INTERVAL_MS = 10_000L

/**
 * How many inbound events may be waiting for the delegate before one is refused.
 *
 * Bounded, because the alternative on the D-Bus dispatch thread is to block it, and unbounded is
 * just a slower way to fail. 256 whole frames outstanding means the delegate has been stalled for
 * a long time and something upstream is already wrong.
 *
 * Deliberately *not* a `DROP_OLDEST` channel. That policy makes `trySend` always succeed, so the
 * drop is invisible and no counter can ever see it -- silent loss on the inbound path is precisely
 * the failure mode the counters exist to name. Refusing the newest event instead makes
 * [LinuxGattServerService.post] able to count and log it.
 */
private const val INBOUND_QUEUE_CAPACITY = 256

// ---------------------------------------------------------------------------------------------
// Shared peripheral-role plumbing
// ---------------------------------------------------------------------------------------------

/*
 * These three helpers are used by this file and by `LinuxAdvertisingService.kt`, which is the other
 * half of the peripheral role and exports objects under the same rules. They live here rather than
 * being duplicated because two copies of the exception-logging wrapper is two places to forget.
 */

/**
 * Runs the body of an exported D-Bus method, logging anything it throws before letting it out.
 *
 * dbus-java turns an exception out of an exported method into a D-Bus error reply and reports it
 * itself only under `org.freedesktop.dbus`, which is normally quiet; BlueZ renders the far end of
 * that error as nothing more informative than "No object received". Without this wrapper the most
 * likely failure of the whole peripheral role -- a value dbus-java declines to marshal -- is silent
 * on both sides. That is precisely how the `Variant(List)` defect stayed hidden in the spike, and
 * it produced a false PASS.
 */
internal fun <T> dbusHandler(tag: String, body: () -> T): T =
    try {
        body()
    } catch (t: Throwable) {
        LoggerFactory.getLogger("bitchat.ble.dbus").error("[{}] exported handler threw", tag, t)
        throw t
    }

/**
 * Both halves of a D-Bus error: the error *name* identifies the fault, the message is often the
 * only clue about which fault it is.
 *
 * dbus-java maps a known error name onto a generated class of that name and otherwise falls back to
 * a plain [DBusExecutionException] carrying the name in `type`, so neither the class nor `type`
 * alone identifies the error in every case. Print both.
 */
@Suppress("DEPRECATION")
internal fun dbusErrorText(t: Throwable): String = when (t) {
    is DBusExecutionException -> "${t.javaClass.name} [${t.type}]: ${t.message}"
    else -> "${t.javaClass.name}: ${t.message}"
}

/**
 * Wraps a list of strings as a D-Bus `as`.
 *
 * The one-argument `Variant(T)` constructor derives its signature from `value.getClass()`, and a
 * `java.util.List` is a raw class with its element type erased -- so *every* list, however built,
 * is rejected with "Can't wrap ... in an unqualified Variant". The two-argument form takes the
 * signature rather than inferring it and is the only way to put a string array in a variant. This
 * is what silently broke the advertisement properties in the spike; it must never be written as
 * `Variant(someList)`.
 */
internal fun variantOfStrings(values: List<String>): Variant<List<String>> = Variant(values, "as")

/** The name of a D-Bus error, or the exception's class when it is not one. */
@Suppress("DEPRECATION")
internal fun dbusErrorName(t: Throwable): String =
    (t as? DBusExecutionException)?.type ?: t.javaClass.name

/**
 * True when BlueZ is telling us this handle range is not available, and only then.
 *
 * The whole point of the function is what it excludes. A ladder that advanced on any exception
 * treats a marshalling bug, a daemon timeout and a genuine collision alike, and a later base
 * succeeding then hides the original defect for good. See [LinuxGattServerService.registerWithHandleLadder].
 */
internal fun isHandleCollision(t: Throwable): Boolean {
    // Only BlueZ can tell us a range is taken. A local failure -- a marshalling bug, say -- is not
    // a collision however its text reads.
    if (t !is DBusExecutionException) return false

    // Errors that say nothing about handles. Advancing the ladder on any of these would retry a
    // different base against a fault that has nothing to do with the base, and a later success
    // would then bury the original defect.
    if (isAlreadyExists(t) || isDoesNotExist(t) || isIndeterminateRegistration(t)) return false

    // The 5.74 handle regression and a genuine collision with another application's range are the
    // same error: `org.bluez.Error.Failed` whose text names gatt-db's entry table.
    //
    // Match on the message, not the error name. dbus-java maps an error name onto a generated class
    // only when it recognises it, and otherwise throws a bare DBusExecutionException whose `type` is
    // that class's own name -- so `org.bluez.Error.Failed` reaches us as
    // "org.freedesktop.dbus.exceptions.DBusExecutionException". An earlier version compared the name
    // to "org.bluez.Error.Failed" and so never advanced the ladder against a real collision, which
    // is exactly the silent-partial-outage this function exists to prevent.
    val message = t.message.orEmpty()
    return message.contains("Failed to create entry", ignoreCase = true) ||
        message.contains("database", ignoreCase = true)
}

/** `RegisterApplication`/`RegisterAdvertisement` on a path BlueZ already holds for us. */
internal fun isAlreadyExists(t: Throwable): Boolean =
    dbusErrorName(t).endsWith(".AlreadyExists")

/** True for the error BlueZ answers an unregister with when it has already dropped the thing. */
internal fun isDoesNotExist(t: Throwable): Boolean =
    dbusErrorName(t).endsWith(".DoesNotExist")

/**
 * True when the registration may have taken effect despite the error, because we never saw a reply.
 *
 * Both register calls have the same shape: BlueZ calls back into our exported object and only then
 * answers. A reply that never arrives therefore says nothing about whether BlueZ went on to
 * register -- it usually did. `linuxMain` records the same ambiguity as `Registration.UNKNOWN`
 * (`BlueZAdvertisingService.kt:59-68`). Treating it as "not registered" leaks the registration for
 * the life of the connection; treating it as "possibly registered" costs one `DoesNotExist` at
 * teardown, which is tolerated anyway.
 */
internal fun isIndeterminateRegistration(t: Throwable): Boolean {
    val name = dbusErrorName(t)
    return name.endsWith(".NoReply") || name.endsWith(".Timeout")
}

// ---------------------------------------------------------------------------------------------
// The service
// ---------------------------------------------------------------------------------------------

/**
 * The desktop-Linux peripheral-role GATT server.
 *
 * Owns the three exported objects, their registration with `org.bluez.GattManager1`, the registry
 * of connected centrals, inbound reassembly and outbound notification.
 *
 * @param bus the shared connection. This class never builds one of its own: exporting an object is
 *   the one thing a proxy cannot do, so it borrows [BlueZBus.connection], but the lifecycle of that
 *   connection is not its to manage.
 */
class LinuxGattServerService(private val bus: BlueZBus) : GattServerService {

    private val log = LoggerFactory.getLogger("bitchat.ble.gatt")

    /**
     * Serializes every *outbound* interaction: registration, re-registration, teardown and
     * notification emission.
     *
     * One mutex rather than two, deliberately. A teardown that overlapped an in-flight chunked
     * notification would unexport the characteristic between two chunks of the same frame, and the
     * peer would be left holding a reassembly buffer that can never complete. Making the two wait
     * for each other costs nothing -- neither is on a hot path -- and removes the whole class of
     * race. It is a coroutine `Mutex` and not a lock precisely because no D-Bus dispatch thread
     * ever touches it: handlers hand off, they do not wait.
     */
    private val gate = Mutex()

    /** What the caller asked for, as opposed to what BlueZ currently believes. */
    @Volatile
    private var desired = false

    /** True while BlueZ holds our application registration. Read without the gate, so volatile. */
    @Volatile
    private var active = false

    /**
     * bluetoothd's unique bus name at the moment registration succeeded, or when the outcome was
     * indeterminate -- i.e. whenever BlueZ may be holding a registration of ours.
     *
     * Gates the unregister at teardown: nothing to unregister and nothing to answer `DoesNotExist`.
     */
    @Volatile
    private var registeredOwner: String? = null

    /**
     * The unique bus name we last *attempted* a registration against, whatever came of it.
     *
     * This, not a boolean and not [registeredOwner], is what distinguishes "BlueZ has been up all
     * along" from "bluetoothd restarted under us". Our exported objects survive that restart; every
     * registration we made against BlueZ does not, and BlueZ never calls us again to say so, so a
     * new owner is the only signal there is.
     *
     * It tracks the attempt rather than the success on purpose. Keyed on success, a registration
     * that failed would leave this null and [watchStatus] -- which sees the current value of a
     * `StateFlow` the moment it subscribes -- would immediately retry the attempt that just failed
     * and log the same error twice. A failure is reported once, with its real error; recovery is
     * scoped to a daemon that has actually changed.
     */
    @Volatile
    private var attemptedOwner: String? = null

    @Volatile
    private var delegate: GattServerDelegate? = null

    private var scope: CoroutineScope? = null

    private val clients = GattClientRegistry()

    /**
     * Inbound reassembly. One long-lived instance because it holds per-device state across calls;
     * the outbound side is a separate, resizable one -- see [chunkerFor].
     */
    private val chunker = BleChunker()

    /**
     * The ATT MTU each central negotiated, harvested from `WriteValue`.
     *
     * A [ConcurrentHashMap] because it is written from the D-Bus dispatch thread and read from the
     * coroutine that emits. Entries are dropped when the link goes, so a stale MTU from a peer that
     * has left cannot go on constraining the cap.
     */
    private val mtus = ConcurrentHashMap<String, Int>()

    private val writesReceived = AtomicLong()
    private val writesUnattributed = AtomicLong()
    private val notificationsEmitted = AtomicLong()
    private val framesDelivered = AtomicLong()
    private val inboundDropped = AtomicLong()
    private val startNotifyCalls = AtomicLong()
    /**
     * Whether any central currently holds a subscription, from `StartNotify`/`StopNotify`.
     *
     * BlueZ raises those once per characteristic -- on the first subscriber and after the last one
     * leaves -- so a flag is the right shape, not a count.
     */
    private val subscriberPresent = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * Reassembled frames and link events on their way to the delegate.
     *
     * The hand-off that keeps [onWriteValue] fast. A delegate is the mesh's packet processor: it
     * decrypts, it may answer, and answering means an outbound D-Bus call. Calling it inline would
     * put that call on the single METHODCALL thread that is meanwhile the only thing able to accept
     * the next write.
     */
    private val inbound = Channel<InboundEvent>(capacity = INBOUND_QUEUE_CAPACITY)

    /**
     * The outbound chunker, and the size it was built for. Guarded by [gate]: only [emit] reads or
     * replaces them, and [emit] holds the gate throughout.
     */
    private var outboundChunker = BleChunker(MAX_NOTIFICATION_CHUNK)
    private var outboundChunkSize = MAX_NOTIFICATION_CHUNK

    private val service = BitchatGattService()

    private val characteristic = BitchatGattCharacteristic(
        onWrite = ::onWriteValue,
        onSubscribe = ::onStartNotify,
        onUnsubscribe = ::onStopNotify
    )

    private val application = BitchatGattApplication(service, characteristic)

    // -----------------------------------------------------------------------------------------
    // GattServerService
    // -----------------------------------------------------------------------------------------

    override fun setDelegate(delegate: GattServerDelegate) {
        this.delegate = delegate
    }

    /**
     * Exports the object tree and registers it with `GattManager1`.
     *
     * Named `startAdvertising` by the `commonMain` interface, which is a misnomer everywhere except
     * Android: nothing here advertises. Advertising is `LinuxAdvertisingService`, and the two are
     * independent -- a GATT application with no advertisement is reachable by a peer that already
     * knows our address, and an advertisement with no application is discoverable and useless.
     *
     * Never throws. A peripheral that cannot register must degrade to central-only operation, not
     * take the transport down with it.
     */
    override suspend fun startAdvertising() {
        gate.withLock {
            if (active) {
                log.debug("GATT application already registered")
                return@withLock
            }
            desired = true
            registerLocked("startup")
            // After the first attempt, so that the status watcher -- which sees the current value
            // of a StateFlow the moment it subscribes -- does not treat this very daemon as new.
            // Started even when that attempt failed: BlueZ returning later is exactly the case the
            // watcher exists for.
            startScopeLocked()
        }
    }

    /**
     * Unregisters, unexports and releases, in that order, exactly once.
     *
     * The order is not cosmetic. Unregistering first tells BlueZ to stop routing calls to objects
     * that are about to disappear; unexporting first would leave BlueZ making calls into a tree
     * that answers `UnknownObject`, which it logs as our fault. `live` is cleared before either, so
     * an inbound call already being dispatched when this runs is refused cleanly rather than
     * racing a half-torn-down server.
     */
    override suspend fun stopAdvertising() {
        gate.withLock {
            if (!desired && !active && registeredOwner == null && scope == null) return@withLock
            desired = false

            // Step zero: make every exported object refuse work. Volatile, so a handler already on
            // the dispatch thread sees it.
            application.live = false
            service.live = false
            characteristic.live = false

            unregisterLocked()
            unexportLocked()

            active = false
            registeredOwner = null
            attemptedOwner = null
            clients.addresses().forEach { chunker.forget(it) }
            clients.clear()
            mtus.clear()
            scope?.cancel()
            scope = null
            log.info("GATT server stopped")
        }
    }

    /**
     * Feeds a write in as though it had arrived over the air.
     *
     * Exists for the `commonMain` contract and for tests; the real path is [onWriteValue], which
     * BlueZ drives. Both funnel into [ingest] so that ordering through the delegate is the same
     * whichever end the bytes came from.
     */
    override suspend fun onCharacteristicWriteRequest(data: ByteArray, deviceAddress: String) {
        ingest(deviceAddress, data)
    }

    /**
     * Notifies [deviceAddress], if we still hold a link to it.
     *
     * The address is a *precondition*, not a destination. A notification is a signal on the
     * characteristic's object path, which names no device, so the bytes reach every subscriber
     * regardless of what is passed here. What the address buys is honesty: without the registry
     * check a send into a link that went down a minute ago succeeds exactly like a delivered one,
     * which is how a Noise handshake spends its whole retry budget on packets that cannot arrive.
     */
    override suspend fun notifyCharacteristic(deviceAddress: String, data: ByteArray): Boolean {
        if (!clients.isConnected(deviceAddress)) {
            log.warn("notify of {}B to {} refused: no live link to that central", data.size, deviceAddress)
            return false
        }
        return emit(deviceAddress, data)
    }

    // -----------------------------------------------------------------------------------------
    // Peripheral-role extras used by the connection service
    // -----------------------------------------------------------------------------------------

    /**
     * Emits once to every subscribed central.
     *
     * This is the correct shape for a broadcast and [notifyCharacteristic] is not: emitting per
     * registry entry sends N copies of every packet to everyone, which is where the duplicated
     * `Packet received` lines in the Orange Pi journal came from.
     *
     * @return false when no central holds a link, so a caller can tell a delivered broadcast from
     *   one that went nowhere.
     */
    suspend fun notifySubscribers(data: ByteArray): Boolean {
        val count = clients.size()
        if (count > 0) return emit("$count client(s)", data)

        // No registered client, but somebody has subscribed and simply not written yet. The
        // emission is device-agnostic, so it reaches them regardless -- see [canReachSubscriber].
        if (subscriberPresent.get()) return emit("an unidentified subscriber", data)

        log.warn("broadcast of {}B refused: no central holds a live link", data.size)
        return false
    }

    /** Which centrals hold a link to this server right now. */
    fun connectedClients(): Set<String> = clients.addresses()

    fun isClientConnected(address: String): Boolean = clients.isConnected(address)

    /** Which of [addresses] still hold a link, and which are stale entries to drop. */
    fun partitionBroadcastTargets(addresses: List<String>): BroadcastTargets =
        clients.partitionTargets(addresses)

    /**
     * One line of counters for the transport's periodic health log.
     *
     * The set spans the whole inbound path on purpose: the useful signal is *where* the count
     * stops. Writes rising with frames flat is a reassembly problem; frames rising with deliveries
     * flat is a wiring problem; and the drop buckets separate a strict length-mismatch drop from a
     * peer that vanished mid-frame. Drop counts come from [BleChunker.stats] rather than being
     * recounted here.
     */
    fun statusLine(): String {
        val stats = chunker.stats
        return "gatt: clients=${clients.size()} writes=${writesReceived.get()}" +
            " unattributed=${writesUnattributed.get()}" +
            " frames reassembled=${stats.framesReassembled}" +
            " dropped=${stats.framesDropped}" +
            " (mismatch=${stats.droppedLengthMismatch} orphan=${stats.droppedOrphanContinuation}" +
            " restart=${stats.droppedRestartInFlight} aged=${stats.droppedAgedOut})" +
            " delivered=${framesDelivered.get()} queue-dropped=${inboundDropped.get()}" +
            " notifications=${notificationsEmitted.get()} startNotify=${startNotifyCalls.get()}" +
            " chunkCap=${notificationChunkCap()}B"
    }

    // -----------------------------------------------------------------------------------------
    // Registration
    // -----------------------------------------------------------------------------------------

    /** Caller holds [gate]. */
    private suspend fun registerLocked(reason: String): Boolean {
        val status = bus.status.value
        if (status !is BlueZStatus.Available) {
            log.warn("cannot register the GATT application ({}): BlueZ is {}", reason, status)
            return false
        }
        val connection = bus.connection()
        if (connection == null) {
            log.warn("cannot register the GATT application ({}): no bus connection", reason)
            return false
        }

        return withContext(Dispatchers.IO) {
            /*
             * Reported, never changed. Powering the adapter on is a global side effect on hardware
             * this process does not own, and the spike's version of it never put the state back --
             * so a user who had Bluetooth off found it on afterwards. Say so and carry on; the
             * registration below fails cleanly if the radio really is down.
             */
            if (!bus.isPowered()) {
                log.warn(
                    "adapter {} reports Powered=false -- registering anyway and leaving it alone",
                    status.adapterPath
                )
            }

            // Recorded before the attempt, not after it: see [attemptedOwner].
            attemptedOwner = status.owner

            if (!exportLocked(connection)) return@withContext false

            val manager = bus.proxy(status.adapterPath, GattManager1::class.java)
            if (manager == null) {
                log.warn("no GattManager1 proxy on {}", status.adapterPath)
                return@withContext false
            }

            registerWithHandleLadder(manager, reason, status.owner)
        }
    }

    /**
     * Walks [HANDLE_BASES] until BlueZ accepts the application, classifying every failure.
     *
     * The ladder advances **only** on a confirmed handle/database collision. The spike advanced it
     * on any exception at all, which is a trap: a marshalling bug or a daemon timeout then looks
     * exactly like a collision, and a later base succeeding hides the original defect entirely --
     * the tree registers at 0x0080 and nobody ever learns that 0x0040 failed for an unrelated
     * reason. Anything that is not a collision fails immediately, carrying the real error.
     */
    private fun registerWithHandleLadder(
        manager: GattManager1,
        reason: String,
        owner: String
    ): Boolean {
        for (base in HANDLE_BASES) {
            service.handle = base
            characteristic.handle = base + CHARACTERISTIC_HANDLE_OFFSET
            log.info(
                "RegisterApplication({}) attempt: service Handle=0x{}, characteristic Handle=0x{} ({})",
                GATT_APP_PATH,
                "%04X".format(service.handle),
                "%04X".format(characteristic.handle),
                reason
            )

            try {
                manager.RegisterApplication(DBusPath(GATT_APP_PATH), emptyMap())
                active = true
                registeredOwner = owner
                log.info(
                    "GATT application registered at handle base 0x{} with bluetoothd {}",
                    "%04X".format(base),
                    owner
                )
                return true
            } catch (t: Throwable) {
                val text = dbusErrorText(t)
                if (isAlreadyExists(t)) {
                    // BlueZ already holds this exact path for us -- a re-registration that raced a
                    // registration that had in fact survived. The ladder must not advance: moving
                    // the handles now would leave our exported properties disagreeing with what
                    // BlueZ registered.
                    log.info("GATT application was already registered at {}: {}", GATT_APP_PATH, text)
                    active = true
                    registeredOwner = owner
                    return true
                }
                if (isIndeterminateRegistration(t)) {
                    // We never saw the reply, so BlueZ may well have registered the tree. Do not
                    // move the handles under a registration that might exist, and make sure
                    // teardown still tries to give it back.
                    log.error(
                        "RegisterApplication at handle base 0x{} got no reply; BlueZ may have " +
                            "registered the application anyway: {}",
                        "%04X".format(base),
                        text
                    )
                    registeredOwner = owner
                    return false
                }
                if (!isHandleCollision(t)) {
                    log.error(
                        "RegisterApplication failed at handle base 0x{} and not because of a handle " +
                            "collision -- not advancing the ladder: {}",
                        "%04X".format(base),
                        text
                    )
                    return false
                }
                log.warn("handle base 0x{} collided, trying the next: {}", "%04X".format(base), text)
            }
        }
        log.error("every handle base in {} collided; the GATT application is not registered", HANDLE_BASES)
        return false
    }

    /** Caller holds [gate]. Idempotent: dbus-java replaces an export on the same path. */
    private fun exportLocked(connection: DBusConnection): Boolean = try {
        application.live = true
        service.live = true
        characteristic.live = true
        // Leaves first. BlueZ reads the root's GetManagedObjects during RegisterApplication and
        // then calls GetAll on the paths that reply names, so the children must exist by the time
        // the root can be reached.
        connection.exportObject(GATT_CHARACTERISTIC_PATH, characteristic)
        connection.exportObject(GATT_SERVICE_PATH, service)
        connection.exportObject(GATT_APP_PATH, application)
        log.debug("exported {}, {}, {}", GATT_APP_PATH, GATT_SERVICE_PATH, GATT_CHARACTERISTIC_PATH)
        true
    } catch (t: Throwable) {
        log.error("exporting the GATT object tree failed: {}", dbusErrorText(t))
        false
    }

    /** Caller holds [gate]. */
    private fun unregisterLocked() {
        if (!active && registeredOwner == null) return
        active = false
        val adapterPath = (bus.status.value as? BlueZStatus.Available)?.adapterPath
        val manager = adapterPath?.let { bus.proxy(it, GattManager1::class.java) }
        if (manager == null) {
            log.debug("no GattManager1 to unregister against; BlueZ has dropped the registration already")
            return
        }
        try {
            manager.UnregisterApplication(DBusPath(GATT_APP_PATH))
            log.info("GATT application unregistered")
        } catch (t: Throwable) {
            // Expected whenever BlueZ already dropped us -- an adapter power cycle, a bluetoothd
            // restart. Not a failure, and not worth a warning.
            if (isDoesNotExist(t)) {
                log.debug("UnregisterApplication: BlueZ had already dropped it ({})", dbusErrorText(t))
            } else {
                log.warn("UnregisterApplication failed: {}", dbusErrorText(t))
            }
        }
    }

    /** Caller holds [gate]. Tolerates paths that were never exported. */
    private fun unexportLocked() {
        val connection = bus.connection() ?: return
        listOf(GATT_APP_PATH, GATT_SERVICE_PATH, GATT_CHARACTERISTIC_PATH).forEach { path ->
            runCatching { connection.unExportObject(path) }
                .onFailure { log.debug("unExportObject({}): {}", path, it.message) }
        }
    }

    // -----------------------------------------------------------------------------------------
    // Background collectors
    // -----------------------------------------------------------------------------------------

    /** Caller holds [gate]. */
    private fun startScopeLocked() {
        if (scope != null) return
        val serverScope =
            CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineName("bitchat-gatt-server"))
        scope = serverScope
        serverScope.launch { watchStatus() }
        serverScope.launch { watchDisconnects() }
        serverScope.launch { watchRemovals() }
        serverScope.launch { drainInbound() }
        serverScope.launch { sweepReassembly() }
    }

    /**
     * Re-registers when bluetoothd comes back under a new unique name.
     *
     * The failure this exists for is invisible from the inside: after a bluetoothd restart our
     * objects are still exported, `active` is still true, and no error is ever reported -- BlueZ
     * simply never calls us again. A node in that state looks healthy and is unreachable. Comparing
     * the owner is the only signal that says so.
     */
    private suspend fun watchStatus() {
        bus.status.collect { status ->
            when (status) {
                is BlueZStatus.Available -> {
                    // Checked twice: once cheaply, and again under the gate, because a start or a
                    // teardown may have been running the whole time we waited for it.
                    if (!desired || status.owner == attemptedOwner) return@collect
                    gate.withLock {
                        if (!desired || status.owner == attemptedOwner) return@withLock
                        log.warn(
                            "bluetoothd is now {} (was {}) -- re-exporting and re-registering",
                            status.owner,
                            attemptedOwner ?: "absent"
                        )
                        active = false
                        // Whatever the old daemon held died with it, so there is nothing left to
                        // give back and no reason for teardown to try.
                        registeredOwner = null
                        // Re-export rather than assume: our objects survive a daemon restart, but
                        // not a reconnect of our own socket, and from here the two look identical.
                        unexportLocked()
                        registerLocked("bluetoothd owner ${status.owner}")
                    }
                }

                else -> {
                    if (active) {
                        log.warn("BlueZ is {} -- the GATT registration is void until it returns", status)
                    }
                    active = false
                    registeredOwner = null
                }
            }
        }
    }

    /**
     * `Device1.Connected = false` -- one of the two ways a central leaves.
     *
     * Note there is no matching *connect* watcher. A link coming up is not by itself a bitchat
     * central: any device BlueZ connects for any reason raises it, and it says nothing about
     * whether the peer ever found our characteristic. Registration happens on the first write
     * instead; see [onWriteValue].
     */
    private suspend fun watchDisconnects() {
        bus.deviceStateChanges.collect { event ->
            if (event.connected == false) onLinkDown(event.address, "Connected=false")
        }
    }

    /**
     * The other way: BlueZ drops the device object entirely.
     *
     * This is what an Android phone rotating its resolvable private address looks like, and it
     * arrives with no `Connected = false` beforehand. Missing it is what let the registry grow one
     * dead entry per rotation.
     */
    private suspend fun watchRemovals() {
        bus.interfacesRemoved.collect { event ->
            if (IFACE_DEVICE1 !in event.interfaces) return@collect
            event.deviceAddress?.let { onLinkDown(it, "InterfacesRemoved") }
        }
    }

    /** The single ordered path from the radio to the delegate. */
    private suspend fun drainInbound() {
        for (event in inbound) {
            val target = delegate
            if (target == null) {
                log.debug("dropping {} -- no delegate is set yet", event)
                continue
            }
            // A throwing delegate must not take the drain loop down with it; the next packet is
            // still deliverable and the mesh has no other way in.
            runCatching {
                when (event) {
                    is InboundEvent.Connected -> target.onClientConnected(event.address)
                    is InboundEvent.Disconnected -> target.onClientDisconnected(event.address)
                    is InboundEvent.Data -> {
                        framesDelivered.incrementAndGet()
                        target.onDataReceived(event.frame, event.address)
                    }
                }
            }.onFailure { log.error("the delegate threw on {}", event, it) }
        }
    }

    /**
     * Ages out reassembly buffers left behind by a peer that vanished mid-frame.
     *
     * Without it, an address that starts a chunked frame and then rotates pins its partial buffer
     * forever and starts a second one under its new address. `linuxMain` has exactly this leak.
     */
    private suspend fun sweepReassembly() {
        while (true) {
            delay(REASSEMBLY_SWEEP_INTERVAL_MS)
            chunker.sweep(System.currentTimeMillis())
        }
    }

    // -----------------------------------------------------------------------------------------
    // Inbound
    // -----------------------------------------------------------------------------------------

    /**
     * `GattCharacteristic1.WriteValue`, on dbus-java's METHODCALL thread.
     *
     * Everything here is bounded and non-blocking: parse the options dict, update two maps, feed
     * the chunker (a `memcpy` under one lock), post at most one event. In particular it does **not**
     * notify. Echoing from inside a write would make an outbound D-Bus emission part of an inbound
     * dispatch, which is the one thing the threading contract forbids; the delegate decides what to
     * answer and [emit] sends it from a coroutine.
     */
    private fun onWriteValue(value: ByteArray, options: Map<String, Variant<*>>) {
        val address = deviceAddressOf(options)
        if (address == null) {
            /*
             * BlueZ documents `device` as always present on a server-role write, and the spike
             * observed it on every one. Without it there is nothing to key reassembly by and no
             * peer to attribute the bytes to, so the write is refused rather than filed under a
             * synthetic address that would then be handed to the mesh as though it were a peer.
             */
            writesUnattributed.incrementAndGet()
            log.warn("WriteValue of {}B with no usable 'device' option; dropped", value.size)
            return
        }

        writesReceived.incrementAndGet()
        harvestMtu(address, options)

        /*
         * A central is registered on its first write, and only there.
         *
         * This is a real limitation, not a shortcut: `StartNotify` is raised once per
         * characteristic rather than once per central and carries no device identity, so a peer
         * that subscribes and never writes is invisible to this registry -- we would never count it
         * as connected and `notifyCharacteristic` would refuse to send to it, even though the
         * notification it emits would in fact reach it. Every bitchat peer announces itself on
         * connect, so in practice the first write arrives within a second of the link. The
         * `startNotify` counter against the client count in [statusLine] is what would show this
         * assumption failing in the wild.
         */
        if (clients.onConnected(address)) {
            log.info("central {} registered on its first write", address)
            post(InboundEvent.Connected(address))
        }

        ingestOnDispatchThread(address, value)
    }

    /** Reassembly, and the hand-off. Safe on the dispatch thread; see [BleChunker]. */
    private fun ingestOnDispatchThread(address: String, value: ByteArray) {
        val frame = chunker.receive(address, value) ?: return
        post(InboundEvent.Data(frame, address))
    }

    private suspend fun ingest(address: String, value: ByteArray) {
        if (clients.onConnected(address)) post(InboundEvent.Connected(address))
        val frame = chunker.receive(address, value) ?: return
        inbound.send(InboundEvent.Data(frame, address))
    }

    /**
     * Non-blocking hand-off. Refuses rather than waits, because every caller of this is either a
     * D-Bus dispatch thread or a signal collector, and neither may be parked on the delegate.
     */
    private fun post(event: InboundEvent) {
        if (inbound.trySend(event).isSuccess) return
        inboundDropped.incrementAndGet()
        log.warn("inbound queue is full ({} events); dropped {}", INBOUND_QUEUE_CAPACITY, event)
    }

    private fun onStartNotify() {
        startNotifyCalls.incrementAndGet()
        subscriberPresent.set(true)
        log.info(
            "StartNotify -- at least one central is subscribed (BlueZ does not say which; {} registered)",
            clients.size()
        )
    }

    private fun onStopNotify() {
        subscriberPresent.set(false)
        log.info("StopNotify -- the last subscriber went away")
    }

    /**
     * True when a notification emitted now would reach somebody.
     *
     * Two independent facts can each make that so, and the registry only knows one of them. A
     * central that has written is in [clients]; a central that has only subscribed is not, because
     * `StartNotify` carries no device identity. Emitting still reaches the latter -- the signal goes
     * to the characteristic's own object path, which names no device.
     *
     * Gating delivery on the registry alone therefore deadlocked a real peer: the Orange Pi
     * subscribed, waited for our announce before writing, and we refused to send one because we had
     * not seen a write. It gave up after a few seconds, twice, visible as a `StartNotify` and a
     * `StopNotify` with no write between them.
     */
    fun canReachSubscriber(): Boolean = clients.size() > 0 || subscriberPresent.get()

    private fun onLinkDown(address: String, cause: String) {
        if (!clients.onDisconnected(address)) return
        chunker.forget(address)
        mtus.remove(address)
        log.info("central {} disconnected ({}); notification cap is now {}B", address, cause, notificationChunkCap())
        post(InboundEvent.Disconnected(address))
    }

    /**
     * The calling central's address, from `options["device"]`.
     *
     * The value is a `Variant<DBusPath>` and **not** a `Variant<String>`; reading it as a string
     * throws a class cast at the exact moment a real peer first writes to us. The path is turned
     * into an address by [BlueZObjectPath] rather than by local string surgery, so that this, the
     * `Device1` signal and `InterfacesRemoved` cannot end up spelling the same peer three ways.
     */
    private fun deviceAddressOf(options: Map<String, Variant<*>>): String? {
        val path = when (val value = options[OPTION_DEVICE]?.value) {
            is DBusPath -> value.path
            // Tolerated, not expected: a daemon that sent `s` here would otherwise be a silent
            // total failure of the peripheral role rather than one line in the log.
            is String -> value.also { log.debug("options[device] arrived as a String, not a DBusPath") }
            else -> null
        } ?: return null
        return BlueZObjectPath.deviceAddress(path)
    }

    /**
     * Records the ATT MTU BlueZ reported for this write.
     *
     * In the peripheral role this is the *only* way the MTU ever reaches us. BlueZ cannot put an
     * `MTU` property on an object we export, so the central-role trick of reading it off the remote
     * characteristic has no equivalent here; the value arrives in the options dict of every
     * `WriteValue`, typed `q`, which on the JVM is a [UInt16] rather than a `Short`.
     */
    private fun harvestMtu(address: String, options: Map<String, Variant<*>>) {
        val mtu = (options[OPTION_MTU]?.value as? Number)?.toInt() ?: return
        if (mtu <= 0) return
        if (mtus.put(address, mtu) == mtu) return
        log.info(
            "central {} negotiated an ATT MTU of {}; notification chunk cap is now {}B",
            address,
            mtu,
            notificationChunkCap()
        )
    }

    // -----------------------------------------------------------------------------------------
    // Outbound
    // -----------------------------------------------------------------------------------------

    /**
     * The largest notification chunk that is safe right now: `min(500, smallest live MTU - 3)`.
     *
     * Three properties, each of which was a bug in an earlier version of this code:
     *
     *  - **It is a floor across links, not a per-link value.** A notification reaches every
     *    subscriber at once, so with two centrals at different MTUs the only safe size is the
     *    smaller one. There is no such thing as a per-subscriber notification MTU.
     *  - **500 is both the ceiling and the fallback.** Discovery may only ever lower the cap. With
     *    no MTU known at all -- no central has written yet, or the only ones that did have left --
     *    this returns 500, which is exactly what every existing peer already assumes of us.
     *  - **Only live links count.** An entry for a peer that has gone would otherwise pin the cap
     *    low forever; [onLinkDown] drops it, and the intersection with the registry here means a
     *    missed drop cannot do lasting harm either.
     *
     * The cap matters because bluetoothd truncates an oversized notification *silently*: the
     * symptom is large messages vanishing while small ones work, with nothing logged anywhere.
     */
    private fun notificationChunkCap(): Int {
        val smallest = clients.addresses().mapNotNull { mtus[it] }.minOrNull()
            ?: return MAX_NOTIFICATION_CHUNK
        return min(MAX_NOTIFICATION_CHUNK, smallest - 3).coerceAtLeast(MIN_NOTIFICATION_CHUNK)
    }

    /**
     * The chunker sized for [cap], rebuilt only when the cap moves.
     *
     * Separate from the inbound [chunker] because that one carries per-device reassembly state that
     * must survive an MTU change, while this one is pure arithmetic. Caller holds [gate].
     */
    private fun chunkerFor(cap: Int): BleChunker {
        if (cap != outboundChunkSize) {
            outboundChunker = BleChunker(cap)
            outboundChunkSize = cap
            log.info("outbound chunk size is now {}B", cap)
        }
        return outboundChunker
    }

    /**
     * The one serialized outbound path. [label] names the intended audience for the log only.
     */
    private suspend fun emit(label: String, data: ByteArray): Boolean = gate.withLock {
        if (!active) {
            log.warn("notify of {}B refused: the GATT application is not registered", data.size)
            return@withLock false
        }
        val connection = bus.connection()
        if (connection == null) {
            log.warn("notify of {}B refused: no bus connection", data.size)
            return@withLock false
        }

        val chunks = chunkerFor(notificationChunkCap()).chunk(data)
        if (chunks.size > 1) {
            log.debug("notifying {} in {} chunks ({}B total)", label, chunks.size, data.size)
        }
        chunks.forEachIndexed { index, chunk ->
            if (!emitOne(connection, chunk)) {
                log.warn("notify to {} failed at chunk {}/{}", label, index + 1, chunks.size)
                return@withLock false
            }
            // Pacing, not politeness: both peers space chunks this far apart, and a burst without
            // it overruns the controller's transmit queue and drops the tail of the frame.
            if (index < chunks.lastIndex) delay(CHUNK_DELAY_MS)
        }
        true
    }

    /**
     * A notification is a `PropertiesChanged` on our characteristic's own path carrying the new
     * `Value`; BlueZ picks it off the bus and turns it into an ATT handle-value notification. It is
     * emitted once, never once per client -- the path names no device, so N emissions would send
     * N copies to every subscriber rather than one each.
     */
    private fun emitOne(connection: DBusConnection, bytes: ByteArray): Boolean = try {
        val signal = Properties.PropertiesChanged(
            GATT_CHARACTERISTIC_PATH,
            IFACE_GATT_CHARACTERISTIC1,
            mapOf<String, Variant<*>>("Value" to Variant(bytes)),
            emptyList<String>()
        )
        connection.sendMessage(signal)
        notificationsEmitted.incrementAndGet()
        true
    } catch (t: Throwable) {
        log.warn("emitting a {}B notification failed: {}", bytes.size, dbusErrorText(t))
        false
    }

    /** What crosses [inbound]. */
    private sealed interface InboundEvent {
        data class Connected(val address: String) : InboundEvent
        data class Disconnected(val address: String) : InboundEvent
        data class Data(val frame: ByteArray, val address: String) : InboundEvent {
            // ByteArray gives a data class identity equals/hashCode, which is meaningless here and
            // a trap for anyone who later compares two events. Stated explicitly.
            override fun equals(other: Any?): Boolean = this === other
            override fun hashCode(): Int = System.identityHashCode(this)
            override fun toString(): String = "Data(${frame.size}B from $address)"
        }
    }
}

// ---------------------------------------------------------------------------------------------
// The exported objects
// ---------------------------------------------------------------------------------------------

/*
 * Three rules govern all three classes below, and none of them is obvious from the source:
 *
 *  - They are top-level `public` classes. A `private` top-level class compiles to a package-private
 *    JVM class, and dbus-java invokes exported methods reflectively from its own package, so BlueZ's
 *    first call would be an IllegalAccessException. A Kotlin `object` would not work either: the
 *    exported instance has to be an ordinary one.
 *  - Each is annotated `@JvmSuppressWildcards`. Kotlin's `Map<K, out V>` variance becomes
 *    `Map<String, ? extends Variant<?>>` in an override's reflected signature, where dbus-java
 *    compares against the invariant form on `Properties`/`ObjectManager`. Omitting it does not stop
 *    the class compiling; it stops BlueZ's call being routed to it.
 *  - Each implements `Properties`, including the application root. bluetoothd issues
 *    `org.freedesktop.DBus.Properties.GetAll` against objects in our tree and an object that cannot
 *    answer it is a hole in the tree. The spike left the root without it.
 *
 * `live` is the teardown interlock. It is set false before anything is unregistered or unexported,
 * so a call already in flight on the dispatch thread is refused cleanly instead of being answered
 * out of a half-dismantled server.
 */

/**
 * The application root. Its whole job is to answer the `GetManagedObjects` that BlueZ issues from
 * inside `RegisterApplication`, describing the service and the characteristic in one reply.
 */
@JvmSuppressWildcards
class BitchatGattApplication(
    private val service: BitchatGattService,
    private val characteristic: BitchatGattCharacteristic
) : ObjectManager, Properties {

    private val log = LoggerFactory.getLogger("bitchat.ble.gatt.app")

    @Volatile
    var live: Boolean = false

    override fun getObjectPath(): String = GATT_APP_PATH

    override fun isRemote(): Boolean = false

    override fun GetManagedObjects(): Map<DBusPath, Map<String, Map<String, Variant<*>>>> =
        dbusHandler("app.GetManagedObjects") {
            if (!live) {
                log.debug("GetManagedObjects after teardown; answering with an empty tree")
                return@dbusHandler emptyMap()
            }
            val objects = linkedMapOf<DBusPath, Map<String, Map<String, Variant<*>>>>(
                DBusPath(GATT_SERVICE_PATH) to mapOf(IFACE_GATT_SERVICE1 to service.properties()),
                DBusPath(GATT_CHARACTERISTIC_PATH) to
                    mapOf(IFACE_GATT_CHARACTERISTIC1 to characteristic.properties())
            )
            // Logged in full because this single reply is the entire contract BlueZ registers
            // against: a wrong handle, flag or Service path shows up here and nowhere else.
            objects.forEach { (path, interfaces) ->
                interfaces.forEach { (name, properties) ->
                    log.info("  {} {} -> {}", path.path, name, describeProperties(properties))
                }
            }
            objects
        }

    /**
     * The root carries no D-Bus properties of its own, but it must still *answer*. A `GetAll` that
     * errors out mid-registration is indistinguishable, from BlueZ's side, from an object that is
     * not there.
     */
    override fun GetAll(interfaceName: String): Map<String, Variant<*>> =
        dbusHandler("app.GetAll") { emptyMap() }

    override fun <A> Get(interfaceName: String, propertyName: String): A =
        throw DBusExecutionException("$GATT_APP_PATH has no property $propertyName")

    override fun <A> Set(interfaceName: String, propertyName: String, value: A) {
        log.debug("Properties.Set({}, {}) on the application root -- ignored", interfaceName, propertyName)
    }
}

/** The primary service. `org.bluez.GattService1` has no methods at all; only properties. */
@JvmSuppressWildcards
class BitchatGattService : GattService1, Properties {

    private val log = LoggerFactory.getLogger("bitchat.ble.gatt.svc")

    @Volatile
    var live: Boolean = false

    /**
     * Mutable because the handle ladder picks it across successive `RegisterApplication` attempts,
     * and because BlueZ writes back the handle it actually assigned once registration succeeds.
     */
    @Volatile
    var handle: Int = HANDLE_BASES.first()

    override fun getObjectPath(): String = GATT_SERVICE_PATH

    override fun isRemote(): Boolean = false

    fun properties(): Map<String, Variant<*>> = linkedMapOf<String, Variant<*>>(
        "UUID" to Variant(BITCHAT_SERVICE_UUID),
        "Primary" to Variant(true),
        // D-Bus `q`. UInt16, never Kotlin's UShort: UShort is an inline class that erases to a JVM
        // short, so dbus-java would compute `n` -- a different type on the wire, and not the one
        // BlueZ reads.
        "Handle" to Variant(UInt16(handle))
    )

    override fun GetAll(interfaceName: String): Map<String, Variant<*>> =
        dbusHandler("svc.GetAll") {
            if (!live) return@dbusHandler emptyMap()
            if (interfaceName != IFACE_GATT_SERVICE1 && interfaceName.isNotEmpty()) {
                return@dbusHandler emptyMap()
            }
            properties()
        }

    @Suppress("UNCHECKED_CAST")
    override fun <A> Get(interfaceName: String, propertyName: String): A {
        val value = dbusHandler("svc.Get") { properties()[propertyName] }
            ?: throw DBusExecutionException("$IFACE_GATT_SERVICE1 has no property $propertyName")
        return value as A
    }

    override fun <A> Set(interfaceName: String, propertyName: String, value: A) {
        if (propertyName != "Handle") return
        handle = unwrapUInt16(value) ?: handle
        log.info("BlueZ assigned the service Handle=0x{}", "%04X".format(handle))
    }
}

/**
 * The characteristic -- the only object BlueZ ever calls a real method on.
 *
 * The three callbacks are the seam between dbus-java's dispatch threads and the service. They are
 * plain function types rather than a back-reference so that this class cannot accidentally reach
 * into the service and do something slow.
 */
@JvmSuppressWildcards
class BitchatGattCharacteristic(
    private val onWrite: (ByteArray, Map<String, Variant<*>>) -> Unit,
    private val onSubscribe: () -> Unit,
    private val onUnsubscribe: () -> Unit
) : GattCharacteristic1, Properties {

    private val log = LoggerFactory.getLogger("bitchat.ble.gatt.chr")

    @Volatile
    var live: Boolean = false

    @Volatile
    var handle: Int = HANDLE_BASES.first() + CHARACTERISTIC_HANDLE_OFFSET

    override fun getObjectPath(): String = GATT_CHARACTERISTIC_PATH

    override fun isRemote(): Boolean = false

    fun properties(): Map<String, Variant<*>> = linkedMapOf<String, Variant<*>>(
        "UUID" to Variant(BITCHAT_CHARACTERISTIC_UUID),
        // `o`, not `s`. BlueZ resolves the owning service by path and rejects a string.
        "Service" to Variant(DBusPath(GATT_SERVICE_PATH)),
        // Never `Variant(CHARACTERISTIC_FLAGS)`: see variantOfStrings.
        "Flags" to variantOfStrings(CHARACTERISTIC_FLAGS),
        "Handle" to Variant(UInt16(handle))
    )

    override fun GetAll(interfaceName: String): Map<String, Variant<*>> =
        dbusHandler("chr.GetAll") {
            if (!live) return@dbusHandler emptyMap()
            if (interfaceName != IFACE_GATT_CHARACTERISTIC1 && interfaceName.isNotEmpty()) {
                return@dbusHandler emptyMap()
            }
            properties()
        }

    @Suppress("UNCHECKED_CAST")
    override fun <A> Get(interfaceName: String, propertyName: String): A {
        val value = dbusHandler("chr.Get") { properties()[propertyName] }
            ?: throw DBusExecutionException("$IFACE_GATT_CHARACTERISTIC1 has no property $propertyName")
        return value as A
    }

    override fun <A> Set(interfaceName: String, propertyName: String, value: A) {
        if (propertyName != "Handle") return
        handle = unwrapUInt16(value) ?: handle
        log.info("BlueZ assigned the characteristic Handle=0x{}", "%04X".format(handle))
    }

    /**
     * Always empty.
     *
     * bitchat is a push protocol: everything a peer needs arrives as a notification, and no peer
     * reads. The `read` flag exists because some central stacks probe a characteristic on
     * discovery and treat a refusal as a broken service, so answering with zero bytes is cheaper
     * than the alternative. Deliberately does not replay the last notification -- that would hand a
     * peer that connected late a stale frame it has no way to place.
     */
    override fun ReadValue(options: Map<String, Variant<*>>): ByteArray = dbusHandler("chr.ReadValue") {
        log.debug("ReadValue -- answering with zero bytes")
        ByteArray(0)
    }

    override fun WriteValue(value: ByteArray, options: Map<String, Variant<*>>) {
        if (!live) {
            log.debug("WriteValue of {}B after teardown; refused", value.size)
            throw DBusExecutionException("the bitchat GATT server is shutting down")
        }
        dbusHandler("chr.WriteValue") { onWrite(value, options) }
    }

    /**
     * BlueZ raises this once per characteristic rather than once per central, so it says that at
     * least one subscriber exists and nothing whatsoever about who. That is why the client registry
     * is driven by `WriteValue` instead.
     */
    override fun StartNotify() = dbusHandler("chr.StartNotify") { onSubscribe() }

    override fun StopNotify() = dbusHandler("chr.StopNotify") { onUnsubscribe() }
}

/** BlueZ writes `Handle` back as a `q`; dbus-java may hand it over bare or still wrapped. */
private fun unwrapUInt16(value: Any?): Int? = when (value) {
    // UInt16 extends java.lang.Number, so one branch covers both it and a bare Short.
    is Number -> value.toInt()
    is Variant<*> -> unwrapUInt16(value.value)
    else -> null
}

private fun describeProperties(properties: Map<String, Variant<*>>): String =
    properties.entries.joinToString(", ") { (key, variant) ->
        "$key=${variant.value}[${variant.sig}/${variant.value?.javaClass?.simpleName}]"
    }
