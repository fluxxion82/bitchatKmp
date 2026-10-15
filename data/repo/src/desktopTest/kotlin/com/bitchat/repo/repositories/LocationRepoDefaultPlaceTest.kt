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
import com.bitchat.domain.location.model.SavedPlace
import com.bitchat.local.prefs.BookmarkPreferences
import com.bitchat.local.prefs.GeohashPreferences
import com.bitchat.local.service.GeocoderService
import com.bitchat.local.service.LocationService
import com.bitchat.nostr.participant.NostrParticipantTracker
import com.bitchat.nostr.util.GeohashUtils
import io.mockk.Called
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
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
import kotlin.test.assertNull
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
    fun `a saved place lists each level it contains`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val service = mockk<LocationService>(relaxed = true)
        val geocoder = mockk<GeocoderService>(relaxed = true)
        val preferences = mockk<GeohashPreferences>(relaxed = true)
        every { preferences.getLocationServicesEnabled() } returns true
        coEvery { service.getCurrentLocation() } throws AssertionError("location service must not be called")
        coEvery { geocoder.reverseGeocodeAll(any(), any()) } returns emptyMap()
        try {
            val repo = locationRepo(service, geocoder, scope, dispatcher, preferences)

            every { preferences.getSavedPlace() } returns "9q8yymh"
            val nearby = repo.getAvailableGeohashChannels()
            assertEquals(LocationFixInfo.Source.MANUAL, nearby.fix?.source)
            assertEquals(0, nearby.fix?.ageMillis)
            assertEquals(
                listOf(
                    GeohashChannel(GeohashChannelLevel.BLOCK, "9q8yymh"),
                    GeohashChannel(GeohashChannelLevel.NEIGHBORHOOD, "9q8yym"),
                    GeohashChannel(GeohashChannelLevel.CITY, "9q8yy"),
                    GeohashChannel(GeohashChannelLevel.PROVINCE, "9q8y"),
                    GeohashChannel(GeohashChannelLevel.REGION, "9q"),
                ),
                nearby.channels,
            )

            every { preferences.getSavedPlace() } returns "9q8yy"
            assertEquals(
                listOf(GeohashChannelLevel.CITY, GeohashChannelLevel.PROVINCE, GeohashChannelLevel.REGION),
                repo.getAvailableGeohashChannels().channels.map { it.level },
            )
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `a saved place still returns channels when geocoding fails`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val preferences = mockk<GeohashPreferences>(relaxed = true)
        val geocoder = mockk<GeocoderService>(relaxed = true)
        every { preferences.getSavedPlace() } returns "9q8yy"
        coEvery { geocoder.reverseGeocodeAll(any(), any()) } throws IllegalStateException("geocoder unavailable")
        try {
            val nearby = locationRepo(mockk(relaxed = true), geocoder, scope, dispatcher, preferences)
                .getAvailableGeohashChannels()

            assertEquals(LocationFixInfo.Source.MANUAL, nearby.fix?.source)
            assertEquals(
                listOf(
                    GeohashChannel(GeohashChannelLevel.CITY, "9q8yy"),
                    GeohashChannel(GeohashChannelLevel.PROVINCE, "9q8y"),
                    GeohashChannel(GeohashChannelLevel.REGION, "9q"),
                ),
                nearby.channels,
            )
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `a device fix still returns channels when geocoding fails`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val fix = GeoPoint(52.52, 13.405)
        val preferences = mockk<GeohashPreferences>(relaxed = true)
        val service = mockk<LocationService>(relaxed = true)
        val geocoder = mockk<GeocoderService>(relaxed = true)
        every { preferences.getSavedPlace() } returns null
        coEvery { service.getCurrentLocation() } returns fix
        coEvery { service.lastFixInfo() } returns LocationFixInfo(LocationFixInfo.Source.DEVICE, ageMillis = 0)
        coEvery { geocoder.reverseGeocodeAll(any(), any()) } throws IllegalStateException("geocoder unavailable")
        try {
            val nearby = locationRepo(service, geocoder, scope, dispatcher, preferences)
                .getAvailableGeohashChannels()

            assertEquals(channelsAt(fix), nearby.channels)
            assertEquals(LocationFixInfo.Source.DEVICE, nearby.fix?.source)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `geocoding cancellation is not converted into an empty channel list`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val preferences = mockk<GeohashPreferences>(relaxed = true)
        val geocoder = mockk<GeocoderService>(relaxed = true)
        every { preferences.getSavedPlace() } returns "9q8yy"
        coEvery { geocoder.reverseGeocodeAll(any(), any()) } throws CancellationException()
        try {
            val repo = locationRepo(mockk(relaxed = true), geocoder, scope, dispatcher, preferences)

            assertFailsWith<CancellationException> { repo.getAvailableGeohashChannels() }
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `a saved place is not teleported until registration says so`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val service = mockk<LocationService>(relaxed = true)
        val preferences = mockk<GeohashPreferences>(relaxed = true)
        every { preferences.getSavedPlace() } returns "9q8yy"
        coEvery { service.hasLocationPermission() } throws AssertionError("location service must not be called")
        try {
            val repo = locationRepo(service, mockk(relaxed = true), scope, dispatcher, preferences)

            assertFalse(repo.isTeleported())
            repo.registerSelfAsParticipant("9q8yy", "nick", isTeleported = true)
            assertTrue(repo.isTeleported())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `an invalid stored place falls back to the location service`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val service = mockk<LocationService>(relaxed = true)
        val preferences = mockk<GeohashPreferences>(relaxed = true)
        every { preferences.getLocationServicesEnabled() } returns true
        every { preferences.getSavedPlace() } returns "ai!"
        coEvery { service.getCurrentLocation() } returns GeoPoint(37.7749, -122.4194)
        coEvery { service.lastFixInfo() } returns LocationFixInfo(LocationFixInfo.Source.DEVICE, ageMillis = 0)
        try {
            val repo = locationRepo(service, mockk(relaxed = true), scope, dispatcher, preferences)

            assertEquals(null, repo.getSavedPlace())
            repo.getAvailableGeohashChannels()
            coVerify { service.getCurrentLocation() }
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `a short saved place supplies only levels it contains`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val service = mockk<LocationService>(relaxed = true)
        val preferences = mockk<GeohashPreferences>(relaxed = true)
        every { preferences.getSavedPlace() } returns "9q8yy"
        coEvery { service.getCurrentLocation() } throws AssertionError("location service must not be called")
        try {
            val repo = locationRepo(service, mockk(relaxed = true), scope, dispatcher, preferences)

            val unavailable = assertFailsWith<LocationUnavailableException> {
                repo.getLocationGeohash(GeohashChannelLevel.BLOCK)
            }
            assertEquals(LocationUnavailableException.Reason.NO_SOURCE, unavailable.reason)
            assertEquals("9q8yy", repo.getLocationGeohash(GeohashChannelLevel.CITY))
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `clear data removes a saved place`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        var storedPlace: String? = "9q8yy"
        val preferences = mockk<GeohashPreferences>(relaxed = true)
        every { preferences.getSavedPlace() } answers { storedPlace }
        every { preferences.clearSavedPlace() } answers { storedPlace = null }
        try {
            val repo = locationRepo(mockk(relaxed = true), mockk(relaxed = true), scope, dispatcher, preferences)

            repo.clearData()

            assertNull(repo.getSavedPlace())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `the repository saves and clears a saved place`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        var storedPlace: String? = null
        val preferences = mockk<GeohashPreferences>(relaxed = true)
        every { preferences.getSavedPlace() } answers { storedPlace }
        every { preferences.saveSavedPlace(any()) } answers { storedPlace = firstArg() }
        every { preferences.clearSavedPlace() } answers { storedPlace = null }
        try {
            val repo = locationRepo(mockk(relaxed = true), mockk(relaxed = true), scope, dispatcher, preferences)
            val place = requireNotNull(SavedPlace.of("9q8yy"))

            repo.setSavedPlace(place)
            assertEquals(place, repo.getSavedPlace())

            repo.clearSavedPlace()
            assertNull(repo.getSavedPlace())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `location services off skips the location service`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val preferences = mockk<GeohashPreferences>(relaxed = true)
        val service = mockk<LocationService>(relaxed = true)
        try {
            val repo = locationRepo(service, mockk(relaxed = true), scope, dispatcher, preferences)
            every { preferences.getLocationServicesEnabled() } returns false

            assertEquals(NearbyChannels(emptyList(), null), repo.getAvailableGeohashChannels())
            verify { service wasNot Called }
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `a name lookup for an older saved place cannot label the current place`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        var storedPlace = "9q8yy"
        val heldName = CompletableDeferred<String>()
        val preferences = mockk<GeohashPreferences>(relaxed = true)
        val geocoder = mockk<GeocoderService>(relaxed = true)
        every { preferences.getSavedPlace() } answers { storedPlace }
        coEvery { geocoder.reverseGeocode(any(), any(), GeohashChannelLevel.CITY) } coAnswers { heldName.await() }
        coEvery { geocoder.reverseGeocodeAll(any(), any()) } returns emptyMap()
        try {
            val repo = locationRepo(mockk(relaxed = true), geocoder, scope, dispatcher, preferences)
            val oldPlace = "9q8yy"
            val newerPlace = "u33db"

            val oldName = async { repo.resolveLocationName(oldPlace, GeohashChannelLevel.CITY) }
            runCurrent()
            storedPlace = newerPlace
            repo.getAvailableGeohashChannels()
            heldName.complete("old place")
            oldName.await()

            assertFalse(repo.getLocationNames().values.contains("old place"))
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `names that arrive for an older place do not replace the current place's`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        var storedPlace = "9q8yy"
        val olderNames = CompletableDeferred<Map<GeohashChannelLevel, String>>()
        val preferences = mockk<GeohashPreferences>(relaxed = true)
        val geocoder = mockk<GeocoderService>(relaxed = true)
        every { preferences.getSavedPlace() } answers { storedPlace }
        coEvery { geocoder.reverseGeocodeAll(any(), any()) } coAnswers {
            if (storedPlace == "9q8yy") olderNames.await() else mapOf(GeohashChannelLevel.CITY to "newer place")
        }
        try {
            val repo = locationRepo(mockk(relaxed = true), geocoder, scope, dispatcher, preferences)

            val olderLoad = async { repo.getAvailableGeohashChannels() }
            runCurrent()
            storedPlace = "u33db"
            repo.getAvailableGeohashChannels()
            olderNames.complete(mapOf(GeohashChannelLevel.CITY to "older place"))
            olderLoad.await()

            assertEquals(mapOf(GeohashChannelLevel.CITY to "newer place"), repo.getLocationNames())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `an older device load cannot replace a saved place's cached fix or names`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val olderFix = GeoPoint(37.7749, -122.4194)
        val olderFixCanReturn = CompletableDeferred<Unit>()
        var storedPlace: String? = null
        val preferences = mockk<GeohashPreferences>(relaxed = true)
        val service = mockk<LocationService>(relaxed = true)
        val geocoder = mockk<GeocoderService>(relaxed = true)
        val placeB = requireNotNull(SavedPlace.of("u33db"))
        val bNames = mapOf(GeohashChannelLevel.CITY to "place B")
        val aNames = mapOf(GeohashChannelLevel.CITY to "place A")
        every { preferences.getSavedPlace() } answers { storedPlace }
        every { preferences.saveSavedPlace(any()) } answers { storedPlace = firstArg() }
        coEvery { service.getCurrentLocation() } coAnswers {
            olderFixCanReturn.await()
            olderFix
        }
        coEvery { service.lastFixInfo() } returns LocationFixInfo(LocationFixInfo.Source.DEVICE, ageMillis = 0)
        coEvery { geocoder.reverseGeocodeAll(any(), any()) } coAnswers {
            if (firstArg<Double>() == olderFix.lat) aNames else bNames
        }
        try {
            val repo = locationRepo(service, geocoder, scope, dispatcher, preferences)

            val olderLoad = async { repo.getAvailableGeohashChannels() }
            runCurrent()
            repo.setSavedPlace(placeB)
            repo.getAvailableGeohashChannels()
            olderFixCanReturn.complete(Unit)
            olderLoad.await()

            assertEquals(bNames, repo.getLocationNames())
            assertEquals(placeB.geohash.take(GeohashChannelLevel.CITY.precision), repo.getLocationGeohash(GeohashChannelLevel.CITY))
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `a device load that returns after a place is saved writes nothing and asks the geocoder nothing`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        var storedPlace: String? = null
        val deviceFix = CompletableDeferred<Unit>()
        val tokyo = GeoPoint(35.68, 139.69)
        val preferences = mockk<GeohashPreferences>(relaxed = true)
        val service = mockk<LocationService>(relaxed = true)
        val geocoder = mockk<GeocoderService>(relaxed = true)
        every { preferences.getSavedPlace() } answers { storedPlace }
        every { preferences.saveSavedPlace(any()) } answers { storedPlace = firstArg() }
        coEvery { service.getCurrentLocation() } coAnswers { deviceFix.await(); tokyo }
        coEvery { service.lastFixInfo() } returns null
        coEvery { geocoder.reverseGeocodeAll(any(), any()) } returns emptyMap()
        coEvery { geocoder.reverseGeocode(any(), any(), GeohashChannelLevel.CITY) } returns "berlin"
        try {
            val repo = locationRepo(service, geocoder, scope, dispatcher, preferences)

            val deviceLoad = async { repo.getAvailableGeohashChannels() }
            runCurrent()
            repo.setSavedPlace(requireNotNull(SavedPlace.of("u33db")))
            repo.getAvailableGeohashChannels()
            deviceFix.complete(Unit)
            deviceLoad.await()

            coVerify(exactly = 0) { geocoder.reverseGeocodeAll(tokyo.lat, tokyo.lon) }
            assertEquals("berlin", repo.resolveLocationName("u33db", GeohashChannelLevel.CITY))
            assertEquals("berlin", repo.getLocationNames()[GeohashChannelLevel.CITY])
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `names that arrive after the place was cleared are not cached`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        var storedPlace: String? = "u33db"
        val names = CompletableDeferred<Unit>()
        val preferences = mockk<GeohashPreferences>(relaxed = true)
        val geocoder = mockk<GeocoderService>(relaxed = true)
        every { preferences.getSavedPlace() } answers { storedPlace }
        every { preferences.clearSavedPlace() } answers { storedPlace = null }
        coEvery { geocoder.reverseGeocodeAll(any(), any()) } coAnswers {
            names.await()
            mapOf(GeohashChannelLevel.CITY to "berlin")
        }
        try {
            val repo = locationRepo(mockk(relaxed = true), geocoder, scope, dispatcher, preferences)

            val load = async { repo.getAvailableGeohashChannels() }
            runCurrent()
            repo.clearSavedPlace()
            names.complete(Unit)
            load.await()

            assertFalse(repo.getLocationNames().values.contains("berlin"))
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `saving or clearing a place forgets the names of the place before`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        var storedPlace: String? = "u33db"
        val preferences = mockk<GeohashPreferences>(relaxed = true)
        val geocoder = mockk<GeocoderService>(relaxed = true)
        every { preferences.getSavedPlace() } answers { storedPlace }
        every { preferences.saveSavedPlace(any()) } answers { storedPlace = firstArg() }
        every { preferences.clearSavedPlace() } answers { storedPlace = null }
        coEvery { geocoder.reverseGeocodeAll(any(), any()) } returns mapOf(GeohashChannelLevel.CITY to "berlin")
        try {
            val repo = locationRepo(mockk(relaxed = true), geocoder, scope, dispatcher, preferences)
            repo.getAvailableGeohashChannels()
            assertEquals("berlin", repo.getLocationNames()[GeohashChannelLevel.CITY])

            repo.setSavedPlace(requireNotNull(SavedPlace.of("9q8yy")))
            assertTrue(repo.getLocationNames().isEmpty())

            repo.getAvailableGeohashChannels()
            repo.clearSavedPlace()
            assertTrue(repo.getLocationNames().isEmpty())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `a name looked up for the old place after the place changed is not cached`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        var storedPlace: String? = "u33db"
        val preferences = mockk<GeohashPreferences>(relaxed = true)
        val geocoder = mockk<GeocoderService>(relaxed = true)
        every { preferences.getSavedPlace() } answers { storedPlace }
        every { preferences.saveSavedPlace(any()) } answers { storedPlace = firstArg() }
        coEvery { geocoder.reverseGeocodeAll(any(), any()) } returns emptyMap()
        coEvery { geocoder.reverseGeocode(any(), any(), GeohashChannelLevel.CITY) } returns "berlin"
        try {
            val repo = locationRepo(mockk(relaxed = true), geocoder, scope, dispatcher, preferences)
            repo.getAvailableGeohashChannels()
            repo.setSavedPlace(requireNotNull(SavedPlace.of("9q8yy")))

            assertEquals("berlin", repo.resolveLocationName("u33db", GeohashChannelLevel.CITY))
            assertTrue(repo.getLocationNames().isEmpty())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `a load that reads the place while everything is erased leaves nothing of it`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        var storedPlace: String? = "u33db"
        val preferences = mockk<GeohashPreferences>(relaxed = true)
        val bookmarks = mockk<BookmarkPreferences>(relaxed = true)
        val geocoder = mockk<GeocoderService>(relaxed = true)
        every { preferences.getSavedPlace() } answers { storedPlace }
        every { preferences.clearSavedPlace() } answers { storedPlace = null }
        coEvery { geocoder.reverseGeocodeAll(any(), any()) } returns mapOf(GeohashChannelLevel.CITY to "berlin")
        try {
            val repo = locationRepo(mockk(relaxed = true), geocoder, scope, dispatcher, preferences, bookmarks)
            // A whole load inside the wipe, while the stored place is still there.
            coEvery { bookmarks.clearAllBookmarks() } coAnswers {
                repo.getAvailableGeohashChannels()
                Unit
            }

            repo.clearData()

            assertTrue(repo.getLocationNames().isEmpty())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `a here fix that arrives after a coarser place was saved obeys the place`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        var storedPlace: String? = null
        val deviceFix = CompletableDeferred<Unit>()
        val preferences = mockk<GeohashPreferences>(relaxed = true)
        val service = mockk<LocationService>(relaxed = true)
        every { preferences.getSavedPlace() } answers { storedPlace }
        every { preferences.saveSavedPlace(any()) } answers { storedPlace = firstArg() }
        coEvery { service.getCurrentLocation() } coAnswers { deviceFix.await(); GeoPoint(52.52, 13.40) }
        try {
            val repo = locationRepo(service, mockk(relaxed = true), scope, dispatcher, preferences)

            val here = async { runCatching { repo.getLocationGeohash(GeohashChannelLevel.BUILDING) } }
            runCurrent()
            repo.setSavedPlace(requireNotNull(SavedPlace.of("9q")))
            deviceFix.complete(Unit)

            val failure = here.await().exceptionOrNull()
            assertEquals(LocationUnavailableException.Reason.NO_SOURCE, (failure as? LocationUnavailableException)?.reason)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `a here lookup that fails after a place was saved answers from the place`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        var storedPlace: String? = null
        val lookupFails = CompletableDeferred<Unit>()
        val preferences = mockk<GeohashPreferences>(relaxed = true)
        val service = mockk<LocationService>(relaxed = true)
        every { preferences.getSavedPlace() } answers { storedPlace }
        every { preferences.saveSavedPlace(any()) } answers { storedPlace = firstArg() }
        coEvery { service.getCurrentLocation() } coAnswers {
            lookupFails.await()
            throw LocationUnavailableException(LocationUnavailableException.Reason.SUPPRESSED_BY_POLICY)
        }
        try {
            val repo = locationRepo(service, mockk(relaxed = true), scope, dispatcher, preferences)

            val fine = async { runCatching { repo.getLocationGeohash(GeohashChannelLevel.BLOCK) } }
            runCurrent()
            repo.setSavedPlace(requireNotNull(SavedPlace.of("9q8yyk8y")))
            lookupFails.complete(Unit)

            assertEquals("9q8yyk8", fine.await().getOrNull())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `a region name looked up for the old place is not cached when the new place shares its region`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        var storedPlace: String? = "u33db"
        val oldRegion = CompletableDeferred<String>()
        val preferences = mockk<GeohashPreferences>(relaxed = true)
        val geocoder = mockk<GeocoderService>(relaxed = true)
        every { preferences.getSavedPlace() } answers { storedPlace }
        every { preferences.saveSavedPlace(any()) } answers { storedPlace = firstArg() }
        coEvery { geocoder.reverseGeocodeAll(any(), any()) } answers {
            if (storedPlace == "u3buw") mapOf(GeohashChannelLevel.REGION to "new region") else emptyMap()
        }
        coEvery { geocoder.reverseGeocode(any(), any(), GeohashChannelLevel.REGION) } coAnswers { oldRegion.await() }
        try {
            val repo = locationRepo(mockk(relaxed = true), geocoder, scope, dispatcher, preferences)
            repo.getAvailableGeohashChannels()

            val olderName = async { repo.resolveLocationName("u3", GeohashChannelLevel.REGION) }
            runCurrent()
            repo.setSavedPlace(requireNotNull(SavedPlace.of("u3buw")))
            repo.getAvailableGeohashChannels()
            oldRegion.complete("old region")
            olderName.await()

            assertEquals("new region", repo.getLocationNames()[GeohashChannelLevel.REGION])
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `a single name lookup that spans a change of place is not cached`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        var storedPlace: String? = "9q8yy"
        val heldName = CompletableDeferred<String>()
        val preferences = mockk<GeohashPreferences>(relaxed = true)
        val geocoder = mockk<GeocoderService>(relaxed = true)
        every { preferences.getSavedPlace() } answers { storedPlace }
        every { preferences.saveSavedPlace(any()) } answers { storedPlace = firstArg() }
        coEvery { geocoder.reverseGeocodeAll(any(), any()) } returns emptyMap()
        coEvery { geocoder.reverseGeocode(any(), any(), GeohashChannelLevel.CITY) } coAnswers { heldName.await() }
        try {
            val repo = locationRepo(mockk(relaxed = true), geocoder, scope, dispatcher, preferences)
            repo.getAvailableGeohashChannels()

            val olderName = async { repo.resolveLocationName("9q8yy", GeohashChannelLevel.CITY) }
            runCurrent()
            repo.setSavedPlace(requireNotNull(SavedPlace.of("u33db")))
            heldName.complete("older place")
            olderName.await()

            assertFalse(repo.getLocationNames().values.contains("older place"))
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `resolving a name for a noncurrent place leaves cached names unchanged`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val preferences = mockk<GeohashPreferences>(relaxed = true)
        val geocoder = mockk<GeocoderService>(relaxed = true)
        every { preferences.getSavedPlace() } returns "u33db"
        coEvery { geocoder.reverseGeocodeAll(any(), any()) } returns mapOf(GeohashChannelLevel.CITY to "current place")
        coEvery { geocoder.reverseGeocode(any(), any(), GeohashChannelLevel.CITY) } returns "other place"
        try {
            val repo = locationRepo(mockk(relaxed = true), geocoder, scope, dispatcher, preferences)
            repo.getAvailableGeohashChannels()
            val namesBefore = repo.getLocationNames()

            assertEquals("other place", repo.resolveLocationName("9q8yy", GeohashChannelLevel.CITY))
            assertEquals(namesBefore, repo.getLocationNames())
        } finally {
            scope.cancel()
        }
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
        geohashPreferences: GeohashPreferences = mockk(relaxed = true),
        bookmarkPrefs: BookmarkPreferences = mockk(relaxed = true),
    ) = LocationRepo(
        nostrClient = mockk(relaxed = true),
        nostrRelay = mockk(relaxed = true),
        nostrPreferences = mockk(relaxed = true),
        geohashPreferences = geohashPreferences.also {
            every { it.getLocationServicesEnabled() } returns true
        },
        locationService = service,
        bookmarkPrefs = bookmarkPrefs,
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
