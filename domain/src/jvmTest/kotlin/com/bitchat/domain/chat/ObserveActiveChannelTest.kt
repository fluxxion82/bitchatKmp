package com.bitchat.domain.chat

import app.cash.turbine.test
import com.bitchat.domain.app.model.ActiveState
import com.bitchat.domain.app.model.UserState
import com.bitchat.domain.app.repository.AppRepository
import com.bitchat.domain.base.defaultContextFacade
import com.bitchat.domain.chat.eventbus.InMemoryChatEventBus
import com.bitchat.domain.chat.model.ActiveChannel
import com.bitchat.domain.chat.model.ChatEvent
import com.bitchat.domain.connectivity.repository.ConnectivityRepository
import com.bitchat.domain.location.model.Channel
import com.bitchat.domain.user.GetUserState
import com.bitchat.domain.user.repository.UserRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class ObserveActiveChannelTest {
    private val userRepository = mockk<UserRepository>()
    private val appRepository = mockk<AppRepository> {
        coEvery { hasRequiredPermissions() } returns true
        coEvery { isBatteryOptimizationSkipped() } returns true
    }
    private val connectivityRepository = mockk<ConnectivityRepository> {
        coEvery { isBluetoothEnabled() } returns true
        coEvery { isLocationServicesEnabled() } returns true
    }
    private val chatEventBus = InMemoryChatEventBus(defaultContextFacade)
    private val useCase = ObserveActiveChannel(
        GetUserState(userRepository, appRepository, connectivityRepository),
        chatEventBus,
    )

    private val named = Channel.NamedChannel("#dev")
    private val namedChat = ActiveChannel.Chat(named)
    private val meshChat = ActiveChannel.Chat(Channel.Mesh)
    private fun chat(channel: Channel) = UserState.Active(ActiveState.Chat(channel))

    @Test
    fun `the start emits the channel of the saved chat`() = runTest {
        coEvery { userRepository.getUserState() } returns chat(named)

        useCase(Unit).test {
            assertEquals(namedChat, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a saved state that is not a chat gives no channel`() = runTest {
        coEvery { userRepository.getUserState() } returns UserState.Active(ActiveState.Settings)

        useCase(Unit).test {
            assertEquals(ActiveChannel.None, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `with Bluetooth off the mesh channel is followed for LoRa`() = runTest {
        coEvery { connectivityRepository.isBluetoothEnabled() } returns false

        useCase(Unit).test {
            assertEquals(ActiveChannel.MeshForLoRa, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        coVerify(exactly = 0) { userRepository.getUserState() }
    }

    @Test
    fun `another blocking state gives no channel`() = runTest {
        coEvery { appRepository.hasRequiredPermissions() } returns false

        useCase(Unit).test {
            assertEquals(ActiveChannel.None, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `ChannelChanged reads the channel again`() = runTest {
        coEvery { userRepository.getUserState() } returnsMany listOf(chat(Channel.Mesh), chat(named))

        useCase(Unit).test {
            assertEquals(meshChat, awaitItem())

            chatEventBus.update(ChatEvent.ChannelChanged)
            assertEquals(namedChat, awaitItem())

            cancelAndIgnoreRemainingEvents()
        }
        coVerify(exactly = 2) { userRepository.getUserState() }
    }

    @Test
    fun `an unrelated event after the start does not read the channel again`() = runTest {
        coEvery { userRepository.getUserState() } returns chat(Channel.Mesh)

        useCase(Unit).test {
            assertEquals(meshChat, awaitItem())

            chatEventBus.update(ChatEvent.MessageReceived)
            expectNoEvents()

            cancelAndIgnoreRemainingEvents()
        }
        coVerify(exactly = 1) { userRepository.getUserState() }
    }

    @Test
    fun `an unrelated event in the replay slot does not read the channel again at the start`() = runTest {
        coEvery { userRepository.getUserState() } returns chat(Channel.Mesh)
        chatEventBus.update(ChatEvent.ChannelJoined)

        useCase(Unit).test {
            assertEquals(meshChat, awaitItem())
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
        coVerify(exactly = 1) { userRepository.getUserState() }
    }

    @Test
    fun `two ChannelChanged give two reads even when the channel is the same`() = runTest {
        coEvery { userRepository.getUserState() } returns chat(Channel.Mesh)

        useCase(Unit).test {
            assertEquals(meshChat, awaitItem())

            chatEventBus.update(ChatEvent.ChannelChanged)
            assertEquals(meshChat, awaitItem())
            chatEventBus.update(ChatEvent.ChannelChanged)
            assertEquals(meshChat, awaitItem())

            cancelAndIgnoreRemainingEvents()
        }
        coVerify(exactly = 3) { userRepository.getUserState() }
    }

    @Test
    fun `today a ChannelChanged in the replay slot reads the channel a second time at the start`() = runTest {
        coEvery { userRepository.getUserState() } returns chat(Channel.Mesh)
        chatEventBus.update(ChatEvent.ChannelChanged)

        useCase(Unit).test {
            assertEquals(meshChat, awaitItem())
            assertEquals(meshChat, awaitItem())
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
        coVerify(exactly = 2) { userRepository.getUserState() }
    }

    @Test
    fun `a ChannelChanged published during the first read is read after it`() = runTest {
        val firstRead = CompletableDeferred<Unit>()
        coEvery { userRepository.getUserState() } coAnswers { firstRead.await(); chat(Channel.Mesh) } andThen chat(named)

        useCase(Unit).test {
            chatEventBus.update(ChatEvent.ChannelChanged)
            chatEventBus.update(ChatEvent.MessageReceived)
            firstRead.complete(Unit)

            assertEquals(meshChat, awaitItem())
            assertEquals(namedChat, awaitItem())
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
        coVerify(exactly = 2) { userRepository.getUserState() }
    }
}
