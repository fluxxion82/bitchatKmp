package com.bitchat.bluetooth.linux

import org.freedesktop.dbus.DBusPath
import org.freedesktop.dbus.Marshalling
import org.freedesktop.dbus.annotations.DBusBoundProperty
import org.freedesktop.dbus.interfaces.DBusInterface
import org.freedesktop.dbus.interfaces.ObjectManager
import org.freedesktop.dbus.interfaces.Properties
import org.freedesktop.dbus.types.Variant
import org.freedesktop.dbus.utils.DBusNamingUtil
import java.lang.reflect.Method
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type
import java.lang.reflect.WildcardType
import kotlin.coroutines.Continuation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Pins the D-Bus wire contract of everything in `BlueZInterfaces.kt`.
 *
 * dbus-java does not read our source; it reads the *class file* at runtime, through
 * [Marshalling] and [DBusNamingUtil], and derives an interface name, a member name and an
 * argument/return signature for every method. Kotlin will happily compile a declaration whose
 * reflected shape is wrong -- a `String` where BlueZ wants an `o`, an `Int` where it wants an `n`,
 * a `suspend` marker, a stray wildcard -- and the mistake surfaces only when BlueZ makes the call
 * on a real adapter. Nothing between here and there checks it.
 *
 * So this test asks dbus-java's own machinery the same questions it will ask at runtime, and
 * compares the answers against strings copied from the BlueZ API documentation. It deliberately
 * hard-codes both sides: an expectation derived from the declaration under test would move with it
 * and pin nothing.
 */
class BlueZInterfaceSignatureTest {

    // ---------------------------------------------------------------------------------------
    // The contract, stated once
    // ---------------------------------------------------------------------------------------

    private data class MethodSpec(val member: String, val args: String, val returns: String)

    private data class PropertySpec(val name: String, val signature: String)

    private data class InterfaceSpec(
        val type: Class<*>,
        val dbusName: String,
        val methods: List<MethodSpec>,
        val properties: List<PropertySpec> = emptyList()
    )

    private val interfaces: List<InterfaceSpec> = listOf(
        InterfaceSpec(
            type = Adapter1::class.java,
            dbusName = "org.bluez.Adapter1",
            methods = listOf(
                MethodSpec("StartDiscovery", args = "", returns = ""),
                MethodSpec("StopDiscovery", args = "", returns = ""),
                // The one that bites: a{sv}, not a{s?}. See wildcard tests below.
                MethodSpec("SetDiscoveryFilter", args = "a{sv}", returns = ""),
                // `o`, not `s`. BlueZ rejects a string where it wants an object path.
                MethodSpec("RemoveDevice", args = "o", returns = "")
            ),
            properties = listOf(
                PropertySpec("Address", "s"),
                PropertySpec("Name", "s"),
                PropertySpec("Alias", "s"),
                PropertySpec("Powered", "b"),
                PropertySpec("Discovering", "b"),
                PropertySpec("UUIDs", "as")
            )
        ),
        InterfaceSpec(
            type = Device1::class.java,
            dbusName = "org.bluez.Device1",
            methods = listOf(
                MethodSpec("Connect", args = "", returns = ""),
                MethodSpec("Disconnect", args = "", returns = "")
            ),
            properties = listOf(
                PropertySpec("Address", "s"),
                PropertySpec("Name", "s"),
                PropertySpec("Alias", "s"),
                // `n` -- signed 16-bit. An `Int` here would compute `i` and fail to deserialise.
                PropertySpec("RSSI", "n"),
                PropertySpec("Connected", "b"),
                PropertySpec("ServicesResolved", "b"),
                PropertySpec("UUIDs", "as"),
                PropertySpec("Adapter", "o")
            )
        ),
        InterfaceSpec(
            type = GattManager1::class.java,
            dbusName = "org.bluez.GattManager1",
            methods = listOf(
                MethodSpec("RegisterApplication", args = "oa{sv}", returns = ""),
                MethodSpec("UnregisterApplication", args = "o", returns = "")
            )
        ),
        InterfaceSpec(
            type = LEAdvertisingManager1::class.java,
            dbusName = "org.bluez.LEAdvertisingManager1",
            methods = listOf(
                MethodSpec("RegisterAdvertisement", args = "oa{sv}", returns = ""),
                MethodSpec("UnregisterAdvertisement", args = "o", returns = "")
            )
        ),
        InterfaceSpec(
            type = LEAdvertisement1::class.java,
            dbusName = "org.bluez.LEAdvertisement1",
            methods = listOf(MethodSpec("Release", args = "", returns = ""))
        ),
        InterfaceSpec(
            type = GattService1::class.java,
            dbusName = "org.bluez.GattService1",
            methods = emptyList()
        ),
        InterfaceSpec(
            type = GattCharacteristic1::class.java,
            dbusName = "org.bluez.GattCharacteristic1",
            methods = listOf(
                // ReadValue(a{sv}) -> ay. `ay` demands a byte[]; a List<Byte> is a different
                // thing on the wire.
                MethodSpec("ReadValue", args = "a{sv}", returns = "ay"),
                MethodSpec("WriteValue", args = "aya{sv}", returns = ""),
                MethodSpec("StartNotify", args = "", returns = ""),
                MethodSpec("StopNotify", args = "", returns = "")
            )
        )
    )

    /** Every `a{sv}` parameter in the file, as (interface, member, zero-based parameter index). */
    private val dictParameters: List<Triple<Class<*>, String, Int>> = listOf(
        Triple(Adapter1::class.java, "SetDiscoveryFilter", 0),
        Triple(GattManager1::class.java, "RegisterApplication", 1),
        Triple(LEAdvertisingManager1::class.java, "RegisterAdvertisement", 1),
        Triple(GattCharacteristic1::class.java, "ReadValue", 0),
        Triple(GattCharacteristic1::class.java, "WriteValue", 1)
    )

    // ---------------------------------------------------------------------------------------
    // 1. Interface names
    // ---------------------------------------------------------------------------------------

    @Test
    fun everyInterfaceResolvesToItsBlueZName() {
        // Without @DBusInterfaceName, dbus-java falls back to the fully-qualified Kotlin class
        // name -- `com.bitchat.bluetooth.linux.Adapter1` -- which BlueZ would never match.
        for (spec in interfaces) {
            assertEquals(
                spec.dbusName,
                DBusNamingUtil.getInterfaceName(spec.type),
                "${spec.type.simpleName} must be exported as ${spec.dbusName}. dbus-java resolved " +
                    "it to '${DBusNamingUtil.getInterfaceName(spec.type)}' -- check @DBusInterfaceName."
            )
        }
    }

    @Test
    fun everyInterfaceExtendsDBusInterface() {
        for (spec in interfaces) {
            assertTrue(
                DBusInterface::class.java.isAssignableFrom(spec.type),
                "${spec.type.simpleName} must extend org.freedesktop.dbus.interfaces.DBusInterface " +
                    "or dbus-java will neither export it nor build a proxy for it."
            )
        }
    }

    @Test
    fun theInterfaceNameConstantsAgreeWithTheAnnotations() {
        // The constants are restated by hand because annotation values cannot be read back as
        // compile-time constants; this keeps the two spellings from drifting apart.
        assertEquals(IFACE_ADAPTER1, DBusNamingUtil.getInterfaceName(Adapter1::class.java))
        assertEquals(IFACE_DEVICE1, DBusNamingUtil.getInterfaceName(Device1::class.java))
        assertEquals(IFACE_GATT_MANAGER1, DBusNamingUtil.getInterfaceName(GattManager1::class.java))
        assertEquals(
            IFACE_LE_ADVERTISING_MANAGER1,
            DBusNamingUtil.getInterfaceName(LEAdvertisingManager1::class.java)
        )
        assertEquals(
            IFACE_LE_ADVERTISEMENT1,
            DBusNamingUtil.getInterfaceName(LEAdvertisement1::class.java)
        )
        assertEquals(IFACE_GATT_SERVICE1, DBusNamingUtil.getInterfaceName(GattService1::class.java))
        assertEquals(
            IFACE_GATT_CHARACTERISTIC1,
            DBusNamingUtil.getInterfaceName(GattCharacteristic1::class.java)
        )
    }

    // ---------------------------------------------------------------------------------------
    // 2. Method signatures
    // ---------------------------------------------------------------------------------------

    @Test
    fun everyMethodMarshalsToItsDocumentedSignature() {
        for (spec in interfaces) {
            for (expected in spec.methods) {
                val method = spec.type.methodNamed(expected.member)
                assertEquals(
                    expected.member,
                    DBusNamingUtil.getMethodName(method),
                    "${spec.dbusName}.${expected.member} must appear on the wire under that exact " +
                        "member name; renaming the Kotlin function renames the D-Bus member."
                )
                assertEquals(
                    expected.args,
                    method.argumentSignature(),
                    "${spec.dbusName}.${expected.member} takes '${expected.args}' on the wire but " +
                        "dbus-java derives '${method.argumentSignature()}' from " +
                        "${method.parameterList()}."
                )
                assertEquals(
                    expected.returns,
                    method.returnSignature(),
                    "${spec.dbusName}.${expected.member} returns '${expected.returns}' on the wire " +
                        "but dbus-java derives '${method.returnSignature()}' from " +
                        "${method.genericReturnType.typeName}."
                )
            }
        }
    }

    @Test
    fun noInterfaceCarriesAnUndeclaredMember() {
        // A method added to one of these interfaces without a line in the table above is a member
        // BlueZ has never been told about; a method removed silently drops a call site's contract.
        for (spec in interfaces) {
            val actual = spec.type.declaredMethods
                .filterNot { it.isSynthetic }
                .filterNot { it.isPropertyAccessor() }
                .map { DBusNamingUtil.getMethodName(it) }
                .toSortedSet()
            assertEquals(
                spec.methods.map { it.member }.toSortedSet(),
                actual,
                "The member set of ${spec.dbusName} changed. Update the expectation table only " +
                    "after confirming the new shape against the BlueZ D-Bus API docs."
            )
        }
    }

    // ---------------------------------------------------------------------------------------
    // 3. The wildcard trap
    // ---------------------------------------------------------------------------------------

    @Test
    fun noDictParameterCarriesAWildcard() {
        for ((type, member, index) in dictParameters) {
            val method = type.methodNamed(member)
            val declared = method.genericParameterTypes[index]

            val parameterized = declared as? ParameterizedType ?: fail(
                "${DBusNamingUtil.getInterfaceName(type)}.$member parameter #$index should be a " +
                    "Map<String, Variant<?>> but reflects as '${declared.typeName}'."
            )
            assertEquals(
                Map::class.java,
                parameterized.rawType,
                "${DBusNamingUtil.getInterfaceName(type)}.$member parameter #$index must be a Map."
            )

            val valueArgument = parameterized.actualTypeArguments[1]

            if (valueArgument is WildcardType) {
                fail(
                    """
                    @JvmSuppressWildcards has been removed from ${type.simpleName}.

                    ${DBusNamingUtil.getInterfaceName(type)}.$member parameter #$index now reflects as
                        Map<String, ${valueArgument.typeName}>
                    where dbus-java requires the invariant
                        Map<String, org.freedesktop.dbus.types.Variant<?>>

                    kotlin.collections.Map declares `out V`, so Kotlin emits a `? extends` use-site
                    wildcard for every Map value in parameter position unless the declaration (or an
                    enclosing one) carries @JvmSuppressWildcards. dbus-java's Marshalling and its own
                    Properties/ObjectManager interfaces are written against the invariant form; the
                    wildcard form compiles, exports, and then fails the moment BlueZ actually calls
                    this method. Restore @JvmSuppressWildcards on ${type.simpleName}.
                    """.trimIndent()
                )
            }

            val valueParameterized = valueArgument as? ParameterizedType ?: fail(
                "${DBusNamingUtil.getInterfaceName(type)}.$member parameter #$index has value type " +
                    "'${valueArgument.typeName}'; it must be exactly Variant<?>."
            )
            assertEquals(
                Variant::class.java,
                valueParameterized.rawType,
                "${DBusNamingUtil.getInterfaceName(type)}.$member parameter #$index must map to " +
                    "Variant, not '${valueParameterized.rawType.typeName}'. Only a Variant carries " +
                    "the `v` that makes the dict an a{sv}."
            )
            assertEquals(
                String::class.java,
                parameterized.actualTypeArguments[0],
                "${DBusNamingUtil.getInterfaceName(type)}.$member parameter #$index must be keyed " +
                    "by String to marshal as a{sv}."
            )
        }
    }

    @Test
    fun noParameterAnywhereCarriesAWildcard() {
        // The list above names the dicts we know about. This one is the backstop: any generic
        // parameter on any of these interfaces, wildcard-free.
        for (spec in interfaces) {
            for (method in spec.type.declaredMethods.filterNot { it.isSynthetic }) {
                for ((index, parameter) in method.genericParameterTypes.withIndex()) {
                    val offender = parameter.findWildcardOutsideVariant()
                    assertTrue(
                        offender == null,
                        "${spec.dbusName}.${DBusNamingUtil.getMethodName(method)} parameter #$index " +
                            "reflects as '${parameter.typeName}', which contains the wildcard " +
                            "'${offender?.typeName}'. That is what @JvmSuppressWildcards exists to " +
                            "prevent -- dbus-java needs the invariant type. See the note at the top " +
                            "of BlueZInterfaces.kt."
                    )
                }
            }
        }
    }

    // ---------------------------------------------------------------------------------------
    // 4. Property accessors
    // ---------------------------------------------------------------------------------------

    @Test
    fun everyPropertyMarshalsToItsDocumentedSignature() {
        for (spec in interfaces) {
            for (expected in spec.properties) {
                val getter = spec.type.propertyGetter(expected.name)
                assertEquals(
                    expected.name,
                    DBusNamingUtil.getPropertyName(getter),
                    "BlueZ spells this property '${expected.name}'. Left implicit, dbus-java would " +
                        "derive it from the Kotlin getter name (getRssi -> Rssi, getUuids -> " +
                        "Uuids); the `name = ` argument on @DBusBoundProperty is what stops that."
                )
                assertEquals(
                    expected.signature,
                    getter.returnSignature(),
                    "${spec.dbusName}.${expected.name} is '${expected.signature}' on the wire but " +
                        "dbus-java derives '${getter.returnSignature()}' from the getter's return " +
                        "type ${getter.genericReturnType.typeName}."
                )
            }
        }
    }

    @Test
    fun everyPropertyIsExposedThroughDBusBoundProperty() {
        for (spec in interfaces) {
            val annotated = spec.type.declaredMethods
                .filter { it.isPropertyAccessor() }
                .map { DBusNamingUtil.getPropertyName(it) }
                .toSortedSet()
            assertEquals(
                spec.properties.map { it.name }.toSortedSet(),
                annotated,
                "The property set of ${spec.dbusName} changed. A Kotlin `val` without " +
                    "@get:DBusBoundProperty is not a D-Bus property at all -- dbus-java would treat " +
                    "the getter as a method member named getFoo()."
            )
        }
    }

    @Test
    fun objectPathPropertiesAreDBusPathAndNotString() {
        // `o` and `s` are different types on the wire and dbus-java cannot guess the intent from a
        // String, so this is stated separately from the signature table.
        assertEquals(
            DBusPath::class.java,
            Device1::class.java.propertyGetter("Adapter").returnType,
            "Device1.Adapter is an object path (`o`); declaring it as String marshals `s`."
        )
        assertEquals(
            DBusPath::class.java,
            Adapter1::class.java.methodNamed("RemoveDevice").parameterTypes[0],
            "Adapter1.RemoveDevice takes an object path (`o`); a String argument marshals `s` and " +
                "BlueZ rejects it."
        )
    }

    // ---------------------------------------------------------------------------------------
    // 5. No suspend functions
    // ---------------------------------------------------------------------------------------

    @Test
    fun noMethodIsASuspendFunction() {
        // A suspend function compiles to a JVM method with a trailing Continuation and an erased
        // Object return. dbus-java would try to marshal the continuation as an argument.
        for (spec in interfaces) {
            for (method in spec.type.declaredMethods.filterNot { it.isSynthetic }) {
                assertFalse(
                    method.parameterTypes.lastOrNull() == Continuation::class.java,
                    "${spec.dbusName}.${method.name} is a suspend function. It compiles, but on the " +
                        "JVM it carries a trailing kotlin.coroutines.Continuation parameter and " +
                        "returns Object, so dbus-java cannot marshal it. Make it a plain function " +
                        "and bridge at the call site, e.g. withContext(Dispatchers.IO) { ... }."
                )
            }
        }
    }

    // ---------------------------------------------------------------------------------------
    // 6. dbus-java's own Properties / ObjectManager, not a local shadow
    // ---------------------------------------------------------------------------------------

    @Test
    fun propertiesAndObjectManagerComeFromDbusJava() {
        assertEquals(
            "org.freedesktop.dbus.interfaces.Properties",
            Properties::class.java.name
        )
        assertEquals(
            "org.freedesktop.dbus.interfaces.ObjectManager",
            ObjectManager::class.java.name
        )
        assertEquals("org.freedesktop.DBus.Properties", DBusNamingUtil.getInterfaceName(Properties::class.java))
        assertEquals(
            "org.freedesktop.DBus.ObjectManager",
            DBusNamingUtil.getInterfaceName(ObjectManager::class.java)
        )

        for (shadow in listOf(
            "com.bitchat.bluetooth.linux.Properties",
            "com.bitchat.bluetooth.linux.ObjectManager"
        )) {
            val found = runCatching { Class.forName(shadow) }.getOrNull()
            assertTrue(
                found == null,
                "$shadow exists. These two interfaces must be dbus-java's own: they already carry " +
                    "the org.freedesktop.DBus.* names and the invariant Map signatures BlueZ " +
                    "expects, and a local redeclaration would shadow them at the import site."
            )
        }
    }

    @Test
    fun objectManagerReturnsTheManagedObjectsDictShape() {
        val getManagedObjects = ObjectManager::class.java.methodNamed("GetManagedObjects")
        assertEquals("", getManagedObjects.argumentSignature())
        assertEquals(
            "a{oa{sa{sv}}}",
            getManagedObjects.returnSignature(),
            "The GATT application root replies to GetManagedObjects with a{oa{sa{sv}}} -- outer key " +
                "a DBusPath, not a String. Anything else and BlueZ abandons RegisterApplication."
        )
    }

    @Test
    fun propertiesGetAllReturnsAnInvariantDict() {
        val getAll = Properties::class.java.methodNamed("GetAll")
        assertEquals("s", getAll.argumentSignature())
        assertEquals("a{sv}", getAll.returnSignature())

        val returned = getAll.genericReturnType as ParameterizedType
        assertTrue(
            returned.actualTypeArguments[1] !is WildcardType,
            "dbus-java's Properties.GetAll returns the invariant Map<String, Variant<?>>. An " +
                "override of it in Kotlin must match that erasure, which is why the implementing " +
                "classes need @JvmSuppressWildcards too."
        )
    }

    // ---------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------

    private fun Class<*>.methodNamed(name: String): Method =
        declaredMethods.singleOrNull { it.name == name && !it.isSynthetic }
            ?: fail("$name is not declared on ${this.name}; it may have been renamed or removed.")

    private fun Class<*>.propertyGetter(dbusName: String): Method {
        val candidates = declaredMethods.filter {
            it.isPropertyAccessor() && DBusNamingUtil.getPropertyName(it) == dbusName
        }
        assertEquals(
            1,
            candidates.size,
            "Expected exactly one @DBusBoundProperty accessor named '$dbusName' on ${this.name}, " +
                "found ${candidates.size}."
        )
        return assertNotNull(candidates.firstOrNull())
    }

    private fun Method.isPropertyAccessor(): Boolean =
        isAnnotationPresent(DBusBoundProperty::class.java)

    private fun Method.argumentSignature(): String =
        Marshalling.getDBusType(genericParameterTypes) ?: ""

    private fun Method.returnSignature(): String =
        if (returnType == Void.TYPE) "" else Marshalling.getDBusType(genericReturnType).joinToString("")

    private fun Method.parameterList(): String =
        genericParameterTypes.joinToString(", ", "(", ")") { it.typeName }

    /**
     * Finds a wildcard anywhere in [this] type, ignoring the one inside `Variant<?>` itself --
     * that one is `Variant`'s own declaration and is what dbus-java expects.
     */
    private fun Type.findWildcardOutsideVariant(): Type? = when (this) {
        is WildcardType -> this
        is ParameterizedType -> {
            if (rawType == Variant::class.java) null
            else actualTypeArguments.firstNotNullOfOrNull { it.findWildcardOutsideVariant() }
        }
        else -> null
    }
}
