package com.bitchat.bluetooth.manager

/**
 * What a gattlib_connect status says about the D-Bus calls behind it. gattlib packs a GError as
 * `0x10000000 | domain << 8 | code`, and the domains are GLib quarks numbered at run time.
 */
enum class ConnectCall {
    /** bluetoothd itself answered a call of this connection: success, `UnknownObject`, `org.bluez.Error.*`. */
    BLUEZ_ANSWERED,

    /** GDBus stopped waiting after 25 s; the `Connect` may still be pending in bluetoothd. */
    TIMED_OUT,

    /** The bus refused the call: `LimitsExceeded`. */
    BUS_REFUSED,

    /** Proves nothing about the bus: gattlib's own refusals, a closed connection, access denied, ... */
    OTHER;

    companion object {
        fun of(status: Int, ioErrorDomain: UInt, dbusErrorDomain: UInt): ConnectCall {
            if (status == 0 || status == 2) return BLUEZ_ANSWERED
            if ((status and 0xF0000000.toInt()) != 0x10000000) return OTHER

            val domain = (status ushr 8) and 0xFFFFF
            val code = status and 0xFF
            return when {
                ioErrorDomain != 0u && domain == ioErrorDomain.toInt() && code == 24 -> TIMED_OUT
                ioErrorDomain != 0u && domain == ioErrorDomain.toInt() && code == 36 -> BLUEZ_ANSWERED
                dbusErrorDomain != 0u && domain == dbusErrorDomain.toInt() && code == 8 -> BUS_REFUSED
                else -> OTHER
            }
        }
    }
}
