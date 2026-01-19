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

class ObserveUnreadPrivatePeersTest {
    private val chatRepository = mockk<ChatRepository>()
    private val chatEventBus = InMemoryChatEventBus(defaultContextFacade)
    private val useCase = ObserveUnreadPrivatePeers(chatRepository, chatEventBus)

    @Test
    fun `emits initial unread peers on subscription`() = runTest {
        val initialUnread = setOf("nostr_abcd")
        coEvery { chatRepository.getUnreadPrivatePeers() } returns initialUnread

        useCase(Unit).test {
            assertEquals(initialUnread, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `emits updated unread peers on event`() = runTest {
        val initialUnread = setOf("nostr_abcd")
        val updatedUnread = setOf("nostr_efgh")
        coEvery { chatRepository.getUnreadPrivatePeers() } returnsMany listOf(initialUnread, updatedUnread)

        useCase(Unit).test {
            assertEquals(initialUnread, awaitItem())

            chatEventBus.update(ChatEvent.UnreadPrivatePeersUpdated)
            assertEquals(updatedUnread, awaitItem())

            cancelAndIgnoreRemainingEvents()
        }
    }
}
