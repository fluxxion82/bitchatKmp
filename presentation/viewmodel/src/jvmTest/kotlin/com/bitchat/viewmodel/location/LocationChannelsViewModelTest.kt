package com.bitchat.viewmodel.location

import androidx.lifecycle.ViewModel
import app.cash.turbine.test
import com.bitchat.domain.app.model.ActiveState
import com.bitchat.domain.app.model.UserState
import com.bitchat.domain.location.BeginGeohashSampling
import com.bitchat.domain.location.EndGeohashSampling
import com.bitchat.domain.location.GetAvailableChannels
import com.bitchat.domain.location.GetBookmarkNames
import com.bitchat.domain.location.GetBookmarkedChannels
import com.bitchat.domain.location.GetLastFixInfo
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
import com.bitchat.domain.location.model.GeohashChannel
import com.bitchat.domain.location.model.GeohashChannelLevel
import com.bitchat.domain.location.model.LocationFixInfo
import com.bitchat.domain.location.model.LocationUnavailableException
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
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
    private val getLastFixInfo = mockk<GetLastFixInfo>(relaxed = true)
    private val observeRequestedTorMode = mockk<ObserveRequestedTorMode>(relaxed = true)
    private val torMode = MutableStateFlow(TorMode.OFF)

    /**
     * [configure] runs after the defaults below and before construction, because the view model
     * loads in its init block -- stubbing after it is built is too late, and stubbing before this
     * function is called is overwritten by the defaults.
     */
    private fun buildViewModel(configure: () -> Unit = {}): LocationChannelsViewModel {
        coEvery { getAvailableChannels(Unit) } returns emptyList()
        coEvery { getParticipantCounts(Unit) } returns ParticipantCounts()
        coEvery { getBookmarkedChannels(Unit) } returns emptyList()
        coEvery { getBookmarkNames(Unit) } returns emptyMap()
        coEvery { getTeleportState(Unit) } returns false
        coEvery { getLocationServicesEnabled(Unit) } returns false
        coEvery { getLocationNames(Unit) } returns emptyMap()
        coEvery { resolveLocationName(any()) } returns null
        coEvery { getUserState(Unit) } returns UserState.Active(ActiveState.Chat(Channel.Mesh))
        coEvery { getLastFixInfo(Unit) } returns null
        coEvery { observeRequestedTorMode(Unit) } returns torMode

        configure()

        return LocationChannelsViewModel(
            saveUserStateAction = saveUserStateAction,
            getAvailableChannels = getAvailableChannels,
            getParticipantCounts = getParticipantCounts,
            getBookmarkedChannels = getBookmarkedChannels,
            beginGeohashSampling = beginGeohashSampling,
            endGeohashSampling = endGeohashSampling,
            toggleBookmark = toggleBookmark,
            toggleLocationServices = toggleLocationServices,
            getBookmarkNames = getBookmarkNames,
            getTeleportState = getTeleportState,
            getLocationServicesEnabled = getLocationServicesEnabled,
            getLocationNames = getLocationNames,
            getLastFixInfo = getLastFixInfo,
            observeRequestedTorMode = observeRequestedTorMode,
            resolveLocationName = resolveLocationName,
            getUserState = getUserState,
        )
    }

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
            coEvery { getAvailableChannels(Unit) } throws
                LocationUnavailableException(LocationUnavailableException.Reason.NO_SOURCE)
        }
        instantExecutorRule.scheduler.runCurrent()

        val state = viewModel.state.value
        assertEquals(listOf("9q8yy"), state.bookmarkedGeohashes)
        assertEquals(mapOf("9q8yy" to "home"), state.bookmarkNames)
        assertTrue(state.locationServicesEnabled, "the preference must be read even when no fix exists")
        assertEquals(
            LocationUnavailableException.Reason.NO_SOURCE.message,
            state.locationUnavailableReason
        )
        assertTrue(!state.isLoading, "an unavailable fix is a finished state, not a spinner")

        viewModel.clearForTest()
    }

    @Test
    fun anUnavailableFixClearsChannelsFromAnEarlierSuccess() = runTest {
        // Leaving the old list up would present channels derived from a fix the app has just said
        // it cannot obtain.
        val viewModel = buildViewModel {
            coEvery { getLocationServicesEnabled(Unit) } returns true
            coEvery { getAvailableChannels(Unit) } returns
                listOf(GeohashChannel(GeohashChannelLevel.CITY, "9q8yy"))
        }
        instantExecutorRule.scheduler.runCurrent()
        assertEquals(1, viewModel.state.value.availableChannels.size)

        coEvery { getAvailableChannels(Unit) } throws
            LocationUnavailableException(LocationUnavailableException.Reason.LOOKUP_FAILED)
        instantExecutorRule.scheduler.advanceTimeBy(6_000)
        instantExecutorRule.scheduler.runCurrent()

        val state = viewModel.state.value
        assertTrue(state.availableChannels.isEmpty(), "stale channels survived an unavailable fix")
        assertEquals(
            LocationUnavailableException.Reason.LOOKUP_FAILED.message,
            state.locationUnavailableReason
        )

        viewModel.clearForTest()
    }

    @Test
    fun anIpDerivedFixIsFlaggedApproximate() = runTest {
        val viewModel = buildViewModel {
            coEvery { getLocationServicesEnabled(Unit) } returns true
            coEvery { getAvailableChannels(Unit) } returns
                listOf(GeohashChannel(GeohashChannelLevel.CITY, "9q8yy"))
            coEvery { getLastFixInfo(Unit) } returns
                LocationFixInfo(LocationFixInfo.Source.IP_ADDRESS, ageMillis = 1_000)
        }
        instantExecutorRule.scheduler.runCurrent()

        assertTrue(viewModel.state.value.locationApproximate)
        assertTrue(!viewModel.state.value.locationStale)

        viewModel.clearForTest()
    }

    @Test
    fun anOldFixIsFlaggedStale() = runTest {
        val viewModel = buildViewModel {
            coEvery { getLocationServicesEnabled(Unit) } returns true
            coEvery { getAvailableChannels(Unit) } returns
                listOf(GeohashChannel(GeohashChannelLevel.CITY, "9q8yy"))
            coEvery { getLastFixInfo(Unit) } returns
                LocationFixInfo(LocationFixInfo.Source.DEVICE, ageMillis = 3 * 60 * 60 * 1000L)
        }
        instantExecutorRule.scheduler.runCurrent()

        assertTrue(viewModel.state.value.locationStale)

        viewModel.clearForTest()
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
            coEvery { getAvailableChannels(Unit) } returns
                listOf(GeohashChannel(GeohashChannelLevel.CITY, "9q8yy"))
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
    fun `a change in tor policy refreshes without waiting out the poll`() = runTest {
        // Whether a live lookup may run is policy-dependent now, so the sheet must not keep
        // showing an answer the policy has just invalidated.
        val viewModel = buildViewModel {
            coEvery { getLocationServicesEnabled(Unit) } returns true
            coEvery { getAvailableChannels(Unit) } returns emptyList()
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
            coEvery { getAvailableChannels(Unit) } throws
                LocationUnavailableException(LocationUnavailableException.Reason.NO_SOURCE)
            coEvery { getParticipantCounts(Unit) } returns
                ParticipantCounts(geohashCounts = mapOf("9q8yy" to 3), meshCount = 2)
        }
        viewModel.clearedAfter {
            instantExecutorRule.scheduler.runCurrent()

            val state = viewModel.state.value
            assertEquals(2, state.meshParticipantCount)
            assertEquals(mapOf("9q8yy" to 3), state.participantCounts)
            assertEquals(
                LocationUnavailableException.Reason.NO_SOURCE.message,
                state.locationUnavailableReason
            )
            assertTrue(state.availableChannels.isEmpty())
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
            coEvery { getAvailableChannels(Unit) } throws
                LocationUnavailableException(LocationUnavailableException.Reason.NO_SOURCE)
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
            assertEquals(
                LocationUnavailableException.Reason.NO_SOURCE.message,
                state.locationUnavailableReason
            )
            assertTrue(state.availableChannels.isEmpty())
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
            coVerify(exactly = 0) { getLastFixInfo(Unit) }
            coVerify(exactly = 0) { resolveLocationName(any()) }
        }
    }
}
