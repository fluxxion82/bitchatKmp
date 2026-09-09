@file:Suppress("FunctionName")

package com.bitchat.bluetooth.linux.spike

import com.bitchat.bluetooth.linux.Adapter1
import com.bitchat.bluetooth.linux.GattCharacteristic1
import com.bitchat.bluetooth.linux.GattManager1
import com.bitchat.bluetooth.linux.GattService1
import com.bitchat.bluetooth.linux.IFACE_ADAPTER1
import com.bitchat.bluetooth.linux.IFACE_GATT_CHARACTERISTIC1
import com.bitchat.bluetooth.linux.IFACE_GATT_MANAGER1
import com.bitchat.bluetooth.linux.IFACE_GATT_SERVICE1
import com.bitchat.bluetooth.linux.IFACE_LE_ADVERTISEMENT1
import com.bitchat.bluetooth.linux.IFACE_LE_ADVERTISING_MANAGER1
import com.bitchat.bluetooth.linux.LEAdvertisement1
import com.bitchat.bluetooth.linux.LEAdvertisingManager1
import com.bitchat.bluetooth.linux.OPTION_DEVICE
import com.bitchat.bluetooth.linux.OPTION_MTU
import org.freedesktop.dbus.DBusPath
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.exceptions.DBusExecutionException
import org.freedesktop.dbus.interfaces.ObjectManager
import org.freedesktop.dbus.interfaces.Properties
import org.freedesktop.dbus.types.UInt16
import org.freedesktop.dbus.types.Variant
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicBoolean

/*
 * ABI gate for the JVM/dbus-java route into BlueZ.
 *
 * The question this answers is not "does the code compile" -- dbus-java derives every D-Bus
 * signature by reflection at runtime, so compiling proves nothing. It is: does dbus-java put on the
 * wire exactly what bluetoothd's gdbus proxies expect to read back? The only way to know is to run
 * it against a live system bus and let bluetoothd judge, which is what this file does.
 *
 * Three gates, in dependency order:
 *
 *   S1  Reach the system bus, find an adapter, power it on.
 *   S2  Export an LEAdvertisement1 and have bluetoothd accept it. This is the cheap gate: BlueZ
 *       reads the advertisement back with a single Properties.GetAll, so a failure here is almost
 *       certainly a marshalling fault on our side rather than anything subtle.
 *   S3  Export a GATT application and have bluetoothd accept it. This is the expensive gate: BlueZ
 *       calls ObjectManager.GetManagedObjects on our tree *before* replying to RegisterApplication,
 *       so it exercises the nested a{oa{sa{sv}}} shape, object-path-typed variants, and the
 *       callback-during-a-blocking-call threading model all at once.
 *
 * The prize beyond the gates is the WriteValue options dict. Nothing short of a real central
 * writing to us produces one, and its contents are what the production code has to decode: the
 * `device` entry is documented as an object path, and the whole peripheral role depends on reading
 * it as one. The handler below dumps every key with its Java class, its D-Bus signature and its
 * value precisely so that the next person does not have to guess.
 */

private const val SERVICE_UUID = "F47B5E2D-4A9E-4C5A-9B3F-8E1D2C3A4B5C"
private const val CHARACTERISTIC_UUID = "A1B2C3D4-E5F6-4A5B-8C9D-0E1F2A3B4C5D"
private const val LOCAL_NAME = "bitchat-spike"

private const val BLUEZ_BUS = "org.bluez"
private const val ADV_PATH = "/org/bitchat/advertisement0"
private const val APP_PATH = "/org/bitchat/gatt"
private const val SERVICE_PATH = "/org/bitchat/gatt/service0"
private const val CHAR_PATH = "/org/bitchat/gatt/service0/char0"

private val CHAR_FLAGS = listOf("read", "write", "write-without-response", "notify")

/*
 * Stock BlueZ 5.74/5.75 cannot register a GATT application that omits `Handle`. `gatt-database.c`
 * hands `handle - 1` to gatt-db as the range start; with `Handle` absent it defaults to 0, the
 * subtraction underflows to UINT16_MAX and gatt-db rejects the range. So a handle has to be picked,
 * and picking one is guesswork: too low collides with the local services bluetoothd already owns
 * (GAP/GATT sit in the low handles), and the characteristic needs room for its own declaration, so
 * it must sit at least two above its service. The ladder exists because the safe floor is a
 * property of whatever else is registered on this adapter right now, not a constant.
 */
private val HANDLE_BASES = listOf(0x0040, 0x0080, 0x0100, 0x0200)
private const val CHAR_HANDLE_OFFSET = 2

/**
 * Logger held lazily on purpose.
 *
 * A top-level `val` is initialised in the file class's static initialiser, which runs *before*
 * `main`'s body. Logback reads its configuration on the first `LoggerFactory.getLogger`, and the
 * `org.freedesktop.dbus` level in logback.xml is a substitution of the `ble.dbus.level` system
 * property -- which `main` sets from `-Dble.trace`. Eager initialisation here would resolve that
 * substitution before the property existed and silently pin dbus-java's logging to the default.
 *
 * The `bleSpike` Gradle task forks its own JVM and does not forward `-D` from the Gradle command
 * line, so the way to actually turn tracing on is to put it in the forked JVM's environment:
 *
 *     JAVA_TOOL_OPTIONS=-Dble.trace=true ./gradlew :data:remote:transport:bluetooth:bleSpike \
 *         --console=plain -Pembedded.enabled=false --args="45"
 */
private val log: Logger by lazy { LoggerFactory.getLogger("spike") }

// ---------------------------------------------------------------------------------------------
// Exported objects
// ---------------------------------------------------------------------------------------------

/*
 * Every class below is annotated `@JvmSuppressWildcards` for the same reason `BlueZInterfaces.kt`
 * annotates its interfaces: Kotlin's `Map<K, out V>` variance becomes a Java `? extends` wildcard in
 * both the declared parameter and the declared return type of an override, and dbus-java's
 * reflection compares those against the invariant types on `Properties`/`ObjectManager`. Omitting
 * it here does not stop the class compiling; it stops BlueZ's call from being routed to it.
 *
 * They are also all `public` (Kotlin's default). A `private` top-level class compiles to a
 * package-private JVM class, and dbus-java invokes exported methods reflectively from its own
 * package, which would throw IllegalAccessException the first time BlueZ called in.
 */

/**
 * The advertisement. BlueZ never calls a method on it during registration -- it only reads the four
 * properties back -- so `Release` existing at all is what makes dbus-java list
 * `org.bluez.LEAdvertisement1` among the exported interfaces.
 */
@JvmSuppressWildcards
class SpikeAdvertisement : LEAdvertisement1, Properties {

    override fun getObjectPath(): String = ADV_PATH

    override fun isRemote(): Boolean = false

    override fun Release() {
        // Not a request to unregister: BlueZ has already dropped the advertisement by the time this
        // arrives, and calling UnregisterAdvertisement in response yields DoesNotExist.
        log.warn("[ADV] Release() from BlueZ -- advertisement was dropped by the daemon")
        released.set(true)
    }

    val released = AtomicBoolean(false)

    override fun GetAll(interfaceName: String): Map<String, Variant<*>> = handler("ADV") {
        log.info("[ADV] Properties.GetAll({})", interfaceName)
        if (interfaceName != IFACE_LE_ADVERTISEMENT1 && interfaceName.isNotEmpty()) {
            return@handler emptyMap()
        }
        properties()
    }

    @Suppress("UNCHECKED_CAST")
    override fun <A> Get(interfaceName: String, propertyName: String): A {
        log.info("[ADV] Properties.Get({}, {})", interfaceName, propertyName)
        val value = properties()[propertyName]
            ?: throw DBusExecutionException("No such property $propertyName")
        return value as A
    }

    override fun <A> Set(interfaceName: String, propertyName: String, value: A) {
        log.info("[ADV] Properties.Set({}, {}, {}) -- ignored", interfaceName, propertyName, value)
    }

    private fun properties(): Map<String, Variant<*>> = linkedMapOf(
        "Type" to Variant("peripheral"),
        "ServiceUUIDs" to stringArray(listOf(SERVICE_UUID)),
        "LocalName" to Variant(LOCAL_NAME),
        "Discoverable" to Variant(true)
    )
}

/**
 * The GATT application root. Its whole job is to answer the `GetManagedObjects` that BlueZ issues
 * from inside `RegisterApplication`, describing the service and characteristic in one reply.
 */
@JvmSuppressWildcards
class SpikeGattApplication(
    private val service: SpikeGattService,
    private val characteristic: SpikeGattCharacteristic
) : ObjectManager {

    override fun getObjectPath(): String = APP_PATH

    override fun isRemote(): Boolean = false

    override fun GetManagedObjects(): Map<DBusPath, Map<String, Map<String, Variant<*>>>> =
        handler("APP") {
            log.info("[APP] ObjectManager.GetManagedObjects() -- BlueZ is reading our tree")
            val objects = linkedMapOf<DBusPath, Map<String, Map<String, Variant<*>>>>(
                DBusPath(SERVICE_PATH) to mapOf(IFACE_GATT_SERVICE1 to service.properties()),
                DBusPath(CHAR_PATH) to mapOf(IFACE_GATT_CHARACTERISTIC1 to characteristic.properties())
            )
            // Printed in full because this single reply is the entire contract BlueZ registers
            // against: if a handle, a flag or the Service path is wrong, this is where it shows.
            objects.forEach { (path, ifaces) ->
                ifaces.forEach { (iface, props) ->
                    log.info("[APP]   {} {} -> {}", path.path, iface, describe(props))
                }
            }
            objects
        }
}

/** The primary service. No methods at all in `org.bluez.GattService1`; only UUID/Primary/Handle. */
@JvmSuppressWildcards
class SpikeGattService : GattService1, Properties {

    /**
     * Mutable because the handle is chosen by the ladder in `main` across successive
     * `RegisterApplication` attempts, and because BlueZ writes the handle it actually assigned back
     * through `Properties.Set` once registration succeeds.
     */
    var handle: Int = HANDLE_BASES.first()

    override fun getObjectPath(): String = SERVICE_PATH

    override fun isRemote(): Boolean = false

    fun properties(): Map<String, Variant<*>> = linkedMapOf(
        "UUID" to Variant(SERVICE_UUID),
        "Primary" to Variant(true),
        // D-Bus `q`. UInt16 and not Kotlin UShort: UShort erases to a JVM short and dbus-java would
        // compute `n`, which is a different type on the wire and not what BlueZ reads.
        "Handle" to Variant(UInt16(handle))
    )

    override fun GetAll(interfaceName: String): Map<String, Variant<*>> = handler("SVC") {
        log.info("[SVC] Properties.GetAll({})", interfaceName)
        if (interfaceName != IFACE_GATT_SERVICE1 && interfaceName.isNotEmpty()) return@handler emptyMap()
        properties()
    }

    @Suppress("UNCHECKED_CAST")
    override fun <A> Get(interfaceName: String, propertyName: String): A {
        log.info("[SVC] Properties.Get({}, {})", interfaceName, propertyName)
        val value = properties()[propertyName]
            ?: throw DBusExecutionException("No such property $propertyName")
        return value as A
    }

    override fun <A> Set(interfaceName: String, propertyName: String, value: A) {
        log.info("[SVC] Properties.Set({}, {}, {})", interfaceName, propertyName, value)
        if (propertyName == "Handle") {
            handle = unwrapUInt16(value) ?: handle
            log.info("[SVC] BlueZ assigned Handle=0x{}", "%04X".format(handle))
        }
    }
}

/**
 * The characteristic -- the only object BlueZ ever calls a real method on, and therefore the only
 * one whose logging matters beyond registration.
 */
@JvmSuppressWildcards
class SpikeGattCharacteristic : GattCharacteristic1, Properties {

    /** Set once the connection exists; the object has to be constructed before it is exported. */
    var connection: DBusConnection? = null

    var handle: Int = HANDLE_BASES.first() + CHAR_HANDLE_OFFSET

    private var lastValue: ByteArray = ByteArray(0)

    val sawWrite = AtomicBoolean(false)

    override fun getObjectPath(): String = CHAR_PATH

    override fun isRemote(): Boolean = false

    fun properties(): Map<String, Variant<*>> = linkedMapOf(
        "UUID" to Variant(CHARACTERISTIC_UUID),
        // `o`, not `s`. BlueZ resolves the owning service by path and a string here is rejected.
        "Service" to Variant(DBusPath(SERVICE_PATH)),
        "Flags" to stringArray(CHAR_FLAGS),
        "Handle" to Variant(UInt16(handle))
    )

    override fun GetAll(interfaceName: String): Map<String, Variant<*>> = handler("CHR") {
        log.info("[CHR] Properties.GetAll({})", interfaceName)
        if (interfaceName != IFACE_GATT_CHARACTERISTIC1 && interfaceName.isNotEmpty()) {
            return@handler emptyMap()
        }
        properties()
    }

    @Suppress("UNCHECKED_CAST")
    override fun <A> Get(interfaceName: String, propertyName: String): A {
        log.info("[CHR] Properties.Get({}, {})", interfaceName, propertyName)
        val value = properties()[propertyName]
            ?: throw DBusExecutionException("No such property $propertyName")
        return value as A
    }

    override fun <A> Set(interfaceName: String, propertyName: String, value: A) {
        log.info("[CHR] Properties.Set({}, {}, {})", interfaceName, propertyName, value)
        if (propertyName == "Handle") {
            handle = unwrapUInt16(value) ?: handle
            log.info("[CHR] BlueZ assigned Handle=0x{}", "%04X".format(handle))
        }
    }

    override fun ReadValue(options: Map<String, Variant<*>>): ByteArray = handler("CHR/ReadValue") {
        log.info("[CHR] ===== ReadValue =====")
        dumpOptions("CHR/ReadValue", options)
        log.info("[CHR] returning {} byte(s): {}", lastValue.size, hex(lastValue))
        lastValue
    }

    override fun WriteValue(value: ByteArray, options: Map<String, Variant<*>>) = handler("CHR/WriteValue") {
        sawWrite.set(true)
        log.info("[CHR] ===== WriteValue =====")
        log.info("[CHR] value: {} byte(s) {} | ascii={}", value.size, hex(value), ascii(value))
        dumpOptions("CHR/WriteValue", options)
        lastValue = value
        // Echo it straight back as a notification. This is the whole peripheral write/notify loop in
        // one line, and it is the only way to see whether a PropertiesChanged we construct is one
        // BlueZ will forward to the central.
        notifyValue(value)
    }

    override fun StartNotify() {
        // BlueZ raises this once per characteristic, not once per central, so it tells us that at
        // least one subscriber exists and nothing about who.
        log.info("[CHR] ===== StartNotify ===== (>=1 central subscribed)")
    }

    override fun StopNotify() {
        log.info("[CHR] ===== StopNotify ===== (last subscriber gone)")
    }

    /**
     * Notifications are not a method call: a peripheral pushes by emitting
     * `org.freedesktop.DBus.Properties.PropertiesChanged` on its own characteristic path with the
     * new `Value`. BlueZ picks the signal up off the bus and turns it into an ATT handle-value
     * notification.
     */
    fun notifyValue(bytes: ByteArray) {
        val conn = connection ?: run {
            log.warn("[CHR] notify skipped -- no connection")
            return
        }
        val signal = Properties.PropertiesChanged(
            CHAR_PATH,
            IFACE_GATT_CHARACTERISTIC1,
            mapOf<String, Variant<*>>("Value" to Variant(bytes)),
            emptyList<String>()
        )
        conn.sendMessage(signal)
        log.info("[CHR] emitted PropertiesChanged Value={} ({} byte(s))", hex(bytes), bytes.size)
    }
}

// ---------------------------------------------------------------------------------------------
// Option-dict forensics
// ---------------------------------------------------------------------------------------------

/**
 * Prints an `a{sv}` in full: key, the Java class dbus-java actually produced, the signature the
 * variant carries, and the value.
 *
 * The runtime class is the interesting column. Two entries decide how the production peripheral has
 * to be written and neither is inferable from the interface declaration: `device`, which must arrive
 * as a `Variant<DBusPath>` (reading it as `Variant<String>` throws a ClassCastException at the exact
 * moment a real peer writes to us), and `mtu`, whose presence and type govern how large a
 * notification may be. Both are asserted explicitly below rather than left for a reader to spot.
 */
private fun dumpOptions(tag: String, options: Map<String, Variant<*>>) {
    log.info("[{}] options dict: {} entr(y|ies)", tag, options.size)
    if (options.isEmpty()) {
        log.info("[{}]   (empty)", tag)
    }
    options.forEach { (key, variant) ->
        val value = variant.value
        val rendered = when (value) {
            is ByteArray -> "${hex(value)} (${value.size}B)"
            else -> value?.toString()
        }
        log.info(
            "[{}]   key={} javaClass={} dbusSig={} genericType={} value={}",
            tag,
            key,
            value?.javaClass?.name ?: "null",
            variant.sig,
            variant.type,
            rendered
        )
    }

    val device = options[OPTION_DEVICE]
    log.info(
        "[{}] ASSERT device -> present={} isDBusPath={} class={}",
        tag,
        device != null,
        device?.value is DBusPath,
        device?.value?.javaClass?.name ?: "-"
    )
    val mtu = options[OPTION_MTU]
    log.info(
        "[{}] ASSERT mtu    -> present={} isUInt16={} class={} value={}",
        tag,
        mtu != null,
        mtu?.value is UInt16,
        mtu?.value?.javaClass?.name ?: "-",
        mtu?.value ?: "-"
    )
}

/**
 * Wraps a list of strings as a D-Bus `as`.
 *
 * The one-argument `Variant(T)` constructor derives the signature from `value.getClass()`, and a
 * `java.util.List` is a raw class with its element type erased -- every list, however built, is
 * rejected with "Can't wrap ... in an unqualified Variant". The two-argument form takes the
 * signature instead of inferring it, which is the only way to put a string array inside a variant.
 * (A Kotlin `Array<String>` also works, because a Java array carries its component type in the
 * Class object, but then the property no longer prints usefully in a log.)
 */
private fun stringArray(values: List<String>): Variant<List<String>> = Variant(values, "as")

private fun describe(props: Map<String, Variant<*>>): String =
    props.entries.joinToString(", ") { (k, v) ->
        "$k=${v.value}[${v.sig}/${v.value?.javaClass?.simpleName}]"
    }

/**
 * Runs a D-Bus handler body, logging anything it throws.
 *
 * dbus-java turns an exception out of an exported method into a D-Bus error reply, and reports it
 * itself only under `org.freedesktop.dbus`, which logback.xml keeps quiet by default. BlueZ in turn
 * renders the far end of that error as nothing more than "No object received". Without this, the
 * most likely failure mode of the whole file -- a value dbus-java declines to marshal -- is silent
 * on both sides, and that is exactly how the `Variant(List)` problem below stayed hidden.
 */
private fun <T> handler(tag: String, body: () -> T): T =
    try {
        body()
    } catch (e: Throwable) {
        log.error("[{}] handler threw", tag, e)
        throw e
    }

private fun hex(bytes: ByteArray): String =
    if (bytes.isEmpty()) "<empty>" else bytes.joinToString(" ") { "%02X".format(it) }

private fun ascii(bytes: ByteArray): String =
    String(bytes.map { if (it in 32..126) it else '.'.code.toByte() }.toByteArray(), Charsets.US_ASCII)

/** BlueZ writes `Handle` back as a `q`; dbus-java may hand it over bare or still wrapped. */
private fun unwrapUInt16(value: Any?): Int? = when (value) {
    // UInt16 extends java.lang.Number, so a single Number branch covers both it and a bare Short.
    is Number -> value.toInt()
    is Variant<*> -> unwrapUInt16(value.value)
    else -> null
}

// ---------------------------------------------------------------------------------------------
// Gates
// ---------------------------------------------------------------------------------------------

private class Gate(val name: String) {
    var passed: Boolean = false
    var detail: String = "not run"
}

fun main(args: Array<String>) {
    // Must happen before anything touches SLF4J -- see the note on `log`.
    if (System.getProperty("ble.trace")?.toBoolean() == true) {
        System.setProperty("ble.dbus.level", "TRACE")
    }

    val durationSeconds = args.firstOrNull()?.toIntOrNull() ?: 60

    val s1 = Gate("S1 bus + adapter")
    val s2 = Gate("S2 RegisterAdvertisement")
    val s3 = Gate("S3 RegisterApplication")

    val advertisement = SpikeAdvertisement()
    val service = SpikeGattService()
    val characteristic = SpikeGattCharacteristic()
    val application = SpikeGattApplication(service, characteristic)

    // The teardown has to be reachable from both the normal exit path and the SIGINT hook, and must
    // run exactly once: BlueZ answers a second UnregisterAdvertisement with DoesNotExist, and a
    // shutdown hook racing the main thread would otherwise produce exactly that.
    val tornDown = AtomicBoolean(false)

    var connection: DBusConnection? = null
    var advertisementRegistered = false
    var applicationRegistered = false
    var handleBaseUsed: Int? = null

    fun teardown() {
        if (!tornDown.compareAndSet(false, true)) return
        val conn = connection ?: return
        log.info("--- teardown ---")
        val advManager = runCatching {
            conn.getRemoteObject(BLUEZ_BUS, ADAPTER_PATH_HOLDER.get(), LEAdvertisingManager1::class.java)
        }.getOrNull()
        val gattManager = runCatching {
            conn.getRemoteObject(BLUEZ_BUS, ADAPTER_PATH_HOLDER.get(), GattManager1::class.java)
        }.getOrNull()

        if (applicationRegistered && gattManager != null) {
            runCatching { gattManager.UnregisterApplication(DBusPath(APP_PATH)) }
                .onSuccess { log.info("UnregisterApplication OK") }
                // DoesNotExist is the expected answer when BlueZ already dropped the registration,
                // e.g. because the adapter was powered off under us. It is not a failure.
                .onFailure { log.warn("UnregisterApplication: {}", errorText(it)) }
        }
        if (advertisementRegistered && advManager != null) {
            runCatching { advManager.UnregisterAdvertisement(DBusPath(ADV_PATH)) }
                .onSuccess { log.info("UnregisterAdvertisement OK") }
                .onFailure { log.warn("UnregisterAdvertisement: {}", errorText(it)) }
        }

        listOf(CHAR_PATH, SERVICE_PATH, APP_PATH, ADV_PATH).forEach { path ->
            runCatching { conn.unExportObject(path) }
                .onFailure { log.warn("unExportObject({}): {}", path, errorText(it)) }
        }

        verifyClean(conn)
        runCatching { conn.disconnect() }
        log.info("--- teardown done ---")
    }

    Runtime.getRuntime().addShutdownHook(Thread({ teardown() }, "spike-sigint"))

    try {
        // ---- S1 -------------------------------------------------------------------------------
        // forSystemBus() resolves DBUS_SYSTEM_BUS_ADDRESS (or the /var/run default) and the
        // native-unixsocket transport is picked up off the classpath by ServiceLoader; there is no
        // API call that selects it, so its presence on the classpath is the whole configuration.
        val conn = DBusConnectionBuilder.forSystemBus().build()
        connection = conn
        log.info("connected to system bus as {}", conn.getUniqueName())

        val adapters = findAdapters(conn)
        if (adapters.isEmpty()) {
            s1.detail = "no org.bluez.Adapter1 found"
            throw IllegalStateException(s1.detail)
        }
        val adapterPath = adapters.first()
        ADAPTER_PATH_HOLDER.set(adapterPath)

        val adapter = conn.getRemoteObject(BLUEZ_BUS, adapterPath, Adapter1::class.java)
        log.info("adapter {} Address={} Powered={}", adapterPath, adapter.address, adapter.powered)
        if (!adapter.powered) {
            log.info("powering on {}", adapterPath)
            conn.getRemoteObject(BLUEZ_BUS, adapterPath, Properties::class.java)
                .Set(IFACE_ADAPTER1, "Powered", Variant(true))
            Thread.sleep(500)
            log.info("adapter Powered={} after power-on", adapter.powered)
        }
        s1.passed = adapter.powered
        s1.detail = "$adapterPath ${adapter.address} powered=${adapter.powered}"

        val bluezVersion = detectBlueZVersion()
        log.info("")
        log.info("================ bitchat BlueZ/dbus-java spike ================")
        log.info("  BlueZ         : {}", bluezVersion)
        log.info("  adapter       : {} ({})", adapterPath, adapter.address)
        log.info("  dbus-java     : {}", dbusJavaVersion())
        log.info("  JVM           : {} {}", System.getProperty("java.vendor"), System.getProperty("java.version"))
        // Stated because non-root registration is the point: RegisterAdvertisement and
        // RegisterApplication both work for an unprivileged user given the right D-Bus policy.
        log.info("  user          : {} (pid {})", System.getProperty("user.name"), ProcessHandle.current().pid())
        log.info("  duration      : {}s", durationSeconds)
        log.info("==============================================================")
        log.info("")

        // ---- S2 -------------------------------------------------------------------------------
        conn.exportObject(ADV_PATH, advertisement)
        val advManager = conn.getRemoteObject(BLUEZ_BUS, adapterPath, LEAdvertisingManager1::class.java)
        log.info("S2: RegisterAdvertisement({}) on {}", ADV_PATH, IFACE_LE_ADVERTISING_MANAGER1)
        runCatching { advManager.RegisterAdvertisement(DBusPath(ADV_PATH), emptyMap()) }
            .onSuccess {
                advertisementRegistered = true
                s2.passed = true
                s2.detail = "registered $ADV_PATH"
                log.info("S2: PASS -- advertisement accepted by bluetoothd")
            }
            .onFailure {
                s2.detail = errorText(it)
                log.error("S2: FAIL -- {}", s2.detail)
            }

        // ---- S3 -------------------------------------------------------------------------------
        characteristic.connection = conn
        conn.exportObject(SERVICE_PATH, service)
        conn.exportObject(CHAR_PATH, characteristic)
        conn.exportObject(APP_PATH, application)

        val gattManager = conn.getRemoteObject(BLUEZ_BUS, adapterPath, GattManager1::class.java)
        log.info("S3: RegisterApplication({}) on {}", APP_PATH, IFACE_GATT_MANAGER1)
        for (base in HANDLE_BASES) {
            service.handle = base
            characteristic.handle = base + CHAR_HANDLE_OFFSET
            log.info(
                "S3: attempt with service Handle=0x{} char Handle=0x{}",
                "%04X".format(service.handle),
                "%04X".format(characteristic.handle)
            )
            val result = runCatching { gattManager.RegisterApplication(DBusPath(APP_PATH), emptyMap()) }
            if (result.isSuccess) {
                applicationRegistered = true
                handleBaseUsed = base
                s3.passed = true
                s3.detail = "handle base 0x%04X".format(base)
                log.info("S3: PASS -- application accepted at handle base 0x{}", "%04X".format(base))
                break
            }
            s3.detail = errorText(result.exceptionOrNull()!!)
            log.warn("S3: base 0x{} rejected -- {}", "%04X".format(base), s3.detail)
        }
        if (!s3.passed) log.error("S3: FAIL -- every handle base rejected; last error: {}", s3.detail)

        log.info("")
        log.info("---- startup summary ----")
        log.info("  BlueZ       : {}", bluezVersion)
        log.info("  adapter     : {}", adapterPath)
        log.info("  dbus-java   : {}", dbusJavaVersion())
        log.info("  handle base : {}", handleBaseUsed?.let { "0x%04X".format(it) } ?: "none (S3 failed)")
        log.info("-------------------------")
        log.info("")
        log.info("adapter UUIDs now advertise our service: {}", adapter.uuids.any { it.equals(SERVICE_UUID, true) })
        log.info(
            "idling {}s -- connect a central to {} and write to {} to exercise WriteValue",
            durationSeconds, LOCAL_NAME, CHARACTERISTIC_UUID
        )

        // dbus-java services incoming calls on its own reader/receiving threads, so the main thread
        // only has to stay alive. Sleeping in slices keeps the SIGINT hook responsive.
        val deadline = System.nanoTime() + durationSeconds * 1_000_000_000L
        while (System.nanoTime() < deadline) {
            Thread.sleep(250)
        }

        log.info("")
        log.info("duration elapsed; WriteValue seen during this run: {}", characteristic.sawWrite.get())
    } catch (e: Exception) {
        log.error("spike aborted: {}", errorText(e), e)
    } finally {
        teardown()
    }

    log.info("")
    log.info("==================== RESULTS ====================")
    listOf(s1, s2, s3).forEach {
        log.info("  {} : {} ({})", if (it.passed) "PASS" else "FAIL", it.name, it.detail)
    }
    log.info("  handle base used : {}", handleBaseUsed?.let { "0x%04X".format(it) } ?: "n/a")
    log.info("  WriteValue path  : {}", if (characteristic.sawWrite.get()) "EXERCISED" else "NOT EXERCISED (no central wrote)")
    log.info("=================================================")

    // Explicit, because dbus-java's non-daemon threads can otherwise hold the JVM open past the
    // point where the spike has nothing left to say.
    System.exit(if (s1.passed && s2.passed && s3.passed) 0 else 1)
}

/**
 * The adapter path is discovered in S1 but needed by the teardown closure, which is installed as a
 * shutdown hook before S1 runs. A holder rather than a `var` because the closure captures it before
 * the value exists.
 */
private val ADAPTER_PATH_HOLDER = java.util.concurrent.atomic.AtomicReference("/org/bluez/hci0")

private fun findAdapters(conn: DBusConnection): List<String> {
    val objectManager = conn.getRemoteObject(BLUEZ_BUS, "/", ObjectManager::class.java)
    val managed = objectManager.GetManagedObjects()
    val adapters = mutableListOf<String>()
    managed.forEach { (path, ifaces) ->
        if (ifaces.containsKey(IFACE_ADAPTER1)) {
            val props = ifaces[IFACE_ADAPTER1].orEmpty()
            log.info(
                "S1: adapter {} Address={} Powered={}",
                path.path, props["Address"]?.value, props["Powered"]?.value
            )
            adapters += path.path
        }
    }
    return adapters
}

/**
 * Confirms nothing of ours survived teardown.
 *
 * Our own objects live on our unique bus name, so `busctl tree org.bluez` would never have shown
 * them; the observable trace a registered application leaves on BlueZ is the service UUID appearing
 * in `Adapter1.UUIDs`. That, plus the absence of any `/org/bitchat` path in BlueZ's own object tree,
 * is what "clean" means here.
 */
private fun verifyClean(conn: DBusConnection) {
    runCatching {
        val objectManager = conn.getRemoteObject(BLUEZ_BUS, "/", ObjectManager::class.java)
        val managed = objectManager.GetManagedObjects()
        val strays = managed.keys.map { it.path }.filter { it.contains("bitchat") }
        val adapterProps = managed.entries
            .firstOrNull { it.key.path == ADAPTER_PATH_HOLDER.get() }
            ?.value?.get(IFACE_ADAPTER1)
        @Suppress("UNCHECKED_CAST")
        val uuids = (adapterProps?.get("UUIDs")?.value as? List<String>).orEmpty()
        val stillAdvertised = uuids.any { it.equals(SERVICE_UUID, ignoreCase = true) }
        log.info("cleanup check: stray /org/bitchat objects in org.bluez tree = {}", strays)
        log.info("cleanup check: our service UUID still in Adapter1.UUIDs   = {}", stillAdvertised)
        log.info(
            "cleanup check: {}",
            if (strays.isEmpty() && !stillAdvertised) "CLEAN" else "DIRTY"
        )
    }.onFailure { log.warn("cleanup check failed: {}", errorText(it)) }
}

/**
 * A `DBusExecutionException` carries BlueZ's error name (`org.bluez.Error.Failed`,
 * `org.freedesktop.DBus.Error.NoReply`, ...) separately from its message, and the name is the part
 * that identifies the fault. Both are printed because BlueZ's message is often the only clue.
 */
@Suppress("DEPRECATION")
private fun errorText(t: Throwable): String = when (t) {
    // dbus-java maps a known D-Bus error name onto a generated class of that name and otherwise
    // falls back to a plain DBusExecutionException carrying the name in `type`, so neither the class
    // nor `type` alone identifies the fault in every case. Print both.
    is DBusExecutionException -> "${t.javaClass.name} [${t.type}]: ${t.message}"
    else -> "${t.javaClass.name}: ${t.message}"
}

/** Best-effort; the daemon does not expose its version over D-Bus. */
private fun detectBlueZVersion(): String {
    val candidates = listOf(
        listOf("bluetoothctl", "--version"),
        listOf("/usr/libexec/bluetooth/bluetoothd", "--version")
    )
    for (cmd in candidates) {
        val out = runCatching {
            val process = ProcessBuilder(cmd).redirectErrorStream(true).start()
            val text = process.inputStream.bufferedReader().readText().trim()
            process.waitFor()
            text
        }.getOrNull()
        if (!out.isNullOrBlank()) return "$out (via ${cmd.first()})"
    }
    return "unknown (no bluetoothctl/bluetoothd on PATH)"
}

private fun dbusJavaVersion(): String =
    DBusConnection::class.java.`package`?.implementationVersion
        ?: DBusConnection::class.java.protectionDomain?.codeSource?.location?.path?.substringAfterLast('/')
        ?: "unknown"
