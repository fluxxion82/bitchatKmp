package com.bitchat.repo.repositories

import com.bitchat.domain.base.CoroutineScopeFacade
import com.bitchat.domain.base.CoroutinesContextFacade
import com.bitchat.domain.location.model.GeoPoint
import com.bitchat.domain.location.model.GeohashChannelLevel
import com.bitchat.domain.location.model.LocationFixInfo
import com.bitchat.domain.location.model.LocationUnavailableException
import com.bitchat.domain.location.model.ObservedFix
import com.bitchat.domain.location.model.SavedPlace
import com.bitchat.local.prefs.GeohashPreferences
import com.bitchat.local.service.LocationService
import com.bitchat.nostr.participant.NostrParticipantTracker
import com.bitchat.nostr.util.GeohashUtils
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A geohash message goes out without the teleport tag only when the device's own place contains its
 * channel, asked for that channel when the message is sent. Anything unknown answers "teleported".
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LocationRepoPresenceTest {
    private val here = GeoPoint(37.7749, -122.4194)
    private val city = GeohashUtils.encode(here.lat, here.lon, 5)
    private val block = GeohashUtils.encode(here.lat, here.lon, 7)
    private val elsewhere = "u33db"
    private val freshDeviceFix = ObservedFix(here, LocationFixInfo.Source.DEVICE, ageMillis = 0)

    private class Preferences : GeohashPreferences {
        var services = true
        var place: String? = null
        override fun saveLevel(level: GeohashChannelLevel) = Unit
        override fun getLevel(): GeohashChannelLevel? = null
        override fun saveLocationServicesEnabled(enabled: Boolean) { services = enabled }
        override fun getLocationServicesEnabled() = services
        override fun getSavedPlace() = place
        override fun saveSavedPlace(geohash: String) { place = geohash }
        override fun clearSavedPlace() { place = null }
    }

    @Test
    fun `a fresh device fix in the channel's cell is here`() = withRepo { repo, service, _ ->
        coEvery { service.presenceFix() } returns freshDeviceFix

        assertFalse(repo.isTeleportedIn(block))
        assertFalse(repo.isTeleportedIn(city))
    }

    @Test
    fun `a fix in another cell is teleported`() = withRepo { repo, service, _ ->
        coEvery { service.presenceFix() } returns freshDeviceFix

        assertTrue(repo.isTeleportedIn(elsewhere))
    }

    @Test
    fun `no fix is teleported`() = withRepo { repo, service, _ ->
        coEvery { service.presenceFix() } returns null

        assertTrue(repo.isTeleportedIn(city))
    }

    @Test
    fun `a platform read that fails is teleported`() = withRepo { repo, service, _ ->
        coEvery { service.presenceFix() } throws IllegalStateException("broken")
        assertTrue(repo.isTeleportedIn(city))

        coEvery { service.presenceFix() } throws LocationUnavailableException(LocationUnavailableException.Reason.LOOKUP_FAILED)
        assertTrue(repo.isTeleportedIn(city))
    }

    @Test
    fun `a platform read that takes longer than three seconds is teleported and is cancelled`() = withRepo { repo, service, _ ->
        var cancelled = false
        coEvery { service.presenceFix() } coAnswers {
            try {
                awaitCancellation()
            } finally {
                cancelled = true
            }
        }

        val answer = async { repo.isTeleportedIn(city) }
        advanceTimeBy(2_999)
        runCurrent()
        assertFalse(answer.isCompleted)
        advanceTimeBy(2)
        runCurrent()

        assertTrue(answer.isCompleted)
        assertTrue(answer.await())
        assertTrue(cancelled)
    }

    @Test
    fun `a saved place decides without asking the platform`() = withRepo { repo, service, preferences ->
        preferences.place = city
        coEvery { service.presenceFix() } throws AssertionError("the platform must not be asked")

        assertFalse(repo.isTeleportedIn(city))
        assertFalse(repo.isTeleportedIn(city.take(4)))
        assertTrue(repo.isTeleportedIn(block))
        assertTrue(repo.isTeleportedIn(elsewhere))
        coVerify(exactly = 0) { service.presenceFix() }
    }

    @Test
    fun `location services off is teleported whatever the place or the fix`() = withRepo { repo, service, preferences ->
        preferences.services = false
        preferences.place = city
        coEvery { service.presenceFix() } returns freshDeviceFix

        assertTrue(repo.isTeleportedIn(city))
        preferences.place = null
        assertTrue(repo.isTeleportedIn(city))
        coVerify(exactly = 0) { service.presenceFix() }
    }

    @Test
    fun `switching location services off during the platform read is teleported`() = withRepo { repo, service, _ ->
        val read = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        coEvery { service.presenceFix() } coAnswers {
            read.complete(Unit)
            release.await()
            freshDeviceFix
        }

        val answer = async { repo.isTeleportedIn(city) }
        read.await()
        repo.toggleLocationServices()
        release.complete(Unit)

        assertTrue(answer.await())
    }

    @Test
    fun `saving or clearing a place during the platform read is teleported`() = withRepo { repo, service, _ ->
        for (change in listOf<suspend () -> Unit>(
            { repo.setSavedPlace(requireNotNull(SavedPlace.of(city))) },
            { repo.clearSavedPlace() },
        )) {
            val read = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            coEvery { service.presenceFix() } coAnswers {
                read.complete(Unit)
                release.await()
                freshDeviceFix
            }

            val answer = async { repo.isTeleportedIn(city) }
            read.await()
            change()
            release.complete(Unit)

            assertTrue(answer.await())
            repo.clearSavedPlace()
        }
    }

    @Test
    fun `clearing all data during the platform read is teleported`() = withRepo { repo, service, _ ->
        val read = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        coEvery { service.presenceFix() } coAnswers {
            read.complete(Unit)
            release.await()
            freshDeviceFix
        }

        val answer = async { repo.isTeleportedIn(city) }
        read.await()
        repo.clearData()
        release.complete(Unit)

        assertTrue(answer.await())
    }

    @Test
    fun `an explicit teleport is teleported even when the device is there`() = withRepo { repo, service, preferences ->
        coEvery { service.presenceFix() } returns freshDeviceFix
        preferences.place = city

        repo.registerSelfAsParticipant(city.uppercase(), "me", isTeleported = true)

        assertTrue(repo.isTeleportedIn(city))
        assertFalse(repo.isTeleportedIn(city.take(4)))
    }

    @Test
    fun `an explicit teleport reads no location`() = withRepo { repo, service, _ ->
        coEvery { service.presenceFix() } throws AssertionError("an explicit teleport must not read a fix")

        repo.registerSelfAsParticipant(city, "me", isTeleported = true)

        assertTrue(repo.isTeleportedIn(city))
        coVerify(exactly = 0) { service.presenceFix() }
    }

    @Test
    fun `an explicit teleport is not undone by a later registration without one`() = withRepo { repo, service, _ ->
        coEvery { service.presenceFix() } returns freshDeviceFix

        repo.registerSelfAsParticipant(city, "me", isTeleported = true)
        repo.registerSelfAsParticipant(city, "me", isTeleported = false)

        assertTrue(repo.isTeleportedIn(city))
    }

    @Test
    fun `clearing all data forgets explicit teleports`() = withRepo { repo, service, preferences ->
        coEvery { service.presenceFix() } returns freshDeviceFix
        repo.registerSelfAsParticipant(city, "me", isTeleported = true)

        repo.clearData()
        preferences.services = true

        assertFalse(repo.isTeleportedIn(city))
    }

    @Test
    fun `explicit teleports are kept for the newest 256 channels`() = withRepo { repo, service, preferences ->
        coEvery { service.presenceFix() } returns freshDeviceFix
        repo.registerSelfAsParticipant(city, "me", isTeleported = true)
        val base32 = "0123456789bcdefghjkmnpqrstuvwxyz"
        for (index in 0 until 256) {
            repo.registerSelfAsParticipant("u" + base32[index / 32] + base32[index % 32], "me", isTeleported = true)
        }

        assertFalse(repo.isTeleportedIn(city))
        // The oldest of the 256 kept, checked where the device counts as there, so only the teleport tags it.
        preferences.place = "u00bc"
        assertFalse(repo.isTeleportedIn("u00b"))
        assertTrue(repo.isTeleportedIn("u00"))
    }

    @Test
    fun `registering asks the platform nothing and marks only an explicit teleport`() = withRepo { repo, service, _ ->
        coEvery { service.presenceFix() } throws AssertionError("registering must not read a fix")
        coEvery { service.hasLocationPermission() } throws AssertionError("registering must not read the permission")

        repo.registerSelfAsParticipant(city, "me", isTeleported = false)
        repo.registerSelfAsParticipant(elsewhere, "me", isTeleported = true)

        coVerify { tracker.updateParticipant(city, any(), "me", any(), false) }
        coVerify { tracker.updateParticipant(elsewhere, any(), "me", any(), true) }
    }

    private val tracker = mockk<NostrParticipantTracker>(relaxed = true)

    private fun withRepo(test: suspend TestScope.(LocationRepo, LocationService, Preferences) -> Unit) = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val service = mockk<LocationService>(relaxed = true)
        val preferences = Preferences()
        coEvery { service.presenceFix() } returns null
        try {
            test(locationRepo(service, preferences, scope, dispatcher), service, preferences)
        } finally {
            scope.cancel()
        }
    }

    private fun locationRepo(
        service: LocationService,
        preferences: GeohashPreferences,
        scope: CoroutineScope,
        dispatcher: CoroutineContext,
    ) = LocationRepo(
        nostrClient = mockk(relaxed = true),
        nostrRelay = mockk(relaxed = true),
        nostrPreferences = mockk(relaxed = true),
        geohashPreferences = preferences,
        locationService = service,
        bookmarkPrefs = mockk(relaxed = true),
        participantTracker = tracker,
        geocoder = mockk(relaxed = true),
        coroutinesContextFacade = object : CoroutinesContextFacade {
            override val io: CoroutineContext = dispatcher
            override val main: CoroutineContext = dispatcher
            override val default: CoroutineContext = dispatcher
            override val unconfined: CoroutineContext = dispatcher
        },
        coroutineScopeFacade = object : CoroutineScopeFacade {
            override val applicationScope: CoroutineScope = scope
            override val connectivityEventScope: CoroutineScope = scope
            override val nostrScope: CoroutineScope = scope
        },
        locationEventBus = mockk(relaxed = true),
    )
}
