@file:Suppress("FunctionName")

package com.bitchat.bluetooth.linux

import org.freedesktop.dbus.DBusPath
import org.freedesktop.dbus.annotations.DBusInterfaceName
import org.freedesktop.dbus.annotations.DBusBoundProperty
import org.freedesktop.dbus.interfaces.DBusInterface
import org.freedesktop.dbus.interfaces.ObjectManager
import org.freedesktop.dbus.interfaces.Properties
import org.freedesktop.dbus.types.Variant

/*
 * dbus-java interface declarations for the BlueZ D-Bus API.
 *
 * These are the JVM-desktop equivalent of what `linuxMain` builds by hand against libdbus. There
 * the wire format is written out message-iterator by message-iterator, so a mistake shows up as a
 * malformed message. Here dbus-java derives the D-Bus signature of every method from Java
 * reflection at *runtime*, which means a declaration can compile perfectly and then only fail the
 * moment BlueZ actually calls it. Everything below is shaped for that reflection step, not for
 * Kotlin ergonomics; the KDoc says why wherever the two disagree.
 *
 * Four rules govern the whole file:
 *
 *  1. Every interface extends `DBusInterface` and carries `@DBusInterfaceName`. Without the
 *     annotation dbus-java falls back to the fully-qualified Kotlin class name as the D-Bus
 *     interface name, and BlueZ would never match it.
 *
 *  2. Every interface with a `Map` in a *parameter* position is annotated `@JvmSuppressWildcards`.
 *     See the note on that annotation below -- it is the single most likely cause of a
 *     compiles-fine-fails-at-runtime bug in this file.
 *
 *  3. Anything D-Bus types as `o` (object path) is `DBusPath`, never `String`. BlueZ rejects a
 *     string where it wants a path, and dbus-java has no way to guess the intent from `String`.
 *
 *  4. No `suspend` functions. A suspend function compiles to a JVM method with a trailing
 *     `Continuation` parameter and an erased `Object` return; dbus-java would try to marshal the
 *     continuation as an argument and fail. Callers must bridge to coroutines themselves, e.g.
 *     with `withContext(Dispatchers.IO) { adapter.StartDiscovery() }`.
 *
 * Method names are deliberately PascalCase so they match the D-Bus member names verbatim
 * (`DBusNamingUtil.getMethodName` uses the Java method name unless `@DBusMemberName` overrides it).
 * The file-level `@Suppress("FunctionName")` exists only to silence the Kotlin naming warning that
 * follows from that.
 */

/*
 * ---------------------------------------------------------------------------------------------
 * On @JvmSuppressWildcards
 * ---------------------------------------------------------------------------------------------
 *
 * `kotlin.collections.Map` declares its value parameter as `out V`. Declaration-site variance is
 * translated to Java use-site wildcards, so a Kotlin parameter of type `Map<String, Variant<*>>`
 * is emitted into the class file as:
 *
 *     void SetDiscoveryFilter(Map<String, ? extends Variant<?>> filter)
 *
 * where dbus-java -- and dbus-java's own interfaces, such as `Properties.GetAll` and
 * `ObjectManager.GetManagedObjects` -- declare the invariant:
 *
 *     void SetDiscoveryFilter(Map<String, Variant<?>> filter)
 *
 * `@JvmSuppressWildcards` on the interface suppresses the wildcards for every type inside it, so
 * the reflected signature is the invariant one dbus-java expects. It is applied at interface level
 * rather than per-type because `@JvmSuppressWildcards` has no `FILE` annotation target, so a
 * `@file:` form is not available, and per-parameter application is easy to forget when this file
 * grows.
 *
 * Kotlin does not emit wildcards in *return* positions, so a return type is safe either way; the
 * annotation is still applied interface-wide so that no future edit has to remember the
 * distinction.
 */

/**
 * Marker for the BlueZ adapter object, e.g. `/org/bluez/hci0`, on the `org.bluez` bus name.
 *
 * Read-only properties are declared with `@get:DBusBoundProperty`. dbus-java's
 * `RemoteInvocationHandler` spots that annotation on the getter and rewrites the call into an
 * `org.freedesktop.DBus.Properties.Get(interfaceName, propertyName)` round trip, deserialising the
 * reply against the getter's *generic* return type -- which is why `List<String>` below correctly
 * round-trips a D-Bus `as` rather than arriving as a raw list.
 *
 * The `name` is always spelled out. Left implicit, dbus-java derives the property name by
 * stripping a `get`/`is` prefix from the Java method name, which would turn Kotlin's `getRssi()`
 * into `Rssi` and `getUuids()` into `Uuids`. BlueZ spells them `RSSI` and `UUIDs`.
 *
 * `Powered` and `Alias` are read/write in BlueZ; they are declared read-only here because nothing
 * in this transport writes them. Promoting either to a `var` with a matching
 * `@set:DBusBoundProperty` is all that is needed if that changes.
 */
@DBusInterfaceName("org.bluez.Adapter1")
@JvmSuppressWildcards
interface Adapter1 : DBusInterface {

    /** D-Bus `s`. The adapter's own MAC, e.g. `B8:27:EB:00:00:01`. */
    @get:DBusBoundProperty(name = "Address")
    val address: String

    /** D-Bus `s`. The system-wide adapter name; read-only in BlueZ, unlike [alias]. */
    @get:DBusBoundProperty(name = "Name")
    val name: String

    /** D-Bus `s`. The name BlueZ actually advertises, falling back to [name] when unset. */
    @get:DBusBoundProperty(name = "Alias")
    val alias: String

    /** D-Bus `b`. False here is the most common reason every other call fails. */
    @get:DBusBoundProperty(name = "Powered")
    val powered: Boolean

    /** D-Bus `b`. Adapter-wide, and shared with every other client on the system bus. */
    @get:DBusBoundProperty(name = "Discovering")
    val discovering: Boolean

    /** D-Bus `as`. The profiles the local adapter supports, not the ones it has discovered. */
    @get:DBusBoundProperty(name = "UUIDs")
    val uuids: List<String>

    fun StartDiscovery()

    fun StopDiscovery()

    /**
     * Narrows what discovery reports. The dict is the BlueZ filter vocabulary -- `Transport` (`s`,
     * use `"le"`), `UUIDs` (`as`), `RSSI` (`n`), `DuplicateData` (`b`) -- each wrapped in a
     * [Variant] whose payload type decides the D-Bus type on the wire. An `n` threshold therefore
     * has to be `Variant(someShort)`, not `Variant(someInt)`, or BlueZ sees an `i` and rejects the
     * filter.
     *
     * The filter is per-client and scoped to the D-Bus connection, so setting it does not disturb
     * other scanners on the same adapter.
     */
    fun SetDiscoveryFilter(filter: Map<String, Variant<*>>)

    /**
     * Forgets a device object entirely, which is the only reliable way to clear a wedged
     * pairing/connection record. The argument is the device's object path (`o`), so it is a
     * [DBusPath] -- passing the same text as a `String` would be marshalled as `s` and rejected.
     */
    fun RemoveDevice(device: DBusPath)
}

/**
 * A remote peer as BlueZ models it, e.g. `/org/bluez/hci0/dev_AA_BB_CC_DD_EE_FF`.
 *
 * Note that `Connected` going true only means the ACL link is up. The characteristic objects under
 * this device do not exist until `ServicesResolved` goes true as well, which is why both are
 * exposed: a client that acts on `Connected` alone will race BlueZ's service discovery and find
 * nothing to talk to.
 */
@DBusInterfaceName("org.bluez.Device1")
interface Device1 : DBusInterface {

    /** D-Bus `s`, upper-case and colon-separated. */
    @get:DBusBoundProperty(name = "Address")
    val address: String

    /** D-Bus `s`. Absent devices can leave this unset, so treat it as best-effort. */
    @get:DBusBoundProperty(name = "Name")
    val name: String

    /** D-Bus `s`. */
    @get:DBusBoundProperty(name = "Alias")
    val alias: String

    /**
     * D-Bus `n` -- a signed 16-bit integer, so [Short] and not [Int]. Declaring this as `Int` would
     * make dbus-java compute a signature of `i`, and the `Get` reply carrying an `n` would fail to
     * deserialise.
     *
     * Only present while the device is in range of an active scan; BlueZ removes the property
     * otherwise, which surfaces as a `Properties.Get` error rather than a null.
     */
    @get:DBusBoundProperty(name = "RSSI")
    val rssi: Short

    /** D-Bus `b`. True once the ACL link is up -- see the note on the interface. */
    @get:DBusBoundProperty(name = "Connected")
    val connected: Boolean

    /** D-Bus `b`. True once GATT discovery has finished and characteristic objects exist. */
    @get:DBusBoundProperty(name = "ServicesResolved")
    val servicesResolved: Boolean

    /** D-Bus `as`. What the peer advertised or resolved, depending on connection state. */
    @get:DBusBoundProperty(name = "UUIDs")
    val uuids: List<String>

    /** D-Bus `o` -- the owning adapter's object path, hence [DBusPath] rather than [String]. */
    @get:DBusBoundProperty(name = "Adapter")
    val adapter: DBusPath

    /**
     * Returns once the link is established *and* services are resolved, or throws. BlueZ can hold
     * this call open for tens of seconds, so callers must move it off any thread that also needs to
     * service incoming D-Bus traffic.
     */
    fun Connect()

    fun Disconnect()
}

/**
 * The adapter-side registrar for a locally exported GATT application. Obtained on the same object
 * path as [Adapter1].
 */
@DBusInterfaceName("org.bluez.GattManager1")
@JvmSuppressWildcards
interface GattManager1 : DBusInterface {

    /**
     * Hands BlueZ the root path of our exported object tree. BlueZ immediately calls
     * `org.freedesktop.DBus.ObjectManager.GetManagedObjects` *on that path* and only then replies
     * to this call -- so whichever thread issues `RegisterApplication` must not be the only thread
     * able to answer incoming calls, or the two deadlock and this times out. `linuxMain` hits the
     * same trap; see `BlueZGattServerService.registerApplication`.
     *
     * `application` is an `o`. `options` is an `a{sv}` that BlueZ currently ignores; pass an empty
     * map rather than null.
     */
    fun RegisterApplication(application: DBusPath, options: Map<String, Variant<*>>)

    fun UnregisterApplication(application: DBusPath)
}

/**
 * The adapter-side registrar for a locally exported advertisement.
 */
@DBusInterfaceName("org.bluez.LEAdvertisingManager1")
@JvmSuppressWildcards
interface LEAdvertisingManager1 : DBusInterface {

    /**
     * Same callback-before-reply shape as [GattManager1.RegisterApplication]: BlueZ reads the
     * advertisement's properties via `org.freedesktop.DBus.Properties.GetAll` before it answers,
     * so the exported [LEAdvertisement1] object has to be reachable while this call is in flight.
     */
    fun RegisterAdvertisement(advertisement: DBusPath, options: Map<String, Variant<*>>)

    fun UnregisterAdvertisement(advertisement: DBusPath)
}

/*
 * ---------------------------------------------------------------------------------------------
 * Interfaces we export -- BlueZ is the caller
 * ---------------------------------------------------------------------------------------------
 *
 * Implementations of the three interfaces below are ordinary classes exported with
 * `DBusConnection.exportObject(path, obj)`. Two constraints follow from dbus-java's reflection:
 *
 *  - They must be classes, not Kotlin `object` singletons or companions. A Kotlin `object` is a
 *    JVM class with a static INSTANCE field and a private constructor, and dbus-java's exported
 *    object machinery reflects over the instance's implemented interfaces expecting a normal
 *    class; a companion additionally has a synthetic name that is not a legal path segment.
 *
 *  - Each must also implement `org.freedesktop.dbus.interfaces.Properties` (and, for the GATT
 *    application root, `org.freedesktop.dbus.interfaces.ObjectManager`). Both ship with dbus-java
 *    already annotated `org.freedesktop.DBus.Properties` / `org.freedesktop.DBus.ObjectManager`
 *    and with exactly the signatures BlueZ expects, so they are deliberately *not* redeclared
 *    here:
 *
 *        Properties.GetAll(String): Map<String, Variant<?>>
 *        ObjectManager.GetManagedObjects(): Map<DBusPath, Map<String, Map<String, Variant<?>>>>
 *
 *    Note the `a{oa{sa{sv}}}` shape of `GetManagedObjects`: the outer key is a [DBusPath], matching
 *    the `<arg name="objects" type="a{oa{sa{sv}}}"/>` the `linuxMain` implementation introspects
 *    as. A Kotlin override of it needs no `@JvmSuppressWildcards`, because Kotlin does not emit
 *    wildcards in return position -- but adding it changes nothing and is safe.
 */

/**
 * The advertisement object BlueZ reads back after [LEAdvertisingManager1.RegisterAdvertisement].
 *
 * Its properties -- `Type` (`s`, `"peripheral"`), `ServiceUUIDs` (`as`), `LocalName` (`s`),
 * `Discoverable` (`b`) -- are not declared as members here. BlueZ fetches them exclusively through
 * `org.freedesktop.DBus.Properties.GetAll`, so the implementing class serves them from its
 * `GetAll`/`Get` override, exactly as `BlueZAdvertisingService` does on the native side. Declaring
 * them as `@DBusBoundProperty` instead would work, but it splits the advertisement's contents
 * across two mechanisms for no gain.
 *
 * `Release` is BlueZ telling us the advertisement is gone -- typically because the adapter was
 * powered off or another client took the slot. It is not a request to unregister; calling
 * `UnregisterAdvertisement` in response yields `DoesNotExist`.
 */
@DBusInterfaceName("org.bluez.LEAdvertisement1")
interface LEAdvertisement1 : DBusInterface {
    fun Release()
}

/**
 * A primary GATT service in our exported tree.
 *
 * Genuinely a marker: `org.bluez.GattService1` has no methods, only the properties `UUID` (`s`) and
 * `Primary` (`b`). BlueZ learns both from the application root's [ObjectManager.GetManagedObjects]
 * reply and re-reads them via [Properties.GetAll], so the implementing class carries them in those
 * two places and this interface exists purely so dbus-java lists `org.bluez.GattService1` among the
 * exported object's interfaces.
 */
@DBusInterfaceName("org.bluez.GattService1")
interface GattService1 : DBusInterface

/**
 * `org.bluez.GattCharacteristic1` in both directions -- one declaration, because the member set is
 * identical whichever end owns the object.
 *
 * As a *remote proxy* (central role) it addresses a characteristic BlueZ discovered under a
 * [Device1], and the calls travel outward. As an *exported* object (peripheral role) BlueZ invokes
 * these on us on behalf of a connected central. The declared properties -- `UUID` (`s`), `Service`
 * (`o`), `Flags` (`as`), `Value` (`ay`) -- are served the same way as [GattService1]'s, through
 * `Properties` and the application root's `GetManagedObjects`.
 *
 * Notifications are not a method: after `StartNotify`, a peripheral pushes data by emitting
 * `org.freedesktop.DBus.Properties.PropertiesChanged` on the characteristic path with `Value` in
 * the changed-properties dict. dbus-java models that as `Properties.PropertiesChanged`, which is
 * sent with `DBusConnection.sendMessage(...)`, so there is nothing to declare here for it.
 */
@DBusInterfaceName("org.bluez.GattCharacteristic1")
@JvmSuppressWildcards
interface GattCharacteristic1 : DBusInterface {

    /**
     * D-Bus `ReadValue(a{sv}) -> ay`. The return is a [ByteArray], i.e. a Java `byte[]`, which is
     * what dbus-java marshals as `ay`; a `List<Byte>` would be marshalled as an array of separate
     * bytes and is not the same thing on the wire.
     *
     * See [OPTION_DEVICE] and friends for what the options dict may carry.
     */
    fun ReadValue(options: Map<String, Variant<*>>): ByteArray

    /**
     * D-Bus `WriteValue(ay, a{sv})`.
     *
     * On the exported side this is the only call that tells us *which* central sent the payload:
     * the options dict carries [OPTION_DEVICE], whose value is a `Variant` wrapping a [DBusPath]
     * such as `/org/bluez/hci0/dev_AA_BB_CC_DD_EE_FF`. It is an object path, not a string --
     * reading it as `Variant<String>` throws a class cast at exactly the moment a real peer writes
     * to us. The `linuxMain` implementation makes the same distinction, decoding the `device` entry
     * only when the variant's contained type is an object path.
     */
    fun WriteValue(value: ByteArray, options: Map<String, Variant<*>>)

    /**
     * Subscribes. On the exported side, BlueZ calls this once per characteristic rather than once
     * per central, so it says "at least one subscriber exists", not who.
     */
    fun StartNotify()

    fun StopNotify()
}

/*
 * ---------------------------------------------------------------------------------------------
 * Option-dict keys
 * ---------------------------------------------------------------------------------------------
 *
 * The `a{sv}` options passed to [GattCharacteristic1.ReadValue] and [GattCharacteristic1.WriteValue]
 * are untyped at the signature level; the contained type of each `Variant` is what actually decides
 * the D-Bus type. These constants exist so that key spelling and the required payload type are
 * stated once, next to the declarations they belong to.
 */

/** `Variant<DBusPath>` -- the calling central's device object path. Never a `Variant<String>`. */
const val OPTION_DEVICE: String = "device"

/**
 * `Variant<UInt16>` -- the negotiated ATT MTU. D-Bus `q` is unsigned 16-bit, which on the JVM is
 * `org.freedesktop.dbus.types.UInt16`, not Kotlin's `UShort`: `UShort` is an inline class that
 * erases to a JVM `short`, so dbus-java would compute a signature of `n` (signed) and an MTU above
 * 32767 would arrive negative. The same holds for D-Bus `u`, which is
 * `org.freedesktop.dbus.types.UInt32` rather than Kotlin `UInt`.
 */
const val OPTION_MTU: String = "mtu"

/** `Variant<UInt16>` -- start offset for a long read/write, for the same reason as [OPTION_MTU]. */
const val OPTION_OFFSET: String = "offset"

/** `Variant<String>` -- `"command"`, `"request"` or `"reliable"` on a write. */
const val OPTION_TYPE: String = "type"

/** `Variant<String>` -- the link type, `"LE"` or `"BR/EDR"`. */
const val OPTION_LINK: String = "link"

/*
 * ---------------------------------------------------------------------------------------------
 * Interface names
 * ---------------------------------------------------------------------------------------------
 *
 * The same strings as the `@DBusInterfaceName` annotations above. They are needed as literal map
 * keys when building a `GetManagedObjects` reply or dispatching a `Properties.GetAll`, and the
 * annotation values cannot be read back as constants, so they are restated once here rather than
 * spelled out at each call site.
 */

const val IFACE_ADAPTER1: String = "org.bluez.Adapter1"
const val IFACE_DEVICE1: String = "org.bluez.Device1"
const val IFACE_GATT_MANAGER1: String = "org.bluez.GattManager1"
const val IFACE_LE_ADVERTISING_MANAGER1: String = "org.bluez.LEAdvertisingManager1"
const val IFACE_LE_ADVERTISEMENT1: String = "org.bluez.LEAdvertisement1"
const val IFACE_GATT_SERVICE1: String = "org.bluez.GattService1"
const val IFACE_GATT_CHARACTERISTIC1: String = "org.bluez.GattCharacteristic1"
