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
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A mesh private chat that already has a name of its own is opened under it. `/msg bob` finds a peer by
 * what it is announced as right now, and anyone in range can announce for any peer: the name in the
 * request must not become the title of a conversation known as somebody else's.
 */
class SaveUserStateActionMeshDmNameTest {
    private val saved = slot<UserState>()
    private var current: UserState = UserState.Active(ActiveState.Chat(Channel.Mesh))

    private val repository = mockk<UserRepository>(relaxed = true) {
        coEvery { getUserState() } answers { current }
        coEvery { setUserState(capture(saved)) } answers { current = saved.captured }
    }
    private val chatRepository = mockk<ChatRepository>(relaxed = true) {
        coEvery { getPrivateChatNames() } returns mapOf(NAMED to "alice#a1a1", UNNAMED to null)
    }
    private val action = SaveUserStateAction(
        repository = repository,
        appRepository = mockk<AppRepository>(relaxed = true) { coEvery { hasRequiredPermissions() } returns true },
        connectivityRepository = mockk<ConnectivityRepository>(relaxed = true) {
            coEvery { isBluetoothEnabled() } returns true
            coEvery { isLocationServicesEnabled() } returns true
        },
        chatRepository = chatRepository,
        userEventBus = mockk(relaxed = true),
        locationRepository = mockk(relaxed = true),
        locationEventBus = mockk(relaxed = true),
        chatEventBus = mockk(relaxed = true),
    )

    private fun savedChannel() = ((saved.captured as UserState.Active).activeState as ActiveState.Chat).channel

    @Test
    fun `a chat switched to under another name is opened under its own`() = runTest {
        action(UserStateAction.Chat(Channel.MeshDM(NAMED, "bob")))

        assertEquals(Channel.MeshDM(NAMED, "alice#a1a1"), savedChannel())
        coVerify { chatRepository.setSelectedChannel(Channel.MeshDM(NAMED, "alice#a1a1")) }
    }

    @Test
    fun `a chat started under another name is opened under its own`() = runTest {
        action(UserStateAction.MeshDM(peerID = NAMED, displayName = "bob"))

        assertEquals(Channel.MeshDM(NAMED, "alice#a1a1"), savedChannel())
        coVerify { chatRepository.setSelectedChannel(Channel.MeshDM(NAMED, "alice#a1a1")) }
    }

    @Test
    fun `a chat without a name of its own is opened under the name asked for`() = runTest {
        action(UserStateAction.Chat(Channel.MeshDM(UNNAMED, "carol")))
        assertEquals(Channel.MeshDM(UNNAMED, "carol"), savedChannel())

        action(UserStateAction.MeshDM(peerID = NEW, displayName = "dave"))
        assertEquals(Channel.MeshDM(NEW, "dave"), savedChannel())
    }

    private companion object {
        const val NAMED = "a1a1000000000001"
        const val UNNAMED = "b2b2000000000002"
        const val NEW = "c3c3000000000003"
    }
}
