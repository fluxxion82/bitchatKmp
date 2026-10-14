package com.bitchat.viewmodel.location

import androidx.lifecycle.ViewModel
import app.cash.turbine.test
import com.bitchat.domain.app.model.ActiveState
import com.bitchat.domain.app.model.UserState
import com.bitchat.domain.location.BeginGeohashSampling
import com.bitchat.domain.location.EndGeohashSampling
import com.bitchat.domain.location.GetAvailableChannels
import com.bitchat.domain.location.GetSavedPlace
import com.bitchat.domain.location.SetSavedPlace
import com.bitchat.domain.location.ClearSavedPlace
import com.bitchat.domain.location.GetBookmarkNames
import com.bitchat.domain.location.GetBookmarkedChannels
import com.bitchat.domain.tor.ObserveRequestedTorMode
import com.bitchat.domain.tor.model.TorMode
import com.bitchat.domain.location.GetLocationNames
import com.bitchat.domain.location.GetLocationServicesEnabled
import com.bitchat.domain.location.GetParticipantCounts
import com.bitchat.domain.location.GetTeleportState
import com.bitchat.domain.location.ResolveLocationName
import com.bitchat.domain.location.ToggleBookmark
import com.bitchat.domain.location.ToggleLocationServices
import com.bitchat.domain.location.model.Channel
import com.bitchat.domain.location.model.DEFAULT_PLACE_NAME
import com.bitchat.domain.location.model.GeohashChannel
import com.bitchat.domain.location.model.GeohashChannelLevel
import com.bitchat.domain.location.model.LocationFixInfo
import com.bitchat.domain.location.model.NearbyChannels
import com.bitchat.domain.location.model.SavedPlace
import com.bitchat.domain.location.model.LocationUnavailableException.Reason
import com.bitchat.domain.location.model.ParticipantCounts
import com.bitchat.domain.user.GetUserState
import com.bitchat.domain.user.SaveUserStateAction
import com.bitchat.domain.user.model.UserStateAction
import com.bitchat.viewmodel.BaseViewModelTest
import com.bitchat.viewvo.location.LocationChannelsEffect
import io.mockk.coEvery
import kotlinx.coroutines.flow.MutableStateFlow
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun ViewModel.clearForTest() {
    val method = ViewModel::class.java.getDeclaredMethod("onCleared")
    method.isAccessible = true
    method.invoke(this)
}

@OptIn(ExperimentalCoroutinesApi::class)
class LocationChannelsViewModelTest : BaseViewModelTest() {
    private val saveUserStateAction = mockk<SaveUserStateAction>(relaxed = true)
    private val getAvailableChannels = mockk<GetAvailableChannels>(relaxed = true)
    private val getParticipantCounts = mockk<GetParticipantCounts>(relaxed = true)
    private val getBookmarkedChannels = mockk<GetBookmarkedChannels>(relaxed = true)
    private val beginGeohashSampling = mockk<BeginGeohashSampling>(relaxed = true)
    private val endGeohashSampling = mockk<EndGeohashSampling>(relaxed = true)
    private val toggleBookmark = mockk<ToggleBookmark>(relaxed = true)
    private val toggleLocationServices = mockk<ToggleLocationServices>(relaxed = true)
    private val getBookmarkNames = mockk<GetBookmarkNames>(relaxed = true)
    private val getTeleportState = mockk<GetTeleportState>(relaxed = true)
    private val getLocationServicesEnabled = mockk<GetLocationServicesEnabled>(relaxed = true)
    private val getLocationNames = mockk<GetLocationNames>(relaxed = true)
    private val resolveLocationName = mockk<ResolveLocationName>(relaxed = true)
    private val getUserState = mockk<GetUserState>(relaxed = true)
    private val getSavedPlace = mockk<GetSavedPlace>(relaxed = true)
    private val setSavedPlace = mockk<SetSavedPlace>(relaxed = true)
    private val clearSavedPlace = mockk<ClearSavedPlace>(relaxed = true)
    private val observeRequestedTorMode = mockk<ObserveRequestedTorMode>(relaxed = true)
    private val torMode = MutableStateFlow(TorMode.OFF)

    /**
     * [configure] runs after the defaults below and before construction, because the view model
     * loads in its init block -- stubbing after it is built is too late, and stubbing before this
     * function is called is overwritten by the defaults.
     */
    private fun buildViewModel(configure: () -> Unit = {}): LocationChannelsViewModel {
        coEvery { getAvailableChannels(Unit) } returns NearbyChannels(emptyList(), null)
        coEvery { getParticipantCounts(Unit) } returns ParticipantCounts()
        coEvery { getBookmarkedChannels(Unit) } returns emptyList()
        coEvery { getBookmarkNames(Unit) } returns emptyMap()
        coEvery { getTeleportState(Unit) } returns false
        coEvery { getLocationServicesEnabled(Unit) } returns false
        coEvery { getLocationNames(Unit) } returns emptyMap()
        coEvery { getSavedPlace(Unit) } returns null
        coEvery { resolveLocationName(any()) } returns null
        coEvery { getUserState(Unit) } returns UserState.Active(ActiveState.Chat(Channel.Mesh))
        coEvery { observeRequestedTorMode(Unit) } returns torMode

        configure()

        return LocationChannelsViewModel(
            saveUserStateAction = saveUserStateAction,
            getAvailableChannels = getAvailableChannels,
            getParticipantCounts = getParticipantCounts,
            getBookmarkedChannels = getBookmarkedChannels,
            beginGeohashSampling = beginGeohashSampling,
            endGeohashSamplingUseCase = endGeohashSampling,
            toggleBookmark = toggleBookmark,
            toggleLocationServices = toggleLocationServices,
            getBookmarkNames = getBookmarkNames,
            getTeleportState = getTeleportState,
            getLocationServicesEnabled = getLocationServicesEnabled,
            getLocationNames = getLocationNames,
            observeRequestedTorMode = observeRequestedTorMode,
            resolveLocationName = resolveLocationName,
            getUserState = getUserState,
            getSavedPlace = getSavedPlace,
            setSavedPlace = setSavedPlace,
            clearSavedPlace = clearSavedPlace,
        )
    }

    private val defaultPlaceChannels = listOf(
        GeohashChannel(GeohashChannelLevel.BLOCK, "9q8yymh"),
        GeohashChannel(GeohashChannelLevel.NEIGHBORHOOD, "9q8yym"),
        GeohashChannel(GeohashChannelLevel.CITY, "9q8yy"),
        GeohashChannel(GeohashChannelLevel.PROVINCE, "9q8y"),
        GeohashChannel(GeohashChannelLevel.REGION, "9q"),
    )

    private fun defaultPlace(reason: Reason) =
        LocationFixInfo(LocationFixInfo.Source.DEFAULT, ageMillis = 0, unavailableReason = reason)

    private fun defaultNearby(reason: Reason) = NearbyChannels(defaultPlaceChannels, defaultPlace(reason))

    private fun deviceFix() = LocationFixInfo(LocationFixInfo.Source.DEVICE, ageMillis = 1_000)

    private fun notice(why: String) = "$why. showing channels for the default place ($DEFAULT_PLACE_NAME)"

    @Test
    fun locationFailureStillLoadsBookmarksAndThePreference() = runTest {
        /*
         * Regression: location used to be the first call in one try block, so a machine with no fix
         * threw before bookmarks and the location-services preference had been read. The preference
         * kept its default of false, and the sheet hides the section containing the explanation when
         * it is false -- so a cold launch with no fix showed an empty sheet giving no reason at all.
         */
        val viewModel = buildViewModel {
            coEvery { getBookmarkedChannels(Unit) } returns listOf("9q8yy")
            coEvery { getBookmarkNames(Unit) } returns mapOf("9q8yy" to "home")
            coEvery { getLocationServicesEnabled(Unit) } returns true
            coEvery { getAvailableChannels(Unit) } returns defaultNearby(Reason.NO_SOURCE)
        }
        viewModel.clearedAfter {
            instantExecutorRule.scheduler.runCurrent()

            val state = viewModel.state.value
            assertEquals(listOf("9q8yy"), state.bookmarkedGeohashes)
            assertEquals(mapOf("9q8yy" to "home"), state.bookmarkNames)
            assertTrue(state.locationServicesEnabled, "the preference must be read even when no fix exists")
            assertEquals(notice("location not set"), state.locationUnavailableReason)
            assertTrue(!state.isLoading, "an unavailable fix is a finished state, not a spinner")
        }
    }

    @Test
    fun aLostFixFallsBackToTheChannelsOfTheDefaultPlace() = runTest {
        // Leaving the old list up would present channels derived from a fix the app no longer has.
        val viewModel = buildViewModel {
            coEvery { getLocationServicesEnabled(Unit) } returns true
            coEvery { getAvailableChannels(Unit) } returns NearbyChannels(listOf(GeohashChannel(GeohashChannelLevel.CITY, "u33db")), deviceFix())
        }
        viewModel.clearedAfter {
            instantExecutorRule.scheduler.runCurrent()
            assertEquals(listOf("u33db"), viewModel.state.value.availableChannels.map { it.geohash })
            assertNull(viewModel.state.value.locationUnavailableReason)

            coEvery { getAvailableChannels(Unit) } returns defaultNearby(Reason.LOOKUP_FAILED)
            instantExecutorRule.scheduler.advanceTimeBy(6_000)
            instantExecutorRule.scheduler.runCurrent()

            val state = viewModel.state.value
            assertEquals(defaultPlaceChannels, state.availableChannels)
            assertEquals(notice("could not determine your location"), state.locationUnavailableReason)
        }
    }

    @Test
    fun theDefaultPlaceIsLabelledWithTheReasonItIsShown() = runTest {
        val expected = mapOf(
            Reason.NO_SOURCE to notice("location not set"),
            Reason.PERMISSION_DENIED to notice("location permission denied"),
            Reason.LOOKUP_FAILED to notice("could not determine your location"),
            Reason.SUPPRESSED_BY_POLICY to notice("location lookup is off while Tor is on"),
        )
        assertEquals(Reason.entries.toSet(), expected.keys, "a new reason needs a notice")
        for ((reason, text) in expected) {
            val viewModel = buildViewModel {
                coEvery { getLocationServicesEnabled(Unit) } returns true
                coEvery { getAvailableChannels(Unit) } returns defaultNearby(reason)
            }
            viewModel.clearedAfter {
                instantExecutorRule.scheduler.runCurrent()

                val state = viewModel.state.value
                assertEquals(text, state.locationUnavailableReason, "notice for $reason")
                assertEquals(defaultPlaceChannels, state.availableChannels)
                assertTrue(!state.locationApproximate, "the default place is not an IP fix")
                assertTrue(!state.locationStale, "the default place is not an old fix")
            }
        }
    }

    @Test
    fun aRealFixCarriesNoNotice() = runTest {
        val viewModel = buildViewModel {
            coEvery { getLocationServicesEnabled(Unit) } returns true
            coEvery { getAvailableChannels(Unit) } returns NearbyChannels(defaultPlaceChannels, deviceFix())
        }
        viewModel.clearedAfter {
            instantExecutorRule.scheduler.runCurrent()

            assertNull(viewModel.state.value.locationUnavailableReason)
        }
    }

    @Test
    fun aChannelChosenFromTheDefaultPlaceIsATeleport() = runTest {
        val viewModel = buildViewModel {
            coEvery { getLocationServicesEnabled(Unit) } returns true
            coEvery { getAvailableChannels(Unit) } returns defaultNearby(Reason.NO_SOURCE)
        }
        viewModel.clearedAfter {
            instantExecutorRule.scheduler.runCurrent()
            val city = defaultPlaceChannels.first { it.level == GeohashChannelLevel.CITY }

            viewModel.onSelectChannel(city)
            instantExecutorRule.scheduler.runCurrent()

            assertTrue(viewModel.state.value.isTeleported)
            coVerify { saveUserStateAction(UserStateAction.Chat(Channel.Location(city.level, city.geohash), isTeleport = true)) }
        }
    }

    @Test
    fun aChannelChosenWithARealFixIsNotATeleport() = runTest {
        val viewModel = buildViewModel {
            coEvery { getLocationServicesEnabled(Unit) } returns true
            coEvery { getAvailableChannels(Unit) } returns NearbyChannels(defaultPlaceChannels, deviceFix())
        }
        viewModel.clearedAfter {
            instantExecutorRule.scheduler.runCurrent()
            val city = defaultPlaceChannels.first { it.level == GeohashChannelLevel.CITY }

            viewModel.onSelectChannel(city)
            instantExecutorRule.scheduler.runCurrent()

            assertTrue(!viewModel.state.value.isTeleported)
            coVerify { saveUserStateAction(UserStateAction.Chat(Channel.Location(city.level, city.geohash), isTeleport = false)) }
        }
    }

    @Test
    fun overlappingLoadsNeverShowTheDefaultPlaceWithoutItsNotice() = runTest {
        // A refresh that got the default place waits on the place names while another load, started
        // by a change of Tor policy, gets a real fix and finishes first. The channels and the notice
        // come from one read, so whichever load is applied last, they agree.
        val realChannels = listOf(GeohashChannel(GeohashChannelLevel.CITY, "u33db"))
        val slowNames = CompletableDeferred<Map<GeohashChannelLevel, String>>()
        val viewModel = buildViewModel {
            coEvery { getLocationServicesEnabled(Unit) } returns true
            coEvery { getAvailableChannels(Unit) } returns NearbyChannels(realChannels, deviceFix())
        }
        viewModel.clearedAfter {
            instantExecutorRule.scheduler.runCurrent()
            val seen = mutableListOf<Pair<List<GeohashChannel>, String?>>()
            val watching = backgroundScope.launch { viewModel.state.collect { seen += it.availableChannels to it.locationUnavailableReason } }
            instantExecutorRule.scheduler.runCurrent()

            coEvery { getAvailableChannels(Unit) } returns defaultNearby(Reason.NO_SOURCE)
            coEvery { getLocationNames(Unit) } coAnswers { slowNames.await() }
            instantExecutorRule.scheduler.advanceTimeBy(6_000)
            instantExecutorRule.scheduler.runCurrent()

            coEvery { getAvailableChannels(Unit) } returns NearbyChannels(realChannels, deviceFix())
            coEvery { getLocationNames(Unit) } returns emptyMap()
            torMode.value = TorMode.ON
            instantExecutorRule.scheduler.runCurrent()
            assertEquals(realChannels, viewModel.state.value.availableChannels)
            assertNull(viewModel.state.value.locationUnavailableReason)

            slowNames.complete(emptyMap())
            instantExecutorRule.scheduler.runCurrent()

            assertTrue(
                seen.any { it.first == defaultPlaceChannels },
                "the slow load was never applied, so the overlap was not reproduced"
            )
            seen.forEach { (channels, reason) ->
                assertEquals(channels == defaultPlaceChannels, reason != null, "channels $channels with notice $reason")
            }
            watching.cancel()
        }
    }

    @Test
    fun anIpDerivedFixIsFlaggedApproximate() = runTest {
        val viewModel = buildViewModel {
            coEvery { getLocationServicesEnabled(Unit) } returns true
            coEvery { getAvailableChannels(Unit) } returns NearbyChannels(listOf(GeohashChannel(GeohashChannelLevel.CITY, "9q8yy")), LocationFixInfo(LocationFixInfo.Source.IP_ADDRESS, ageMillis = 1_000))
        }
        viewModel.clearedAfter {
            instantExecutorRule.scheduler.runCurrent()

            assertTrue(viewModel.state.value.locationApproximate)
            assertTrue(!viewModel.state.value.locationStale)
        }
    }

    @Test
    fun anOldFixIsFlaggedStale() = runTest {
        val viewModel = buildViewModel {
            coEvery { getLocationServicesEnabled(Unit) } returns true
            coEvery { getAvailableChannels(Unit) } returns NearbyChannels(listOf(GeohashChannel(GeohashChannelLevel.CITY, "9q8yy")), LocationFixInfo(LocationFixInfo.Source.DEVICE, ageMillis = 3 * 60 * 60 * 1000L))
        }
        viewModel.clearedAfter {
            instantExecutorRule.scheduler.runCurrent()

            assertTrue(viewModel.state.value.locationStale)
        }
    }

    @Test
    fun emitsOpenMapEffectWithCustomGeohash() = runTest {
        val viewModel = buildViewModel()
        instantExecutorRule.scheduler.runCurrent()

        viewModel.onCustomGeohashChange("9q8yy")

        viewModel.effects.test {
            viewModel.onOpenMap()
            instantExecutorRule.scheduler.runCurrent()
            val effect = awaitItem() as LocationChannelsEffect.OpenMap
            assertEquals("9q8yy", effect.initialGeohash)
            cancelAndIgnoreRemainingEvents()
        }

        viewModel.clearForTest()
    }

    @Test
    fun onMapResultAppliesTeleportState() = runTest {
        val viewModel = buildViewModel()
        instantExecutorRule.scheduler.runCurrent()

        viewModel.onTeleport() // invalid, sets error
        viewModel.onMapResult("9q8yy")
        instantExecutorRule.scheduler.runCurrent()

        val state = viewModel.state.value
        assertTrue(state.isTeleported)
        assertEquals("9q8yy", state.customGeohash)
        assertNull(state.customGeohashError)
        assertEquals(Channel.Location(GeohashChannelLevel.CITY, "9q8yy"), state.selectedChannel)
        coVerify { saveUserStateAction(UserStateAction.Chat(Channel.Location(GeohashChannelLevel.CITY, "9q8yy"), true)) }

        viewModel.clearForTest()
    }

    @Test
    fun `no fix is requested when the user has turned location services off`() = runTest {
        /*
         * Reading the preference first fixed the ordering, but the fix was still taken
         * unconditionally. On desktop that meant an outbound request to a geolocation provider the
         * user had just declined.
         */
        val viewModel = buildViewModel {
            coEvery { getLocationServicesEnabled(Unit) } returns false
        }
        instantExecutorRule.scheduler.runCurrent()

        coVerify(exactly = 0) { getAvailableChannels(Unit) }
        assertTrue(!viewModel.state.value.isLoading, "and it must not sit on a spinner")

        viewModel.clearForTest()
    }

    @Test
    fun `turning location services off clears what the last fix produced`() = runTest {
        coEvery { toggleLocationServices(Unit) } returns Unit
        val viewModel = buildViewModel {
            coEvery { getLocationServicesEnabled(Unit) } returns true
            coEvery { getAvailableChannels(Unit) } returns NearbyChannels(listOf(GeohashChannel(GeohashChannelLevel.CITY, "9q8yy")), null)
        }
        instantExecutorRule.scheduler.runCurrent()
        assertEquals(1, viewModel.state.value.availableChannels.size)

        coEvery { getLocationServicesEnabled(Unit) } returns false
        viewModel.onToggleLocationServices()
        instantExecutorRule.scheduler.runCurrent()

        assertTrue(
            viewModel.state.value.availableChannels.isEmpty(),
            "channels derived from a fix outlived the switch that produced them"
        )

        viewModel.clearForTest()
    }

    @Test
    fun `using and clearing a device location updates its notice`() = runTest {
        var saved: SavedPlace? = null
        val viewModel = buildViewModel {
            coEvery { getLocationServicesEnabled(Unit) } returns true
            coEvery { getSavedPlace(Unit) } answers { saved }
            coEvery { setSavedPlace(any()) } answers { saved = firstArg() }
            coEvery { clearSavedPlace(Unit) } answers { saved = null }
        }
        viewModel.clearedAfter {
            instantExecutorRule.scheduler.runCurrent()

            viewModel.onUseAsDeviceLocation("#9Q8YY")
            instantExecutorRule.scheduler.runCurrent()
            assertEquals("9q8yy", viewModel.state.value.savedPlace)
            assertEquals("location set on this device (#9q8yy)", viewModel.state.value.savedPlaceNotice)

            viewModel.onClearDeviceLocation()
            instantExecutorRule.scheduler.runCurrent()
            assertNull(viewModel.state.value.savedPlace)
            assertNull(viewModel.state.value.savedPlaceNotice)
        }
    }

    @Test
    fun `an invalid device location saves nothing`() = runTest {
        val viewModel = buildViewModel()
        viewModel.clearedAfter {
            instantExecutorRule.scheduler.runCurrent()

            viewModel.onUseAsDeviceLocation("invalid")
            instantExecutorRule.scheduler.runCurrent()

            assertEquals("invalid geohash", viewModel.state.value.customGeohashError)
            coVerify(exactly = 0) { setSavedPlace(any()) }
        }
    }

    @Test
    fun `a load started before setting a device location cannot replace its channels`() = runTest {
        val firstLoadStarted = CompletableDeferred<Unit>()
        val finishFirstLoad = CompletableDeferred<Unit>()
        val oldChannels = listOf(GeohashChannel(GeohashChannelLevel.CITY, "u33db"))
        val savedChannels = listOf(
            GeohashChannel(GeohashChannelLevel.CITY, "9q8yy"),
            GeohashChannel(GeohashChannelLevel.PROVINCE, "9q8y"),
            GeohashChannel(GeohashChannelLevel.REGION, "9q"),
        )
        var saved: SavedPlace? = null
        var calls = 0
        val viewModel = buildViewModel {
            coEvery { getLocationServicesEnabled(Unit) } returns true
            coEvery { getSavedPlace(Unit) } answers { saved }
            coEvery { setSavedPlace(any()) } answers { saved = firstArg() }
            coEvery { getAvailableChannels(Unit) } coAnswers {
                if (calls++ == 0) {
                    firstLoadStarted.complete(Unit)
                    finishFirstLoad.await()
                    NearbyChannels(oldChannels, deviceFix())
                } else {
                    NearbyChannels(savedChannels, LocationFixInfo(LocationFixInfo.Source.MANUAL, ageMillis = 0))
                }
            }
        }
        viewModel.clearedAfter {
            instantExecutorRule.scheduler.runCurrent()
            firstLoadStarted.await()

            viewModel.onUseAsDeviceLocation("9q8yy")
            instantExecutorRule.scheduler.runCurrent()
            assertEquals(savedChannels, viewModel.state.value.availableChannels)

            finishFirstLoad.complete(Unit)
            instantExecutorRule.scheduler.runCurrent()
            assertEquals(savedChannels, viewModel.state.value.availableChannels)
        }
    }

    @Test
    fun `a load started before clearing a device location cannot replace its channels`() = runTest {
        val firstLoadStarted = CompletableDeferred<Unit>()
        val finishFirstLoad = CompletableDeferred<Unit>()
        val savedChannels = listOf(GeohashChannel(GeohashChannelLevel.CITY, "9q8yy"))
        val clearedChannels = listOf(GeohashChannel(GeohashChannelLevel.CITY, "u33db"))
        var saved: SavedPlace? = SavedPlace.of("9q8yy")
        var calls = 0
        val viewModel = buildViewModel {
            coEvery { getLocationServicesEnabled(Unit) } returns true
            coEvery { getSavedPlace(Unit) } answers { saved }
            coEvery { clearSavedPlace(Unit) } answers { saved = null }
            coEvery { getAvailableChannels(Unit) } coAnswers {
                if (calls++ == 0) {
                    firstLoadStarted.complete(Unit)
                    finishFirstLoad.await()
                    NearbyChannels(savedChannels, LocationFixInfo(LocationFixInfo.Source.MANUAL, ageMillis = 0))
                } else {
                    NearbyChannels(clearedChannels, deviceFix())
                }
            }
        }
        viewModel.clearedAfter {
            instantExecutorRule.scheduler.runCurrent()
            firstLoadStarted.await()

            viewModel.onClearDeviceLocation()
            instantExecutorRule.scheduler.runCurrent()
            assertEquals(clearedChannels, viewModel.state.value.availableChannels)

            finishFirstLoad.complete(Unit)
            instantExecutorRule.scheduler.runCurrent()
            assertEquals(clearedChannels, viewModel.state.value.availableChannels)
        }
    }

    @Test
    fun `a load started while setting a device location cannot restore the old place`() = runTest {
        val writeStarted = CompletableDeferred<Unit>()
        val finishWrite = CompletableDeferred<Unit>()
        val oldLoadStarted = CompletableDeferred<Unit>()
        val finishOldLoad = CompletableDeferred<Unit>()
        val oldChannels = listOf(GeohashChannel(GeohashChannelLevel.CITY, "u33db"))
        val savedChannels = listOf(GeohashChannel(GeohashChannelLevel.CITY, "9q8yy"))
        var saved: SavedPlace? = null
        var loads = 0
        val viewModel = buildViewModel {
            coEvery { getLocationServicesEnabled(Unit) } returns true
            coEvery { getSavedPlace(Unit) } answers { saved }
            coEvery { setSavedPlace(any()) } coAnswers {
                saved = firstArg()
                writeStarted.complete(Unit)
                finishWrite.await()
            }
            coEvery { getAvailableChannels(Unit) } coAnswers {
                when (loads++) {
                    0 -> NearbyChannels(emptyList(), deviceFix())
                    1 -> {
                        oldLoadStarted.complete(Unit)
                        finishOldLoad.await()
                        NearbyChannels(oldChannels, deviceFix())
                    }
                    else -> NearbyChannels(savedChannels, LocationFixInfo(LocationFixInfo.Source.MANUAL, ageMillis = 0))
                }
            }
        }
        viewModel.clearedAfter {
            instantExecutorRule.scheduler.runCurrent()

            viewModel.onUseAsDeviceLocation("9q8yy")
            instantExecutorRule.scheduler.runCurrent()
            writeStarted.await()
            torMode.value = TorMode.ON
            instantExecutorRule.scheduler.runCurrent()
            oldLoadStarted.await()

            finishWrite.complete(Unit)
            instantExecutorRule.scheduler.runCurrent()
            assertEquals(savedChannels, viewModel.state.value.availableChannels)

            finishOldLoad.complete(Unit)
            instantExecutorRule.scheduler.runCurrent()
            assertEquals(savedChannels, viewModel.state.value.availableChannels)
        }
    }

    @Test
    fun `setting a device location clears prior rows before its refresh`() = runTest {
        val refreshStarted = CompletableDeferred<Unit>()
        val finishRefresh = CompletableDeferred<Unit>()
        val oldChannels = listOf(GeohashChannel(GeohashChannelLevel.BLOCK, "u33db7k"))
        var saved: SavedPlace? = null
        var loads = 0
        val viewModel = buildViewModel {
            coEvery { getLocationServicesEnabled(Unit) } returns true
            coEvery { getSavedPlace(Unit) } answers { saved }
            coEvery { setSavedPlace(any()) } answers { saved = firstArg() }
            coEvery { getAvailableChannels(Unit) } coAnswers {
                if (loads++ == 0) {
                    NearbyChannels(oldChannels, deviceFix())
                } else {
                    refreshStarted.complete(Unit)
                    finishRefresh.await()
                    NearbyChannels(emptyList(), LocationFixInfo(LocationFixInfo.Source.MANUAL, ageMillis = 0))
                }
            }
        }
        viewModel.clearedAfter {
            instantExecutorRule.scheduler.runCurrent()

            viewModel.onUseAsDeviceLocation("9q")
            instantExecutorRule.scheduler.runCurrent()
            refreshStarted.await()

            val state = viewModel.state.value
            assertTrue(state.availableChannels.isEmpty())
            assertEquals("location set on this device (#9q)", state.savedPlaceNotice)

            finishRefresh.complete(Unit)
        }
    }

    @Test
    fun `a resumed older set cannot replace a cleared device location`() = runTest {
        val afterWrite = CompletableDeferred<Unit>()
        val resumeSet = CompletableDeferred<Unit>()
        var saved: SavedPlace? = null
        var locationReads = 0
        val viewModel = buildViewModel {
            coEvery { getLocationServicesEnabled(Unit) } coAnswers {
                when (locationReads++) {
                    1 -> {
                        afterWrite.complete(Unit)
                        resumeSet.await()
                        false
                    }
                    else -> false
                }
            }
            coEvery { getSavedPlace(Unit) } answers { saved }
            coEvery { setSavedPlace(any()) } answers { saved = firstArg() }
            coEvery { clearSavedPlace(Unit) } answers { saved = null }
        }
        viewModel.clearedAfter {
            instantExecutorRule.scheduler.runCurrent()

            viewModel.onUseAsDeviceLocation("9q8yy")
            instantExecutorRule.scheduler.runCurrent()
            afterWrite.await()

            viewModel.onClearDeviceLocation()
            instantExecutorRule.scheduler.runCurrent()
            assertNull(viewModel.state.value.savedPlace)

            resumeSet.complete(Unit)
            instantExecutorRule.scheduler.runCurrent()
            assertNull(viewModel.state.value.savedPlace)
        }
    }

    @Test
    fun `clearing a device location wins over a toggle started while it reads the preference`() = runTest {
        val clearReadStarted = CompletableDeferred<Unit>()
        val resumeClear = CompletableDeferred<Unit>()
        var saved: SavedPlace? = requireNotNull(SavedPlace.of("9q8yy"))
        var enabled = false
        var locationReads = 0
        val viewModel = buildViewModel {
            coEvery { getSavedPlace(Unit) } answers { saved }
            coEvery { clearSavedPlace(Unit) } answers { saved = null }
            coEvery { toggleLocationServices(Unit) } answers { enabled = !enabled }
            coEvery { getLocationServicesEnabled(Unit) } coAnswers {
                when (locationReads++) {
                    1 -> {
                        clearReadStarted.complete(Unit)
                        resumeClear.await()
                        enabled
                    }
                    else -> enabled
                }
            }
        }
        viewModel.clearedAfter {
            instantExecutorRule.scheduler.runCurrent()

            viewModel.onClearDeviceLocation()
            instantExecutorRule.scheduler.runCurrent()
            clearReadStarted.await()

            viewModel.onToggleLocationServices()
            instantExecutorRule.scheduler.runCurrent()

            resumeClear.complete(Unit)
            instantExecutorRule.scheduler.runCurrent()

            assertNull(viewModel.state.value.savedPlace)
            assertNull(viewModel.state.value.savedPlaceNotice)
        }
    }

    @Test
    fun `a clear made while a toggle reads the saved place wins`() = runTest {
        val toggleRead = CompletableDeferred<Unit>()
        val resumeToggle = CompletableDeferred<Unit>()
        var saved: SavedPlace? = requireNotNull(SavedPlace.of("9q8yy"))
        var reads = 0
        var enabled = false
        val viewModel = buildViewModel {
            coEvery { getSavedPlace(Unit) } coAnswers {
                val value = saved
                if (reads++ == 1) {
                    toggleRead.complete(Unit)
                    resumeToggle.await()
                }
                value
            }
            coEvery { clearSavedPlace(Unit) } answers { saved = null }
            coEvery { toggleLocationServices(Unit) } answers { enabled = !enabled }
            coEvery { getLocationServicesEnabled(Unit) } answers { enabled }
        }
        viewModel.clearedAfter {
            instantExecutorRule.scheduler.runCurrent()

            viewModel.onToggleLocationServices()
            instantExecutorRule.scheduler.runCurrent()
            toggleRead.await()
            viewModel.onClearDeviceLocation()
            instantExecutorRule.scheduler.runCurrent()
            resumeToggle.complete(Unit)
            instantExecutorRule.scheduler.runCurrent()

            assertNull(viewModel.state.value.savedPlace)
        }
    }

    @Test
    fun `saving a place while the sheet opens keeps its bookmarks and selection`() = runTest {
        val namesAsked = CompletableDeferred<Unit>()
        val finishNames = CompletableDeferred<Unit>()
        var saved: SavedPlace? = null
        val viewModel = buildViewModel {
            coEvery { getSavedPlace(Unit) } answers { saved }
            coEvery { setSavedPlace(any()) } answers { saved = firstArg() }
            coEvery { getBookmarkedChannels(Unit) } returns listOf("u33db")
            coEvery { getBookmarkNames(Unit) } coAnswers {
                namesAsked.complete(Unit)
                finishNames.await()
                mapOf("u33db" to "berlin")
            }
            coEvery { getUserState(Unit) } returns UserState.Active(ActiveState.Chat(Channel.Location(GeohashChannelLevel.CITY, "u33db")))
        }
        viewModel.clearedAfter {
            instantExecutorRule.scheduler.runCurrent()
            namesAsked.await()

            viewModel.onUseAsDeviceLocation("9q")
            instantExecutorRule.scheduler.runCurrent()
            finishNames.complete(Unit)
            instantExecutorRule.scheduler.runCurrent()

            assertEquals(listOf("u33db"), viewModel.state.value.bookmarkedGeohashes)
            assertEquals(mapOf("u33db" to "berlin"), viewModel.state.value.bookmarkNames)
            assertEquals(Channel.Location(GeohashChannelLevel.CITY, "u33db"), viewModel.state.value.selectedChannel)
            assertEquals("9q", viewModel.state.value.savedPlace)
        }
    }

    @Test
    fun `a toggle that cuts the opening load short still shows the saved place`() = runTest {
        val namesAsked = CompletableDeferred<Unit>()
        val finishNames = CompletableDeferred<Unit>()
        var enabled = false
        val viewModel = buildViewModel {
            coEvery { getSavedPlace(Unit) } returns SavedPlace.of("9q8yy")
            coEvery { getBookmarkNames(Unit) } coAnswers {
                namesAsked.complete(Unit)
                finishNames.await()
                emptyMap()
            }
            coEvery { toggleLocationServices(Unit) } answers { enabled = !enabled }
            coEvery { getLocationServicesEnabled(Unit) } answers { enabled }
        }
        viewModel.clearedAfter {
            instantExecutorRule.scheduler.runCurrent()
            namesAsked.await()

            viewModel.onToggleLocationServices()
            instantExecutorRule.scheduler.runCurrent()
            finishNames.complete(Unit)
            instantExecutorRule.scheduler.runCurrent()

            assertEquals("9q8yy", viewModel.state.value.savedPlace)
            assertEquals("location set on this device (#9q8yy)", viewModel.state.value.savedPlaceNotice)
        }
    }

    @Test
    fun `a load started before toggling location services cannot change the screen afterwards`() = runTest {
        val loadStarted = CompletableDeferred<Unit>()
        val finishLoad = CompletableDeferred<Unit>()
        val oldChannels = listOf(GeohashChannel(GeohashChannelLevel.CITY, "u33db"))
        var enabled = true
        val viewModel = buildViewModel {
            coEvery { getLocationServicesEnabled(Unit) } answers { enabled }
            coEvery { toggleLocationServices(Unit) } answers { enabled = false }
            coEvery { getAvailableChannels(Unit) } coAnswers {
                loadStarted.complete(Unit)
                finishLoad.await()
                NearbyChannels(oldChannels, deviceFix())
            }
        }
        viewModel.clearedAfter {
            instantExecutorRule.scheduler.runCurrent()
            loadStarted.await()

            viewModel.onToggleLocationServices()
            instantExecutorRule.scheduler.runCurrent()
            assertTrue(viewModel.state.value.availableChannels.isEmpty())

            finishLoad.complete(Unit)
            instantExecutorRule.scheduler.runCurrent()
            assertTrue(viewModel.state.value.availableChannels.isEmpty())
        }
    }

    @Test
    fun `background names from before setting a device location are not applied afterwards`() = runTest {
        val nameStarted = CompletableDeferred<Unit>()
        val finishName = CompletableDeferred<Unit>()
        var saved: SavedPlace? = null
        var loads = 0
        val oldChannel = GeohashChannel(GeohashChannelLevel.CITY, "u33db")
        val viewModel = buildViewModel {
            coEvery { getLocationServicesEnabled(Unit) } returns true
            coEvery { getSavedPlace(Unit) } answers { saved }
            coEvery { setSavedPlace(any()) } answers { saved = firstArg() }
            coEvery { getAvailableChannels(Unit) } coAnswers {
                if (loads++ == 0) NearbyChannels(listOf(oldChannel), deviceFix())
                else NearbyChannels(emptyList(), LocationFixInfo(LocationFixInfo.Source.MANUAL, ageMillis = 0))
            }
            coEvery { resolveLocationName(any()) } coAnswers {
                nameStarted.complete(Unit)
                finishName.await()
                "old place"
            }
        }
        viewModel.clearedAfter {
            instantExecutorRule.scheduler.runCurrent()
            nameStarted.await()

            viewModel.onUseAsDeviceLocation("9q")
            instantExecutorRule.scheduler.runCurrent()
            finishName.complete(Unit)
            instantExecutorRule.scheduler.runCurrent()

            assertTrue(viewModel.state.value.locationNames.isEmpty())
        }
    }

    @Test
    fun `a background name job from before setting a device location asks for no further name`() = runTest {
        val nameStarted = CompletableDeferred<Unit>()
        val finishName = CompletableDeferred<Unit>()
        var saved: SavedPlace? = null
        var loads = 0
        val oldChannels = listOf(
            GeohashChannel(GeohashChannelLevel.NEIGHBORHOOD, "u33dbf"),
            GeohashChannel(GeohashChannelLevel.CITY, "u33db"),
        )
        val viewModel = buildViewModel {
            coEvery { getLocationServicesEnabled(Unit) } returns true
            coEvery { getSavedPlace(Unit) } answers { saved }
            coEvery { setSavedPlace(any()) } answers { saved = firstArg() }
            coEvery { getAvailableChannels(Unit) } coAnswers {
                if (loads++ == 0) NearbyChannels(oldChannels, deviceFix())
                else NearbyChannels(emptyList(), LocationFixInfo(LocationFixInfo.Source.MANUAL, ageMillis = 0))
            }
            coEvery { resolveLocationName(ResolveLocationName.Params("u33dbf", GeohashChannelLevel.NEIGHBORHOOD)) } coAnswers {
                nameStarted.complete(Unit)
                finishName.await()
                "old neighbourhood"
            }
        }
        viewModel.clearedAfter {
            instantExecutorRule.scheduler.runCurrent()
            nameStarted.await()

            viewModel.onUseAsDeviceLocation("9q")
            instantExecutorRule.scheduler.runCurrent()
            finishName.complete(Unit)
            instantExecutorRule.scheduler.runCurrent()

            coVerify(exactly = 0) { resolveLocationName(ResolveLocationName.Params("u33db", GeohashChannelLevel.CITY)) }
        }
    }

    @Test
    fun `a change in tor policy refreshes without waiting out the poll`() = runTest {
        // Whether a live lookup may run is policy-dependent now, so the sheet must not keep
        // showing an answer the policy has just invalidated.
        val viewModel = buildViewModel {
            coEvery { getLocationServicesEnabled(Unit) } returns true
            coEvery { getAvailableChannels(Unit) } returns NearbyChannels(emptyList(), null)
        }
        instantExecutorRule.scheduler.runCurrent()

        torMode.value = TorMode.ON
        instantExecutorRule.scheduler.runCurrent()

        // Twice: the initial load, then the policy change. Not three times -- no poll has elapsed.
        coVerify(exactly = 2) { getAvailableChannels(Unit) }

        viewModel.clearForTest()
    }
    /**
     * Runs [block], then clears the view model even if [block] failed. Otherwise a failed assertion
     * leaves the five-second poll running on the scheduler runTest drains before it returns, and
     * runTest steps through that poll for ever: the test hangs instead of failing.
     */
    private inline fun LocationChannelsViewModel.clearedAfter(block: () -> Unit) {
        try {
            block()
        } finally {
            clearForTest()
        }
    }

    @Test
    fun `counts are shown with location services off`() = runTest {
        /*
         * Regression: the counts were read only alongside the nearby channels, so with location
         * services off the mesh and every bookmark read 0 for ever -- while the chat header, which
         * reads the same mesh peer list, said "2 people". Neither count comes from a fix.
         */
        val viewModel = buildViewModel {
            coEvery { getLocationServicesEnabled(Unit) } returns false
            coEvery { getBookmarkedChannels(Unit) } returns listOf("9q8yy", "u4pru")
            coEvery { getParticipantCounts(Unit) } returns
                ParticipantCounts(geohashCounts = mapOf("9q8yy" to 3, "u4pru" to 1), meshCount = 2)
        }
        viewModel.clearedAfter {
            instantExecutorRule.scheduler.runCurrent()

            val state = viewModel.state.value
            assertEquals(2, state.meshParticipantCount)
            assertEquals(mapOf("9q8yy" to 3, "u4pru" to 1), state.participantCounts)
        }
    }

    @Test
    fun `counts are shown when there is no fix, beside the reason`() = runTest {
        // The Pi: no location source at all. The reason and the empty nearby list stay as they were.
        val viewModel = buildViewModel {
            coEvery { getLocationServicesEnabled(Unit) } returns true
            coEvery { getBookmarkedChannels(Unit) } returns listOf("9q8yy")
            coEvery { getAvailableChannels(Unit) } returns defaultNearby(Reason.NO_SOURCE)
            coEvery { getParticipantCounts(Unit) } returns
                ParticipantCounts(geohashCounts = mapOf("9q8yy" to 3), meshCount = 2)
        }
        viewModel.clearedAfter {
            instantExecutorRule.scheduler.runCurrent()

            val state = viewModel.state.value
            assertEquals(2, state.meshParticipantCount)
            assertEquals(mapOf("9q8yy" to 3), state.participantCounts)
            assertEquals(notice("location not set"), state.locationUnavailableReason)
            assertEquals(defaultPlaceChannels, state.availableChannels)
            assertTrue(!state.isLoading)
        }
    }

    @Test
    fun `counts follow the poll with location services off`() = runTest {
        val viewModel = buildViewModel {
            coEvery { getLocationServicesEnabled(Unit) } returns false
            coEvery { getBookmarkedChannels(Unit) } returns listOf("9q8yy")
            coEvery { getParticipantCounts(Unit) } returns
                ParticipantCounts(geohashCounts = mapOf("9q8yy" to 1), meshCount = 1)
        }
        viewModel.clearedAfter {
            instantExecutorRule.scheduler.runCurrent()

            coEvery { getParticipantCounts(Unit) } returns
                ParticipantCounts(geohashCounts = mapOf("9q8yy" to 4), meshCount = 2)
            instantExecutorRule.scheduler.advanceTimeBy(6_000)
            instantExecutorRule.scheduler.runCurrent()

            val state = viewModel.state.value
            assertEquals(2, state.meshParticipantCount)
            assertEquals(mapOf("9q8yy" to 4), state.participantCounts)
        }
    }

    @Test
    fun `counts follow the poll when there is no fix`() = runTest {
        val viewModel = buildViewModel {
            coEvery { getLocationServicesEnabled(Unit) } returns true
            coEvery { getBookmarkedChannels(Unit) } returns listOf("9q8yy")
            coEvery { getAvailableChannels(Unit) } returns defaultNearby(Reason.NO_SOURCE)
            coEvery { getParticipantCounts(Unit) } returns
                ParticipantCounts(geohashCounts = mapOf("9q8yy" to 1), meshCount = 1)
        }
        viewModel.clearedAfter {
            instantExecutorRule.scheduler.runCurrent()

            coEvery { getParticipantCounts(Unit) } returns
                ParticipantCounts(geohashCounts = mapOf("9q8yy" to 4), meshCount = 2)
            instantExecutorRule.scheduler.advanceTimeBy(6_000)
            instantExecutorRule.scheduler.runCurrent()

            val state = viewModel.state.value
            assertEquals(2, state.meshParticipantCount)
            assertEquals(mapOf("9q8yy" to 4), state.participantCounts)
            assertEquals(notice("location not set"), state.locationUnavailableReason)
            assertEquals(defaultPlaceChannels, state.availableChannels)
        }
    }

    @Test
    fun `polling the counts never asks for a fix with location services off`() = runTest {
        // Reading counts must not become a back door to the geolocation provider the user declined.
        val viewModel = buildViewModel {
            coEvery { getLocationServicesEnabled(Unit) } returns false
        }
        viewModel.clearedAfter {
            instantExecutorRule.scheduler.runCurrent()
            // Three polls: at 5, 10 and 15 seconds.
            instantExecutorRule.scheduler.advanceTimeBy(16_000)
            instantExecutorRule.scheduler.runCurrent()

            coVerify(atLeast = 4) { getParticipantCounts(Unit) }
            coVerify(exactly = 0) { getAvailableChannels(Unit) }
            coVerify(exactly = 0) { resolveLocationName(any()) }
        }
    }
}
