package com.bitchat.local.service

import com.bitchat.domain.location.model.GeoPoint
import com.bitchat.domain.location.model.LocationUnavailableException
import com.bitchat.domain.tor.RequestedTorIntent
import com.bitchat.domain.tor.model.TorMode
import com.russhwolf.settings.PropertiesSettings
import com.russhwolf.settings.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class JvmLocationServiceClockTest {
    private class Factory : Settings.Factory {
        override fun create(name: String?): Settings = PropertiesSettings(java.util.Properties())
    }

    private class Intent : RequestedTorIntent {
        override val current = TorMode.OFF
        override val updates: StateFlow<TorMode> = MutableStateFlow(TorMode.OFF)
    }

    @Test
    fun `freshness and retry spacing follow the desktop system clock`() = runTest {
        var now = 1_000_000L
        var successfulCalls = 0
        val successful = JvmLocationService(
            settingsFactory = Factory(),
            requestedTorIntent = Intent(),
            ipLookup = { successfulCalls++; GeoPoint(1.0, 2.0) },
            now = { now },
        )

        successful.getCurrentLocation()
        now += 16 * 60 * 1000L
        successful.getCurrentLocation()
        assertEquals(2, successfulCalls)

        var failedCalls = 0
        val failing = JvmLocationService(
            settingsFactory = Factory(),
            requestedTorIntent = Intent(),
            ipLookup = { failedCalls++; null },
            now = { now },
        )
        assertFailsWith<LocationUnavailableException> { failing.getCurrentLocation() }
        now += 59 * 1000L
        assertFailsWith<LocationUnavailableException> { failing.getCurrentLocation() }
        assertEquals(1, failedCalls)
        now += 2 * 1000L
        assertFailsWith<LocationUnavailableException> { failing.getCurrentLocation() }
        assertEquals(2, failedCalls)
    }

    @Test
    fun `the location iplookup property set to false switches the lookup off`() = runTest {
        var calls = 0
        val service = JvmLocationService(
            settingsFactory = Factory(),
            requestedTorIntent = Intent(),
            ipLookup = { calls++; GeoPoint(1.0, 2.0) },
        )
        System.setProperty("location.iplookup", "false")
        try {
            val failure = assertFailsWith<LocationUnavailableException> { service.getCurrentLocation() }
            assertEquals(LocationUnavailableException.Reason.SUPPRESSED_BY_POLICY, failure.reason)
            assertEquals(0, calls)
        } finally {
            System.clearProperty("location.iplookup")
        }
        service.getCurrentLocation()
        assertEquals(1, calls)
    }
}
