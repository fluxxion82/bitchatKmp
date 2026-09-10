package com.bitchat.local.service

import com.bitchat.domain.location.model.GeoPoint
import com.bitchat.domain.location.model.LocationUnavailableException
import com.bitchat.domain.location.model.LocationFixInfo
import com.russhwolf.settings.PropertiesSettings
import com.russhwolf.settings.Settings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JvmLocationServiceTest {

    /** In-memory, and shared across create() calls so a "restart" sees the previous run's fix. */
    private class RecordingFactory : Settings.Factory {
        private val backing = java.util.Properties()
        override fun create(name: String?): Settings = PropertiesSettings(backing)

        /** Ages the stored fix, standing in for time passing between runs. */
        fun rewindObservedAt(byMillis: Long) {
            val key = "last_fix_observed_at"
            val at = backing.getProperty(key)?.toLongOrNull() ?: return
            backing.setProperty(key, (at - byMillis).toString())
        }
    }

    private fun service(
        factory: Settings.Factory = RecordingFactory(),
        lookup: suspend () -> GeoPoint?
    ) = JvmLocationService(settingsFactory = factory, ipLookup = lookup)

    @Test
    fun `returns the looked up fix`() = runTest {
        val subject = service { GeoPoint(37.7749, -122.4194) }

        val fix = subject.getCurrentLocation()

        assertEquals(37.7749, fix.lat)
        assertEquals(-122.4194, fix.lon)
    }

    @Test
    fun `does not look up again while the fix is fresh`() = runTest {
        // The channel sheet refreshes every five seconds. Before caching, that was twelve
        // outbound requests a minute to a third party for a city-level reading that cannot change
        // that fast.
        val calls = AtomicInteger()
        val subject = service {
            calls.incrementAndGet()
            GeoPoint(37.7749, -122.4194)
        }

        repeat(20) { subject.getCurrentLocation() }

        assertEquals(1, calls.get())
    }

    @Test
    fun `backs off rather than retrying every call when the lookup fails`() = runTest {
        val calls = AtomicInteger()
        val subject = service {
            calls.incrementAndGet()
            null
        }

        repeat(20) {
            assertFailsWith<LocationUnavailableException> { subject.getCurrentLocation() }
        }

        assertEquals(1, calls.get())
    }

    @Test
    fun `falls back to a fix stored by an earlier run`() = runTest {
        val shared = RecordingFactory()
        service(shared) { GeoPoint(51.5072, -0.1276) }.getCurrentLocation()

        // A fresh instance, as after a restart, with no network.
        val restarted = service(shared) { null }
        val fix = restarted.getCurrentLocation()

        assertEquals(51.5072, fix.lat)
        assertEquals(-0.1276, fix.lon)
    }

    @Test
    fun `reports unavailable when there is no fix and none was ever stored`() = runTest {
        val subject = service { null }

        val failure = assertFailsWith<LocationUnavailableException> { subject.getCurrentLocation() }

        assertEquals(LocationUnavailableException.Reason.LOOKUP_FAILED, failure.reason)
    }

    @Test
    fun `clearing wipes the fix from memory and from disk`() = runTest {
        // It used to persist coordinates to a store nothing cleared, so the user's last position
        // stayed recoverable after an operation advertised as deleting all stored data.
        val shared = RecordingFactory()
        service(shared) { GeoPoint(51.5072, -0.1276) }.getCurrentLocation()

        service(shared) { null }.clearCachedLocation()

        val afterRestart = service(shared) { null }
        assertFailsWith<LocationUnavailableException> { afterRestart.getCurrentLocation() }
        assertNull(afterRestart.lastFixInfo())
    }

    @Test
    fun `a lookup in flight when the cache is cleared cannot write it back`() = runTest {
        val shared = RecordingFactory()
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val subject = service(shared) {
            started.complete(Unit)
            release.await()
            GeoPoint(48.8566, 2.3522)
        }

        val inFlight = launch { runCatching { subject.getCurrentLocation() } }
        started.await()
        subject.clearCachedLocation()
        release.complete(Unit)
        inFlight.join()

        assertNull(subject.lastFixInfo())
        assertFailsWith<LocationUnavailableException> {
            service(shared) { null }.getCurrentLocation()
        }
    }

    @Test
    fun `an IP fix is reported as approximate`() = runTest {
        val subject = service { GeoPoint(37.7749, -122.4194) }
        subject.getCurrentLocation()

        val info = assertNotNull(subject.lastFixInfo())
        assertEquals(LocationFixInfo.Source.IP_ADDRESS, info.source)
        assertTrue(info.ageMillis < 60_000, "a fix just taken should not read as old")
    }

    @Test
    fun `a fix restored from disk carries its original age, not a fresh one`() = runTest {
        // Provenance is what stops a coordinate stored before a journey presenting as the user's
        // current neighbourhood.
        val shared = RecordingFactory()
        service(shared) { GeoPoint(35.6762, 139.6503) }.getCurrentLocation()
        shared.rewindObservedAt(2 * 60 * 60 * 1000L)

        val restarted = service(shared) { null }
        restarted.getCurrentLocation()

        val info = assertNotNull(restarted.lastFixInfo())
        assertTrue(
            info.ageMillis >= 2 * 60 * 60 * 1000L,
            "restored fix reported an age of ${info.ageMillis}ms"
        )
    }

    @Test
    fun `a stored fix does not count as fresh, so a restart still tries to refresh`() = runTest {
        val shared = RecordingFactory()
        service(shared) { GeoPoint(55.6761, 12.5683) }.getCurrentLocation()

        val calls = AtomicInteger()
        val restarted = service(shared) {
            calls.incrementAndGet()
            GeoPoint(55.7, 12.6)
        }
        restarted.getCurrentLocation()

        assertEquals(1, calls.get(), "a restart must re-ask rather than serve last session's fix")
    }

    @Test
    fun `a stale stored fix is preferred to no channels at all`() = runTest {
        val shared = RecordingFactory()
        service(shared) { GeoPoint(48.8566, 2.3522) }.getCurrentLocation()

        val offline = service(shared) { null }
        repeat(5) { assertEquals(48.8566, offline.getCurrentLocation().lat) }
    }
}
