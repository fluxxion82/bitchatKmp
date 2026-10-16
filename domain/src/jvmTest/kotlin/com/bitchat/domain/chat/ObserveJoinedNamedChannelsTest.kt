package com.bitchat.domain.chat

import app.cash.turbine.test
import com.bitchat.domain.base.defaultContextFacade
import com.bitchat.domain.chat.eventbus.InMemoryChatEventBus
import com.bitchat.domain.chat.model.ChannelInfo
import com.bitchat.domain.chat.model.ChatEvent
import com.bitchat.domain.chat.repository.ChatRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest

class ObserveJoinedNamedChannelsTest {
    private val chatRepository = mockk<ChatRepository>()
    private val chatEventBus = InMemoryChatEventBus(defaultContextFacade)
    private val useCase = ObserveJoinedNamedChannels(chatRepository, chatEventBus)

    private fun channel(name: String) = ChannelInfo(
        name = name, isProtected = false, memberCount = 1, creatorNpub = null,
        keyCommitment = null, isOwner = false, nostrEventId = null,
    )

    @Test
    fun `the start emits what is stored`() = runTest {
        coEvery { chatRepository.getAvailableChannels() } returns listOf(channel("#a"))

        useCase(Unit).test {
            assertEquals(listOf(channel("#a")), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a matching event published during the first read is read after it`() = runTest {
        val firstRead = CompletableDeferred<Unit>()
        coEvery { chatRepository.getAvailableChannels() } coAnswers { firstRead.await(); listOf(channel("#a")) } andThen listOf(channel("#a"), channel("#b"))

        useCase(Unit).test {
            chatEventBus.update(ChatEvent.ChannelListUpdated)
            chatEventBus.update(ChatEvent.MessageReceived)
            firstRead.complete(Unit)

            assertEquals(listOf(channel("#a")), awaitItem())
            assertEquals(listOf(channel("#a"), channel("#b")), awaitItem())
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
        coVerify(exactly = 2) { chatRepository.getAvailableChannels() }
    }
}
