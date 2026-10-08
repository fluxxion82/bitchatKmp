package com.bitchat.bluetooth.manager

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BlueZObjectPathTest {

    @Test
    fun aDevicePathYieldsItsAddress() {
        assertEquals(
            "5C:00:46:51:7B:0E",
            BlueZObjectPath.deviceAddress("/org/bluez/hci0/dev_5C_00_46_51_7B_0E")
        )
    }

    @Test
    fun aPathBelowTheDeviceStillNamesTheDevice() {
        // WriteValue arrives with the device path, but Device1 signals can name objects underneath
        // it. Both must resolve to the same key or a client is registered under one spelling and
        // dropped under another.
        assertEquals(
            "63:F5:53:74:B0:6F",
            BlueZObjectPath.deviceAddress("/org/bluez/hci0/dev_63_F5_53_74_B0_6F/service000a/char000b")
        )
    }

    @Test
    fun anotherAdapterIsHandled() {
        assertEquals(
            "64:A8:3E:11:22:33",
            BlueZObjectPath.deviceAddress("/org/bluez/hci1/dev_64_A8_3E_11_22_33")
        )
    }

    @Test
    fun aPathThatNamesNoDeviceYieldsNull() {
        assertNull(BlueZObjectPath.deviceAddress("/org/bluez/hci0"))
        assertNull(BlueZObjectPath.deviceAddress("/org/bitchat/gatt/service0/char0"))
        assertNull(BlueZObjectPath.deviceAddress("/"))
        assertNull(BlueZObjectPath.deviceAddress(""))
    }

    @Test
    fun aMalformedDeviceSegmentYieldsNullRatherThanGarbage() {
        assertNull(BlueZObjectPath.deviceAddress("/org/bluez/hci0/dev_"))
        assertNull(BlueZObjectPath.deviceAddress("/org/bluez/hci0/dev_5C_00_46"))
        assertNull(BlueZObjectPath.deviceAddress("/org/bluez/hci0/dev_ZZ_00_46_51_7B_0E"))
        assertNull(BlueZObjectPath.deviceAddress("/org/bluez/hci0/dev_5C_00_46_51_7B_0E_AA"))
    }

    @Test
    fun anAddressYieldsItsDevicePath() {
        assertEquals(
            "/org/bluez/hci0/dev_5C_00_46_51_7B_0E",
            BlueZObjectPath.devicePath("5C:00:46:51:7B:0E")
        )
    }

    @Test
    fun aDevicePathIsWrittenTheWayBlueZWritesIt() {
        // BlueZ uses upper case hex; a path in another spelling names no object.
        assertEquals(
            "/org/bluez/hci0/dev_5C_00_46_51_7B_0E",
            BlueZObjectPath.devicePath("5c:00:46:51:7b:0e")
        )
    }

    @Test
    fun aDevicePathLeadsBackToItsAddress() {
        val address = "90:82:8D:69:79:2D"
        assertEquals(address, BlueZObjectPath.deviceAddress(BlueZObjectPath.devicePath(address)!!))
    }

    @Test
    fun aDevicePathCanNameAnotherAdapter() {
        assertEquals(
            "/org/bluez/hci1/dev_64_A8_3E_11_22_33",
            BlueZObjectPath.devicePath("64:A8:3E:11:22:33", adapterPath = "/org/bluez/hci1")
        )
    }

    @Test
    fun nothingButABluetoothAddressYieldsADevicePath() {
        // The path goes into a D-Bus method call, and libdbus aborts the process on a path that is
        // not valid, so nothing that is not six hex octets may get through.
        assertNull(BlueZObjectPath.devicePath(""))
        assertNull(BlueZObjectPath.devicePath("5C:00:46"))
        assertNull(BlueZObjectPath.devicePath("ZZ:00:46:51:7B:0E"))
        assertNull(BlueZObjectPath.devicePath("5C:00:46:51:7B:0E:AA"))
        assertNull(BlueZObjectPath.devicePath("5C_00_46_51_7B_0E"))
        assertNull(BlueZObjectPath.devicePath("5C:00:46:51:7B:0E/../x"))
        assertNull(BlueZObjectPath.devicePath(" 5C:00:46:51:7B:0E"))
        assertNull(BlueZObjectPath.devicePath("/org/bluez/hci0/dev_5C_00_46_51_7B_0E"))
    }

    @Test
    fun aPathIsOnTheAdapterItLiesBelow() {
        assertTrue(BlueZObjectPath.isOnAdapter("/org/bluez/hci0/dev_5C_00_46_51_7B_0E"))
        assertTrue(BlueZObjectPath.isOnAdapter("/org/bluez/hci0/dev_5C_00_46_51_7B_0E/service000a/char000b"))
        assertTrue(BlueZObjectPath.isOnAdapter("/org/bluez/hci0"))
        assertTrue(BlueZObjectPath.isOnAdapter("/org/bluez/hci1/dev_5C_00_46_51_7B_0E", adapterPath = "/org/bluez/hci1"))
    }

    @Test
    fun aDeviceOfAnotherControllerIsNotOnTheAdapter() {
        // The same peer can be connected on two controllers. What the app knows of the link on
        // its own adapter must not be applied to the other one.
        assertFalse(BlueZObjectPath.isOnAdapter("/org/bluez/hci1/dev_5C_00_46_51_7B_0E"))
        assertFalse(BlueZObjectPath.isOnAdapter("/org/bluez/hci0/dev_5C_00_46_51_7B_0E", adapterPath = "/org/bluez/hci1"))
    }

    @Test
    fun anAdapterWhoseNameOnlyBeginsTheSameIsAnotherAdapter() {
        assertFalse(BlueZObjectPath.isOnAdapter("/org/bluez/hci01/dev_5C_00_46_51_7B_0E"))
        assertFalse(BlueZObjectPath.isOnAdapter("/org/bluez/hci0x"))
        assertFalse(BlueZObjectPath.isOnAdapter("/org/bluez"))
        assertFalse(BlueZObjectPath.isOnAdapter("/"))
        assertFalse(BlueZObjectPath.isOnAdapter(""))
    }
}
