package com.bitchat.viewmodel.main

import com.bitchat.domain.app.model.ActiveState
import com.bitchat.domain.app.model.UserState
import com.bitchat.domain.location.model.Channel
import com.bitchat.domain.user.ObserveFavorites
import com.bitchat.domain.user.ObserveUserState
import com.bitchat.domain.user.model.FavoriteRelationship
import com.bitchat.viewmodel.BaseViewModelTest
import com.bitchat.viewmodel.navigation.Chat
import com.bitchat.viewmodel.navigation.Settings
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModelUserStateTest : BaseViewModelTest() {

    private val userStates = MutableSharedFlow<UserState>(extraBufferCapacity = 8)
    private val favorites = MutableSharedFlow<Map<String, FavoriteRelationship>>(extraBufferCapacity = 8)

    @Test
    fun `every observed state is shown, also while an earlier navigation waits to be taken`() = runTest {
        val viewModel = buildViewModel()
        runCurrent()

        userStates.emit(UserState.Active(ActiveState.Settings))
        userStates.emit(UserState.Active(ActiveState.Chat(Channel.MeshDM("peer1"))))
        runCurrent()

        assertEquals("peer1", viewModel.headerState.value.selectedPrivatePeer)
        val taken = listOf(viewModel.navigation.receive(), viewModel.navigation.receive())
        assertEquals(1, taken.count { it == Settings })
        assertIs<Chat>(taken.single { it != Settings })
    }

    @Test
    fun `every observed favourites map is shown`() = runTest {
        val viewModel = buildViewModel()
        runCurrent()

        favorites.emit(mapOf("PEER1" to favorite(isFavorite = true), "peer2" to favorite(isFavorite = false)))
        runCurrent()
        assertEquals(setOf("peer1"), viewModel.headerState.value.favoritePeers)

        favorites.emit(emptyMap())
        runCurrent()
        assertEquals(emptySet(), viewModel.headerState.value.favoritePeers)
    }

    private fun favorite(isFavorite: Boolean) = FavoriteRelationship(
        peerNoisePublicKeyHex = "key",
        peerNostrPublicKey = null,
        peerNickname = "alice",
        isFavorite = isFavorite,
        theyFavoritedUs = false,
        favoritedAt = 0,
        lastUpdated = 0,
    )

    private fun buildViewModel(): MainViewModel {
        val observeUserState = mockk<ObserveUserState>()
        coEvery { observeUserState(Unit) } returns userStates
        val observeFavorites = mockk<ObserveFavorites>()
        coEvery { observeFavorites(Unit) } returns favorites
        return MainViewModel(
            getUserState = mockk(relaxed = true),
            observeUserState = observeUserState,
            observeAppTheme = mockk(relaxed = true),
            observePowSettings = mockk(relaxed = true),
            observeTorStatus = mockk(relaxed = true),
            observeJoinedChannels = mockk(relaxed = true),
            observeUserNickname = mockk(relaxed = true),
            observePrivateChats = mockk(relaxed = true),
            observeUnreadPrivatePeers = mockk(relaxed = true),
            observeLatestUnreadPrivatePeer = mockk(relaxed = true),
            observePeerSessionStates = mockk(relaxed = true),
            observeLocationServicesEnabled = mockk(relaxed = true),
            observePermissionState = mockk(relaxed = true),
            observeCurrentChannelBookmarkState = mockk(relaxed = true),
            observeHasNotes = mockk(relaxed = true),
            observeChannelParticipants = mockk(relaxed = true),
            observeFavorites = observeFavorites,
            toggleFavoriteUseCase = mockk(relaxed = true),
            leaveChannel = mockk(relaxed = true),
            setUserNickname = mockk(relaxed = true),
            toggleBookmarkUseCase = mockk(relaxed = true),
            saveUserStateAction = mockk(relaxed = true),
            markPrivateChatRead = mockk(relaxed = true),
            clearAllData = mockk(relaxed = true),
            observeMeshChannelPeople = mockk(relaxed = true),
            clearSelectedPrivatePeer = mockk(relaxed = true),
            resolveChatFallback = mockk(relaxed = true),
        )
    }
}
