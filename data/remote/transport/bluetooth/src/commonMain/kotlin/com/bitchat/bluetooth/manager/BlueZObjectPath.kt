package com.bitchat.bluetooth.manager

/**
 * Parsing of BlueZ D-Bus object paths.
 *
 * This is pure string handling with no cinterop in it, and it lives in commonMain rather than
 * beside the BlueZ services purely so it can be tested on the JVM: the linuxArm64 target is
 * cross-compiled and its tests cannot run on a development machine.
 *
 * The three places that need a device address from a path — the `WriteValue` options dict, the
 * `Device1` `PropertiesChanged` signal and `InterfacesRemoved` — must agree on the answer, or the
 * server registers a client under one spelling and tries to drop it under another.
 */
object BlueZObjectPath {

    private const val DEVICE_SEGMENT_PREFIX = "dev_"

    /** The adapter the embedded app works with; gattlib's default and the GATT server's. */
    const val ADAPTER_PATH = "/org/bluez/hci0"

    /**
     * Whether [path] is [adapterPath] itself or an object below it.
     *
     * An address alone does not name a link when a machine has two controllers: the same peer can
     * be connected on each, and what is known of one link (its age above all) must not be taken
     * for the other's. Everything that watches or lists BlueZ's devices for the app keeps to the
     * one adapter the app uses.
     */
    fun isOnAdapter(path: String, adapterPath: String = ADAPTER_PATH): Boolean =
        path == adapterPath || path.startsWith("$adapterPath/")

    /**
     * The Bluetooth address in `/org/bluez/hci0/dev_5C_00_46_51_7B_0E`, or null when the path does
     * not name a device.
     *
     * BlueZ writes the address into the last path segment with underscores for colons, upper case
     * hex. Paths below the device (a GATT service or characteristic BlueZ owns, say) still name
     * that device, so the `dev_` segment is looked for anywhere in the path.
     */
    fun deviceAddress(path: String): String? {
        val segment = path
            .split('/')
            .firstOrNull { it.startsWith(DEVICE_SEGMENT_PREFIX) }
            ?: return null

        val address = segment.removePrefix(DEVICE_SEGMENT_PREFIX).replace('_', ':')
        return if (isBluetoothAddress(address)) address else null
    }

    /**
     * The BlueZ device object path for [address], or null when it is not a Bluetooth address.
     *
     * The path goes into a D-Bus method call and libdbus aborts the process on an invalid path.
     */
    fun devicePath(address: String, adapterPath: String = ADAPTER_PATH): String? {
        if (!isBluetoothAddress(address)) return null
        return "$adapterPath/$DEVICE_SEGMENT_PREFIX${address.uppercase().replace(':', '_')}"
    }

    private fun isBluetoothAddress(candidate: String): Boolean {
        val octets = candidate.split(':')
        if (octets.size != 6) return false
        return octets.all { octet ->
            octet.length == 2 && octet.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
        }
    }
}
