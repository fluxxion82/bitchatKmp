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

class ObserveSelectedPrivatePeerTest {
    private val chatRepository = mockk<ChatRepository>()
    private val chatEventBus = InMemoryChatEventBus(defaultContextFacade)
    private val useCase = ObserveSelectedPrivatePeer(chatRepository, chatEventBus)

    @Test
    fun `emits initial selected peer on subscription`() = runTest {
        coEvery { chatRepository.getSelectedPrivatePeer() } returns null

        useCase(Unit).test {
            assertEquals(null, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `emits updated selected peer on event`() = runTest {
        coEvery { chatRepository.getSelectedPrivatePeer() } returnsMany listOf(null, "nostr_abcd")

        useCase(Unit).test {
            assertEquals(null, awaitItem())

            chatEventBus.update(ChatEvent.SelectedPrivatePeerChanged)
            assertEquals("nostr_abcd", awaitItem())

            cancelAndIgnoreRemainingEvents()
        }
    }
}
