package com.bitchat.domain.chat

import app.cash.turbine.test
import com.bitchat.domain.base.defaultContextFacade
import com.bitchat.domain.chat.eventbus.InMemoryChatEventBus
import com.bitchat.domain.chat.model.ChatEvent
import com.bitchat.domain.chat.repository.ChatRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
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

    @Test
    fun `a matching event published during the first read is read after it`() = runTest {
        val firstRead = CompletableDeferred<Unit>()
        coEvery { chatRepository.getUnreadPrivatePeers() } coAnswers { firstRead.await(); emptySet() } andThen setOf("aa")

        useCase(Unit).test {
            chatEventBus.update(ChatEvent.UnreadPrivatePeersUpdated)
            chatEventBus.update(ChatEvent.MessageReceived)
            firstRead.complete(Unit)

            assertEquals(emptySet<String>(), awaitItem())
            assertEquals(setOf("aa"), awaitItem())
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
        coVerify(exactly = 2) { chatRepository.getUnreadPrivatePeers() }
    }
}
