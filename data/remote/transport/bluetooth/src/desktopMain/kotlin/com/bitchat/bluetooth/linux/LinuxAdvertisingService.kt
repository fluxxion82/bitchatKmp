@file:Suppress("FunctionName")

package com.bitchat.bluetooth.linux

import com.bitchat.bluetooth.service.AdvertisingService
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
import org.freedesktop.dbus.interfaces.Properties
import org.freedesktop.dbus.types.Variant
import org.slf4j.LoggerFactory

/*
 * =============================================================================================
 * Peripheral-role advertising: one exported LEAdvertisement1 object, registered with BlueZ.
 * =============================================================================================
 *
 * The whole mechanism is: export an object carrying four properties, hand its path to
 * `org.bluez.LEAdvertisingManager1.RegisterAdvertisement`, and let bluetoothd read the properties
 * back over `Properties.GetAll` *before* it answers that call. It is the cheap half of the
 * peripheral role -- BlueZ makes exactly one callback into us, and a failure is almost always a
 * marshalling fault on our side rather than anything subtle.
 *
 * Two things this deliberately does not do, both of which the `linuxMain` reference does:
 *
 *  - **It never touches `Adapter1.Powered`.** The spike powers the adapter on and never puts it
 *    back, so a user who had Bluetooth off finds it on afterwards. Power state is reported here and
 *    left alone.
 *  - **There is no legacy `Discoverable`/`Alias` fallback.** Both are global adapter state. Setting
 *    them can leave a host renamed and discoverable with no way to restore it -- no in-process
 *    teardown runs after `kill -9` -- and the fallback only ever existed for daemons this transport
 *    does not target. A `RegisterAdvertisement` that fails is reported as a failure.
 */

/**
 * Where our advertisement object lives.
 *
 * Local convention, not a wire constant: a private conversation between this process and its own
 * bluetoothd that no peer ever observes.
 */
const val ADVERTISEMENT_PATH: String = "/org/bitchat/advertisement0"

/** The name BlueZ advertises when the caller does not supply one. */
private const val DEFAULT_LOCAL_NAME = "bitchat"

/**
 * How long to wait before re-arming after BlueZ releases the advertisement.
 *
 * A daemon that drops the instance immediately -- an adapter that is going down, another client
 * that took the last advertising slot -- would otherwise put this into a tight register/release
 * loop against bluetoothd. One second is long enough that the loop is visible in the log rather
 * than being a spin.
 */
private const val REARM_DELAY_MS = 1_000L

/**
 * The desktop-Linux advertising service.
 *
 * @param bus the shared connection; this class exports onto it but does not own it.
 */
class LinuxAdvertisingService(private val bus: BlueZBus) : AdvertisingService {

    private val log = LoggerFactory.getLogger("bitchat.ble.adv")

    /**
     * Serializes registration, re-registration, re-arm and teardown -- every outbound call this
     * class makes. A coroutine `Mutex` and not a lock, because nothing that runs on a D-Bus
     * dispatch thread is ever allowed to wait for it: [BitchatAdvertisement.Release] hands off
     * through [rearm] instead.
     */
    private val gate = Mutex()

    /** What the caller asked for, as opposed to what BlueZ currently believes. */
    @Volatile
    private var desired = false

    /** True while BlueZ holds our advertisement. This is the advertising-active gauge. */
    @Volatile
    private var advertising = false

    /**
     * bluetoothd's unique bus name from the moment BlueZ may be holding our advertisement -- on
     * success, and also when the outcome was indeterminate. Gates the unregister at teardown.
     */
    @Volatile
    private var registeredOwner: String? = null

    /**
     * The unique bus name we last *attempted* a registration against, whatever came of it.
     *
     * A bluetoothd restart leaves our exported object untouched and silently discards the
     * registration, and BlueZ never calls to say so; comparing the owner is the only way to notice.
     * It tracks the attempt rather than the success so that a failed registration is not
     * immediately retried by [watchStatus], which sees the current value of a `StateFlow` the
     * moment it subscribes -- that would report the same error twice and recover from nothing.
     */
    @Volatile
    private var attemptedOwner: String? = null

    @Volatile
    private var serviceUuid: String = BITCHAT_SERVICE_UUID

    @Volatile
    private var localName: String = DEFAULT_LOCAL_NAME

    private var scope: CoroutineScope? = null

    /**
     * Re-arm requests, carrying the reason for the log.
     *
     * Conflated: only the latest request is actionable, and `trySend` on a conflated channel can
     * neither block nor be refused -- which is what makes it legal to call from BlueZ's inbound
     * `Release` on the D-Bus dispatch thread.
     */
    private val rearm = Channel<String>(Channel.CONFLATED)

    private val advertisement = BitchatAdvertisement(
        properties = ::advertisementProperties,
        onRelease = ::onReleased
    )

    // -----------------------------------------------------------------------------------------
    // AdvertisingService
    // -----------------------------------------------------------------------------------------

    /**
     * Exports the advertisement and registers it.
     *
     * Never throws: an advertisement that cannot be registered leaves a node that is unreachable by
     * *new* peers but otherwise perfectly functional, and taking the transport down over it would
     * turn a degradation into an outage. [desired] stays set, so a bluetoothd that returns later
     * gets a fresh attempt without anybody calling back in.
     */
    override suspend fun startAdvertising(serviceUuid: String, deviceName: String) {
        val uuid = serviceUuid.ifBlank { BITCHAT_SERVICE_UUID }
        val name = deviceName.ifBlank { DEFAULT_LOCAL_NAME }

        gate.withLock {
            if (advertising && uuid == this.serviceUuid && name == this.localName) {
                log.debug("already advertising as {} ({})", name, uuid)
                return@withLock
            }
            this.serviceUuid = uuid
            this.localName = name
            desired = true

            if (advertising) {
                /*
                 * BlueZ reads the advertisement's properties exactly once, from inside
                 * `RegisterAdvertisement`, and never looks at the object again. Updating the fields
                 * and returning would leave `isAdvertising` true, `GetAll` reporting the new name,
                 * and the air carrying the old one -- so a rename only reaches a peer by
                 * registering afresh.
                 */
                log.info("advertised identity changed to {} ({}); re-registering", name, uuid)
                unregisterLocked()
            }

            registerLocked("startAdvertising")
            // After the first attempt, so that the status watcher -- which sees the current value
            // of a StateFlow the moment it subscribes -- does not treat this very daemon as new.
            // Started even when that attempt failed: BlueZ returning later is what it is for.
            startScopeLocked()
        }
    }

    /**
     * Unregisters, unexports and releases, in that order, exactly once.
     *
     * `live` is cleared first so that a `GetAll` already being dispatched when this runs is
     * answered with an empty map rather than racing the teardown, and the whole sequence is under
     * [gate] so it cannot interleave with a re-arm or a re-registration.
     */
    override suspend fun stopAdvertising() {
        gate.withLock {
            if (!desired && !advertising && registeredOwner == null && scope == null) return@withLock
            desired = false
            advertisement.live = false

            unregisterLocked()
            unexportLocked()

            advertising = false
            registeredOwner = null
            attemptedOwner = null
            scope?.cancel()
            scope = null
            log.info("advertising stopped")
        }
    }

    /** True while BlueZ holds the registration -- false again the moment it calls `Release`. */
    override fun isAdvertising(): Boolean = advertising

    // -----------------------------------------------------------------------------------------
    // Registration
    // -----------------------------------------------------------------------------------------

    /** Caller holds [gate]. */
    private suspend fun registerLocked(reason: String): Boolean {
        val status = bus.status.value
        if (status !is BlueZStatus.Available) {
            log.warn("cannot register the advertisement ({}): BlueZ is {}", reason, status)
            return false
        }
        val connection = bus.connection()
        if (connection == null) {
            log.warn("cannot register the advertisement ({}): no bus connection", reason)
            return false
        }

        return withContext(Dispatchers.IO) {
            // Reported, never changed -- see the file header. A powered-off adapter is by far the
            // most common reason the call below fails, so it is worth naming before it does.
            if (!bus.isPowered()) {
                log.warn(
                    "adapter {} reports Powered=false -- registering anyway and leaving it alone",
                    status.adapterPath
                )
            }

            // Recorded before the attempt, not after it: see [attemptedOwner].
            attemptedOwner = status.owner

            if (!exportLocked(connection)) return@withContext false

            val manager = bus.proxy(status.adapterPath, LEAdvertisingManager1::class.java)
            if (manager == null) {
                log.warn("no LEAdvertisingManager1 proxy on {}", status.adapterPath)
                return@withContext false
            }

            log.info(
                "RegisterAdvertisement({}) as {} advertising {} ({})",
                ADVERTISEMENT_PATH, localName, serviceUuid, reason
            )
            try {
                manager.RegisterAdvertisement(DBusPath(ADVERTISEMENT_PATH), emptyMap())
                onRegistered(status.owner, "accepted")
                true
            } catch (t: Throwable) {
                when {
                    // BlueZ already holds this exact path for us. Registering is idempotent from
                    // our side, so this is the state we wanted rather than a failure.
                    isAlreadyExists(t) -> {
                        onRegistered(status.owner, "already registered")
                        true
                    }

                    isIndeterminateRegistration(t) -> {
                        // BlueZ reads our properties back before it answers, so a reply we never
                        // saw says nothing about whether it went on to register -- it usually did.
                        // Not advertising as far as we know, but teardown must still give it back.
                        log.error(
                            "RegisterAdvertisement got no reply; BlueZ may have registered the " +
                                "advertisement anyway: {}",
                            dbusErrorText(t)
                        )
                        registeredOwner = status.owner
                        false
                    }

                    else -> {
                        log.error("RegisterAdvertisement failed: {}", dbusErrorText(t))
                        false
                    }
                }
            }
        }
    }

    private fun onRegistered(owner: String, how: String) {
        advertising = true
        registeredOwner = owner
        log.info("advertisement {} by bluetoothd {}", how, owner)
    }

    /** Caller holds [gate]. Idempotent: dbus-java replaces an export on the same path. */
    private fun exportLocked(connection: DBusConnection): Boolean = try {
        advertisement.live = true
        connection.exportObject(ADVERTISEMENT_PATH, advertisement)
        true
    } catch (t: Throwable) {
        log.error("exporting {} failed: {}", ADVERTISEMENT_PATH, dbusErrorText(t))
        false
    }

    /** Caller holds [gate]. */
    private fun unregisterLocked() {
        if (!advertising && registeredOwner == null) return
        advertising = false
        val adapterPath = (bus.status.value as? BlueZStatus.Available)?.adapterPath
        val manager = adapterPath?.let { bus.proxy(it, LEAdvertisingManager1::class.java) }
        if (manager == null) {
            log.debug("no LEAdvertisingManager1 to unregister against; BlueZ has dropped it already")
            return
        }
        try {
            manager.UnregisterAdvertisement(DBusPath(ADVERTISEMENT_PATH))
            log.info("advertisement unregistered")
        } catch (t: Throwable) {
            // The expected answer when BlueZ has already dropped the instance -- an adapter power
            // cycle, a `Release` we processed, a daemon restart. Not a failure.
            if (isDoesNotExist(t)) {
                log.debug("UnregisterAdvertisement: BlueZ had already dropped it")
            } else {
                log.warn("UnregisterAdvertisement failed: {}", dbusErrorText(t))
            }
        }
    }

    /** Caller holds [gate]. Tolerates a path that was never exported. */
    private fun unexportLocked() {
        val connection = bus.connection() ?: return
        runCatching { connection.unExportObject(ADVERTISEMENT_PATH) }
            .onFailure { log.debug("unExportObject({}): {}", ADVERTISEMENT_PATH, it.message) }
    }

    // -----------------------------------------------------------------------------------------
    // Background collectors
    // -----------------------------------------------------------------------------------------

    /** Caller holds [gate]. */
    private fun startScopeLocked() {
        if (scope != null) return
        val advScope =
            CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineName("bitchat-advertising"))
        scope = advScope
        advScope.launch { watchStatus() }
        advScope.launch { watchRearm() }
    }

    /**
     * Re-registers when bluetoothd comes back under a new unique name.
     *
     * The advertisement object of ours survives a daemon restart; BlueZ's registration of it does
     * not, and nothing reports the loss. A node in that state keeps its existing links and can
     * never be found by a new peer.
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
                            "bluetoothd is now {} (was {}) -- re-exporting and re-registering the advertisement",
                            status.owner,
                            attemptedOwner ?: "absent"
                        )
                        advertising = false
                        // Whatever the old daemon held died with it: nothing left to give back.
                        registeredOwner = null
                        // Re-export rather than assume: the object survives a daemon restart but
                        // not a reconnect of our own socket, and the two are indistinguishable here.
                        unexportLocked()
                        registerLocked("bluetoothd owner ${status.owner}")
                    }
                }

                else -> {
                    if (advertising) {
                        log.warn("BlueZ is {} -- the advertisement is void until it returns", status)
                    }
                    advertising = false
                    registeredOwner = null
                }
            }
        }
    }

    /** The serialized outbound half of [onReleased]. */
    private suspend fun watchRearm() {
        for (reason in rearm) {
            delay(REARM_DELAY_MS)
            gate.withLock {
                if (!desired || advertising) return@withLock
                registerLocked("re-arm after $reason")
            }
        }
    }

    /**
     * BlueZ has dropped our advertisement.
     *
     * This runs on a D-Bus dispatch thread, so it does two non-blocking things and returns: it
     * clears the advertising state -- `Release` is the daemon telling us the instance is *gone*,
     * and merely logging it leaves us believing we are still discoverable when no new peer can find
     * us -- and it asks the serialized outbound path to re-arm. Calling `UnregisterAdvertisement`
     * from here would be both an outbound call inside an inbound handler and pointless: BlueZ has
     * already dropped it and answers `DoesNotExist`.
     */
    private fun onReleased() {
        advertising = false
        registeredOwner = null
        log.warn("BlueZ released the advertisement -- no new peer can discover us until it re-arms")
        if (desired) rearm.trySend("LEAdvertisement1.Release")
    }

    /**
     * The four properties BlueZ reads back during `RegisterAdvertisement`.
     *
     * `ServiceUUIDs` is what a scanning peer filters on, and it must go through
     * [variantOfStrings]: the one-argument `Variant(List)` constructor cannot derive a signature
     * from an erased list and throws, which is exactly how the advertisement silently failed to
     * register in the spike.
     */
    private fun advertisementProperties(): Map<String, Variant<*>> = linkedMapOf<String, Variant<*>>(
        "Type" to Variant("peripheral"),
        "ServiceUUIDs" to variantOfStrings(listOf(serviceUuid)),
        "LocalName" to Variant(localName),
        "Discoverable" to Variant(true)
    )
}

/**
 * The exported `org.bluez.LEAdvertisement1`.
 *
 * A top-level public class with `@JvmSuppressWildcards`, implementing `Properties` as well as
 * `LEAdvertisement1`, for the reasons set out over the exported objects in
 * `LinuxGattServerService.kt`. BlueZ calls no method on it during registration -- it only reads the
 * properties -- so `Release` existing at all is what makes dbus-java list
 * `org.bluez.LEAdvertisement1` among the exported interfaces.
 */
@JvmSuppressWildcards
class BitchatAdvertisement(
    private val properties: () -> Map<String, Variant<*>>,
    private val onRelease: () -> Unit
) : LEAdvertisement1, Properties {

    private val log = LoggerFactory.getLogger("bitchat.ble.adv.obj")

    /** Cleared before teardown so an inbound call already in flight is answered, not raced. */
    @Volatile
    var live: Boolean = false

    override fun getObjectPath(): String = ADVERTISEMENT_PATH

    override fun isRemote(): Boolean = false

    override fun Release() = dbusHandler("adv.Release") { onRelease() }

    override fun GetAll(interfaceName: String): Map<String, Variant<*>> =
        dbusHandler("adv.GetAll") {
            if (!live) {
                log.debug("GetAll after teardown; answering with an empty map")
                return@dbusHandler emptyMap()
            }
            if (interfaceName != IFACE_LE_ADVERTISEMENT1 && interfaceName.isNotEmpty()) {
                return@dbusHandler emptyMap()
            }
            properties()
        }

    @Suppress("UNCHECKED_CAST")
    override fun <A> Get(interfaceName: String, propertyName: String): A {
        val value = dbusHandler("adv.Get") { properties()[propertyName] }
            ?: throw DBusExecutionException("$IFACE_LE_ADVERTISEMENT1 has no property $propertyName")
        return value as A
    }

    /**
     * Accepted and ignored, and it must be: bluetoothd writes to this object.
     *
     * Observed against BlueZ on a live registration -- it issues
     * `Properties.Set("org.bluez.LEAdvertisement1", "TxPower", ...)` on our advertisement while
     * `RegisterAdvertisement` is still in flight, reporting the transmit power the controller
     * actually chose. Throwing here would answer the daemon's own callback with a D-Bus error in
     * the middle of the registration it is trying to complete. We advertise no `TxPower` and have
     * no use for the value, so it is recorded in the log and dropped.
     */
    override fun <A> Set(interfaceName: String, propertyName: String, value: A) {
        log.debug(
            "Properties.Set({}, {}) = {} on the advertisement -- accepted and ignored",
            interfaceName, propertyName, value
        )
    }
}
