package com.bitchat.domain.chat

import app.cash.turbine.test
import com.bitchat.domain.base.defaultContextFacade
import com.bitchat.domain.chat.eventbus.InMemoryChatEventBus
import com.bitchat.domain.chat.model.ChatEvent
import com.bitchat.domain.chat.repository.ChatRepository
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class GetJoinedChannelsTest {
    private val chatRepository = mockk<ChatRepository>()
    private val chatEventBus = InMemoryChatEventBus(defaultContextFacade)
    private val useCase = GetJoinedChannels(chatRepository, chatEventBus)

    @Test
    fun `emits initial joined channels on subscription`() = runTest {
        coEvery { chatRepository.getJoinedChannelsList() } returns listOf("#general", "#dev")

        useCase(Unit).test {
            assertEquals(setOf("#general", "#dev"), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `emits updated channels on ChannelJoined event`() = runTest {
        coEvery { chatRepository.getJoinedChannelsList() } returnsMany listOf(
            listOf("#general"),
            listOf("#general", "#dev")
        )

        useCase(Unit).test {
            assertEquals(setOf("#general"), awaitItem())

            chatEventBus.update(ChatEvent.ChannelJoined)
            assertEquals(setOf("#general", "#dev"), awaitItem())

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `emits updated channels on ChannelLeft event`() = runTest {
        coEvery { chatRepository.getJoinedChannelsList() } returnsMany listOf(
            listOf("#general", "#dev"),
            listOf("#general")
        )

        useCase(Unit).test {
            assertEquals(setOf("#general", "#dev"), awaitItem())

            chatEventBus.update(ChatEvent.ChannelLeft)
            assertEquals(setOf("#general"), awaitItem())

            cancelAndIgnoreRemainingEvents()
        }
    }
}
