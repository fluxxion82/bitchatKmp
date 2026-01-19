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
import com.bitchat.domain.location.GetLocationNames
import com.bitchat.domain.location.GetLocationServicesEnabled
import com.bitchat.domain.location.GetParticipantCounts
import com.bitchat.domain.location.GetTeleportState
import com.bitchat.domain.location.ResolveLocationName
import com.bitchat.domain.location.ToggleBookmark
import com.bitchat.domain.location.ToggleLocationServices
import com.bitchat.domain.location.model.Channel
import com.bitchat.domain.location.model.GeohashChannelLevel
import com.bitchat.domain.location.model.ParticipantCounts
import com.bitchat.domain.user.GetUserState
import com.bitchat.domain.user.SaveUserStateAction
import com.bitchat.domain.user.model.UserStateAction
import com.bitchat.viewmodel.BaseViewModelTest
import com.bitchat.viewvo.location.LocationChannelsEffect
import io.mockk.coEvery
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

    private fun buildViewModel(): LocationChannelsViewModel {
        coEvery { getAvailableChannels(Unit) } returns emptyList()
        coEvery { getParticipantCounts(Unit) } returns ParticipantCounts()
        coEvery { getBookmarkedChannels(Unit) } returns emptyList()
        coEvery { getBookmarkNames(Unit) } returns emptyMap()
        coEvery { getTeleportState(Unit) } returns false
        coEvery { getLocationServicesEnabled(Unit) } returns false
        coEvery { getLocationNames(Unit) } returns emptyMap()
        coEvery { resolveLocationName(any()) } returns null
        coEvery { getUserState(Unit) } returns UserState.Active(ActiveState.Chat(Channel.Mesh))

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
            resolveLocationName = resolveLocationName,
            getUserState = getUserState,
        )
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
}
