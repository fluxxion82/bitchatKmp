package com.bitchat.bluetooth.manager

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * What a gattlib_connect status says about the D-Bus calls behind it.
 *
 * gattlib packs a GError as `0x10000000 | domain << 8 | code`. The domains are GLib quarks numbered at
 * run time; 197 and 98 are what the boards' journals show for `g-io-error-quark` and
 * `g-dbus-error-quark`, and the tests pass other numbers too so that nothing depends on them.
 */
class ConnectCallTest {

    private val io = 197u
    private val dbus = 98u

    private fun dbusStatus(domain: UInt, code: Int): Int = 0x10000000 or (domain.toInt() shl 8) or code

    private fun of(status: Int, ioDomain: UInt = io, dbusDomain: UInt = dbus) =
        ConnectCall.of(status, ioErrorDomain = ioDomain, dbusErrorDomain = dbusDomain)

    @Test
    fun theStatusesSeenOnTheBoardsAreClassifiedAsTheyHappened() {
        // Journal, 2026-10-09: "268460552 (0x10006208, D-Bus domain 98 code 8)", the bus refusing.
        assertEquals(ConnectCall.BUS_REFUSED, of(268460552))
        // Journal, 2026-10-14: "268485924 (0x1000c524, D-Bus domain 197 code 36)", org.bluez.Error.Failed.
        assertEquals(ConnectCall.BLUEZ_ANSWERED, of(268485924))
        // gattlib's 25 s GDBus time-out, "domain 197 code 24".
        assertEquals(ConnectCall.TIMED_OUT, of(dbusStatus(io, 24)))
    }

    @Test
    fun aTimeOutIsOnlyTheIoDomainsCode24() {
        assertEquals(ConnectCall.TIMED_OUT, of(dbusStatus(io, 24)))
        assertEquals(ConnectCall.OTHER, of(dbusStatus(io, 23)))
        assertEquals(ConnectCall.OTHER, of(dbusStatus(io, 25)))
        assertEquals(ConnectCall.OTHER, of(dbusStatus(dbus, 24)))
        assertEquals(ConnectCall.OTHER, of(dbusStatus(io + 1u, 24)))
    }

    @Test
    fun aRefusalByTheBusIsOnlyTheDbusDomainsCode8() {
        assertEquals(ConnectCall.BUS_REFUSED, of(dbusStatus(dbus, 8)))
        assertEquals(ConnectCall.OTHER, of(dbusStatus(dbus, 7)))
        assertEquals(ConnectCall.OTHER, of(dbusStatus(dbus, 9)))
        assertEquals(ConnectCall.OTHER, of(dbusStatus(io, 8)))
        assertEquals(ConnectCall.OTHER, of(dbusStatus(dbus - 1u, 8)))
    }

    @Test
    fun anErrorNameGdbusDoesNotKnowIsBluezAnswering() {
        assertEquals(ConnectCall.BLUEZ_ANSWERED, of(dbusStatus(io, 36)))
        assertEquals(ConnectCall.OTHER, of(dbusStatus(io, 35)))
        assertEquals(ConnectCall.OTHER, of(dbusStatus(io, 37)))
        // The bus's own errors map into the D-Bus domain and prove nothing about bluetoothd.
        assertEquals(ConnectCall.OTHER, of(dbusStatus(dbus, 36)))
    }

    @Test
    fun successAndUnknownObjectAreBluezAnswering() {
        // GATTLIB_SUCCESS: bluetoothd answered the Connect. GATTLIB_NOT_FOUND: it answered UnknownObject.
        assertEquals(ConnectCall.BLUEZ_ANSWERED, of(0))
        assertEquals(ConnectCall.BLUEZ_ANSWERED, of(2))
    }

    @Test
    fun theDomainsAreThoseOfThisRunNotFixedNumbers() {
        assertEquals(ConnectCall.TIMED_OUT, of(dbusStatus(5u, 24), ioDomain = 5u, dbusDomain = 6u))
        assertEquals(ConnectCall.BUS_REFUSED, of(dbusStatus(6u, 8), ioDomain = 5u, dbusDomain = 6u))
        assertEquals(ConnectCall.BLUEZ_ANSWERED, of(dbusStatus(5u, 36), ioDomain = 5u, dbusDomain = 6u))
        assertEquals(ConnectCall.OTHER, of(dbusStatus(197u, 24), ioDomain = 5u, dbusDomain = 6u))
        // The largest domain gattlib's packing keeps whole.
        assertEquals(ConnectCall.TIMED_OUT, of(dbusStatus(0xFFFFFu, 24), ioDomain = 0xFFFFFu, dbusDomain = 6u))
    }

    @Test
    fun aDomainThatIsNotRegisteredMatchesNothing() {
        assertEquals(ConnectCall.OTHER, of(dbusStatus(0u, 24), ioDomain = 0u))
        assertEquals(ConnectCall.OTHER, of(dbusStatus(0u, 36), ioDomain = 0u))
        assertEquals(ConnectCall.OTHER, of(dbusStatus(0u, 8), dbusDomain = 0u))
    }

    @Test
    fun everyModuleDomainAndCodeAgainstTheRule() {
        val domains = listOf(0u, io, dbus, io - 1u, io + 1u, dbus - 1u, dbus + 1u)
        for (module in 0..15) for (domain in domains) for (code in 0..255) {
            val status = (module shl 28) or (domain.toInt() shl 8) or code
            val expected = when {
                status == 0 || status == 2 -> ConnectCall.BLUEZ_ANSWERED
                module != 1 -> ConnectCall.OTHER
                domain == io && code == 24 -> ConnectCall.TIMED_OUT
                domain == io && code == 36 -> ConnectCall.BLUEZ_ANSWERED
                domain == dbus && code == 8 -> ConnectCall.BUS_REFUSED
                else -> ConnectCall.OTHER
            }
            assertEquals(expected, of(status), "status 0x${status.toUInt().toString(16)}")
        }
    }

    @Test
    fun onlyTheDbusModuleCarriesAGerror() {
        listOf(0x00000000, 0x20000000, 0x30000000, 0x80000000.toInt(), 0x90000000.toInt()).forEach { module ->
            listOf(io to 24, io to 36, dbus to 8).forEach { (domain, code) ->
                val status = module or (domain.toInt() shl 8) or code
                assertEquals(ConnectCall.OTHER, of(status), "status 0x${status.toUInt().toString(16)}")
            }
        }
    }

    @Test
    fun whatProvesNothingAboutTheBusIsOther() {
        // gattlib's plain codes of include/gattlib.h other than SUCCESS and NOT_FOUND: INVALID_PARAMETER (no
        // record for the address), BUSY, ADAPTER_CLOSE, ...
        listOf(1, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12).forEach { status ->
            assertEquals(ConnectCall.OTHER, of(status), "status $status")
        }
        // The BlueZ, Unix and internal modules, and gattlib's bare D-Bus error with no GError.
        listOf(0x20000000 or 24, 0x30000000 or 8, 0x80000000.toInt() or 24, 0x10000000).forEach { status ->
            assertEquals(ConnectCall.OTHER, of(status), "status 0x${status.toUInt().toString(16)}")
        }
    }
}
