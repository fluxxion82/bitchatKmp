package com.bitchat.repo.repositories

import com.bitchat.domain.base.CoroutineScopeFacade
import com.bitchat.domain.base.CoroutinesContextFacade
import com.bitchat.domain.location.model.DEFAULT_PLACE
import com.bitchat.domain.location.model.GeoPoint
import com.bitchat.domain.location.model.GeohashChannel
import com.bitchat.domain.location.model.GeohashChannelLevel
import com.bitchat.domain.location.model.LocationFixInfo
import com.bitchat.domain.location.model.LocationUnavailableException
import com.bitchat.domain.location.model.NearbyChannels
import com.bitchat.local.service.GeocoderService
import com.bitchat.local.service.LocationService
import com.bitchat.nostr.participant.NostrParticipantTracker
import com.bitchat.nostr.util.GeohashUtils
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * With no real fix the nearby channels are those of [DEFAULT_PLACE], and the fix info says so, so
 * nothing downstream mistakes the default for where the device is.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LocationRepoDefaultPlaceTest {
    private val levels = listOf(
        GeohashChannelLevel.BLOCK,
        GeohashChannelLevel.NEIGHBORHOOD,
        GeohashChannelLevel.CITY,
        GeohashChannelLevel.PROVINCE,
        GeohashChannelLevel.REGION,
    )

    private val tracker = mockk<NostrParticipantTracker>(relaxed = true)

    private fun channelsAt(point: GeoPoint) = levels.map {
        GeohashChannel(it, GeohashUtils.encode(point.lat, point.lon, it.precision))
    }

    private fun defaultPlace(reason: LocationUnavailableException.Reason) = NearbyChannels(
        channelsAt(DEFAULT_PLACE),
        LocationFixInfo(LocationFixInfo.Source.DEFAULT, ageMillis = 0, unavailableReason = reason),
    )

    @Test
    fun withNoSourceTheChannelsAreThoseOfTheDefaultPlaceAndSayWhy() = withRepo { repo, service, geocoder ->
        coEvery { service.getCurrentLocation() } throws LocationUnavailableException(LocationUnavailableException.Reason.NO_SOURCE)

        assertEquals(defaultPlace(LocationUnavailableException.Reason.NO_SOURCE), repo.getAvailableGeohashChannels())
        coVerify { geocoder.reverseGeocodeAll(DEFAULT_PLACE.lat, DEFAULT_PLACE.lon) }
    }

    @Test
    fun everyReasonIsCarriedThrough() = withRepo { repo, service, _ ->
        for (reason in LocationUnavailableException.Reason.entries) {
            coEvery { service.getCurrentLocation() } throws LocationUnavailableException(reason)

            assertEquals(defaultPlace(reason), repo.getAvailableGeohashChannels(), "for $reason")
        }
    }

    @Test
    fun aRealFixAfterwardsIsNoLongerTheDefault() = withRepo { repo, service, _ ->
        val here = GeoPoint(52.52, 13.405)
        val serviceSays = LocationFixInfo(LocationFixInfo.Source.DEVICE, ageMillis = 5)
        coEvery { service.lastFixInfo() } returns serviceSays
        coEvery { service.getCurrentLocation() } throws LocationUnavailableException(LocationUnavailableException.Reason.LOOKUP_FAILED)
        assertEquals(LocationFixInfo.Source.DEFAULT, repo.getAvailableGeohashChannels().fix?.source)

        coEvery { service.getCurrentLocation() } returns here

        assertEquals(NearbyChannels(channelsAt(here), serviceSays), repo.getAvailableGeohashChannels())
    }

    @Test
    fun theFixInfoOfARealFixIsReadRightAfterTheFixNotAfterTheSlowNameLookup() = withRepo { repo, service, geocoder ->
        val here = GeoPoint(52.52, 13.405)
        val atTheFix = LocationFixInfo(LocationFixInfo.Source.DEVICE, ageMillis = 5)
        var serviceSays = atTheFix
        coEvery { service.getCurrentLocation() } returns here
        coEvery { service.lastFixInfo() } answers { serviceSays }
        // The service's answer changes while the names are looked up; this call's answer must not.
        coEvery { geocoder.reverseGeocodeAll(any(), any()) } coAnswers {
            serviceSays = LocationFixInfo(LocationFixInfo.Source.IP_ADDRESS, ageMillis = 9)
            emptyMap()
        }

        assertEquals(NearbyChannels(channelsAt(here), atTheFix), repo.getAvailableGeohashChannels())
    }

    @Test
    fun aCallKeepsItsOwnOutcomeWhileAnotherOneChangesWhatTheRepositoryRemembers() = withRepo { repo, service, geocoder ->
        val here = GeoPoint(52.52, 13.405)
        val names = CompletableDeferred<Map<GeohashChannelLevel, String>>()
        coEvery { service.getCurrentLocation() } throws LocationUnavailableException(LocationUnavailableException.Reason.NO_SOURCE)
        coEvery { geocoder.reverseGeocodeAll(DEFAULT_PLACE.lat, DEFAULT_PLACE.lon) } coAnswers { names.await() }
        val slow = async { repo.getAvailableGeohashChannels() }
        runCurrent()

        // A second call gets a real fix, which clears what the repository remembers about the default.
        coEvery { service.getCurrentLocation() } returns here
        coEvery { service.lastFixInfo() } returns LocationFixInfo(LocationFixInfo.Source.DEVICE, ageMillis = 5)
        assertEquals(LocationFixInfo.Source.DEVICE, repo.getAvailableGeohashChannels().fix?.source)
        names.complete(emptyMap())

        assertEquals(defaultPlace(LocationUnavailableException.Reason.NO_SOURCE), slow.await())
    }

    @Test
    fun theGeohashOfHereIsStillRefusedWithoutARealFix() = withRepo { repo, service, _ ->
        coEvery { service.getCurrentLocation() } throws LocationUnavailableException(LocationUnavailableException.Reason.NO_SOURCE)
        repo.getAvailableGeohashChannels()

        assertFailsWith<LocationUnavailableException> { repo.getLocationGeohash(GeohashChannelLevel.BUILDING) }
    }

    @Test
    fun clearingDataForgetsTheDefault() = withRepo { repo, service, _ ->
        coEvery { service.getCurrentLocation() } throws LocationUnavailableException(LocationUnavailableException.Reason.NO_SOURCE)
        repo.getAvailableGeohashChannels()
        assertTrue(repo.isTeleported())

        repo.clearData()

        assertFalse(repo.isTeleported())
    }

    @Test
    fun withoutARealFixTheDeviceIsTeleportedWhateverRegistrationSays() = withRepo { repo, service, _ ->
        coEvery { service.getCurrentLocation() } throws LocationUnavailableException(LocationUnavailableException.Reason.NO_SOURCE)
        repo.getAvailableGeohashChannels()

        repo.registerSelfAsParticipant("9q8yy", "nick", isTeleported = false)

        assertTrue(repo.isTeleported())
        coVerify { tracker.updateParticipant(eq("9q8yy"), any(), eq("nick"), any(), eq(true)) }
    }

    @Test
    fun withoutPermissionTheDeviceIsTeleportedBeforeAnyChannelWasLoaded() = withRepo { repo, service, _ ->
        coEvery { service.hasLocationPermission() } returns false

        assertTrue(repo.isTeleported())
        repo.registerSelfAsParticipant("9q8yy", "nick", isTeleported = false)

        assertTrue(repo.isTeleported())
        coVerify { tracker.updateParticipant(eq("9q8yy"), any(), eq("nick"), any(), eq(true)) }
        coVerify(exactly = 0) { service.getCurrentLocation() }
    }

    @Test
    fun withAPermissionAndARealFixTheDeviceIsPresentedAsToldAtRegistration() = withRepo { repo, service, _ ->
        coEvery { service.getCurrentLocation() } returns GeoPoint(52.52, 13.405)
        repo.getAvailableGeohashChannels()

        repo.registerSelfAsParticipant("u33db", "nick", isTeleported = false)
        assertFalse(repo.isTeleported())
        coVerify { tracker.updateParticipant(eq("u33db"), any(), eq("nick"), any(), eq(false)) }

        repo.registerSelfAsParticipant("9q8yy", "nick", isTeleported = true)
        assertTrue(repo.isTeleported())
        coVerify { tracker.updateParticipant(eq("9q8yy"), any(), eq("nick"), any(), eq(true)) }
    }

    @Test
    fun aRealFixAfterTheDefaultPlaceLetsTheNextRegistrationBeNotTeleported() = withRepo { repo, service, _ ->
        coEvery { service.getCurrentLocation() } throws LocationUnavailableException(LocationUnavailableException.Reason.LOOKUP_FAILED)
        repo.getAvailableGeohashChannels()
        repo.registerSelfAsParticipant("9q8yy", "nick", isTeleported = false)
        coEvery { service.getCurrentLocation() } returns GeoPoint(52.52, 13.405)
        repo.getAvailableGeohashChannels()
        // What was registered while the device could not be there stays until the next registration.
        assertTrue(repo.isTeleported())

        repo.registerSelfAsParticipant("u33db", "nick", isTeleported = false)

        assertFalse(repo.isTeleported())
        coVerify { tracker.updateParticipant(eq("u33db"), any(), eq("nick"), any(), eq(false)) }
    }

    private fun withRepo(test: suspend TestScope.(LocationRepo, LocationService, GeocoderService) -> Unit) = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val service = mockk<LocationService>(relaxed = true)
        val geocoder = mockk<GeocoderService>(relaxed = true)
        coEvery { service.lastFixInfo() } returns null
        coEvery { service.hasLocationPermission() } returns true
        coEvery { geocoder.reverseGeocodeAll(any(), any()) } returns emptyMap()
        try {
            test(locationRepo(service, geocoder, scope, dispatcher), service, geocoder)
        } finally {
            scope.cancel()
        }
    }

    private fun locationRepo(
        service: LocationService,
        geocoder: GeocoderService,
        scope: CoroutineScope,
        dispatcher: CoroutineContext,
    ) = LocationRepo(
        nostrClient = mockk(relaxed = true),
        nostrRelay = mockk(relaxed = true),
        nostrPreferences = mockk(relaxed = true),
        geohashPreferences = mockk(relaxed = true),
        locationService = service,
        bookmarkPrefs = mockk(relaxed = true),
        participantTracker = tracker,
        geocoder = geocoder,
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
