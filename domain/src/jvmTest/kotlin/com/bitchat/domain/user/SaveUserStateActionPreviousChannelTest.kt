package com.bitchat.domain.user

import com.bitchat.domain.app.model.ActiveState
import com.bitchat.domain.app.model.UserState
import com.bitchat.domain.app.repository.AppRepository
import com.bitchat.domain.chat.repository.ChatRepository
import com.bitchat.domain.connectivity.repository.ConnectivityRepository
import com.bitchat.domain.location.model.Channel
import com.bitchat.domain.user.model.UserStateAction
import com.bitchat.domain.user.repository.UserRepository
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `/j test` run twice: the second join must not make `#test` its own previous channel, or leaving
 * it (`/leave`, back) returns to `#test` instead of where the user came from.
 */
class SaveUserStateActionPreviousChannelTest {
    private val saved = slot<UserState>()
    private var current: UserState = UserState.Active(ActiveState.Chat(Channel.NamedChannel("#test"), previousChannel = Channel.Mesh))

    private val repository = mockk<UserRepository>(relaxed = true) {
        coEvery { getUserState() } answers { current }
        coEvery { setUserState(capture(saved)) } answers { current = saved.captured }
    }
    private val action = SaveUserStateAction(
        repository = repository,
        appRepository = mockk<AppRepository>(relaxed = true) { coEvery { hasRequiredPermissions() } returns true },
        connectivityRepository = mockk<ConnectivityRepository>(relaxed = true) {
            coEvery { isBluetoothEnabled() } returns true
            coEvery { isLocationServicesEnabled() } returns true
        },
        chatRepository = mockk<ChatRepository>(relaxed = true),
        userEventBus = mockk(relaxed = true),
        locationRepository = mockk(relaxed = true),
        locationEventBus = mockk(relaxed = true),
        chatEventBus = mockk(relaxed = true),
    )

    @Test
    fun `switching to the chat already active keeps where the user came from`() = runTest {
        action(UserStateAction.Chat(Channel.NamedChannel("#test")))
        assertEquals(UserState.Active(ActiveState.Chat(Channel.NamedChannel("#test"), previousChannel = Channel.Mesh)), saved.captured)
    }

    @Test
    fun `switching to another chat remembers the one left`() = runTest {
        action(UserStateAction.Chat(Channel.NamedChannel("#other")))
        assertEquals(UserState.Active(ActiveState.Chat(Channel.NamedChannel("#other"), previousChannel = Channel.NamedChannel("#test"))), saved.captured)
    }

    @Test
    fun `a DM described more fully is still the same conversation`() = runTest {
        // The switch that fills in a Nostr DM's pubkey, source geohash and display name must not
        // make the bare DM its own previous chat.
        current = UserState.Active(
            ActiveState.Chat(Channel.NostrDM("nostr_ab", "abcd", null, null), previousChannel = Channel.Mesh),
        )
        action(UserStateAction.NostrDM(peerID = "nostr_ab", fullPubkey = "abcd", sourceGeohash = "9q8yy", displayName = "dora"))
        assertEquals(
            ActiveState.Chat(Channel.NostrDM("nostr_ab", "abcd", "9q8yy", "dora"), previousChannel = Channel.Mesh),
            (saved.captured as UserState.Active).activeState,
        )
    }
}
