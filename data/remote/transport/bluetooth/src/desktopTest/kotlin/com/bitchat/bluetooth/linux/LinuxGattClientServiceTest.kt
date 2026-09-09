package com.bitchat.bluetooth.linux

import org.freedesktop.dbus.DBusPath
import org.freedesktop.dbus.types.UInt16
import org.freedesktop.dbus.types.Variant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The three decisions the central role makes that do not need a radio.
 *
 * Each of them fails silently in the wild. A characteristic lookup that matches nothing looks
 * exactly like a peer that does not run bitchat; a chunk cap that is too large is truncated by the
 * controller with nothing logged at either end; and an error classified as retryable when it is not
 * produces a connection storm rather than an error message.
 */
class LinuxGattClientServiceTest {

    private val device = "/org/bluez/hci0/dev_AA_BB_CC_DD_EE_FF"
    private val service = "$device/service0010"
    private val characteristic = "$service/char0011"

    private fun gattService(uuid: String): Map<String, Map<String, Variant<*>>> =
        mapOf(IFACE_GATT_SERVICE1 to mapOf("UUID" to Variant(uuid), "Primary" to Variant(true)))

    private fun gattCharacteristic(
        uuid: String,
        servicePath: String,
        mtu: Int? = null
    ): Map<String, Map<String, Variant<*>>> {
        val properties = buildMap<String, Variant<*>> {
            put("UUID", Variant(uuid))
            // `o`, not `s`: BlueZ resolves the owning service by object path.
            put("Service", Variant(DBusPath(servicePath)))
            put("Flags", variantOfStrings(listOf("write-without-response", "notify")))
            // D-Bus `q`, which on the JVM is UInt16 and not a Short.
            if (mtu != null) put("MTU", Variant(UInt16(mtu)))
        }
        return mapOf(IFACE_GATT_CHARACTERISTIC1 to properties)
    }

    // -------------------------------------------------------------------------------------
    // findBitchatCharacteristic
    // -------------------------------------------------------------------------------------

    /**
     * The shape BlueZ actually publishes: every UUID lower-cased, the characteristic pointing at
     * its service by path. A case-sensitive comparison against the upper-case wire constants finds
     * nothing here, which is the defect this test exists to catch.
     */
    @Test
    fun `finds the characteristic in a tree spelled the way BlueZ spells it`() {
        val objects = mapOf(
            device to mapOf(IFACE_DEVICE1 to mapOf<String, Variant<*>>("Address" to Variant("AA:BB:CC:DD:EE:FF"))),
            service to gattService(BITCHAT_SERVICE_UUID.lowercase()),
            characteristic to gattCharacteristic(BITCHAT_CHARACTERISTIC_UUID.lowercase(), service)
        )

        val found = findBitchatCharacteristic(objects, device)

        assertEquals(characteristic, found?.path)
        assertEquals(service, found?.servicePath)
        assertNull(found?.mtu)
    }

    @Test
    fun `reads the MTU property when the peer publishes one`() {
        val objects = mapOf(
            service to gattService(BITCHAT_SERVICE_UUID.lowercase()),
            characteristic to gattCharacteristic(BITCHAT_CHARACTERISTIC_UUID.lowercase(), service, mtu = 517)
        )

        assertEquals(517, findBitchatCharacteristic(objects, device)?.mtu)
    }

    /**
     * The tree contains every device on the adapter, so scoping by the device path is what stops us
     * writing this peer's packets to the characteristic of the phone next to it.
     */
    @Test
    fun `ignores an identical characteristic under a different device`() {
        val otherDevice = "/org/bluez/hci0/dev_11_22_33_44_55_66"
        val otherService = "$otherDevice/service0010"
        val objects = mapOf(
            otherService to gattService(BITCHAT_SERVICE_UUID.lowercase()),
            "$otherService/char0011" to
                gattCharacteristic(BITCHAT_CHARACTERISTIC_UUID.lowercase(), otherService)
        )

        assertNull(findBitchatCharacteristic(objects, device))
    }

    /**
     * The second join, and the reason it is not enough to match the characteristic UUID alone: a
     * characteristic whose `Service` names a service we did not match is not ours, however familiar
     * its UUID looks.
     */
    @Test
    fun `ignores a matching characteristic that belongs to another service`() {
        val strangerService = "$device/service0020"
        val objects = mapOf(
            service to gattService("0000180f-0000-1000-8000-00805f9b34fb"),
            strangerService to gattService("0000180a-0000-1000-8000-00805f9b34fb"),
            characteristic to
                gattCharacteristic(BITCHAT_CHARACTERISTIC_UUID.lowercase(), strangerService)
        )

        assertNull(findBitchatCharacteristic(objects, device))
    }

    /** A connected peer that simply is not running bitchat. */
    @Test
    fun `returns null when the device exposes no bitchat service`() {
        val objects = mapOf(
            service to gattService("0000180f-0000-1000-8000-00805f9b34fb"),
            characteristic to
                gattCharacteristic("00002a19-0000-1000-8000-00805f9b34fb", service)
        )

        assertNull(findBitchatCharacteristic(objects, device))
    }

    /**
     * Our service is there but the characteristic is not, which is what a half-populated tree looks
     * like in the moment after `ServicesResolved` on an adopted link. Must be a miss and not a
     * crash: the caller retries.
     */
    @Test
    fun `returns null when the service is present but the characteristic is not`() {
        val objects = mapOf(service to gattService(BITCHAT_SERVICE_UUID.lowercase()))

        assertNull(findBitchatCharacteristic(objects, device))
    }

    /** Tolerated, not expected -- see the KDoc on the object-path reader. */
    @Test
    fun `tolerates a Service property that arrived as a string`() {
        val objects = mapOf(
            service to gattService(BITCHAT_SERVICE_UUID.lowercase()),
            characteristic to mapOf(
                IFACE_GATT_CHARACTERISTIC1 to mapOf<String, Variant<*>>(
                    "UUID" to Variant(BITCHAT_CHARACTERISTIC_UUID.lowercase()),
                    "Service" to Variant(service)
                )
            )
        )

        assertEquals(characteristic, findBitchatCharacteristic(objects, device)?.path)
    }

    // -------------------------------------------------------------------------------------
    // clientChunkCap
    // -------------------------------------------------------------------------------------

    /**
     * The whole MTU policy: 500 is the ceiling and the fallback at once, so a node that never reads
     * an MTU still writes exactly what every existing peer assumes of it.
     */
    @Test
    fun `falls back to 500 when no MTU was reported`() {
        assertEquals(500, clientChunkCap(null))
        assertEquals(500, clientChunkCap(0))
        assertEquals(500, clientChunkCap(-1))
    }

    /** A large MTU may not raise the cap -- the peers' reassembly is sized for 500. */
    @Test
    fun `never exceeds 500 however large the MTU`() {
        assertEquals(500, clientChunkCap(517))
        assertEquals(500, clientChunkCap(23_000))
    }

    /** Three bytes of ATT header come off the top; an oversized write is truncated in silence. */
    @Test
    fun `subtracts the three-byte ATT header from a small MTU`() {
        assertEquals(182, clientChunkCap(185))
        assertEquals(20, clientChunkCap(23))
    }

    /** 503 is the smallest MTU that still allows the full 500-byte chunk. */
    @Test
    fun `takes the cap right up to 500 at an MTU of 503`() {
        assertEquals(499, clientChunkCap(502))
        assertEquals(500, clientChunkCap(503))
        assertEquals(500, clientChunkCap(504))
    }

    /** A peer reporting less than the mandatory ATT MTU is reporting nonsense. */
    @Test
    fun `floors a nonsensical MTU at the minimum chunk`() {
        assertEquals(20, clientChunkCap(10))
        assertEquals(20, clientChunkCap(1))
    }

    // -------------------------------------------------------------------------------------
    // classifyConnectFault
    // -------------------------------------------------------------------------------------

    /** BlueZ still owns an earlier attempt. Asking again before it lets go only collects another refusal. */
    @Test
    fun `classifies InProgress as coalescible`() {
        assertEquals(
            ConnectFault.IN_PROGRESS,
            classifyConnectFault("org.bluez.Error.InProgress", "Operation already in progress")
        )
    }

    /** The same state reported as a bare Failed, where only the text says what happened. */
    @Test
    fun `classifies a Failed that says in progress as coalescible`() {
        assertEquals(
            ConnectFault.IN_PROGRESS,
            classifyConnectFault("org.bluez.Error.Failed", "Operation already in progress")
        )
    }

    /** Adopt the link and wait for readiness rather than treating it as a failure. */
    @Test
    fun `classifies AlreadyConnected as adoptable`() {
        assertEquals(
            ConnectFault.ALREADY_CONNECTED,
            classifyConnectFault("org.bluez.Error.AlreadyConnected", "Already Connected")
        )
    }

    /** The device object is not there; the scanner will offer it again if BlueZ re-creates it. */
    @Test
    fun `tolerates a device that is not there`() {
        assertEquals(
            ConnectFault.GONE,
            classifyConnectFault("org.bluez.Error.DoesNotExist", "Does Not Exist")
        )
        assertEquals(
            ConnectFault.GONE,
            classifyConnectFault("org.freedesktop.DBus.Error.UnknownObject", "No such object")
        )
    }

    /**
     * A reply that never came says nothing about whether BlueZ went on to connect -- it usually
     * did -- so it must not end the attempt.
     */
    @Test
    fun `treats a missing reply as indeterminate rather than failed`() {
        assertEquals(
            ConnectFault.INDETERMINATE,
            classifyConnectFault("org.freedesktop.DBus.Error.NoReply", "Message did not receive a reply")
        )
    }

    /**
     * The one every failed connect prints. `att_connect_cb` turns nearly any ATT connect error into
     * `-ECONNABORTED`, so it names no cause at all -- which makes ordinary backoff exactly the
     * right response, and makes reading anything more into it a mistake.
     */
    @Test
    fun `backs off on the connection abort BlueZ prints for everything`() {
        assertEquals(
            ConnectFault.FAILED,
            classifyConnectFault("org.bluez.Error.Failed", "le-connection-abort-by-local")
        )
    }

    @Test
    fun `backs off on an unpowered adapter and on anything unrecognised`() {
        assertEquals(
            ConnectFault.FAILED,
            classifyConnectFault("org.bluez.Error.NotReady", "Resource Not Ready")
        )
        assertEquals(ConnectFault.FAILED, classifyConnectFault("java.lang.IllegalStateException", ""))
    }
}
