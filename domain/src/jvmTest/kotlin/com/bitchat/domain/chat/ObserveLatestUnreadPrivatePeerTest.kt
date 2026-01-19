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

class ObserveLatestUnreadPrivatePeerTest {
    private val chatRepository = mockk<ChatRepository>()
    private val chatEventBus = InMemoryChatEventBus(defaultContextFacade)
    private val useCase = ObserveLatestUnreadPrivatePeer(chatRepository, chatEventBus)

    @Test
    fun `emits initial latest unread peer on subscription`() = runTest {
        coEvery { chatRepository.getLatestUnreadPrivatePeer() } returns null

        useCase(Unit).test {
            assertEquals(null, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `emits updated latest unread peer on event`() = runTest {
        coEvery { chatRepository.getLatestUnreadPrivatePeer() } returnsMany listOf(null, "nostr_abcd")

        useCase(Unit).test {
            assertEquals(null, awaitItem())

            chatEventBus.update(ChatEvent.LatestUnreadPrivatePeerChanged)
            assertEquals("nostr_abcd", awaitItem())

            cancelAndIgnoreRemainingEvents()
        }
    }
}
