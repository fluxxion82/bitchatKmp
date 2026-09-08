package com.bitchat.bluetooth.manager

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

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
}
